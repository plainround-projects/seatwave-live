package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.MessageView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Server-sent events with the JDK's HTTP client, behaving as a browser's {@code EventSource} does
 * on reconnect: the first request carries {@code ?after=} from the seat map, and every later one
 * goes to the same URL with a {@code Last-Event-ID} header holding the last id applied.
 *
 * <p>It parses the event stream format of the WHATWG HTML Living Standard: {@code id}, {@code
 * event} and {@code data} fields, comment lines that start with a colon, and a blank line that ends
 * each event. Not thread-safe: one per client, used by that client's thread.
 */
public final class EventStreamConnection implements FeedConnection {

  private static final int OK = 200;

  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json;
  private final Duration headersTimeout;
  private URI firstUrl;
  private String lastId = "";
  private volatile InputStream current;

  /**
   * Creates the connection.
   *
   * @param http the shared HTTP client
   * @param endpoints the feed's addresses
   * @param json the codec
   * @param headersTimeout the longest wait for the stream's response headers
   */
  public EventStreamConnection(
      HttpClient http, FeedEndpoints endpoints, FeedJson json, Duration headersTimeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.json = Objects.requireNonNull(json, "json");
    this.headersTimeout = Objects.requireNonNull(headersTimeout, "headersTimeout");
  }

  @Override
  public Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .timeout(headersTimeout)
            .header("Accept", "text/event-stream")
            .GET();
    if (firstUrl != null && from.written().equals(lastId)) {
      // A resume after a dropped stream: the same URL, and the newest id in the header.
      request.uri(firstUrl).header("Last-Event-ID", lastId);
    } else {
      // A fresh start from a seat map just loaded.
      firstUrl = endpoints.stream(from);
      request.uri(firstUrl);
    }
    try {
      HttpResponse<InputStream> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() != OK) {
          return Ending.DISCONNECTED;
        }
        current = body;
        sink.connected();
        return readEvents(body, sink);
      } finally {
        current = null;
      }
    } catch (IOException failed) {
      return Ending.DISCONNECTED;
    }
  }

  private Ending readEvents(InputStream body, MessageSink sink) throws IOException {
    BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    StringBuilder data = new StringBuilder();
    String line;
    while ((line = lines.readLine()) != null) {
      if (line.isEmpty()) {
        if (!data.isEmpty()) {
          MessageView message =
              json.read(data.toString().getBytes(StandardCharsets.UTF_8), MessageView.class);
          data.setLength(0);
          if (sink.accept(message) == Verdict.RELOAD) {
            return Ending.RELOAD;
          }
          lastId = String.valueOf(message.position());
        }
      } else if (line.startsWith(":")) {
        sink.heartbeat();
      } else if (line.startsWith("data: ")) {
        data.append(line, "data: ".length(), line.length());
      }
      // id, event and retry lines repeat what the JSON already carries.
    }
    return Ending.DISCONNECTED;
  }

  @Override
  public void abort() {
    InputStream body = current;
    if (body != null) {
      try {
        // Closing the body from this thread makes the blocked read on the client thread fail.
        body.close();
      } catch (IOException alreadyClosed) {
        current = null;
      }
    }
  }
}
