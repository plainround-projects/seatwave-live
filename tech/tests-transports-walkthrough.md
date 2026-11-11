# Walkthrough of the `http`, `websocket` and `client` tests

## What it is

This file walks through every test file of three test packages, in `src/test/java/io/plainround/seatwave/live/`: `http` (2 files, 17 results), `websocket` (3 files, 17 results) and `client` (5 files, 14 results). Together they prove that the three delivery methods work over real sockets: long polling and server-sent events through the JDK's HTTP server, WebSockets through the project's own server, and that a client whose connection is cut ends with exactly the seat map the store has.

Some words first, because these tests speak the protocols directly.

- A **socket** is one end of a network connection between two programs. **TCP** is the protocol that makes a socket a reliable stream of bytes. A **port** is a number that picks one program on a machine. **127.0.0.1** is the **loopback** address: traffic to it never leaves the machine. Every test here uses only loopback.
- **HTTP** is the request-and-reply protocol of the web. A request is text: a **request line** (`GET /path?query HTTP/1.1`, the method, the target and the version), then **header** lines (`Name: value`), then an empty line. A reply starts with a **status line** (`HTTP/1.1 200 OK`), then headers, an empty line and a **body**. Lines end with two characters, **CRLF**, written `\r\n` in Java strings. A **status code** says how it went: 200 fine, 400 the request was wrong, 404 not found, 405 wrong method, 503 busy.
- A **long poll** is an HTTP request that the server holds open until it has news or a time limit passes. **Server-sent events** (SSE) is one HTTP reply that never ends: the server keeps writing small text events into it. **WebSocket** starts as an HTTP request that asks to switch protocols; the server answers `101 Switching Protocols` and from then on both sides send binary **frames** over the same socket.

## Why this repository uses it

A feed that is right in memory but wrong on the wire is wrong. These tests send the exact bytes a browser or a `curl` would send, read the exact bytes the server writes, and check them: the headers, the event format, the status codes, the WebSocket framing rules. They also run the real clients that the comparison uses, against a real node, and cut their connections. The whole of this part takes about 5.3 seconds of the build (`FeedHttpServerTest` 2.5 s, `ReconnectCorrectnessTest` 1.9 s, `WebSocketServerTest` 0.7 s, the rest under 0.2 s).

| File | Results | What it proves |
| --- | --- | --- |
| `http/EventStreamRouteTest.java` | 5 | The event stream: format, resume by `Last-Event-ID`, reload, heartbeat, a bad header. |
| `http/FeedHttpServerTest.java` | 12 | The seat map, long polls, bad requests, the connection limit, health, metrics, the page and what its policy allows, the admin routes. |
| `websocket/FrameCodecTest.java` | 5 | The WebSocket frame format: lengths, masking, refusals and their close codes. |
| `websocket/HandshakeTest.java` | 4 | The opening handshake and each way to refuse it. |
| `websocket/WebSocketServerTest.java` | 8 | The WebSocket server with the JDK's client and with raw bytes, a client's ping included. |
| `client/BackoffTest.java` | 4 | The reconnect wait: random, doubling ceiling, capped. |
| `client/ClientSeatMapTest.java` | 4 | The client's map applies, ignores or refuses each change. |
| `client/EventStreamConnectionTest.java` | 2 | The client reads `data` fields as the event-stream standard says. |
| `client/FeedClientTest.java` | 1 | A failed reload of the seat map is tried again, never followed from the old position. |
| `client/ReconnectCorrectnessTest.java` | 3 | After every connection is dropped twice, every client's map equals the store's. |

## Where it appears

