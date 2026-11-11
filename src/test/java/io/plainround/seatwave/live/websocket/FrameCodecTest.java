package io.plainround.seatwave.live.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

  private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};

  @Test
  void encodesEachLengthFormOfAServerFrameUnmasked() {
    assertThat(FrameCodec.encode(Frame.TEXT, new byte[125]))
        .hasSize(127)
        .startsWith((byte) 0x81, (byte) 125);
    assertThat(FrameCodec.encode(Frame.TEXT, new byte[126]))
        .hasSize(130)
        .startsWith((byte) 0x81, (byte) 126, (byte) 0, (byte) 126);
    assertThat(FrameCodec.encode(Frame.TEXT, new byte[65_536]))
        .hasSize(65_546)
        .startsWith((byte) 0x81, (byte) 127);
    assertThat(FrameCodec.encodeClose(CloseCode.NORMAL)).containsExactly(0x88, 2, 0x03, 0xE8);
  }

  @Test
  void readsAMaskedClientFrameAndUnmasksIt() throws IOException {
    Frame frame = FrameCodec.read(input(clientFrame(0x80 | Frame.PING, "hello")), 125);

    assertThat(frame.opcode()).isEqualTo(Frame.PING);
    assertThat(new String(frame.payload(), StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void refusesUnmaskedFragmentedReservedAndOversizedFrames() {
    assertThatThrownBy(() -> FrameCodec.read(input(new byte[] {(byte) 0x89, 0}), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("masked");
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(Frame.TEXT, "a")), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("fragmented");
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(0xC0 | Frame.TEXT, "a")), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("reserved");
    assertThatThrownBy(
            () -> FrameCodec.read(input(clientFrame(0x80 | Frame.TEXT, "a".repeat(200))), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("larger");
    assertThatThrownBy(
            () -> FrameCodec.read(input(clientFrame(0x80 | Frame.PING, "a".repeat(126))), 1_000))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("control");
    assertThatThrownBy(() -> FrameCodec.read(input(new byte[] {(byte) 0x81}), 125))
        .isInstanceOf(EOFException.class);
  }

  @Test
  void refusesAnUnknownOpcodeAndAStrayContinuationAsProtocolErrors() {
    for (int opcode : new int[] {0x3, 0x7, 0xB, 0xF}) {
      assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(0x80 | opcode, "a")), 125))
          .isInstanceOf(ProtocolViolation.class)
          .hasMessageContaining("unknown opcode")
          .extracting(violation -> ((ProtocolViolation) violation).closeCode())
          .isEqualTo(CloseCode.PROTOCOL_ERROR);
    }
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(0x80, "a")), 125))
        .extracting(violation -> ((ProtocolViolation) violation).closeCode())
        .isEqualTo(CloseCode.PROTOCOL_ERROR);
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(Frame.PING, "a")), 125))
        .extracting(violation -> ((ProtocolViolation) violation).closeCode())
        .isEqualTo(CloseCode.PROTOCOL_ERROR);
  }

  @Test
  void readsALongerFrameAndTheCloseCodeOfAPayload() throws IOException {
    Frame frame = FrameCodec.read(input(clientFrame(0x80 | Frame.TEXT, "b".repeat(300))), 1_000);

    assertThat(frame.payload()).hasSize(300);
    assertThat(FrameCodec.closeCodeOf(new byte[] {0x03, (byte) 0xE9})).isEqualTo(1001);
    assertThat(FrameCodec.closeCodeOf(new byte[0])).isEqualTo(1005);
    assertThat(new ProtocolViolation(CloseCode.TOO_BIG, "x").closeCode().code()).isEqualTo(1009);
  }

  private static ByteArrayInputStream input(byte[] bytes) {
    return new ByteArrayInputStream(bytes);
  }

  // A client frame: masked, with the right length form.
  private static byte[] clientFrame(int firstByte, String text) {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    ByteBuffer frame = ByteBuffer.allocate(payload.length + 14);
    frame.put((byte) firstByte);
    if (payload.length <= 125) {
      frame.put((byte) (0x80 | payload.length));
    } else {
      frame.put((byte) (0x80 | 126));
      frame.putShort((short) payload.length);
    }
    frame.put(MASK);
    for (int index = 0; index < payload.length; index++) {
      frame.put((byte) (payload[index] ^ MASK[index % 4]));
    }
    byte[] bytes = new byte[frame.position()];
    frame.flip();
    frame.get(bytes);
    return bytes;
  }
}
