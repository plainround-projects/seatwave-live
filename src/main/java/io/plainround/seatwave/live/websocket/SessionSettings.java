package io.plainround.seatwave.live.websocket;

import java.time.Duration;
import java.util.Objects;

/**
 * What every WebSocket session needs to know.
 *
 * @param allowedOrigin the one browser origin that may connect
 * @param heartbeat how long the writer stays silent before it pings
 * @param handshakeTimeout how long a new socket may take to send its handshake
 * @param retryAfter the delay a refused client is asked to wait
 */
record SessionSettings(
    String allowedOrigin, Duration heartbeat, Duration handshakeTimeout, Duration retryAfter) {

  SessionSettings {
    Objects.requireNonNull(allowedOrigin, "allowedOrigin");
    Objects.requireNonNull(heartbeat, "heartbeat");
    Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
    Objects.requireNonNull(retryAfter, "retryAfter");
  }
}
