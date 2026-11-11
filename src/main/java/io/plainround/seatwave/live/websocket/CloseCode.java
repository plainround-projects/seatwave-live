package io.plainround.seatwave.live.websocket;

/** The close codes of RFC 6455 that the feed sends. */
public enum CloseCode {
  /** The purpose of the connection is fulfilled; here, after a reload instruction. */
  NORMAL(1000),
  /** The server is going away, or the peer stopped answering heartbeats. */
  GOING_AWAY(1001),
  /** The peer broke the protocol, for example with an unmasked frame. */
  PROTOCOL_ERROR(1002),
  /** The peer sent a kind of data the feed does not accept. */
  UNSUPPORTED_DATA(1003),
  /** The peer sent a frame larger than the feed accepts. */
  TOO_BIG(1009);

  private final int code;

  CloseCode(int code) {
    this.code = code;
  }

  /**
   * The two-byte code sent in the close frame.
   *
   * @return the number
   */
  public int code() {
    return code;
  }
}
