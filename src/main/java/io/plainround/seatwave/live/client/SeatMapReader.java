package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.SnapshotView;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the whole seat map from the feed. Thread-safe: it shares one JDK HTTP client, which is
 * itself safe to share.
 */
public final class SeatMapReader {

  private static final int OK = 200;

  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json;
  private final Duration timeout;

  /**
   * Creates the reader.
   *
   * @param http the shared HTTP client
   * @param endpoints the feed's addresses
   * @param json the codec
   * @param timeout the longest wait for the whole reply
   */
  public SeatMapReader(HttpClient http, FeedEndpoints endpoints, FeedJson json, Duration timeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.json = Objects.requireNonNull(json, "json");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  /**
   * Reads the seat map once.
   *
   * @return the map, or empty if the request failed (the caller backs off and tries again)
   * @throws InterruptedException if the client is being stopped
   */
  public Optional<SnapshotView> read() throws InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(endpoints.seatMap()).timeout(timeout).GET().build();
    try {
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != OK) {
        return Optional.empty();
      }
      return Optional.of(json.read(response.body(), SnapshotView.class));
    } catch (IOException failed) {
      return Optional.empty();
    }
  }
}
