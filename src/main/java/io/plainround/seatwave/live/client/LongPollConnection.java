package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.LongPollView;
import io.plainround.seatwave.live.wire.MessageView;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Long polling with the JDK's HTTP client: ask for the changes after the position, apply them, ask
 * again from the new position. The connection is reused between polls (keep-alive, Module 5), so
 * one poll costs a request and a reply, not a handshake.
 *
 * <p>Thread-safe: no state; the shared HTTP client is safe to share.
 */
public final class LongPollConnection implements FeedConnection {

  private static final int OK = 200;

  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json;
  private final Duration requestTimeout;

  /**
   * Creates the connection.
   *
   * @param http the shared HTTP client
   * @param endpoints the feed's addresses
   * @param json the codec
   * @param requestTimeout the longest wait for one reply; longer than the server's hold
   */
  public LongPollConnection(
      HttpClient http, FeedEndpoints endpoints, FeedJson json, Duration requestTimeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.json = Objects.requireNonNull(json, "json");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
  }

  @Override
  public Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException {
    FeedPosition position = from;
    boolean connected = false;
    while (true) {
      HttpRequest request =
          HttpRequest.newBuilder(endpoints.longPoll(position))
              .timeout(requestTimeout)
              .GET()
              .build();
      LongPollView reply;
      try {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != OK) {
          return Ending.DISCONNECTED;
        }
        reply = json.read(response.body(), LongPollView.class);
      } catch (IOException failed) {
        return Ending.DISCONNECTED;
      }
      if (!connected) {
        sink.connected();
        connected = true;
      }
      if (reply.reload()) {
        return Ending.RELOAD;
      }
      for (MessageView change : reply.changes()) {
        if (sink.accept(change) == Verdict.RELOAD) {
          return Ending.RELOAD;
        }
      }
      position =
          FeedPosition.parse(String.valueOf(reply.next()))
              .orOnErrorThrow(IllegalArgumentException::new);
    }
  }

  @Override
  public void abort() {
    // Every poll ends by itself within the request timeout, so there is nothing to cut.
  }
}
