package io.plainround.seatwave.live.websocket;

import java.util.Objects;

/**
 * One WebSocket frame as RFC 6455 defines it, after unmasking: its opcode and its payload. The feed
 * never sends or accepts fragments, so every frame here is a whole message.
 *
 * @param opcode what the frame carries: {@link #TEXT}, {@link #CLOSE}, {@link #PING} or {@link
 *     #PONG}, or another value the reader refuses
 * @param payload the unmasked payload bytes
 */
public record Frame(int opcode, byte[] payload) {

  /** A text message: UTF-8 bytes (RFC 6455, section 5.6 in the RFC's own numbering). */
  public static final int TEXT = 0x1;

  /** A binary message. */
  public static final int BINARY = 0x2;

  /** The start of the closing handshake. */
  public static final int CLOSE = 0x8;

  /** A heartbeat question; the other side must answer with a pong. */
  public static final int PING = 0x9;

  /** A heartbeat answer. */
  public static final int PONG = 0xA;

  /** Copies the payload, so the frame cannot change later. */
  public Frame {
    payload = Objects.requireNonNull(payload, "payload").clone();
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
