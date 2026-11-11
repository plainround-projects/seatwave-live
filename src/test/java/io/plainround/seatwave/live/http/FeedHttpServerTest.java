package io.plainround.seatwave.live.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.FeedPosition;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedHttpServerTest {

  private final HttpClient http = HttpClient.newHttpClient();
  private LiveFeedNode node;

  @BeforeEach
  void start() throws IOException {
    node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
  }

  @AfterEach
  void stop() throws IOException {
    node.close();
    http.close();
  }

  @Test
  void servesTheWholeSeatMapWithItsPosition() throws Exception {
    node.parts().store().hold(0);

    HttpResponse<String> reply = get("/v1/events/1/seat-map");

    assertThat(reply.statusCode()).isEqualTo(200);
    assertThat(reply.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(reply.body())
        .contains(
            "\"eventId\":1",
            "\"position\":\"" + node.parts().feed().streamId() + ".1\"",
            "\"states\":\"haaa");
  }

  @Test
  void answersALongPollAtOnceWhenItHasNews() throws Exception {
    FeedPosition start = node.parts().feed().position();
    node.parts().store().hold(7);

    HttpResponse<String> reply = get("/v1/events/1/seat-changes?after=" + start.written());

    assertThat(reply.body())
        .contains(
            "\"seat\":\"A-8\"",
            "\"state\":\"held\"",
            "\"next\":\"" + start.next().written() + "\"",
            "\"reload\":false");
  }

  @Test
  void holdsALongPollUntilTheNextChange() throws Exception {
    FeedPosition start = node.parts().feed().position();
    CompletableFuture<HttpResponse<String>> waiting =
        getAsync("/v1/events/1/seat-changes?after=" + start.written());
    Thread.sleep(200);

    node.parts().store().hold(3);

    assertThat(waiting.get().body()).contains("\"seat\":\"A-4\"");
  }

  @Test
  void endsAHeldLongPollWithNoChangesAtTheEndOfTheHold() throws Exception {
    FeedPosition start = node.parts().feed().position();

    HttpResponse<String> reply = get("/v1/events/1/seat-changes?after=" + start.written());

    assertThat(reply.body())
        .isEqualTo("{\"changes\":[],\"next\":\"" + start.written() + "\",\"reload\":false}");
  }

  @Test
  void tellsALongPollFromAnotherStreamToReload() throws Exception {
    HttpResponse<String> reply = get("/v1/events/1/seat-changes?after=00000000.5");

    assertThat(reply.body()).contains("\"reload\":true");
  }

  @Test
  void refusesAMissingMalformedUnknownOrRepeatedParameter() throws Exception {
    String path = "/v1/events/1/seat-changes";
    for (String query :
        new String[] {
          "", "?after=5", "?after=00000000.1&limit=3", "?after=00000000.1&after=00000000.2"
        }) {
      HttpResponse<String> reply = get(path + query);

      assertThat(reply.statusCode()).as(query).isEqualTo(400);
      assertThat(reply.headers().firstValue("Content-Type")).contains("application/problem+json");
      assertThat(reply.body())
          .contains("\"type\":\"https://seatwave.example/problems/invalid-request\"", "urn:uuid:");
    }
  }

  @Test
  void answersAnUnknownEventPathOrMethodWithAProblem() throws Exception {
    assertThat(get("/v1/events/2/seat-map").statusCode()).isEqualTo(404);
    assertThat(get("/nothing-here").statusCode()).isEqualTo(404);
    HttpResponse<String> post =
        http.send(
            HttpRequest.newBuilder(uri("/v1/events/1/seat-map"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(post.statusCode()).isEqualTo(405);
    assertThat(post.headers().firstValue("Allow")).contains("GET");
  }

  @Test
  void refusesAConnectionOverTheLimitWithRetryAfter() throws Exception {
    try (LiveFeedNode full =
        LiveFeedNode.start(TestSettings.withConnectionLimit(1), Clock.systemUTC())) {
      String after = full.parts().feed().position().written();
      CompletableFuture<HttpResponse<String>> holding =
          getAsync(full, "/v1/events/1/seat-changes?after=" + after);
      Thread.sleep(200);

      HttpResponse<String> refused = get(full, "/v1/events/1/seat-changes/stream?after=" + after);

      assertThat(refused.statusCode()).isEqualTo(503);
      assertThat(refused.headers().firstValue("Retry-After")).contains("1");
      assertThat(refused.body()).contains("feed-full");
      full.parts().store().hold(1);
      assertThat(holding.get().statusCode()).isEqualTo(200);
    }
  }

  @Test
  void servesHealthMetricsAndTheBrowserPageWithItsSecurityPolicy() throws Exception {
    assertThat(get("/health").body()).isEqualTo("ok\n");
    assertThat(get("/metrics").body())
        .contains("seatwave_feed_open_connections{method=\"sse\"} 0", "process_heap_used_bytes");
    HttpResponse<String> page = get("/");
    assertThat(page.body()).contains("<title>SeatWave live seat map</title>");
    assertThat(page.headers().firstValue("Content-Security-Policy"))
        .hasValueSatisfying(
            policy -> assertThat(policy).contains("ws://127.0.0.1:" + node.webSocketPort()));
    assertThat(get("/live-seat-map.js").body()).contains("EventSource", "WebSocket");
  }

  @Test
  void servesAPageThatNeedsNothingItsPolicyBlocksAndNamesTheRealWebSocketPort() throws Exception {
    String webSocketOrigin = "ws://127.0.0.1:" + node.webSocketPort();

    HttpResponse<String> page = get("/");
    HttpResponse<String> style = get("/live-seat-map.css");
    String script = get("/live-seat-map.js").body();

    // default-src 'self' blocks inline style and script, so the page must have none.
    assertThat(page.body()).doesNotContain("<style", "style=", "<script>");
    assertThat(page.body())
        .contains("<link rel=\"stylesheet\" href=\"/live-seat-map.css\">")
        .contains("<meta name=\"seatwave-websocket\" content=\"" + webSocketOrigin + "\">");
    assertThat(page.headers().firstValue("Content-Security-Policy"))
        .hasValue("default-src 'self'; connect-src 'self' " + webSocketOrigin);
    assertThat(style.headers().firstValue("Content-Type")).hasValue("text/css; charset=utf-8");
    assertThat(style.body()).contains("#map span.h");
    assertThat(script).contains("WEBSOCKET_ORIGIN").doesNotContain("8091");
  }

  @Test
  void pausesResumesDropsAndSettlesThroughTheAdminRoutes() throws Exception {
    assertThat(post("/admin/pause").body()).isEqualTo("{\"onSale\":\"paused\"}");
    assertThat(post("/admin/resume").body()).isEqualTo("{\"onSale\":\"running\"}");
    assertThat(post("/admin/drop-connections").body()).isEqualTo("{\"dropped\":0}");
    assertThat(post("/admin/settle").body()).startsWith("{\"heapUsedBytes\":");
    assertThat(post("/admin/nothing").statusCode()).isEqualTo(404);
    assertThat(get("/admin/pause").statusCode()).isEqualTo(405);
  }

  @Test
  void hidesTheAdminRoutesWhenTheyAreOff() throws Exception {
    LiveSettings base = TestSettings.small();
    LiveSettings closed =
        new LiveSettings(
            base.network(),
            base.limits(),
            base.timing(),
            base.layout(),
            base.onSale(),
            false,
            base.serveTime());
    try (LiveFeedNode locked = LiveFeedNode.start(closed, Clock.systemUTC())) {
      HttpResponse<String> reply =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + locked.httpPort() + "/admin/pause"))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(reply.statusCode()).isEqualTo(404);
    }
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return get(node, path);
  }

  private HttpResponse<String> get(LiveFeedNode target, String path)
      throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.httpPort() + path))
            .timeout(Duration.ofSeconds(10))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> getAsync(String path) {
    return getAsync(node, path);
  }

  private CompletableFuture<HttpResponse<String>> getAsync(LiveFeedNode target, String path) {
    return http.sendAsync(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.httpPort() + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + node.httpPort() + path);
  }
}
