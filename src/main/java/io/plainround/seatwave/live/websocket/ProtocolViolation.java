package io.plainround.seatwave.live.websocket;

import java.io.IOException;

/** A peer broke the WebSocket protocol; the connection is closed with {@link #closeCode()}. */
public final class ProtocolViolation extends IOException {

  private static final long serialVersionUID = 1L;

  /** The close code to send. */
  private final transient CloseCode closeCode;

  /**
   * Creates the exception.
   *
   * @param closeCode the close code to send
   * @param message what the peer did
   */
  public ProtocolViolation(CloseCode closeCode, String message) {
    super(message);
    this.closeCode = closeCode;
  }

  /**
   * The close code to send.
   *
   * @return the code
   */
  public CloseCode closeCode() {
    return closeCode;
  }
}
