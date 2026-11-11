package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;
import java.net.URI;
import java.util.Objects;

/**
 * The feed's addresses on 127.0.0.1. Positions contain only hex digits, digits and a dot, so they
 * go into a query string as they are.
 *
 * @param httpPort the HTTP server's port
 * @param webSocketPort the WebSocket server's port
 */
public record FeedEndpoints(int httpPort, int webSocketPort) {

  private static final String EVENT = "/v1/events/1";

  /** Checks the ports. */
  public FeedEndpoints {
    if (httpPort < 1 || webSocketPort < 1) {
      throw new IllegalArgumentException("ports must be positive");
    }
  }

  /**
   * The seat map.
   *
   * @return its URI
   */
  public URI seatMap() {
    return http(EVENT + "/seat-map");
  }

  /**
   * A long poll from a position.
   *
   * @param after the client's position
   * @return its URI
   */
  public URI longPoll(FeedPosition after) {
    return http(EVENT + "/seat-changes?after=" + Objects.requireNonNull(after, "after").written());
  }

  /**
   * The event stream from a position.
   *
   * @param after the client's position
   * @return its URI
   */
  public URI stream(FeedPosition after) {
    return http(EVENT + "/seat-changes/stream?after=" + after.written());
  }

  /**
   * The WebSocket from a position.
   *
   * @param after the client's position
   * @return its URI
   */
  public URI socket(FeedPosition after) {
    return URI.create(
        "ws://127.0.0.1:"
            + webSocketPort
            + EVENT
            + "/seat-changes/socket?after="
            + after.written());
  }

  /**
   * Any path of the HTTP server.
   *
   * @param path the path, starting with a slash
   * @return its URI
   */
  public URI http(String path) {
    return URI.create("http://127.0.0.1:" + httpPort + path);
  }
}
