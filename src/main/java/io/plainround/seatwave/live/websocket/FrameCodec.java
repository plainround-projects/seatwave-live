package io.plainround.seatwave.live.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Reads and writes WebSocket frames (RFC 6455, the base framing protocol).
 *
 * <p>Server frames are never masked; client frames must be. A frame is at most {@code maxPayload}
 * bytes, control frames at most 125, and fragments are refused, because the feed's clients send
 * only control frames. Stateless.
 */
public final class FrameCodec {

  private static final int FIN = 0x80;
  private static final int OPCODE_BITS = 0x0F;
  private static final int RESERVED_BITS = 0x70;
  private static final int MASK = 0x80;
  private static final int LENGTH_BITS = 0x7F;
  // Lengths up to 125 fit in the second byte; 126 means a 16-bit length follows, 127 a 64-bit one.
  private static final int LONGEST_SHORT_LENGTH = 125;
  private static final int SIXTEEN_BIT_LENGTH = 126;
  private static final int SIXTY_FOUR_BIT_LENGTH = 127;
  private static final int LARGEST_SIXTEEN_BIT = 0xFFFF;
  private static final int MASK_KEY_BYTES = 4;
  private static final int CONTROL_OPCODES = 0x8;
  private static final int CONTINUATION = 0x0;
  private static final int BYTE = 0xFF;
  private static final int BITS_PER_BYTE = 8;
  private static final int CLOSE_CODE_BYTES = 2;
  // RFC 6455: the code a close frame without a code is reported as.
  private static final int NO_STATUS_RECEIVED = 1005;

  private FrameCodec() {}

  /**
   * Encodes one unmasked, unfragmented server frame.
   *
   * @param opcode the frame's opcode
   * @param payload the payload
   * @return the frame's bytes, header included
   */
  public static byte[] encode(int opcode, byte[] payload) {
    int headerLength =
        payload.length <= LONGEST_SHORT_LENGTH ? 2 : payload.length <= LARGEST_SIXTEEN_BIT ? 4 : 10;
    ByteBuffer frame = ByteBuffer.allocate(headerLength + payload.length);
    frame.put((byte) (FIN | opcode));
    if (payload.length <= LONGEST_SHORT_LENGTH) {
      frame.put((byte) payload.length);
    } else if (payload.length <= LARGEST_SIXTEEN_BIT) {
      frame.put((byte) SIXTEEN_BIT_LENGTH);
      frame.putShort((short) payload.length);
    } else {
      frame.put((byte) SIXTY_FOUR_BIT_LENGTH);
      frame.putLong(payload.length);
    }
    frame.put(payload);
    return frame.array();
  }

  /**
   * Encodes a close frame with a code.
   *
   * @param code the close code
   * @return the frame's bytes
   */
  public static byte[] encodeClose(CloseCode code) {
    byte[] payload = {(byte) (code.code() >> BITS_PER_BYTE), (byte) (code.code() & BYTE)};
    return encode(Frame.CLOSE, payload);
  }

  /**
   * Reads one client frame.
   *
   * @param in the connection's input
   * @param maxPayload the largest payload accepted
   * @return the frame, unmasked
   * @throws ProtocolViolation if the frame is unmasked, fragmented, reserved, of an unknown opcode,
   *     or too large
   * @throws EOFException if the peer closed the connection
   * @throws IOException if reading fails
   */
  public static Frame read(InputStream in, int maxPayload) throws IOException {
    int first = readByte(in);
    int second = readByte(in);
    int opcode = first & OPCODE_BITS;
    if ((first & RESERVED_BITS) != 0) {
      throw new ProtocolViolation(
          CloseCode.PROTOCOL_ERROR, "reserved bits set without an extension");
    }
    if (!isDefined(opcode)) {
      // RFC 6455, section 5.2: an unknown opcode fails the connection; section 7.4.1 names 1002,
      // protocol error, for that. 1003 is only for a defined kind of data the feed does not accept.
      throw new ProtocolViolation(CloseCode.PROTOCOL_ERROR, "unknown opcode " + opcode);
    }
    if (opcode == CONTINUATION || (opcode >= CONTROL_OPCODES && (first & FIN) == 0)) {
      // The feed never accepts a first fragment, so a continuation has nothing to continue, and
      // control frames must never be fragmented (section 5.5).
      throw new ProtocolViolation(
          CloseCode.PROTOCOL_ERROR, "a continuation or a fragmented control frame");
    }
    if ((first & FIN) == 0) {
      throw new ProtocolViolation(
          CloseCode.UNSUPPORTED_DATA, "fragmented messages are not accepted");
    }
    if ((second & MASK) == 0) {
      // RFC 6455: a server must close the connection on an unmasked client frame.
      throw new ProtocolViolation(CloseCode.PROTOCOL_ERROR, "client frames must be masked");
    }
    long length = second & LENGTH_BITS;
    if (length == SIXTEEN_BIT_LENGTH) {
      length = (readByte(in) << BITS_PER_BYTE) | readByte(in);
    } else if (length == SIXTY_FOUR_BIT_LENGTH) {
      length = ByteBuffer.wrap(readExactly(in, Long.BYTES)).getLong();
    }
    if (opcode >= CONTROL_OPCODES && length > LONGEST_SHORT_LENGTH) {
      throw new ProtocolViolation(
          CloseCode.PROTOCOL_ERROR, "control frames carry at most 125 bytes");
    }
    if (length < 0 || length > maxPayload) {
      throw new ProtocolViolation(CloseCode.TOO_BIG, "frame larger than " + maxPayload + " bytes");
    }
    byte[] maskKey = readExactly(in, MASK_KEY_BYTES);
    byte[] payload = readExactly(in, (int) length);
    for (int index = 0; index < payload.length; index++) {
      payload[index] ^= maskKey[index % MASK_KEY_BYTES];
    }
    return new Frame(opcode, payload);
  }

  /**
   * Reads the close code of a close frame's payload.
   *
   * @param payload the payload
   * @return the code, or 1005 ("no status received", RFC 6455) when the payload has none
   */
  public static int closeCodeOf(byte[] payload) {
    if (payload.length < CLOSE_CODE_BYTES) {
      return NO_STATUS_RECEIVED;
    }
    return ((payload[0] & BYTE) << BITS_PER_BYTE) | (payload[1] & BYTE);
  }

  private static boolean isDefined(int opcode) {
    return opcode == CONTINUATION
        || opcode == Frame.TEXT
        || opcode == Frame.BINARY
        || opcode == Frame.CLOSE
        || opcode == Frame.PING
        || opcode == Frame.PONG;
  }

  private static int readByte(InputStream in) throws IOException {
    int value = in.read();
    if (value < 0) {
      throw new EOFException("the peer closed the connection");
    }
    return value;
  }

  private static byte[] readExactly(InputStream in, int length) throws IOException {
    byte[] bytes = in.readNBytes(length);
    if (bytes.length != length) {
      throw new EOFException("the peer closed the connection inside a frame");
    }
    return bytes;
  }
}
