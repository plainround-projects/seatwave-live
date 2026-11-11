package io.plainround.seatwave.live.websocket;

/** The refusal a WebSocket client gets when the feed is at its connection limit. */
final class ServiceUnavailable {

  static final int STATUS = 503;

  private ServiceUnavailable() {}

  static HandshakeRefusal refusal() {
    return new HandshakeRefusal(STATUS, "the feed has as many open connections as it allows");
  }
}
