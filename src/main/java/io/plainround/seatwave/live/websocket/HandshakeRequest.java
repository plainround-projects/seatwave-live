package io.plainround.seatwave.live.websocket;

import java.util.Objects;

/**
 * A valid opening handshake (RFC 6455): the client asked to upgrade the feed's path to a WebSocket.
 *
 * @param rawQuery the query string, possibly empty
 * @param key the {@code Sec-WebSocket-Key} the accept value is computed from
 */
public record HandshakeRequest(String rawQuery, String key) {

  /** Checks the fields. */
  public HandshakeRequest {
    Objects.requireNonNull(rawQuery, "rawQuery");
    Objects.requireNonNull(key, "key");
  }
}
