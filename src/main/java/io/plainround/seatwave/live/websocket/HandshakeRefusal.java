package io.plainround.seatwave.live.websocket;

import java.util.Objects;

/**
 * Why an opening handshake was refused, as the HTTP reply the client gets instead of 101.
 *
 * @param status the HTTP status code
 * @param reason one sentence for the reply body and the log
 */
public record HandshakeRefusal(int status, String reason) {

  /** Checks the fields. */
  public HandshakeRefusal {
    Objects.requireNonNull(reason, "reason");
  }
}