All files are in the test source set and run in `./gradlew test` and `check`. They use the helpers `LiveFeedNode` (the main code's one-call node: seat store, feed, HTTP server, WebSocket server), `TestSettings` (small fast settings with free ports) and `Raw` (a raw TCP client), explained in `tests-other-packages-walkthrough.md`.

## How it works underneath

**How a test of a concurrent, networked program stays deterministic.**
1. **Free ports.** `TestSettings` sets both ports to 0, which asks the operating system for any free port. The test reads the real one with `node.httpPort()`. Two tests never fight for a port.
2. **One node per test.** Each test starts its own `LiveFeedNode` and closes it in a `try` block or `@AfterEach`. Nothing is shared.
3. **The test makes the changes.** No on-sale runs unless the test calls `node.startOnSale()`. Almost every test publishes the changes itself with `node.parts().store().hold(n)`, so it knows exactly which events must arrive, in which order. Only `ReconnectCorrectnessTest` starts the on-sale, and it asserts only invariants (see below).
4. **Wait for a condition, not for a time.** Where the test must wait for the other side, it either blocks on a read with a timeout (`Raw.readUntil`, `Recorder.take`, `HttpResponse` with a timeout) or uses Awaitility (`awaitility.md`).
5. **Assert what must hold under every schedule.** The reconnect test cannot know how many changes happen while a client is away. It asserts that, at the end, each client's map equals the store's map, there is no gap, and the reconnect counter is at least what two drops cause.
6. **A safety net.** Every test has the JUnit default timeout of 60 seconds (`junit.md`), and `Raw` sets a 5-second read timeout on its socket, so a test that waits for bytes that never come fails instead of hanging.

**Which tests rely on real time.** The hold time of a long poll is real: `endsAHeldLongPollWithNoChangesAtTheEndOfTheHold` waits the full 2-second hold (`TestSettings.small()` sets it) and is the slowest test of `http`. `holdsALongPollUntilTheNextChange` and `refusesAConnectionOverTheLimitWithRetryAfter` each sleep 200 ms to let a request reach the server. `sendsACommentLineAsAHeartbeatWhenNothingHappens` and `pingsAnIdleClientThatAnswersAndKeepsItOpen` use a 100 ms heartbeat, so the server's own timer drives them; the second sleeps 500 ms. `ReconnectCorrectnessTest` runs an on-sale at real speed (200 actions per second) and waits, with Awaitility, for conditions on it; it no longer sleeps. `BackoffTest`, `ClientSeatMapTest`, `FrameCodecTest` and `HandshakeTest` use no time at all.

## Walkthrough of this repository's use

### `http/EventStreamRouteTest.java`

```java
package io.plainround.seatwave.live.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.Raw;
import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.feed.FeedPosition;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventStreamRouteTest {
```

- Imports: `LiveFeedNode` starts a node; `Raw` is the raw socket client; `TestSettings` gives the settings; `FeedPosition` is a position; `java.time.Clock` is the clock the node reads; `Duration` sets a heartbeat.
- `class EventStreamRouteTest {` has no fields: each test builds what it needs.

```java
  private static String request(String query, String extraHeaders) {
    return "GET /v1/events/1/seat-changes/stream"
        + (query.isEmpty() ? "" : "?" + query)
        + " HTTP/1.1\r\n"
        + "Host: 127.0.0.1\r\nAccept: text/event-stream\r\n"
        + extraHeaders
        + "\r\n";
  }
```

This helper builds the request text first, because every test uses it.
- `"GET /v1/events/1/seat-changes/stream"` is the request line's method and path: the event-stream route of event 1.
- `(query.isEmpty() ? "" : "?" + query)` appends the query (`after=<position>`) only when there is one. `a ? b : c` is Java's one-line if.
- `" HTTP/1.1\r\n"` ends the request line.
- `"Host: 127.0.0.1\r\nAccept: text/event-stream\r\n"` are two headers: the host (required by HTTP/1.1) and the kind of reply the client wants.
- `extraHeaders` is empty or a header line such as `Last-Event-ID: ...`.
- The last `"\r\n"` is the empty line that ends the headers.

```java
  @Test
  void sendsRetryThenOneEventPerChangeWithItsPositionAsTheId() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      FeedPosition start = node.parts().feed().position();
      stream.send(request("after=" + start.written(), ""));
      String head = stream.readUntil("retry: 1000\n\n");

      node.parts().store().hold(0);
      String event = stream.readUntil("\n\n");

      assertThat(head)
          .contains("HTTP/1.1 200", "Content-type: text/event-stream", "Cache-control: no-store");
      assertThat(event)
          .contains(
              "id: " + start.next().written() + "\n",
              "event: seat\n",
              "data: {\"type\":\"seat\"",
              "\"seat\":\"A-1\"");
    }
  }
```

- `try (LiveFeedNode node = ...; Raw stream = Raw.connect(node.httpPort())) {` starts a node with the small test settings and opens a raw TCP connection to its HTTP port. Both are closed when the block ends, `stream` first.
- `FeedPosition start = node.parts().feed().position();` reads where the feed is now (sequence 0).
- `stream.send(request("after=" + start.written(), ""));` sends the request: "start the event stream after this position".
- `String head = stream.readUntil("retry: 1000\n\n");` reads from the socket until that text has arrived, and returns everything read: the status line, the headers and the first piece of the body. `retry: 1000` is an SSE instruction: a browser that loses the stream should wait 1,000 ms before reconnecting. The node's `retry` setting is 1 second.
- `node.parts().store().hold(0);` the test makes one change: it holds seat 0, which is `A-1`. The feed publishes it; the stream writes one event.
- `String event = stream.readUntil("\n\n");` reads until a blank line, which ends an SSE event.
- The first `assertThat` checks the head: status `HTTP/1.1 200`, the content type `text/event-stream` and `Cache-control: no-store`. (The JDK server writes header names with a lower-case `c` in `Cache-control`.)
- The second checks the event: `id:` is the next position (`start.next()`, sequence 1), `event: seat`, and the `data:` line begins a JSON object of type `seat` that names seat `A-1`. An SSE event has up to three lines: `id` (the browser remembers it for resuming), `event` (the type) and `data` (the payload), ended by a blank line.

```java
  @Test
  void resumesFromLastEventIdEvenWhenTheUrlCarriesAnOlderAfter() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      FeedPosition start = node.parts().feed().position();
      for (int seat = 0; seat < 3; seat++) {
        node.parts().store().hold(seat);
      }
      FeedPosition second = new FeedPosition(start.streamId(), 2);

      stream.send(
          request("after=" + start.written(), "Last-Event-ID: " + second.written() + "\r\n"));
      String events = stream.readUntil("\"seat\":\"A-3\"");

      assertThat(events)
          .doesNotContain("\"seat\":\"A-1\"", "\"seat\":\"A-2\"")
          .contains("id: " + start.streamId() + ".3");
    }
  }
```

This test proves resume. A browser's `EventSource` that loses its stream reconnects to the **same URL** (with the old `after`) and adds the header `Last-Event-ID` with the newest id it saw. The server must prefer the header.
- Three seats are held before the client connects (changes 1, 2 and 3).
- `second` is the position with sequence 2: the client claims to have seen changes up to 2.
- The request carries `after=<start>` (sequence 0, the older value) **and** `Last-Event-ID: <second>\r\n`.
- `stream.readUntil("\"seat\":\"A-3\"")` reads until change 3 appears.
- The checks: the reply does not contain `A-1` or `A-2` (they would be sent if the server obeyed the old `after`), and it contains `id: <stream>.3`. So the server resumed after the header's position and sent only change 3.

```java
  @Test
  void tellsAClientTooFarBehindToReloadAndEndsTheStream() throws Exception {
    try (LiveFeedNode node =
            LiveFeedNode.start(TestSettings.with(2, 64, Duration.ofSeconds(2)), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      FeedPosition start = node.parts().feed().position();
      for (int seat = 0; seat < 5; seat++) {
        node.parts().store().hold(seat);
      }

      stream.send(request("after=" + start.written(), ""));
      String reply = stream.readUntil("\r\n0\r\n\r\n");

      assertThat(reply)
          .contains(
              "event: reload\n",
              "\"type\":\"reload\"",
              "\"position\":\"" + start.streamId() + ".5\"");
    }
  }
```

- `TestSettings.with(2, 64, Duration.ofSeconds(2))` makes a node whose replay buffer keeps only 2 changes (client queue 64, heartbeat 2 s).
- Five seats are held, so the buffer holds changes 4 and 5. A client that says "after sequence 0" is too far behind.
- `stream.readUntil("\r\n0\r\n\r\n")` reads until the end of the reply. The stream is sent in HTTP's **chunked** form (a reply with no known length: each piece is sent with its size), and `0\r\n\r\n` is the closing chunk. So the server ended the stream.
- The checks: the reply contains `event: reload`, a message of type `reload`, and the feed's current position (`<stream>.5`). The client is told to load the seat map again and start from there.

```java
  @Test
  void sendsACommentLineAsAHeartbeatWhenNothingHappens() throws Exception {
    try (LiveFeedNode node =
            LiveFeedNode.start(
                TestSettings.with(100, 64, Duration.ofMillis(100)), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      stream.send(request("after=" + node.parts().feed().position().written(), ""));

      assertThat(stream.readUntil(": heartbeat\n\n")).endsWith(": heartbeat\n\n");
    }
  }
```

- The heartbeat is 100 ms (`Duration.ofMillis(100)`): the node must say something every 100 ms when nothing happens.
- After sending the request the test reads until `": heartbeat\n\n"`. A line that starts with a colon is an SSE **comment**: clients ignore it, but it keeps the connection from looking dead to proxies. `endsWith` checks that the heartbeat is the last thing read. The test depends on the server's own timer (100 ms) and ends as soon as the first heartbeat arrives.

```java
  @Test
  void refusesAMalformedLastEventId() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      stream.send(request("", "Last-Event-ID: seven\r\n"));

      assertThat(stream.readUntil("}")).contains("HTTP/1.1 400", "Last-Event-ID must be");
    }
  }
```

- An empty query and `Last-Event-ID: seven`, which is not a position. The server must refuse with a 400 reply whose problem text names the header. `readUntil("}")` stops at the first closing brace, the end of the JSON problem body, and the assertion looks for `HTTP/1.1 400` and the words `Last-Event-ID must be`.

### `http/FeedHttpServerTest.java`

```java
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
```

- `HttpClient` is the JDK's HTTP client. `private final HttpClient http = HttpClient.newHttpClient();` makes one per test, and `@AfterEach` closes it. `CompletableFuture` is a promise for a reply that arrives later.
- `@BeforeEach void start()` starts the node; `@AfterEach void stop()` closes the node and the client, so every test has a fresh node (`junit.md`).

```java
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
```

The helpers, used by every test:
- `get(path)` sends a GET to the test's node; `get(target, path)` sends it to a given node. `URI.create("http://127.0.0.1:" + target.httpPort() + path)` builds the address. `.timeout(Duration.ofSeconds(10))` makes the client give up after 10 seconds. `HttpResponse.BodyHandlers.ofString()` reads the body as text. `http.send(...)` blocks until the reply arrives.
- `getAsync` uses `sendAsync`, which returns at once with a `CompletableFuture`, so the test can do something else while a long poll is held.
- `post(path)` sends a POST with no body. `uri(path)` builds the address.

```java
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
```

- One seat is held, so the feed is at sequence 1. `GET /v1/events/1/seat-map` returns the whole map.
- The checks: status 200; the `Cache-Control` header contains `no-store` (a stale map would be wrong); the body contains `"eventId":1`, `"position":"<stream>.1"` (the map says which change it includes) and `"states":"haaa` (seat 0 is held, the next are available). Seat map plus position is what a client needs to start following without a gap.

```java
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
```

- The long-poll route `GET .../seat-changes?after=<position>` is asked for what came after `start`, and a change (seat 7, `A-8`) has been made first. The reply comes at once. The body contains the seat `A-8`, the state `held`, `"next":"<position after it>"` (where to ask next) and `"reload":false`.

```java
  @Test
  void holdsALongPollUntilTheNextChange() throws Exception {
    FeedPosition start = node.parts().feed().position();
    CompletableFuture<HttpResponse<String>> waiting =
        getAsync("/v1/events/1/seat-changes?after=" + start.written());
    Thread.sleep(200);

    node.parts().store().hold(3);

    assertThat(waiting.get().body()).contains("\"seat\":\"A-4\"");
  }
```

- `getAsync(...)` sends a long poll with nothing to report, so the server **holds** it. `Thread.sleep(200);` gives the request time to reach the server and start waiting (real time, 200 ms).
- `node.parts().store().hold(3);` makes a change, which wakes the held poll.
- `waiting.get().body()` blocks until the reply arrives and checks it names `A-4`. If the request had reached the server only after the change, the reply would have come at once and the test would still pass; the sleep makes the held path the one tested.

```java
  @Test
  void endsAHeldLongPollWithNoChangesAtTheEndOfTheHold() throws Exception {
    FeedPosition start = node.parts().feed().position();

    HttpResponse<String> reply = get("/v1/events/1/seat-changes?after=" + start.written());

    assertThat(reply.body())
        .isEqualTo("{\"changes\":[],\"next\":\"" + start.written() + "\",\"reload\":false}");
  }
```

- No change is made. The poll is held for the full hold time (2 seconds in `TestSettings`) and then answered with no changes. This is the slowest test of the package, 2.0 seconds by the clock: a real wait.
- The body must be exactly `{"changes":[],"next":"<same position>","reload":false}`: nothing new, ask again from the same place.

```java
  @Test
  void tellsALongPollFromAnotherStreamToReload() throws Exception {
    HttpResponse<String> reply = get("/v1/events/1/seat-changes?after=00000000.5");

    assertThat(reply.body()).contains("\"reload\":true");
  }
```

- Position `00000000.5` belongs to a stream that this node never had (the node's id is random). The reply says `"reload":true`: load the seat map.

```java
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
```

- Four bad queries: none at all; `?after=5` (not a position); `?after=00000000.1&limit=3` (an unknown parameter); `?after=00000000.1&after=00000000.2` (the same parameter twice).
- For each, the status is 400, the content type is `application/problem+json` (the standard JSON form for an HTTP error), and the body contains the problem `type` URL `https://seatwave.example/problems/invalid-request` and an `instance` of the form `urn:uuid:...`. `.as(query)` puts the query in the failure message so the failing case is named (`assertj.md`). The server refuses what it does not understand instead of guessing.

```java
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
```

- Event 2 does not exist: 404. `/nothing-here`: 404. A POST to the seat map: 405 (method not allowed), with an `Allow` header that contains `GET`, which tells the client what is allowed.

```java
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
```

- `try (LiveFeedNode full = ... withConnectionLimit(1))` starts a second node that admits one feed connection.
- A long poll takes the only place and is held; `Thread.sleep(200)` lets it get there. A request for an event stream now arrives and is refused: status 503 (service unavailable), a `Retry-After: 1` header (try again in 1 second), and a body that names the problem `feed-full`.
- The test then makes a change, which ends the held poll, and checks that the held poll's reply is 200: refusing the newcomer did not harm the connection that was already there.

```java
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
```

- `/health` answers `ok` and a newline. `/metrics` contains the counter line `seatwave_feed_open_connections{method="sse"} 0` and the line `process_heap_used_bytes` (the format Prometheus reads).
- `/` returns the browser page with the title `SeatWave live seat map`. The page's header `Content-Security-Policy` must contain `ws://127.0.0.1:<websocket port>`: the page may talk only to this server and its WebSocket port. `hasValueSatisfying` runs the inner check on the header value.
- `/live-seat-map.js` returns the script, which mentions both `EventSource` and `WebSocket`.

```java
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
```

This test proves the three fixes of the browser page of version 1.1.0 without a browser, by reading what the server sends.

- `webSocketOrigin` is the address the page must name: `ws://127.0.0.1:` and the node's real WebSocket port, which the system chose because the test settings ask for port 0.
- Three requests: the page, its style file and its script.
- `doesNotContain("<style", "style=", "<script>")`: the page has no inline style element, no inline style attribute and no inline script. The comment says why that matters: the policy `default-src 'self'` blocks all three, and in version 1.0.0 it blocked the page's own `<style>` element, so the seat map was invisible.
- The page links its style file, and its `meta` element carries the real WebSocket origin: the server replaced the mark `@websocket-origin@`.
- `hasValue(...)` checks the whole policy header: the WebSocket origin it allows is exactly the one the page was told about.
- The style file is served as `text/css` and holds the seat rules (`#map span.h` is the held-seat color).
- The script reads the origin from the page (`WEBSOCKET_ORIGIN`) and has no port number of its own: `8091`, the port version 1.0.0 had written into it, appears nowhere.

What the test cannot see is the browser itself: whether it applies the style and opens the WebSocket. I checked that by hand in a browser (`web-page-and-logging.md`, "Try it yourself").

```java
  @Test
  void pausesResumesDropsAndSettlesThroughTheAdminRoutes() throws Exception {
    assertThat(post("/admin/pause").body()).isEqualTo("{\"onSale\":\"paused\"}");
    assertThat(post("/admin/resume").body()).isEqualTo("{\"onSale\":\"running\"}");
    assertThat(post("/admin/drop-connections").body()).isEqualTo("{\"dropped\":0}");
    assertThat(post("/admin/settle").body()).startsWith("{\"heapUsedBytes\":");
    assertThat(post("/admin/nothing").statusCode()).isEqualTo(404);
    assertThat(get("/admin/pause").statusCode()).isEqualTo(405);
  }
```

- The admin routes exist for the lab and are POST only: `/admin/pause` answers `{"onSale":"paused"}`; `/admin/resume` answers `{"onSale":"running"}`; `/admin/drop-connections` answers `{"dropped":0}` (nothing was open); `/admin/settle` forces a garbage collection and answers `{"heapUsedBytes":...`; an unknown admin path is 404; a GET of a real admin path is 405.

```java
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
```

- The settings are rebuilt with the admin flag (the sixth argument) set to `false`; all else is copied from the small settings. A POST to `/admin/pause` on that node is 404: the routes are hidden, not forbidden, when off. `serve.properties` ships with `admin.enabled=false`, so the real node behaves like this one.

### `websocket/FrameCodecTest.java`

A WebSocket **frame** is a small binary packet. Byte 1 holds a **FIN** bit (this is the last piece of a message), three reserved bits and a four-bit **opcode** (1 text, 8 close, 9 ping, 10 pong). Byte 2 holds a **mask** bit and a length: up to 125 fits in the byte; 126 means "a 16-bit length follows"; 127 means "a 64-bit length follows". A frame from a client must be **masked**: four key bytes follow and the payload is XORed with them (a rule that stops a script in a browser from forging traffic that a proxy might misread). A frame from a server is not masked.

```java
package io.plainround.seatwave.live.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

  private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};
```

- `ByteArrayInputStream` is an input stream over a byte array, so the test feeds the codec bytes without a socket. `EOFException` is "the stream ended". `ByteBuffer` builds bytes.
- `MASK = {0x11, 0x22, 0x33, 0x44}` is the masking key the test uses. `0x11` is a number in base 16.

```java
  @Test
  void encodesEachLengthFormOfAServerFrameUnmasked() {
    assertThat(FrameCodec.encode(Frame.TEXT, new byte[125]))
        .hasSize(127)
        .startsWith((byte) 0x81, (byte) 125);
    assertThat(FrameCodec.encode(Frame.TEXT, new byte[126]))
        .hasSize(130)
        .startsWith((byte) 0x81, (byte) 126, (byte) 0, (byte) 126);
    assertThat(FrameCodec.encode(Frame.TEXT, new byte[65_536]))
        .hasSize(65_546)
        .startsWith((byte) 0x81, (byte) 127);
    assertThat(FrameCodec.encodeClose(CloseCode.NORMAL)).containsExactly(0x88, 2, 0x03, 0xE8);
  }
```

- `FrameCodec.encode(Frame.TEXT, new byte[125])` makes a server text frame with a 125-byte payload. `hasSize(127)` is 2 header bytes plus 125. `startsWith((byte) 0x81, (byte) 125)`: byte 1 is FIN plus opcode 1 (0x81), byte 2 is the length 125 with no mask bit. The `(byte)` casts are needed because Java's `byte` is signed.
- With 126 payload bytes the length does not fit in the second byte: the frame is 130 bytes (4 header bytes plus 126) and starts `0x81, 126, 0, 126` (the marker 126, then the 16-bit length 126 as two bytes).
- With 65,536 bytes (one more than a 16-bit length holds) the frame is 65,546 bytes (a 10-byte header) and the second byte is 127.
- `FrameCodec.encodeClose(CloseCode.NORMAL)` is exactly `0x88, 2, 0x03, 0xE8`: close opcode with FIN, a payload of 2 bytes, and the status code 1,000 (`0x03E8`) in two bytes.

```java
  @Test
  void readsAMaskedClientFrameAndUnmasksIt() throws IOException {
    Frame frame = FrameCodec.read(input(clientFrame(0x80 | Frame.PING, "hello")), 125);

    assertThat(frame.opcode()).isEqualTo(Frame.PING);
    assertThat(new String(frame.payload(), StandardCharsets.UTF_8)).isEqualTo("hello");
  }
```

- `clientFrame(0x80 | Frame.PING, "hello")` builds a masked client ping with the payload `hello`; `FrameCodec.read(input, 125)` reads it (125 is the largest payload accepted). The opcode comes back as `PING` and the payload, unmasked by XORing again, is `hello`.

```java
  @Test
  void refusesUnmaskedFragmentedReservedAndOversizedFrames() {
    assertThatThrownBy(() -> FrameCodec.read(input(new byte[] {(byte) 0x89, 0}), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("masked");
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(Frame.TEXT, "a")), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("fragmented");
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(0xC0 | Frame.TEXT, "a")), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("reserved");
    assertThatThrownBy(
            () -> FrameCodec.read(input(clientFrame(0x80 | Frame.TEXT, "a".repeat(200))), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("larger");
    assertThatThrownBy(
            () -> FrameCodec.read(input(clientFrame(0x80 | Frame.PING, "a".repeat(126))), 1_000))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("control");
    assertThatThrownBy(() -> FrameCodec.read(input(new byte[] {(byte) 0x81}), 125))
        .isInstanceOf(EOFException.class);
  }
```

Six ways to break the rules, each refused with the right reason.
1. `{(byte) 0x89, 0}` is a ping with the mask bit off: refused with a `ProtocolViolation` whose message contains `masked`.
2. `clientFrame(Frame.TEXT, "a")` has the FIN bit off, so it is the first piece of a fragmented text message: `fragmented`. That is legal WebSocket that the feed does not accept, so its close code is 1003.
3. `clientFrame(0xC0 | Frame.TEXT, "a")` sets a reserved bit: `reserved`.
4. A text frame with 200 bytes when only 125 are allowed: `larger`.
5. A ping with 126 bytes: control frames may carry at most 125: `control`.
6. A single byte `0x81`: the stream ends before the second byte, so the codec throws `EOFException`, not a violation.

```java
  @Test
  void refusesAnUnknownOpcodeAndAStrayContinuationAsProtocolErrors() {
    for (int opcode : new int[] {0x3, 0x7, 0xB, 0xF}) {
      assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(0x80 | opcode, "a")), 125))
          .isInstanceOf(ProtocolViolation.class)
          .hasMessageContaining("unknown opcode")
          .extracting(violation -> ((ProtocolViolation) violation).closeCode())
          .isEqualTo(CloseCode.PROTOCOL_ERROR);
    }
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(0x80, "a")), 125))
        .extracting(violation -> ((ProtocolViolation) violation).closeCode())
        .isEqualTo(CloseCode.PROTOCOL_ERROR);
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(Frame.PING, "a")), 125))
        .extracting(violation -> ((ProtocolViolation) violation).closeCode())
        .isEqualTo(CloseCode.PROTOCOL_ERROR);
  }
```

This test is new in version 1.1.0. It checks the close code, not only the message: `extracting(...)` takes a value out of the thrown exception, here `closeCode()` (the cast `(ProtocolViolation) violation` is needed because AssertJ hands the lambda a plain `Throwable`), and `isEqualTo` compares it.

- The loop tries four of the ten opcodes RFC 6455 reserves (3, 7, 11 and 15, the two ends of each reserved range), each with FIN set. Each is refused with the message `unknown opcode` and `CloseCode.PROTOCOL_ERROR`, 1002. Section 5.2 of the RFC says an unknown opcode must fail the connection, and section 7.4.1 names 1002 for that. Version 1.0.0 passed these frames on, and the session closed with 1003, "unsupported data", the code for a valid kind of data the endpoint does not take.
- `clientFrame(0x80, "a")` is FIN with opcode 0, a continuation. The feed never accepts a first fragment, so there is nothing to continue: a protocol error, 1002.
- `clientFrame(Frame.PING, "a")` is a ping without FIN, a fragmented control frame. Section 5.5 forbids that: 1002 too.

```java
  @Test
  void readsALongerFrameAndTheCloseCodeOfAPayload() throws IOException {
    Frame frame = FrameCodec.read(input(clientFrame(0x80 | Frame.TEXT, "b".repeat(300))), 1_000);

    assertThat(frame.payload()).hasSize(300);
    assertThat(FrameCodec.closeCodeOf(new byte[] {0x03, (byte) 0xE9})).isEqualTo(1001);
    assertThat(FrameCodec.closeCodeOf(new byte[0])).isEqualTo(1005);
    assertThat(new ProtocolViolation(CloseCode.TOO_BIG, "x").closeCode().code()).isEqualTo(1009);
  }
```

- A 300-byte masked frame is read whole (`hasSize(300)`), which exercises the 16-bit length path.
- `closeCodeOf` reads the status from a close payload: bytes `0x03, 0xE9` are 1,001 ("going away"). An empty payload is reported as 1,005, the code for "no status received".
- `new ProtocolViolation(CloseCode.TOO_BIG, "x").closeCode().code()` reads the number of a violation made with `CloseCode.TOO_BIG`: 1,009. Version 1.0.0 called a small private class at the end of the file for this one expression; it added nothing, so version 1.1.0 removed it.

```java
  private static ByteArrayInputStream input(byte[] bytes) {
    return new ByteArrayInputStream(bytes);
  }

  // A client frame: masked, with the right length form.
  private static byte[] clientFrame(int firstByte, String text) {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    ByteBuffer frame = ByteBuffer.allocate(payload.length + 14);
    frame.put((byte) firstByte);
    if (payload.length <= 125) {
      frame.put((byte) (0x80 | payload.length));
    } else {
      frame.put((byte) (0x80 | 126));
      frame.putShort((short) payload.length);
    }
    frame.put(MASK);
    for (int index = 0; index < payload.length; index++) {
      frame.put((byte) (payload[index] ^ MASK[index % 4]));
    }
    byte[] bytes = new byte[frame.position()];
    frame.flip();
    frame.get(bytes);
    return bytes;
  }
```

- `input(bytes)` wraps bytes in a stream.
- `clientFrame(firstByte, text)` builds a valid masked client frame: it takes the text's UTF-8 bytes, allocates a buffer of `payload.length + 14` bytes (the largest header this helper writes is 2 + 2 + 4 bytes, so there is room to spare), puts the first byte, then the length with the mask bit `0x80` set (using the 126 form with a two-byte length for payloads above 125), then the mask key, then each payload byte XORed with the key byte `MASK[index % 4]`. `frame.flip()` and `frame.get(bytes)` copy exactly the bytes written into a right-sized array.

### `websocket/HandshakeTest.java`

A WebSocket starts with an HTTP request: `GET <path>`, `Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Version: 13`, a random `Sec-WebSocket-Key` and, from a browser, an `Origin`. The server proves it understood by answering `101 Switching Protocols` with `Sec-WebSocket-Accept`, computed from the key.

```java
package io.plainround.seatwave.live.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HandshakeTest {

  private static final String ORIGIN = "http://127.0.0.1:8090";
  // The example key and accept value printed in RFC 6455 itself.
  private static final String RFC_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

  @Test
  void computesTheAcceptValueOfTheRfcsExample() {
    assertThat(Handshake.acceptFor(RFC_KEY)).isEqualTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
  }
```

- `ORIGIN` is the one origin the server accepts in these tests. An **origin** is the address of the web page that opens the socket; a browser sends it, and the server can refuse pages from other sites.
- `RFC_KEY` is the sample key printed in the standard, RFC 6455; `gitleaks.md` explains why it is safe to keep in a repository. The comment on line 14 says so.
- `Handshake.acceptFor(RFC_KEY)` must equal `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`, the value the RFC prints for that key. This proves the SHA-1 and base64 computation against a published answer.

```java
  @Test
  void acceptsAValidUpgradeFromTheAllowedOrigin() throws IOException {
    Result<HandshakeRequest, HandshakeRefusal> result =
        read(request("GET", Handshake.PATH + "?after=00000000.0", "13", RFC_KEY, ORIGIN));

    assertThat(result.discardError())
        .hasValueSatisfying(request -> {
          assertThat(request.rawQuery()).isEqualTo("after=00000000.0");
          assertThat(new String(Handshake.accepted(request), StandardCharsets.US_ASCII))
              .startsWith("HTTP/1.1 101 Switching Protocols\r\n")
              .contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n");
        });
  }
```

- `read(request(...))` parses a valid handshake from `request("GET", Handshake.PATH + "?after=00000000.0", "13", RFC_KEY, ORIGIN)`: the path with a query, version 13, the RFC key and the allowed origin. The `Result` holds a `HandshakeRequest`.
- `hasValueSatisfying(request -> { ... })` checks that the raw query is kept (`after=00000000.0`) and that `Handshake.accepted(request)` is a reply that starts `HTTP/1.1 101 Switching Protocols\r\n` and contains `Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n`.

```java
  @Test
  void refusesEachBrokenRuleWithItsStatus() throws IOException {
    assertThat(statusOf(request("POST", Handshake.PATH, "13", RFC_KEY, ORIGIN))).isEqualTo(400);
    assertThat(statusOf(request("GET", "/other", "13", RFC_KEY, ORIGIN))).isEqualTo(404);
    assertThat(statusOf(request("GET", Handshake.PATH, "8", RFC_KEY, ORIGIN))).isEqualTo(426);
    assertThat(statusOf(request("GET", Handshake.PATH, "13", "c2hvcnQ=", ORIGIN))).isEqualTo(400);
    assertThat(statusOf(request("GET", Handshake.PATH, "13", "not base64!", ORIGIN)))
        .isEqualTo(400);
    assertThat(statusOf(request("GET", Handshake.PATH, "13", RFC_KEY, "https://evil.example")))
        .isEqualTo(403);
    assertThat(
            statusOf(
                "GET "
                    + Handshake.PATH
                    + " HTTP/1.1\r\nHost: x\r\nSec-WebSocket-Version: 13\r\n\r\n"))
        .isEqualTo(400);
    assertThat(
            statusOf("GET " + Handshake.PATH + " HTTP/1.1\r\nX: " + "y".repeat(9_000) + "\r\n\r\n"))
        .isEqualTo(400);
  }
```

Eight ways to be refused, each with its status:
1. A POST instead of a GET: 400.
2. The wrong path (`/other`): 404.
3. Version 8 instead of 13: 426 (upgrade required, with the supported version in the reply).
4. A key that is valid base64 but is not 16 bytes (`c2hvcnQ=` is the 5 bytes of `short`): 400.
5. A key that is not base64 at all: 400.
6. An origin of another site, `https://evil.example`: 403 (forbidden).
7. A request without `Upgrade` and `Connection` headers: 400.
8. A header line of 9,000 characters: the head is longer than the 8,192 bytes allowed, so 400. This bounds the memory an unfriendly client can make the server use.

```java
  @Test
  void writesARefusalTheClientCanRead() {
    String refused =
        new String(
            Handshake.refused(new HandshakeRefusal(426, "only version 13"), 1),
            StandardCharsets.UTF_8);
    String busy =
        new String(Handshake.refused(ServiceUnavailable.refusal(), 1), StandardCharsets.UTF_8);

    assertThat(refused)
        .startsWith("HTTP/1.1 426 ")
        .contains("Sec-WebSocket-Version: 13\r\n", "Connection: close");
    assertThat(busy).startsWith("HTTP/1.1 503 ").contains("Retry-After: 1\r\n");
  }
```

- `Handshake.refused(refusal, 1)` builds the bytes of an HTTP refusal. A 426 refusal contains `Sec-WebSocket-Version: 13` (tells the client what to use) and `Connection: close`; a 503 refusal (the feed is full) contains `Retry-After: 1`.

```java
  private static int statusOf(String request) throws IOException {
    return switch (read(request)) {
      case Result.Error<HandshakeRequest, HandshakeRefusal>(HandshakeRefusal refusal) ->
          refusal.status();
      case Result.Success<HandshakeRequest, HandshakeRefusal> accepted -> 101;
    };
  }

  private static Result<HandshakeRequest, HandshakeRefusal> read(String request)
      throws IOException {
    return Handshake.read(
        new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII)), ORIGIN);
  }

  private static String request(
      String method, String target, String version, String key, String origin) {
    return method
        + " "
        + target
        + " HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n"
        + "Sec-WebSocket-Version: "
        + version
        + "\r\nSec-WebSocket-Key: "
        + key
        + "\r\nOrigin: "
        + origin
        + "\r\n\r\n";
  }
```

- `statusOf(request)` returns the status of the refusal, or 101 if the request was accepted. It uses a `switch` over the `Result` with **record patterns**: `case Result.Error<...>(HandshakeRefusal refusal) -> refusal.status()` takes the refusal out of the error, and the success case returns 101.
- `read(request)` runs `Handshake.read` on an in-memory stream.
- `request(...)` builds a handshake text from five parts. The `Connection` header is `keep-alive, Upgrade`, as some browsers send it, which proves the server looks for `upgrade` inside the list.

### `websocket/WebSocketServerTest.java`

```java
package io.plainround.seatwave.live.websocket;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.Raw;
import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedPosition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebSocketServerTest {

  private static final Duration WAIT = Duration.ofSeconds(5);
```

- `import static java.nio.charset.StandardCharsets.US_ASCII;` lets the class write `US_ASCII`, the 7-bit text encoding, without the class name.
- `java.net.http.WebSocket` is the JDK's WebSocket **client**. `WebSocket.Listener` is the interface whose methods the client calls when something arrives. `LinkedBlockingQueue` is a queue that one thread fills and another waits on. `CompletionStage` is the JDK's type for "a result that comes later".
- `WAIT` is five seconds, the ceiling for every wait in this class.

```java
  private static WebSocket open(
      HttpClient http, LiveFeedNode node, FeedPosition after, Recorder recorder)
      throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
    URI uri =
        URI.create(
            "ws://127.0.0.1:"
                + node.webSocketPort()
                + Handshake.PATH
                + "?after="
                + after.written());
    return http.newWebSocketBuilder()
        .buildAsync(uri, recorder)
        .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  // A client frame with a short payload, masked as RFC 6455 requires of every client frame.
  private static byte[] maskedFrame(int firstByte, byte[] payload) {
    byte[] mask = {0x11, 0x22, 0x33, 0x44};
    ByteBuffer frame = ByteBuffer.allocate(2 + mask.length + payload.length);
    frame.put((byte) firstByte).put((byte) (0x80 | payload.length)).put(mask);
    for (int index = 0; index < payload.length; index++) {
      frame.put((byte) (payload[index] ^ mask[index % mask.length]));
    }
    return frame.array();
  }
```

- `open(...)` builds the address `ws://127.0.0.1:<port>/v1/events/1/seat-changes/socket?after=<position>` and opens a WebSocket with `http.newWebSocketBuilder().buildAsync(uri, recorder)`. `.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)` waits at most five seconds for the handshake to finish. The returned `WebSocket` is the client's end.
- `maskedFrame(firstByte, payload)` builds one client frame by hand, for the tests that must send bytes the JDK client would never send. The comment says why it masks: RFC 6455 requires every client frame to be masked, and an unmasked one would be refused for that reason before the test's real question is asked. The buffer has room for the 2 header bytes, the 4 mask bytes and the payload. `frame.put(...)` returns the buffer itself, so three puts chain on one line: the first byte, the length with the mask bit `0x80`, and the key. The loop XORs each payload byte with a key byte. The payload must be at most 125 bytes, the short length form, which is all these tests need. `frame.array()` returns the whole buffer, which is exactly full.

```java
  private static String handshake(String query, String extra) {
    return "GET "
        + Handshake.PATH
        + query
        + " HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
        + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
        + extra
        + "\r\n";
  }
```

- `handshake(query, extra)` builds the raw text of a valid handshake request (with the RFC key) for the tests that use `Raw`. `extra` adds one more header line, such as an `Origin`.

```java
  private static final class Recorder implements WebSocket.Listener {

    private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

    String take() throws InterruptedException {
      String event = events.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      return event == null ? "nothing" : event;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      events.add(data.toString());
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
      events.add("ping");
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      events.add("close " + statusCode);
      return null;
    }
  }
```

`Recorder` is the listener. It turns what the client receives into plain strings in a queue.
- `take()` waits up to `WAIT` for the next string and returns `"nothing"` if none came, so a missing event is an assertion failure, not a hang.
- `onText` adds each text message and then calls `webSocket.request(1)`. The JDK client delivers a message only after the listener asks for one (a form of **backpressure**), so the listener asks for the next each time. `onPing` records `"ping"`; `onClose` records `"close "` and the status code. The listener returns `null` to say it has finished with the message.

```java
  @Test
  void deliversEveryChangeToTheJdkClientAsATextFrame() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC())) {
      Recorder recorder = new Recorder();
      WebSocket socket = open(http, node, node.parts().feed().position(), recorder);

      node.parts().store().hold(0);
      node.parts().store().hold(1);

      assertThat(recorder.take()).contains("\"seat\":\"A-1\"");
      assertThat(recorder.take()).contains("\"seat\":\"A-2\"");
      socket
          .sendClose(WebSocket.NORMAL_CLOSURE, "done")
          .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(recorder.take()).isEqualTo("close 1000");
    }
  }
```

- The node and the client are both opened in the `try`. The client connects at the current position.
- Two holds make two changes. `recorder.take()` returns each text frame: the first contains `A-1`, the second `A-2`. Order is the order of the feed.
- `socket.sendClose(WebSocket.NORMAL_CLOSURE, "done")` starts the closing handshake from the client, and the test waits for it to finish. The server answers with its own close frame, and the recorder records `close 1000`.

```java
  @Test
  void pingsAnIdleClientThatAnswersAndKeepsItOpen() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node =
            LiveFeedNode.start(
                TestSettings.with(100, 64, Duration.ofMillis(100)), Clock.systemUTC())) {
      Recorder recorder = new Recorder();
      open(http, node, node.parts().feed().position(), recorder);

      assertThat(recorder.take()).isEqualTo("ping");
      Thread.sleep(500);
      assertThat(node.parts().metrics().openConnections(DeliveryMethod.WEBSOCKET)).isEqualTo(1);
    }
  }
```

- The node's heartbeat is 100 ms. With nothing to send, the server sends a **ping** frame. `recorder.take()` is `"ping"`. The JDK client answers a ping with a pong by itself.
- `Thread.sleep(500);` waits half a second. The server closes a WebSocket that is silent for three heartbeats (300 ms here), so if the client had not answered, the connection would be gone by now. The test checks the node's own count of open WebSocket connections is still 1. This is a real-time test.

```java
  @Test
  void sendsAReloadAndClosesForAPositionTheBufferNoLongerHolds() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node =
            LiveFeedNode.start(
                TestSettings.with(2, 64, Duration.ofSeconds(2)), Clock.systemUTC())) {
      FeedPosition start = node.parts().feed().position();
      for (int seat = 0; seat < 5; seat++) {
        node.parts().store().hold(seat);
      }
      Recorder recorder = new Recorder();

      open(http, node, start, recorder);

      assertThat(recorder.take()).contains("\"type\":\"reload\"");
      assertThat(recorder.take()).isEqualTo("close 1000");
    }
  }
```

- The buffer keeps 2 changes, five are made, and the client connects at the old position. The server sends one text frame, a message of type `reload`, and then a close frame with status 1,000. The recorder sees both, in that order.

```java
  @Test
  void refusesAPageFromAnotherOriginAndAMissingPosition() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw other = Raw.connect(node.webSocketPort());
        Raw noPosition = Raw.connect(node.webSocketPort())) {
      other.send(
          handshake(
              "?after=" + node.parts().feed().position().written(),
              "Origin: https://evil.example\r\n"));
      noPosition.send(handshake("", ""));

      assertThat(other.readUntil("\r\n\r\n")).startsWith("HTTP/1.1 403");
      assertThat(noPosition.readUntil("\r\n\r\n")).startsWith("HTTP/1.1 400");
    }
  }
```

- Two raw connections, `other` and `noPosition`. One sends a handshake with `Origin: https://evil.example` and the other a handshake with no `after` query. `readUntil("\r\n\r\n")` reads each reply's head. The first starts `HTTP/1.1 403`, the second `HTTP/1.1 400`.

```java
  @Test
  void closesAClientThatSendsAnUnmaskedFrameOrADataMessage() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw unmasked = Raw.connect(node.webSocketPort())) {
      unmasked.send(handshake("?after=" + node.parts().feed().position().written(), ""));
      unmasked.readUntil("\r\n\r\n");
      unmasked.sendBytes(new byte[] {(byte) 0x89, 0});
      Recorder recorder = new Recorder();
      WebSocket chatty = open(http, node, node.parts().feed().position(), recorder);
      chatty.sendText("hello", true);

      assertThat(unmasked.input().readNBytes(4)).containsExactly(0x88, 2, 0x03, 0xEA);
      assertThat(recorder.take()).isEqualTo("close 1003");
    }
  }
```

- `unmasked` is a raw connection that completes a handshake (`readUntil` reads the `101` head), then sends the two bytes `0x89, 0`: a ping with the mask bit off. The server must close with a protocol error.
- A second client, `chatty`, is a normal JDK client that sends the text `"hello"`. The JDK masks it correctly, but this feed accepts no messages from clients, so the server closes with status 1,003 (unsupported data).
- `unmasked.input().readNBytes(4)` reads the server's reply: `0x88, 2, 0x03, 0xEA` is a close frame with status 1,002 (protocol error). `recorder.take()` is `close 1003`.

```java
  @Test
  void closesAClientThatSendsAnUnknownOpcodeWithProtocolError() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw client = Raw.connect(node.webSocketPort())) {
      client.send(handshake("?after=" + node.parts().feed().position().written(), ""));
      client.readUntil("\r\n\r\n");

      client.sendBytes(maskedFrame(0x80 | 0x3, new byte[0]));

      assertThat(client.input().readNBytes(4)).containsExactly(0x88, 2, 0x03, 0xEA);
    }
  }
```

- A raw client completes a handshake, then sends one masked frame with FIN set and opcode 3, which RFC 6455 reserves and does not define, with no payload.
- The server must fail the connection with a protocol error: the four bytes `0x88, 2, 0x03, 0xEA` are a close frame (`0x88`), a 2-byte payload, and status `0x03EA`, which is 1,002. Version 1.0.0 answered 1,003 (`0x03, 0xEB`) here, and this test failed against it.

```java
  @Test
  void answersAClientPingWithAPongCarryingTheSamePayload() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw client = Raw.connect(node.webSocketPort())) {
      client.send(handshake("?after=" + node.parts().feed().position().written(), ""));
      client.readUntil("\r\n\r\n");

      client.sendBytes(maskedFrame(0x80 | Frame.PING, "are you there".getBytes(US_ASCII)));

      assertThat(client.input().readNBytes(2)).containsExactly(0x80 | Frame.PONG, 13);
      assertThat(new String(client.input().readNBytes(13), US_ASCII)).isEqualTo("are you there");
    }
  }
```

- The client sends a masked ping with the 13-byte payload `are you there`. RFC 6455 says the answer to a ping is a pong with the same payload.
- `readNBytes(2)` reads the pong's header: `0x80 | Frame.PONG` (FIN and opcode 10, which is `0x8A`) and the length 13, unmasked because a server's frames are never masked. The next 13 bytes are the payload, which must be the same text. Before version 1.1.0 no test sent a ping from the client, so this half of the heartbeat was untested.

```java
  @Test
  void refusesAWebSocketOverTheConnectionLimit() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node =
            LiveFeedNode.start(TestSettings.withConnectionLimit(1), Clock.systemUTC())) {
      open(http, node, node.parts().feed().position(), new Recorder());
      await()
          .atMost(WAIT)
          .until(() -> node.parts().metrics().openConnections(DeliveryMethod.WEBSOCKET) == 1);

      try (Raw second = Raw.connect(node.webSocketPort())) {
        second.send(handshake("?after=" + node.parts().feed().position().written(), ""));

        assertThat(second.readUntil("\r\n\r\n"))
            .startsWith("HTTP/1.1 503")
            .contains("Retry-After: 1");
      }
    }
  }
```

- The node admits one connection. A first WebSocket opens, and `await()...until(...)` waits until the node's counter shows one open WebSocket (`awaitility.md`). A second raw handshake gets `HTTP/1.1 503` with `Retry-After: 1`.

### `client/BackoffTest.java`

When a connection drops, a client waits before it reconnects. If 1,000 clients dropped at the same moment and all waited exactly one second, they would all return at once and drop the server again. The wait is therefore random. This `Backoff` picks a random time between 0 and a ceiling, and the ceiling doubles with each failed attempt up to a cap. (This is called *exponential backoff with full jitter*.)

```java
package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackoffTest {

  private static final Duration BASE = Duration.ofMillis(100);
  private static final Duration CAP = Duration.ofSeconds(5);
```

- `BASE` is the first ceiling, 100 ms. `CAP` is the largest ceiling, 5 seconds.

```java
  @Test
  void waitsAtMostTheDoublingCeilingAndNeverMoreThanTheCap() {
    Backoff backoff = new Backoff(BASE, CAP, 1);

    for (int attempt = 0; attempt < 30; attempt++) {
      long ceiling = Math.min(CAP.toMillis(), BASE.toMillis() << Math.min(attempt, 20));
      for (int sample = 0; sample < 100; sample++) {
        assertThat(backoff.delay(attempt).toMillis()).isBetween(0L, ceiling);
      }
    }
  }
```

- `new Backoff(BASE, CAP, 1)` has the random seed 1. For attempts 0 to 29 the test computes the expected ceiling: `BASE.toMillis() << Math.min(attempt, 20)` shifts 100 left by the attempt number, which doubles it each time (the shift is capped at 20 so it cannot overflow), and `Math.min(CAP.toMillis(), ...)` caps it at 5,000.
- For each attempt it asks for 100 delays and checks each is between 0 and the ceiling, both included.

```java
  @Test
  void spreadsClientsThatFailedAtTheSameMoment() {
    List<Long> firstWaits = new ArrayList<>();
    for (int client = 0; client < 1_000; client++) {
      firstWaits.add(new Backoff(BASE, CAP, client).delay(3).toMillis());
    }

    long distinct = firstWaits.stream().distinct().count();

    assertThat(distinct).isGreaterThan(500);
    assertThat(firstWaits.stream().mapToLong(Long::longValue).average().orElseThrow())
        .isBetween(300.0, 500.0);
  }
```

- 1,000 clients are simulated, each with its own seed (the client number), and each asks for the wait before its fourth attempt (attempt 3, ceiling 800 ms).
- `distinct()` counts the different values: more than 500, so the clients really spread out. The average is between 300 and 500 ms (about 400 expected). The seeds are fixed, so the numbers are the same every run.

```java
  @Test
  void repeatsItsWaitsForTheSameSeed() {
    assertThat(new Backoff(BASE, CAP, 7).delay(4)).isEqualTo(new Backoff(BASE, CAP, 7).delay(4));
  }
```

- The same seed gives the same wait, so a comparison run can be repeated.

```java
  @Test
  void refusesANegativeAttemptOrAnUpsideDownRange() {
    assertThatThrownBy(() -> new Backoff(BASE, CAP, 1).delay(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Backoff(CAP, BASE, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Backoff(Duration.ZERO, CAP, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- Attempt -1, a cap smaller than the base, and a base of zero are all refused.

### `client/ClientSeatMapTest.java`

```java
package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.live.wire.MessageView;
import io.plainround.seatwave.live.wire.SnapshotView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientSeatMapTest {

  private final ClientSeatMap map = new ClientSeatMap();

  @BeforeEach
  void load() {
    map.load(new SnapshotView(1, "0000abcd.10", 1, 5, "aaaaa"));
  }
```

- `SnapshotView` is the JSON shape of the seat map: event 1, position `0000abcd.10`, 1 section of 5 seats, states `aaaaa`. `MessageView` is the shape of one change.
- `@BeforeEach` loads this snapshot before each test, so each test starts with a client at sequence 10.

```java
  private static MessageView seat(String position, String seat, String state) {
    return new MessageView(MessageView.SEAT, position, seat, state, "2026-11-11T10:00:00Z");
  }
```

- `seat(position, seat, state)` builds a seat-change message with a fixed time.

```java
  @Test
  void appliesTheNextChange() {
    assertThat(map.apply(seat("0000abcd.11", "A-2", "held"))).isEqualTo(Applied.APPLIED);
    assertThat(map.states()).isEqualTo("ahaaa");
    assertThat(map.position().written()).isEqualTo("0000abcd.11");
  }
```

- Change 11 (the next) for seat `A-2`, now held: `APPLIED`; the map becomes `ahaaa` and the position `0000abcd.11`.

```java
  @Test
  void ignoresAChangeItAlreadyHas() {
    assertThat(map.apply(seat("0000abcd.10", "A-2", "sold"))).isEqualTo(Applied.DUPLICATE);
    assertThat(map.states()).isEqualTo("aaaaa");
  }
```

- A change with sequence 10, which the map already has: `DUPLICATE`, and the map does not change (the message said `sold`, but the map keeps `aaaaa`). A reconnect can resend a change the client already applied; it must be harmless.

```java
  @Test
  void refusesAChangeAfterAGapOrFromAnotherStream() {
    assertThat(map.apply(seat("0000abcd.12", "A-2", "held"))).isEqualTo(Applied.GAP);
    assertThat(map.apply(seat("ffffffff.11", "A-2", "held"))).isEqualTo(Applied.GAP);
    assertThat(map.sequence()).isEqualTo(10);
  }
```

- Sequence 12 arrives when 11 was expected: `GAP`. A change from the stream `ffffffff` is a gap too. `map.sequence()` stays 10. A gap means the client cannot trust its map, and the real client reloads it.

```java
  @Test
  void refusesAMalformedChangeOrSnapshot() {
    assertThatThrownBy(() -> map.apply(seat("0000abcd.11", "Z-9", "held")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> map.apply(seat("0000abcd.11", "A-1", "taken")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> map.apply(seat("eleven", "A-1", "held")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ClientSeatMap().load(new SnapshotView(1, "0000abcd.1", 1, 5, "aaa")))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- A seat `Z-9`, a state `taken`, a position `eleven`, and a snapshot with 3 states for 5 seats are all malformed. Each throws `IllegalArgumentException`: bad input is refused, not guessed at.

### `client/EventStreamConnectionTest.java`

This test is new in version 1.1.0. It checks how the Java client reads the event-stream format, against two rules of the WHATWG standard that version 1.0.0 got wrong. The feed's own server writes events in one fixed shape, so the tests use a tiny server of their own that sends exactly the bytes the test chooses.

```java
package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.MessageView;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventStreamConnectionTest {

  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final FeedPosition START = new FeedPosition("0000abcd", 10);

```

- `com.sun.net.httpserver.HttpServer` is the JDK's HTTP server, the same one the feed runs on; here it is a stand-in server that sends one fixed stream.
- `MessageView` is the record the client turns each event's JSON into. `FeedJson` is the codec.
- `WAIT` (5 seconds) is the client's timeout for the stream's response headers.
- `START` is the position the client follows from: stream `0000abcd`, sequence 10. The stand-in server ignores it; the client needs one to build its request.

```java
  @Test
  void readsADataFieldWithOrWithoutTheSpaceAfterTheColon() throws Exception {
    String stream =
        "data:{\"type\":\"seat\",\"position\":\"0000abcd.11\",\"seat\":\"A-1\",\"state\":\"held\","
            + "\"changedAt\":\"2026-11-11T10:00:00Z\"}\n\n"
            + "data: {\"type\":\"seat\",\"position\":\"0000abcd.12\",\"seat\":\"A-2\","
            + "\"state\":\"held\",\"changedAt\":\"2026-11-11T10:00:00Z\"}\n\n";

    List<MessageView> received = follow(stream);

    assertThat(received)
        .extracting(MessageView::position)
        .containsExactly("0000abcd.11", "0000abcd.12");
  }
```

- The stream holds two events, each ended by a blank line (`\n\n`). The first is written `data:{...}`, with no space after the colon; the second `data: {...}`, with one space. The standard allows both: one space after the colon is removed if it is there, and none is needed.
- `follow(stream)` serves the stream and returns every message the client handed to its sink.
- `extracting(MessageView::position)` takes the `position` of each message, and `containsExactly` checks both arrived, in order. Version 1.0.0 looked only for `data: ` with the space, so it skipped the first event and this test failed.

```java
  @Test
  void joinsTheDataLinesOfOneEventWithALineFeed() throws Exception {
    // The line feed between the two data lines falls inside a JSON string, where JSON forbids it:
    // a browser's JSON.parse refuses this event, and so must the client. Joined without the line
    // feed, the two halves would wrongly make a valid change.
    String stream =
        "data: {\"type\":\"seat\",\"position\":\"0000abcd.11\",\"seat\":\"A-\n"
            + "data: 1\",\"state\":\"held\",\"changedAt\":\"2026-11-11T10:00:00Z\"}\n\n";

    List<MessageView> received = follow(stream);

    assertThat(received).isEmpty();
  }
```

- One event with two data lines. The standard joins the values of an event's data lines with a line feed. Here the split falls inside the JSON string `"A-1"`, so the joined text has a line feed inside a string, which JSON forbids. A browser's `EventSource` gives `JSON.parse` that text, and it fails; the comment says the client must agree.
- `assertThat(received).isEmpty()`: the client refused the event. (It cannot parse it, so `follow` ends the connection, and the client would reconnect.) Version 1.0.0 joined the lines with nothing between them, turned the two halves into the valid text `"A-1"`, and handed the sink a change no browser would ever see: this test failed against it.
- Joined correctly, a split between two JSON tokens would parse in both versions, so a split inside a string is the case that tells them apart.

```java
  // Serves one event stream on the loopback address and follows it to its end.
  private static List<MessageView> follow(String stream) throws IOException, InterruptedException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = stream.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    Recorder recorder = new Recorder();
    try (HttpClient http = HttpClient.newHttpClient()) {
      new EventStreamConnection(
              http, new FeedEndpoints(server.getAddress().getPort(), 1), new FeedJson(), WAIT)
          .follow(START, recorder);
    } finally {
      server.stop(0);
    }
    return recorder.received;
  }
```

`follow` is the helper both tests use.

- `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1)` makes a server on 127.0.0.1 with a free port (0) and an accept backlog of 1.
- `createContext("/", exchange -> { ... })` answers every path with the same handler: the stream's UTF-8 bytes, the header `Content-Type: text/event-stream`, status 200 with the exact length, then the body. Closing the body stream ends the reply, so the client reads the events and then the end of the stream.
- A new `EventStreamConnection` follows the stand-in server's port (the WebSocket port `1` is never used). `follow(START, recorder)` returns when the stream ends.
- `finally { server.stop(0); }` stops the server at once, also when the client throws.

```java
  private static final class Recorder implements MessageSink {

    private final List<MessageView> received = new ArrayList<>();

    @Override
    public void connected() {
      // Nothing to record: the test looks only at the messages.
    }

    @Override
    public Verdict accept(MessageView message) {
      received.add(message);
      return Verdict.CONTINUE;
    }

    @Override
    public void heartbeat() {
      // The streams in these tests carry no comments.
    }
  }
}
```

`Recorder` is the sink. It keeps every message in `received` and always answers `Verdict.CONTINUE`. `connected` and `heartbeat` do nothing, and their comments say why.

### `client/FeedClientTest.java`

This test is new in version 1.1.0. It checks one path of `FeedClient.run`: a reload instruction, then a failed attempt to load the seat map.

```java
package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeedClientTest {

  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final String OLD_MAP =
      "{\"eventId\":1,\"position\":\"0000abcd.10\",\"sections\":1,\"seatsPerSection\":5,"
          + "\"states\":\"aaaaa\"}";
  private static final String NEW_MAP =
      "{\"eventId\":1,\"position\":\"0000abcd.20\",\"sections\":1,\"seatsPerSection\":5,"
          + "\"states\":\"hhaaa\"}";

```

- `AtomicInteger` counts the stand-in server's requests from its handler thread. `CopyOnWriteArrayList` is a list that is safe to add to from one thread and read from another.
- `OLD_MAP` and `NEW_MAP` are two seat maps as the feed's `/seat-map` route writes them: one section of 5 seats, at positions `0000abcd.10` (all available) and `0000abcd.20` (two seats held).

```java
  @Test
  void triesAFailedReloadAgainInsteadOfFollowingFromTheOldPosition() throws Exception {
    // The seat map answers: the old map, then one failure, then the new map.
    AtomicInteger requests = new AtomicInteger();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
    server.createContext(
        "/",
        exchange -> {
          int request = requests.incrementAndGet();
          if (request == 2) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
          }
          byte[] body = (request == 1 ? OLD_MAP : NEW_MAP).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    ReloadThenStop connection = new ReloadThenStop();
    try (HttpClient http = HttpClient.newHttpClient()) {
      FeedEndpoints endpoints = new FeedEndpoints(server.getAddress().getPort(), 1);
      FeedClient client =
          new FeedClient(
              connection,
              new SeatMapReader(http, endpoints, new FeedJson(), WAIT),
              new Backoff(Duration.ofMillis(1), Duration.ofMillis(5), 1),
              new AtomicBoolean(),
              new ClientStats(10));

      Thread running = Thread.ofVirtual().start(client);
      running.join(WAIT);

      assertThat(running.isAlive()).isFalse();
      assertThat(connection.followedFrom)
          .extracting(FeedPosition::written)
          .containsExactly("0000abcd.10", "0000abcd.20");
      assertThat(client.map().states()).isEqualTo("hhaaa");
    } finally {
      server.stop(0);
    }
  }
```

- The comment says what the stand-in seat-map server does: the first request gets the old map, the second gets status 503 with no body (`-1` means "no body"), every later one gets the new map.
- `ReloadThenStop` (below) is a fake delivery method. A `FeedClient` is built around it, with a real `SeatMapReader` that reads the stand-in server, a backoff of 1 to 5 ms so the test is quick, delay recording off and room for 10 delays.
- `Thread.ofVirtual().start(client)` runs the client; `join(WAIT)` waits for it to end, at most 5 seconds.
- What happens: the client loads the old map (request 1) and follows from `0000abcd.10`. The fake method answers "reload". The client tries to load the map and gets the 503 (request 2). It must try again (request 3), get the new map, and follow from `0000abcd.20`; the fake method then stops it.
- The checks: the client thread ended; the fake method was followed exactly twice, from `0000abcd.10` and then from `0000abcd.20`; the client's map is the new one. In version 1.0.0, after the failed load the client went on with its old map and followed from `0000abcd.10` again, so `followedFrom` was `.10, .10` and the test failed.

```java
  // The first follow is told to reload; the second records its position and stops the client.
  private static final class ReloadThenStop implements FeedConnection {

    private final List<FeedPosition> followedFrom = new CopyOnWriteArrayList<>();

    @Override
    public Ending follow(FeedPosition from, MessageSink sink) {
      followedFrom.add(from);
      if (followedFrom.size() == 1) {
        return Ending.RELOAD;
      }
      Thread.currentThread().interrupt();
      return Ending.DISCONNECTED;
    }

    @Override
    public void abort() {
      // Never silent in this test.
    }
  }
}
```

`ReloadThenStop` is the fake method, described by its comment. `follow` records the position it was asked to follow from. The first time it returns `Ending.RELOAD`, as a server's reload message would. The second time it sets the thread's interrupt flag and returns `DISCONNECTED`; the client then sleeps its backoff, the sleep throws `InterruptedException` because of the flag, and `run` ends. `abort` is never called, because the test has no silence watchdog.

### `client/ReconnectCorrectnessTest.java`

```java
package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.SeatMapSnapshot;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReconnectCorrectnessTest {

  private static final int CLIENTS = 10;
  // About a quarter of a second of the test on-sale's 200 actions per second.
  private static final int CHANGES_AFTER_DROP = 50;
```

- `CLIENTS = 10` clients per delivery method.
- `CHANGES_AFTER_DROP = 50` is how many changes the test lets the on-sale make after the second drop; the comment says that is about a quarter of a second at the test on-sale's 200 actions per second.

```java
  private static FleetPlan plan(DeliveryMethod method, LiveFeedNode node) {
    return new FleetPlan(
        method,
        CLIENTS,
        new FeedEndpoints(node.httpPort(), node.webSocketPort()),
        1_000,
        new FleetPlan.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(6)),
        new FleetPlan.BackoffRange(Duration.ofMillis(20), Duration.ofMillis(200)),
        10_000,
        1);
  }
```

`plan` describes the fleet:
- the delivery method; 10 clients; the node's real ports;
- 1,000 connects per second (so all ten start at once);
- timeouts of 2 s to connect, 5 s per request, and 6 s of silence before a push client drops a quiet link;
- a backoff of 20 to 200 ms, so reconnects are quick;
- room for 10,000 recorded delays per client; and the seed 1.

```java
  @ParameterizedTest
  @EnumSource(DeliveryMethod.class)
  void endsWithTheStoresSeatMapAfterEveryConnectionIsDroppedTwice(DeliveryMethod method)
      throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC())) {
      ClientFleet fleet = ClientFleet.start(plan(method, node));
      try {
        node.startOnSale();
        await().atMost(Duration.ofSeconds(10)).until(() -> fleet.countAtLeast(20) == CLIENTS);
        node.parts().limit().dropAll();
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> fleet.countAtLeast(node.parts().feed().position().sequence()) == CLIENTS);
        long droppedAt = node.parts().feed().position().sequence();
        node.parts().limit().dropAll();
        // Changes keep coming while the clients reconnect, so they resume into a moving feed.
        await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () -> node.parts().feed().position().sequence() >= droppedAt + CHANGES_AFTER_DROP);
        // Pausing waits for a tick already running, so no change can follow the snapshot.
        node.parts().simulator().pause();
        SeatMapSnapshot store = node.parts().store().snapshot();
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> fleet.countAtLeast(store.position().sequence()) == CLIENTS);
        fleet.stop();
        long reconnects = 0;
        for (FeedClient client : fleet.clients()) {
          assertThat(client.map().states()).isEqualTo(store.states());
          assertThat(client.stats().gaps()).isZero();
          reconnects += client.stats().reconnects();
        }
        // Every push client loses its stream both times. A dropped long poll is a GET, which the
        // JDK's HTTP client sends again by itself, so the long-poll client never sees the drop.
        assertThat(reconnects)
            .isGreaterThanOrEqualTo(method == DeliveryMethod.LONG_POLLING ? 0 : 2L * CLIENTS);
      } finally {
        fleet.stop();
      }
    }
  }
```

This is the test of the module's central promise, run once for each of the three methods (`@EnumSource`). Here is what happens, in order.

1. A node starts. `ClientFleet.start(plan(method, node))` starts ten client threads. Each `FeedClient` first loads the seat map over HTTP (`GET /seat-map`), then follows the feed with its method from the map's position.
2. `node.startOnSale()` starts the simulated on-sale: 200 actions per second, so the feed changes all the time.
3. `await()...fleet.countAtLeast(20) == CLIENTS` waits until all ten clients have applied changes up to sequence 20 at least. Now every client is following.
4. **The first forced reconnect.** `node.parts().limit().dropAll()` runs the drop action of every open connection: for an event stream it interrupts the writer, for a WebSocket it closes the socket, for a held long poll it interrupts the handler. The clients' connections end.
5. Each client notices, waits a random backoff (for the first attempt, between 0 and 20 ms in this plan), and connects again with the position of the **last change it applied** (`after=...`, or `Last-Event-ID` for an event stream). The server's catch-up gives it every change it missed, once, or tells it to reload.
6. The next wait, `fleet.countAtLeast(node.parts().feed().position().sequence()) == CLIENTS`, reads the feed's position afresh at every check and waits until every client has reached it. That proves each client reconnected and caught up.
7. **The second forced reconnect.** `droppedAt` notes the feed's sequence, then `dropAll()` drops every connection again. The next wait is on the feed, not on the clients: it ends when 50 more changes have been published (`droppedAt + CHANGES_AFTER_DROP`), so changes really happen while the clients are away and reconnecting. Version 1.0.0 slept 300 ms here instead.
8. `node.parts().simulator().pause()` stops the on-sale, and `node.parts().store().snapshot()` takes the store's seat map together with its position. From now on nothing changes. The comment says why that is certain: `pause()` waits for a tick that is already running to finish, so no change can be published after the snapshot.
9. The third wait: every client's sequence is at least the snapshot's. The clients are caught up with a store that no longer moves.
10. `fleet.stop()` stops the clients and waits for their threads to end.
11. **The proof.** For each client: `assertThat(client.map().states()).isEqualTo(store.states());` the client's seat map, one letter per seat, is identical to the store's. `assertThat(client.stats().gaps()).isZero();` the client never applied a change after a gap. The test adds up `client.stats().reconnects()`.
12. `assertThat(reconnects).isGreaterThanOrEqualTo(method == DeliveryMethod.LONG_POLLING ? 0 : 2L * CLIENTS);` every push client lost its stream twice, so at least 20 reconnects. A long-poll client may count none: the comment says a dropped long poll is a GET, which the JDK's HTTP client sends again by itself, so the client never sees the drop. For long polling, the proof is the equality of the maps.
13. `finally { fleet.stop(); }` stops the fleet even if an assertion failed, so no thread outlives the test. `fleet.stop()` does its work only on the first call, so the second call here returns at once.

The test is deterministic in what it asserts, not in what happens in between: how many changes the clients missed, how long the backoff was and in what order they reconnected differ on every run. The final equality holds under every schedule if the catch-up logic is right. The waits have a 10-second ceiling, and a healthy class run takes about 1.9 seconds for the three methods.

**A race that version 1.1.0 removed.** In version 1.0.0 `pause()` only set a flag. A tick of the on-sale that had already passed that check could still publish one change after the test took its snapshot. The clients then received that change, and one client's map had one more held seat than the snapshot. In 65 runs of this class on a loaded machine, it failed twice, once for the event-stream method and once for the long-polling method, each time with the maps differing in exactly one seat. Nothing was wrong with the feed: the snapshot was taken one change too early. The fix is in `OnSaleSimulator` (`java-feed-walkthrough.md`), where both this test and `SlowConsumer.run` get it, and `OnSaleSimulatorTest` proves it by forcing the interleaving (`tests-feed-walkthrough.md`).

## Try it yourself

All commands ran in a scratch copy of this repository on 11 November 2026. Timings differ on every run.

1. Run the three packages:

```
./gradlew --offline cleanTest test --tests 'io.plainround.seatwave.live.http.*' --tests 'io.plainround.seatwave.live.websocket.*' --tests 'io.plainround.seatwave.live.client.*'
```

   Expected: 48 `PASSED` lines (17 + 17 + 14), then `BUILD SUCCESSFUL`. The long poll that waits for its hold takes about 2 seconds.

2. Speak WebSocket by hand. Start the node (`./gradlew -q serve --args='config/serve.properties'`), read a position with `curl -s http://127.0.0.1:8090/v1/events/1/seat-map`, and use the `curl` command from the README, which sends the RFC key and gets a `101 Switching Protocols` reply with `Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`. This is the same exchange as `HandshakeTest` and `WebSocketServerTest`.

3. Watch the reconnect test for one method:

```
./gradlew --offline cleanTest test --tests '*ReconnectCorrectnessTest'
```

   Expected: three `PASSED` lines, one per method, `ReconnectCorrectnessTest > endsWithTheStoresSeatMapAfterEveryConnectionIsDroppedTwice(DeliveryMethod) > [1] LONG_POLLING PASSED` and the same for `[2] SERVER_SENT_EVENTS` and `[3] WEBSOCKET`.

4. Break the catch-up on purpose, in a scratch copy only. In `SeatFeed.subscribe` (main code) replace the line `subscriber.addAll(changes.changes());` by a comment and run `./gradlew --offline test --tests '*ReconnectCorrectnessTest' --tests '*SeatFeedTest'`. I ran this: `[2] SERVER_SENT_EVENTS` and `[3] WEBSOCKET` of the reconnect test failed at `ReconnectCorrectnessTest.java:51` (`expected: 0L but was: 2L`: the clients counted two gaps, because after a reconnect the next live change did not follow the last one they had), `[1] LONG_POLLING` still passed (it does not use `subscribe`), and `SeatFeedTest.givesASubscriberEveryChangeExactlyOnceWhileChangesArePublishedAsItJoins` failed at line 47. (I ran this again with version 1.1.0.) Put the line back afterwards.

## Mistakes people make

- **Sleeping instead of waiting for a condition.** Most waits here are reads with a timeout or `await()`. The few sleeps only give the other side a head start.
- **Hard-coded ports.** A fixed port makes two test runs collide. Port 0 does not.
- **Forgetting the client's backpressure.** The JDK WebSocket client delivers one message per `request(1)`. Without it, the second message never arrives.
- **Reading until a closing text that never comes.** `Raw.readUntil` stops after a 5-second read timeout and returns what it has plus `<timeout>`, so the assertion fails with the data, not a hang.
- **Testing the protocol only with the project's own client.** `HandshakeTest` and `FrameCodecTest` check the bytes against the RFC's published values; a bug shared by the server and the client would otherwise cancel out.
- **Checking only the happy path.** Half of these tests are refusals: bad queries, bad origins, unmasked frames, full limits.

## Check yourself

1. *How does `Raw.readUntil("\r\n\r\n")` know the reply head is complete?* A blank line (CRLF CRLF) ends the headers of an HTTP message.
2. *Why does `EventStreamRouteTest` send both `after` and `Last-Event-ID`?* A browser reconnects to the old URL and adds the header; the server must prefer the header, and the test proves it.
3. *What are the two close codes in `WebSocketServerTest` for an unmasked frame and for a text message from the client?* 1,002 (protocol error) and 1,003 (unsupported data).
4. *How does `ReconnectCorrectnessTest` force a reconnect, and how does it prove the result?* `limit().dropAll()` closes every connection from the server's side; the proof is that each client's seat map equals the store's snapshot, with zero gaps.
5. *Why can the long-poll client count zero reconnects in that test?* A dropped GET is repeated by the JDK's HTTP client inside one call, so the feed client never sees the drop.

## Further reading

- RFC 6455, The WebSocket Protocol (frames in section 5, handshake in section 4): <https://www.rfc-editor.org/rfc/rfc6455>
- WHATWG HTML Living Standard, server-sent events: <https://html.spec.whatwg.org/multipage/server-sent-events.html>
- RFC 9112, HTTP/1.1 messages: <https://www.rfc-editor.org/rfc/rfc9112>
- RFC 9457, Problem Details for HTTP APIs: <https://www.rfc-editor.org/rfc/rfc9457>
- Java 25 API, `java.net.http.WebSocket`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/WebSocket.html>
- Java 25 API, `java.net.http.HttpClient`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html>
