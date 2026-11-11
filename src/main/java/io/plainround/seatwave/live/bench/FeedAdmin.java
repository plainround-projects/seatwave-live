package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.SnapshotView;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The comparison's view of the server: its metrics, its seat map and the lab's admin routes, all
 * over HTTP on 127.0.0.1, so the same code measures a node in this process or in another.
 *
 * <p>Thread-safe: it shares one HTTP client.
 */
public final class FeedAdmin {

  private static final int OK = 200;
  private static final Pattern NUMBER = Pattern.compile("\"[A-Za-z]+\":([0-9]+)");

  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json = new FeedJson();
  private final Duration timeout;

  /**
   * Creates the admin client.
   *
   * @param http the HTTP client
   * @param endpoints the server's addresses
   * @param timeout the longest wait for one reply (a full collection can take a moment)
   */
  public FeedAdmin(HttpClient http, FeedEndpoints endpoints, Duration timeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  /**
   * Reads every metric.
   *
   * @return each sample by its name and labels, for example {@code process_open_fds}
   * @throws IOException if the server does not answer
   * @throws InterruptedException if interrupted
   */
  public Map<String, Double> metrics() throws IOException, InterruptedException {
    Map<String, Double> samples = new HashMap<>();
    for (String line : get("/metrics").lines().toList()) {
      int space = line.lastIndexOf(' ');
      if (space > 0) {
        samples.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
      }
    }
    return samples;
  }

  /**
   * Reads the seat map.
   *
   * @return the map
   * @throws IOException if the server does not answer
   * @throws InterruptedException if interrupted
   */
  public SnapshotView seatMap() throws IOException, InterruptedException {
    return json.read(
        get("/v1/events/1/seat-map").getBytes(java.nio.charset.StandardCharsets.UTF_8),
        SnapshotView.class);
  }

  /**
   * Calls one admin route and returns the number in its reply, if any.
   *
   * @param route {@code pause}, {@code resume}, {@code drop-connections} or {@code settle}
   * @return the reply's number (connections dropped, heap bytes after a collection), or 0
   * @throws IOException if the server does not answer
   * @throws InterruptedException if interrupted
   */
  public long admin(String route) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(endpoints.http("/admin/" + route))
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    String body = send(request);
    Matcher number = NUMBER.matcher(body);
    return number.find() ? Long.parseLong(number.group(1)) : 0;
  }

  /**
   * Waits until the server answers its health check.
   *
   * @param patience how long to keep trying
   * @throws IOException if it never answers
   * @throws InterruptedException if interrupted
   */
  public void awaitHealthy(Duration patience) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + patience.toNanos();
    while (true) {
      try {
        get("/health");
        return;
      } catch (IOException notYet) {
        if (System.nanoTime() > deadline) {
          throw notYet;
        }
        Thread.sleep(Duration.ofMillis(Math.max(1, patience.toMillis() / 50)));
      }
    }
  }

  private String get(String path) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(endpoints.http(path)).timeout(timeout).GET().build());
  }

  private String send(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != OK) {
      throw new IOException(request.uri().getPath() + " answered " + response.statusCode());
    }
    return response.body();
  }
}
