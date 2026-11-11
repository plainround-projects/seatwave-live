# The Java clients of the feed: `client/`, file by file

## What it is

The `client` package is the other end of the wire. The server pushes seat changes out. These classes are the fans' side: they ask for the seat map, follow the feed with one of three delivery methods, apply every change to their own copy of the map, and recover when the connection breaks.

Three words you need first.

- A **client** is a program that asks another program (the **server**) for something over the network. Here, one `FeedClient` stands for one fan with one browser tab open.
- A **seat map** is the list of every seat and its state: `available`, `held` or `sold`. The server keeps the true one. Each client keeps a copy and must keep that copy equal to the true one.
- A **position** is a place in the feed, written `<stream>.<sequence>`, for example `3f9a2c1e.42`. The stream part is eight random hex digits chosen when the server starts. The sequence part counts changes: change 1, change 2, change 3, with no number skipped. A client that knows its position can say "give me everything after 42".

The package holds sixteen files. Three of them are the three delivery methods (long polling, server-sent events, WebSockets). One is the client itself (`FeedClient`). The rest are small helpers: a backoff timer, a seat-map copy, counters, addresses, and a fleet that starts many clients at once.

## Why this repository uses it

Module 8 compares three ways to push news to a browser. A comparison needs real clients that behave the way real fans' browsers do: they connect, they lose connections, they come back, and they must end up with a correct seat map. These classes are those clients, written on the JDK alone (`java.net.http.HttpClient` and `java.net.http.WebSocket`), so the measured costs belong to the feed and not to a client library.

The clients also hold the module's main promise: **never a silent gap**. Every change is numbered once. A client applies change 43 only if it already has 42. If it sees 45 after 43, it does not patch over the hole. It throws its map away and loads a fresh one.

## Where it appears

| File | What it is |
|---|---|
| `Applied.java` | The three results of applying one change: applied, duplicate, gap. |
| `Verdict.java` | What a client tells a connection after one message: continue or reload. |
| `Ending.java` | How one connection ended: reload or disconnected. |
| `MessageSink.java` | The three calls a connection makes on its client. |
| `FeedConnection.java` | The contract each delivery method fulfils. |
| `Backoff.java` | Waits that double and are randomized. |
| `FeedEndpoints.java` | The feed's URLs on 127.0.0.1. |
| `SeatMapReader.java` | Loads the whole seat map with one request. |
| `ClientSeatMap.java` | A client's copy of the map, with the gap check. |
| `ClientStats.java` | What a client saw, including every delivery delay. |
| `FleetPlan.java` | The settings to start a fleet. |
| `ClientFleet.java` | Starts many clients at a steady rate and watches for silent links. |
| `FeedClient.java` | One fan: load, follow, apply, reconnect, reload. |
| `LongPollConnection.java` | Delivery method 1. |
| `EventStreamConnection.java` | Delivery method 2. |
| `WebSocketConnection.java` | Delivery method 3. |

The benchmark tools in `bench/` use these classes. Their notes are in `java-bench-walkthrough.md`.

## How it works underneath

**One client is one thread.** A **thread** is one line of execution inside a program: a sequence of steps the computer follows. A program can run many threads at once. This package starts every client on a **virtual thread**. A virtual thread is a thread that the JDK (Java's standard library and runtime) schedules itself. It is so cheap that a program can run a million of them, and when one waits for the network, the JDK parks it and lets another run. That is why 1,000 clients that mostly wait fit in one small program. In the code, a client simply waits (blocks) on the network and does not worry about it.

**The loop every client runs.** In words:

1. If I have no seat map, load one. If that fails, wait (backoff) and try again.
2. Follow the feed from the position of my map. Hand every message to my map.
3. When the connection ends, look at why. If the feed said "reload", or I found a gap, count a reload and load the map again. If it just broke, wait a random time (backoff) and follow again from the position I have.
4. Go back to step 2.

**What a message is.** The server sends the same JSON shape for all three methods. A seat change looks like `{"type":"seat","position":"41550af9.368","seat":"D-731","state":"held","changedAt":"2026-11-11T02:39:51.904878Z"}`. A reload instruction has `"type":"reload"` and only a position. In Java this is the record `MessageView` (in the `wire` package).

**The three methods, from the client's side.**

- Long polling: send one request, `GET .../seat-changes?after=<position>`. The server holds it open until there is news or about 25 seconds pass, then answers with a JSON list of changes and the position to ask from next. The client sends the next request at once.
- Server-sent events (SSE): send one request that never ends. The server keeps writing text lines in the event-stream format. The client reads line by line. After a break, the client asks again with the header `Last-Event-ID: <last position>`.
- WebSocket: send an HTTP request that asks to **upgrade** to the WebSocket protocol. The server answers `101 Switching Protocols`, and from then on both sides exchange small **frames** over the same connection. The client receives each frame as a text message.

**What the JDK's HTTP client does for you.** `java.net.http.HttpClient` is a ready-made HTTP client in the JDK. You build one and share it. It keeps a **pool** of open connections and reuses them (keep-alive), so a second request to the same server needs no new handshake. Its `send` method blocks the calling thread until the reply (or the headers, for a stream) arrives, and it throws an `IOException` when the network fails or a timeout passes. A body handler decides what you get back: `ofByteArray()` reads the whole body into memory, `ofInputStream()` hands you a stream to read as bytes arrive.

**What the JDK's WebSocket client does for you.** `HttpClient.newWebSocketBuilder()` builds the upgrade request, checks the server's `101` answer and its `Sec-WebSocket-Accept` header, and then runs the frame protocol: it masks every frame it sends (the protocol requires clients to), it answers a **ping** (a "are you there?" frame the server sends now and then) with a **pong** by itself, and it calls your `Listener` for each text message. It calls the listener only when you have **requested** messages: `request(1)` means "I can take one more". If you do not ask, the JDK stops reading from the socket, TCP's flow control fills, and the server's writes slow down. That is called **backpressure**, and the WebSocket client here uses it on purpose.

**Time.** A client measures a change's delivery delay as `now - changedAt`, where `changedAt` is the timestamp the server's store put in the message. Both programs run on one machine, so they share one clock.

## Walkthrough of this repository's use

Every non-blank line of the sixteen files is quoted below, in the order a beginner should read them: the small types first, then the helpers, then the client, then the three methods.

### `Applied.java`: the result of applying one change

```java
package io.plainround.seatwave.live.client;

```

The package line says this file belongs to `io.plainround.seatwave.live.client`. Every Java file starts with it.

```java
/** What applying one change did to a client's seat map. */
public enum Applied {
```

A `/** ... */` block is a **Javadoc comment**: documentation the compiler ignores and tools turn into web pages. `public enum Applied` declares an **enum**: a type whose values are a fixed, named list. A variable of type `Applied` can only be one of the listed values, never anything else. `public` means any other file may use it.

```java
  /** The change was the next one, and the map now includes it. */
  APPLIED,
  /** The map already had this change; it was ignored. */
  DUPLICATE,
  /** A change was missing before this one, so the map can no longer be trusted. */
  GAP
```

The three values:

- `APPLIED`: the change was exactly the next one (sequence number one above the map's), and the map now includes it.
- `DUPLICATE`: the map already had this change or a later one. It is ignored. A duplicate is harmless; a client that resumes may receive a change twice.
- `GAP`: a change is missing before this one. The map can no longer be trusted. The client must reload.

`ClientSeatMap.apply` returns one of these three; `ClientStats` counts them.

```java
}
```

The closing brace ends the enum.

### `Verdict.java`: what a client tells its connection

```java
package io.plainround.seatwave.live.client;

/** What a client tells its connection after handling one message. */
public enum Verdict {
  /** Keep following the feed. */
  CONTINUE,
  /** Stop and reload the seat map. */
  RELOAD
}
```

Another enum, with two values. After a connection hands the client one message, the client answers with a `Verdict`. `CONTINUE` means "I applied it, keep going". `RELOAD` means "stop, I need a new seat map": the message was a reload instruction, or it exposed a gap. The connection reads this answer and, on `RELOAD`, ends with `Ending.RELOAD`.

### `Ending.java`: why a connection ended

```java
package io.plainround.seatwave.live.client;

/** How one connection to the feed ended. */
public enum Ending {
  /** The feed said the map must be loaded again, or the client found a gap. */
  RELOAD,
  /** The connection failed or closed; the client backs off and reconnects. */
  DISCONNECTED
}
```

The third enum. When `follow` returns, the returned `Ending` tells the client what to do next. `RELOAD` goes to the seat-map reader at once. `DISCONNECTED` goes to a backoff wait first, because the connection failed or closed and hitting the server again immediately could make things worse.

### `MessageSink.java`: what a connection may call on its client

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.wire.MessageView;

/** Receives what a {@link FeedConnection} reads; implemented by {@link FeedClient}. */
```

`import io.plainround.seatwave.live.wire.MessageView;` makes the name `MessageView` usable without its full package path. The Javadoc uses `{@link FeedConnection}` to point at another type; it has no effect at run time.

```java
public interface MessageSink {
```

`public interface MessageSink` declares an **interface**: a list of method signatures with no bodies. A class that `implements` the interface promises to provide those methods. Here `FeedClient` has an inner class that implements it. The connection classes know only this interface, not `FeedClient`, so they can be tested alone.

```java

  /** Called once per connection, when it is established and before its first message. */
  void connected();
```

`void connected();` has no body. A connection calls it once, when the link is established and before its first message. "void" means it returns nothing.

```java

  /**
   * Handles one message.
   *
   * @param message a seat change or a reload instruction
   * @return whether to keep following
   */
  Verdict accept(MessageView message);
```

`Verdict accept(MessageView message);` is called for each message: a seat change or a reload instruction. It returns a `Verdict`. The `@param` and `@return` lines are Javadoc tags that name the parameter and the result.

```java

  /** Called for a heartbeat (an event-stream comment or a WebSocket ping): the link is alive. */
  void heartbeat();
}
```

`void heartbeat();` is called when a quiet signal arrives: a comment line in an event stream, or a WebSocket ping. It does not carry data. It only says "the link is alive". The client records the time. A link that says nothing for too long is cut by the fleet's watchdog (see `ClientFleet`).

### `FeedConnection.java`: the contract of a delivery method

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;

```

The import brings in `FeedPosition`, the feed's cursor type from the `feed` package.

```java
/**
 * One delivery method's way of following the feed from a position until the connection ends: {@link
 * LongPollConnection}, {@link EventStreamConnection} or {@link WebSocketConnection}. The reconnect,
 * backoff and seat-map logic around it is the same for all three, in {@link FeedClient}.
 */
public interface FeedConnection {
```

The class comment names the three implementations. It states the design: **the reconnect, backoff and seat-map logic is the same for all three, so it lives once in `FeedClient`**. A delivery method only knows how to follow the feed until its connection ends.

```java

  /**
   * Follows the feed, handing every message to the sink.
   *
   * @param from the position of the last change the client has
   * @param sink where messages go
   * @return why the connection ended
   * @throws InterruptedException if the client is being stopped
   */
  Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException;
```

`Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException;` is the one important method.

- `from` is the position of the last change the client has. The connection asks the server for everything after it.
- `sink` is where each message goes.
- The return value is why the connection ended.
- `throws InterruptedException` is Java's way to say that the method may stop early because its thread was **interrupted**. Interrupting is how one thread asks another to stop. The fleet interrupts every client thread to shut down.

```java

  /**
   * Ends the connection being followed, from another thread, so {@link #follow} returns {@link
   * Ending#DISCONNECTED}. Used when the link has been silent longer than three heartbeats: a peer
   * or a middlebox may have dropped it without telling anyone.
   */
  void abort();
}
```

`void abort();` ends the connection from a different thread, so that the blocked `follow` returns `Ending.DISCONNECTED`. The fleet's watchdog calls it for a link that has been silent longer than three heartbeats. A phone network or a proxy can drop a link without telling either side, so a client that waits forever would wait for nothing.

### `Backoff.java`: waits that double and are randomized

**Why backoff exists.** When a server fails, every client that was connected loses its connection at the same moment. If they all reconnect at once, the server gets a stampede just as it is weakest. Two ideas fix it. **Exponential backoff**: each failed attempt doubles the longest wait, so a long outage means gentle, rare attempts. **Jitter**: the wait is a random number from zero up to that longest wait, so clients spread out instead of arriving together. Doing both, with the random number between zero and the ceiling, is called **full jitter**. A **cap** stops the ceiling from growing forever.

Here is the real behaviour of this class, from a small program I ran with base 100 ms, cap 5,000 ms and seed 1 (the same seed gives the same numbers on every run):

```text
attempt 0 ceiling 100 ms, waited 70 ms
attempt 1 ceiling 200 ms, waited 105 ms
attempt 2 ceiling 400 ms, waited 2 ms
attempt 3 ceiling 800 ms, waited 435 ms
attempt 4 ceiling 1600 ms, waited 1350 ms
attempt 5 ceiling 3200 ms, waited 3170 ms
attempt 6 ceiling 5000 ms, waited 1402 ms
attempt 7 ceiling 5000 ms, waited 2351 ms
attempt 8 ceiling 5000 ms, waited 485 ms
```

The ceiling doubles (100, 200, 400, ...) until the cap, 5,000. The wait is random under the ceiling: attempt 2 waited only 2 ms, attempt 5 waited 3,170 ms.

```java
package io.plainround.seatwave.live.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

```

The imports: `Duration` is the JDK's type for a length of time; `Objects` has `requireNonNull`; `Random` is the JDK's random-number generator.

```java
/**
 * Exponential backoff with full jitter: the wait before reconnect attempt {@code n} is a random
 * time from zero to {@code min(cap, base × 2^n)}. The doubling stops a failing server from being
 * hit harder; the randomness spreads clients that failed at the same moment, so their reconnects do
 * not arrive together (the guidelines' rule 66: backoff, jitter and a cap).
 *
 * <p>Not thread-safe: each client owns one, with its own seeded generator, so runs repeat.
 */
public final class Backoff {
```

The comment says what the class does and ends with "the guidelines' rule 66": the course's rule that retries need backoff, jitter and a cap. `public final class Backoff` declares a class that no other class may extend (`final`).

```java

  // Beyond 2^20 the doubling has long passed any sane cap; stopping here avoids overflow.
  private static final int MAX_DOUBLINGS = 20;
```

`MAX_DOUBLINGS` is a **constant** (`static final`: one value for the whole program, never changed). Doubling more than 20 times gives a number above a million times the base, far beyond any cap; stopping at 20 also stops the shift in `delay` from overflowing the 64-bit `long`. (A `//` line is an ordinary comment.)

```java

  private final Duration base;
  private final Duration cap;
  private final Random random;
```

Three fields, each `private final`: only this class can see them and they are set once. `base` is the ceiling of the first wait. `cap` is the largest ceiling. `random` is this client's own generator.

```java

  /**
   * Creates the backoff.
   *
   * @param base the ceiling of the first wait
   * @param cap the largest ceiling of any wait
   * @param seed the seed of this client's random waits
   */
  public Backoff(Duration base, Duration cap, long seed) {
    this.base = Objects.requireNonNull(base, "base");
    this.cap = Objects.requireNonNull(cap, "cap");
    if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
      throw new IllegalArgumentException("base must be positive and cap at least base");
    }
    this.random = new Random(seed);
  }
```

The **constructor** (the code that builds a `Backoff`) takes the base, the cap and a seed.

- `Objects.requireNonNull(base, "base")` throws a `NullPointerException` with the message "base" if the value is null. It also returns the value, so it can sit on the right of `=`.
- The `if` rejects a base that is negative or zero (`isNegative()`, `isZero()`) and a cap smaller than the base (`cap.compareTo(base) < 0`). A zero base would give a zero wait forever and defeat the point. `||` means "or".
- `new Random(seed)` makes a generator that always produces the same sequence for the same seed. Each client gets its own seed (`seed + clientIndex`, see `ClientFleet`), so runs repeat, yet clients differ from each other.

```java

  /**
   * The wait before an attempt.
   *
   * @param attempt 0 for the first retry, then 1, 2 and so on
   * @return a random wait from zero to the attempt's ceiling
   */
  public Duration delay(int attempt) {
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    long ceilingMillis =
        Math.min(cap.toMillis(), base.toMillis() << Math.min(attempt, MAX_DOUBLINGS));
    return Duration.ofMillis(random.nextLong(ceilingMillis + 1));
  }
}
```

`delay(int attempt)` returns the wait before retry number `attempt` (0 for the first retry).

- A negative attempt is a programming error, so it throws `IllegalArgumentException`.
- `base.toMillis() << Math.min(attempt, MAX_DOUBLINGS)`: `<<` is a **left shift**. Shifting a number left by `n` bits multiplies it by 2 to the power `n`. So 100 shifted by 3 is 800. `Math.min` keeps `n` at 20 at most.
- `Math.min(cap.toMillis(), ...)` applies the cap. The result is the ceiling, in milliseconds.
- `random.nextLong(ceilingMillis + 1)` returns a random whole number from 0 up to and including the ceiling (the argument is an exclusive upper bound, hence the `+ 1`).
- `Duration.ofMillis(...)` wraps it as a `Duration`.

### `FeedEndpoints.java`: the feed's addresses

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;
import java.net.URI;
import java.util.Objects;

```

Imports. `URI` is the JDK's type for a web address.

```java
/**
 * The feed's addresses on 127.0.0.1. Positions contain only hex digits, digits and a dot, so they
 * go into a query string as they are.
 *
 * @param httpPort the HTTP server's port
 * @param webSocketPort the WebSocket server's port
 */
public record FeedEndpoints(int httpPort, int webSocketPort) {
```

The comment notes why positions can be pasted into a URL unchanged: they contain only hex digits, digits and a dot. `public record FeedEndpoints(int httpPort, int webSocketPort)` declares a **record**: a short way to declare a class that only holds values. Java generates the constructor, the accessor methods `httpPort()` and `webSocketPort()`, `equals`, `hashCode` and `toString`. A record's fields are fixed once built.

```java

  private static final String EVENT = "/v1/events/1";

```

`EVENT` is the path prefix of the one event this feed serves: `/v1/events/1`. It is a constant.

```java
  /** Checks the ports. */
  public FeedEndpoints {
    if (httpPort < 1 || webSocketPort < 1) {
      throw new IllegalArgumentException("ports must be positive");
    }
  }
```

This is a **compact constructor**: it has no parameter list because it uses the record's own. It runs before the fields are set and may reject bad values. A port below 1 is not a port, so it throws.

```java

  /**
   * The seat map.
   *
   * @return its URI
   */
  public URI seatMap() {
    return http(EVENT + "/seat-map");
  }
```

`seatMap()` returns the address of the whole map: `http://127.0.0.1:<httpPort>/v1/events/1/seat-map`. It builds it with `http`, the last method in the file.

```java

  /**
   * A long poll from a position.
   *
   * @param after the client's position
   * @return its URI
   */
  public URI longPoll(FeedPosition after) {
    return http(EVENT + "/seat-changes?after=" + Objects.requireNonNull(after, "after").written());
  }
```

`longPoll(after)` adds `/seat-changes?after=` and the written form of the position (for example `41550af9.368`). `Objects.requireNonNull(after, "after")` guards a null here; `written()` is `FeedPosition`'s text form.

```java

  /**
   * The event stream from a position.
   *
   * @param after the client's position
   * @return its URI
   */
  public URI stream(FeedPosition after) {
    return http(EVENT + "/seat-changes/stream?after=" + after.written());
  }
```

`stream(after)` is the event-stream address: `/seat-changes/stream?after=<position>`.

```java

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
```

`socket(after)` is the WebSocket address. It starts with `ws://`, the WebSocket scheme, and uses `webSocketPort` because the WebSocket server listens on its own port (8091 beside 8090). The string is joined with `+` over several lines to keep each line short. `URI.create(...)` parses the string and throws if it is malformed.

```java

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
```

`http(path)` is the helper the other methods use: `"http://127.0.0.1:" + httpPort + path`. The host is always `127.0.0.1`, the **loopback** address: traffic to it never leaves this computer. That is why all measurements here have no network distance in them.

### `SeatMapReader.java`: load the whole map once

```java
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

```

Imports for the JSON codec (`FeedJson`), the map's JSON shape (`SnapshotView`), and the JDK HTTP client types: `HttpClient` sends, `HttpRequest` describes what to send, `HttpResponse` is what comes back. `Optional` is the JDK's "a value, or nothing" type.

```java
/**
 * Reads the whole seat map from the feed. Thread-safe: it shares one JDK HTTP client, which is
 * itself safe to share.
 */
public final class SeatMapReader {
```

The comment says it is thread-safe because the JDK's `HttpClient` is safe to share between threads. All 1,000 clients of a fleet use one `SeatMapReader`, which means one `HttpClient`. A long-lived client is created once and shared; creating one per request would waste connections.

```java

  private static final int OK = 200;
```

`OK = 200` is the HTTP status code that means "success".

```java

  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json;
  private final Duration timeout;

```

Four fields: the HTTP client, the addresses, the JSON codec, and the longest time to wait for the whole reply.

```java
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
```

The constructor stores its four arguments after checking none is null.

```java

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
```

`read()` returns `Optional<SnapshotView>`: the map, or empty if anything went wrong. It does not throw for network trouble; the caller (`FeedClient.loadMap`) backs off and tries again.

- `HttpRequest.newBuilder(endpoints.seatMap())` starts a request for the seat-map URL. `.timeout(timeout)` sets how long `send` may wait for the reply. `.GET()` chooses the GET method. `.build()` produces the request.
- `http.send(request, HttpResponse.BodyHandlers.ofByteArray())` sends it and **blocks** until the whole body has arrived as a byte array. This is the JDK client at work: it finds or opens a connection, writes the request, reads the status line, the headers and the body.
- A status other than 200 returns `Optional.empty()`.
- `json.read(response.body(), SnapshotView.class)` parses the bytes as JSON into a `SnapshotView` record. `SnapshotView.class` tells the codec which type to build.
- `catch (IOException failed)` handles a refused connection, a reset or a timeout (the JDK's timeout exceptions are kinds of `IOException`). The map and its failures are expected, so they are values, not crashes.
- `throws InterruptedException` is declared because `send` is interruptible: if the thread is told to stop while waiting, the exception passes up to `FeedClient.run`.

### `ClientSeatMap.java`: the client's copy, with the gap check

This is where "never a silent gap" is enforced on the client. Three rules, applied to every change that arrives:

1. A change from another **stream** (another server process, with another random stream id) cannot be placed in this map's history. It counts as a gap.
2. A change whose sequence is not above the map's is a **duplicate**. Ignore it.
3. A change whose sequence is not exactly one above the map's is a **gap**. Do not apply it.

Here is what I got from a small program that loads a six-seat map at `abcd1234.5` and applies changes 6, 6, 8, 7:

```text
loaded at abcd1234.5
change 6 -> APPLIED
change 6 -> DUPLICATE
change 8 -> GAP
change 7 -> APPLIED
states haaaaa at abcd1234.7
```

(In the real client the GAP answer makes `FeedClient` reload at once, so change 7 would never be applied to that map.)

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.feed.SeatState;
import io.plainround.seatwave.live.wire.MessageView;
import io.plainround.seatwave.live.wire.SnapshotView;
import java.util.Objects;

```

Imports: the position type, the seat layout (sections and seats per section), the seat states, and the two JSON shapes.

```java
/**
 * A client's copy of the seat map: loaded from a snapshot, then kept current by applying changes in
 * sequence order. It checks every change against its own position, so a gap or a duplicate is
 * counted and never silently applied.
 *
 * <p>Not thread-safe: one client thread owns it; {@link #sequence()} may be read by any thread.
 */
public final class ClientSeatMap {
```

The comment states the contract: load from a snapshot, then apply changes in sequence order, and **count** a gap or duplicate instead of applying it. "Not thread-safe: one client thread owns it" means only one thread may change it; `sequence()` is the one method other threads may call.

```java

  private char[] states = new char[0];
  private SeatLayout layout = new SeatLayout(1, 1);
  private String streamId = "00000000";
  private volatile long sequence;
  private boolean loaded;
```

The state of the map.

- `char[] states` holds one letter per seat (`a`, `h`, `s`). `new char[0]` is an empty array until the first load.
- `layout` converts a seat label such as `D-731` to an index into that array. `new SeatLayout(1, 1)` is a placeholder.
- `streamId` is the stream the map belongs to; `"00000000"` is a placeholder.
- `volatile long sequence` is the sequence of the last change applied. **`volatile`** tells the JVM that other threads read this field, so every write must be visible to them at once. The fleet's `countAtLeast` reads it from another thread.
- `loaded` says whether a snapshot has ever been loaded.

```java

  /**
   * Replaces the map with a snapshot.
   *
   * @param snapshot the seat map as the feed sent it
   * @throws IllegalArgumentException if the snapshot is malformed
   */
  public void load(SnapshotView snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    SeatLayout snapshotLayout = new SeatLayout(snapshot.sections(), snapshot.seatsPerSection());
    if (snapshot.states() == null || snapshot.states().length() != snapshotLayout.seatCount()) {
      throw new IllegalArgumentException("the snapshot does not have one state per seat");
    }
    FeedPosition position =
        FeedPosition.parse(String.valueOf(snapshot.position()))
            .orOnErrorThrow(IllegalArgumentException::new);
    layout = snapshotLayout;
    states = snapshot.states().toCharArray();
    streamId = position.streamId();
    sequence = position.sequence();
    loaded = true;
  }
```

`load(snapshot)` replaces the whole map.

- It builds the layout from the snapshot's section count and seats per section.
- The snapshot must have exactly one state letter per seat (`seatCount()`). Otherwise it is malformed and the method throws `IllegalArgumentException`.
- `FeedPosition.parse(...)` returns a result4j `Result` (see `result4j.md`): a success holding the position, or an error holding a message. `String.valueOf(snapshot.position())` turns the field into text even if it is null (it becomes the text "null", which fails the parse). `.orOnErrorThrow(IllegalArgumentException::new)` unwraps the success, or throws a new `IllegalArgumentException` built from the error message. `IllegalArgumentException::new` is a **method reference**: shorthand for "call this constructor with the message".
- `toCharArray()` copies the state text into the array. The stream id and sequence are taken from the position, and `loaded` becomes true.

```java

  /**
   * Applies one seat change.
   *
   * @param message a message of type {@code seat}
   * @return whether it was applied, a duplicate, or after a gap
   * @throws IllegalArgumentException if the message is malformed
   */
  public Applied apply(MessageView message) {
    FeedPosition position =
        FeedPosition.parse(String.valueOf(message.position()))
            .orOnErrorThrow(IllegalArgumentException::new);
    if (!position.streamId().equals(streamId)) {
      // A change from another stream cannot be placed in this map's history.
      return Applied.GAP;
    }
    if (position.sequence() <= sequence) {
      return Applied.DUPLICATE;
    }
    if (position.sequence() != sequence + 1) {
      return Applied.GAP;
    }
    int seatIndex =
        layout
            .indexOf(String.valueOf(message.seat()))
            .orElseThrow(() -> new IllegalArgumentException("unknown seat " + message.seat()));
    SeatState state =
        SeatState.fromWireName(String.valueOf(message.state()))
            .orElseThrow(() -> new IllegalArgumentException("unknown state " + message.state()));
    states[seatIndex] = state.code();
    sequence = position.sequence();
    return Applied.APPLIED;
  }
```

`apply(message)` is the check. In order:

- Parse the message's position (throws if it is malformed).
- Another stream id: return `GAP` (rule 1).
- `position.sequence() <= sequence`: return `DUPLICATE` (rule 2).
- `position.sequence() != sequence + 1`: return `GAP` (rule 3).
- Otherwise look up the seat's index: `layout.indexOf(label)` returns an `Optional<Integer>`; `.orElseThrow(() -> new IllegalArgumentException(...))` throws if the label is unknown. `() -> ...` is a **lambda**: a small unnamed function, run only if needed.
- Look up the state: `SeatState.fromWireName("held")` returns the enum value; `state.code()` is its letter.
- Write the letter into the array, move `sequence` forward, return `APPLIED`.

The order matters: the sequence moves only after the write succeeded.

```java

  /**
   * Tells whether a snapshot has been loaded.
   *
   * @return true after the first {@link #load}
   */
  public boolean isLoaded() {
    return loaded;
  }
```

`isLoaded()` returns whether a snapshot has been loaded. `FeedClient.run` uses it to decide whether to load first.

```java

  /**
   * The position of the last change applied.
   *
   * @return the position
   */
  public FeedPosition position() {
    return new FeedPosition(streamId, sequence);
  }
```

`position()` builds a `FeedPosition` from the stream id and sequence. It is what a connection is told to follow from.

```java

  /**
   * The sequence of the last change applied, safe to read from another thread.
   *
   * @return the sequence
   */
  public long sequence() {
    return sequence;
  }
```

`sequence()` returns the sequence. It is safe from another thread because the field is `volatile`.

```java

  /**
   * The map as one letter per seat, the snapshot's format, to compare with the store.
   *
   * @return the states
   */
  public String states() {
    return new String(states);
  }
}
```

`states()` returns the map as one string, one letter per seat: the same format as the snapshot. The benchmark compares it with the store's own string to prove that the client ended correct.

### `ClientStats.java`: what one client saw

```java
package io.plainround.seatwave.live.client;

import java.util.Arrays;

```

`java.util.Arrays` gives `copyOf`.

```java
/**
 * What one client saw: messages, gaps, duplicates, reloads, reconnects, and the delivery delay of
 * every change received while recording was on.
 *
 * <p>Delays go into a fixed-size array; any beyond its capacity are counted but not kept, so a long
 * run cannot grow the client's memory. Not thread-safe: written by one client thread and read after
 * that thread has stopped.
 */
public final class ClientStats {

```

The comment makes a design choice explicit: delays go into a **fixed-size array**. After the array is full, new delays are counted but not kept, so a long run cannot grow a client's memory. The array is allocated whole when the client is made: the benchmark asks for 20,000 slots of 8 bytes (`DELAYS_PER_CLIENT` in `Comparison`), which is 160 KB per client and 160 MB for 1,000 clients, paid up front.

```java
  private final long[] delaysMicros;
  private int delayCount;
  private long delaysDropped;
  private long changesApplied;
  private long duplicates;
  private long gaps;
  private long reloads;
  private long reconnects;
  private long longestOutageMillis;

```

Fields: the delay array (`long[]`, in microseconds), how many slots are used (`delayCount`), delays dropped for lack of room, and counters for applied, duplicates, gaps, reloads, reconnects, and the longest outage in milliseconds. None is `volatile` or locked: "written by one client thread and read after that thread has stopped", as the comment says. A thread that has ended makes its writes visible to the thread that joined it.

```java
  /**
   * Creates empty statistics.
   *
   * @param delayCapacity how many delays to keep
   */
  public ClientStats(int delayCapacity) {
    this.delaysMicros = new long[delayCapacity];
  }

```

The constructor allocates the array once: `new long[delayCapacity]`.

```java
  void recordDelay(long micros) {
    if (delayCount < delaysMicros.length) {
      delaysMicros[delayCount++] = micros;
    } else {
      delaysDropped++;
    }
  }

```

`recordDelay(micros)`: if there is room, store the delay and increment the index (`delayCount++` uses the old value, then adds one). Otherwise count a drop. The methods have no `public`, so only classes in this package (the fleet and the client) may call them.

```java
  void recordApplied(Applied applied) {
    switch (applied) {
      case APPLIED -> changesApplied++;
      case DUPLICATE -> duplicates++;
      case GAP -> gaps++;
    }
  }

```

`recordApplied(applied)` uses a **switch with arrows** over the enum: one counter per case. `case APPLIED -> changesApplied++;` runs only that line. Because `Applied` has exactly three values and all three are listed, the compiler knows every case is covered.

```java
  void recordReload() {
    reloads++;
  }

```

`recordReload()` counts a reload of the whole map.

```java
  void recordReconnect(long outageMillis) {
    reconnects++;
    longestOutageMillis = Math.max(longestOutageMillis, outageMillis);
  }

```

`recordReconnect(outageMillis)` counts a connection re-established and keeps the largest outage with `Math.max`.

```java
  /**
   * The delays kept, in microseconds.
   *
   * @return a copy
   */
  public long[] delaysMicros() {
    return Arrays.copyOf(delaysMicros, delayCount);
  }

  /**
   * Delays not kept because the array was full.
   *
   * @return the count
   */
  public long delaysDropped() {
    return delaysDropped;
  }

  /**
   * Changes applied to the map.
   *
   * @return the count
   */
  public long changesApplied() {
    return changesApplied;
  }

  /**
   * Changes received twice and ignored.
   *
   * @return the count
   */
  public long duplicates() {
    return duplicates;
  }

  /**
   * Changes that arrived after a missing one.
   *
   * @return the count; the feed's invariant says it is always 0
   */
  public long gaps() {
    return gaps;
  }

  /**
   * Reloads of the whole seat map after the first load.
   *
   * @return the count
   */
  public long reloads() {
    return reloads;
  }

  /**
   * Connections established again after one ended.
   *
   * @return the count
   */
  public long reconnects() {
    return reconnects;
  }

  /**
   * The longest time from a connection ending to the next one being established.
   *
   * @return milliseconds
   */
  public long longestOutageMillis() {
    return longestOutageMillis;
  }
}
```

The rest are read-only accessors. `delaysMicros()` returns `Arrays.copyOf(delaysMicros, delayCount)`: a copy of only the used part, so callers cannot change the original. `delaysDropped()`, `changesApplied()`, `duplicates()`, `gaps()` (the feed's invariant says it is always 0), `reloads()`, `reconnects()` and `longestOutageMillis()` each return one field.

### `FleetPlan.java`: the settings to start a fleet

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.time.Duration;
import java.util.Objects;

```

Imports: `DeliveryMethod` is the enum of the three methods (`LONG_POLLING`, `SERVER_SENT_EVENTS`, `WEBSOCKET`).

```java
/**
 * What a {@link ClientFleet} starts.
 *
 * @param method the delivery method every client uses
 * @param clients how many clients
 * @param endpoints the feed's addresses
 * @param connectsPerSecond how many clients start per second
 * @param timeouts the connect and request timeouts
 * @param backoff the reconnect backoff's base and cap
 * @param delayCapacity how many delays each client keeps
 * @param seed the first client's backoff seed; client n uses seed + n
 */
```

The Javadoc lists each field of the record. Note `seed`: the first client's backoff seed; client number n uses `seed + n`.

```java
public record FleetPlan(
    DeliveryMethod method,
    int clients,
    FeedEndpoints endpoints,
    int connectsPerSecond,
    Timeouts timeouts,
    BackoffRange backoff,
    int delayCapacity,
    long seed) {

```

A record with eight fields: which method, how many clients, where the feed is, how many clients to start per second, the timeouts, the backoff range, how many delays each client keeps, and the seed. The two nested records `Timeouts` and `BackoffRange` (below) group related values so the list stays readable.

```java
  /** Checks the fields. */
  public FleetPlan {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(endpoints, "endpoints");
    Objects.requireNonNull(timeouts, "timeouts");
    Objects.requireNonNull(backoff, "backoff");
    if (clients < 1 || connectsPerSecond < 1 || delayCapacity < 1) {
      throw new IllegalArgumentException(
          "clients, connectsPerSecond and delayCapacity must be positive");
    }
  }

```

The compact constructor refuses a null for any object field (`requireNonNull`) and refuses a count below 1 (`clients < 1 || connectsPerSecond < 1 || delayCapacity < 1`). A rate of 0 per second would make the start-up loop in `ClientFleet` divide by zero.

```java
  Duration connectTimeout() {
    return timeouts.connect();
  }

  Duration requestTimeout() {
    return timeouts.request();
  }

  Duration silenceLimit() {
    return timeouts.silence();
  }

  Duration backoffBase() {
    return backoff.base();
  }

  Duration backoffCap() {
    return backoff.cap();
  }

```

Five small package-private accessors (no `public`) that the fleet calls: they unwrap the nested records so `ClientFleet` can write `plan.connectTimeout()`.

```java
  /**
   * The client's timeouts.
   *
   * @param connect the TCP connect timeout
   * @param request the longest wait for a reply, or for a stream's headers; longer than the
   *     long-poll hold
   * @param silence the longest a push connection may stay silent (no change, no heartbeat) before
   *     the client drops it and reconnects; about three server heartbeats
   */
  public record Timeouts(Duration connect, Duration request, Duration silence) {

    /** Checks the fields. */
    public Timeouts {
      Objects.requireNonNull(connect, "connect");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(silence, "silence");
    }
  }

```

`Timeouts` has three durations. `connect` is the TCP connect timeout. `request` is the longest wait for a reply or for a stream's headers; the settings file keeps it **longer than the long-poll hold**, so a held poll is never cut by the client. `silence` is the longest a push connection may say nothing before the client drops it: about three server heartbeats. The compact constructor checks none is null.

```java
  /**
   * The reconnect backoff.
   *
   * @param base the ceiling of the first wait
   * @param cap the largest ceiling
   */
  public record BackoffRange(Duration base, Duration cap) {

    /** Checks the fields. */
    public BackoffRange {
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(cap, "cap");
    }
  }
}
```

`BackoffRange` holds the base and cap handed to every client's `Backoff`.

### `ClientFleet.java`: many clients, started at a steady rate

**Why the rate matters.** When a program connects to a server, the operating system keeps the new connections that wait to be accepted in a **listen queue**, also called the accept backlog. If 1,000 clients arrive in the same instant, the queue (here asked to be 1,024 long, but macOS lowers it to `kern.ipc.somaxconn`, which is 128 on this machine) overflows. The overflowing connections are dropped or delayed by a full second, and the measurement would then measure the operating system, not the feed. So the fleet starts clients at `compare.connectsPerSecond` (250 in `config/compare.properties`): one every 4 milliseconds, 1,000 clients in 4 seconds.

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.wire.FeedJson;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

```

Imports. `AtomicBoolean` is a boolean that threads can set and read safely.

```java
/**
 * Many clients of one delivery method, each on its own virtual thread, sharing one JDK HTTP client
 * (a long-lived client is created once and shared, never per request). They are started at a steady
 * rate, so 1,000 connections do not arrive in one burst that overflows the accept backlog.
 *
 * <p>Thread-safe: the list of clients is fixed after start; recording is an atomic flag.
 */
public final class ClientFleet {
```

The comment: each client is on its own virtual thread; all share one `HttpClient`; clients start at a steady rate. "The list of clients is fixed after start; recording is an atomic flag": so the class is thread-safe.

```java

  private static final Duration STOP_WAIT = Duration.ofSeconds(10);
  private static final int SILENCE_CHECKS = 4;

```

Two constants. `STOP_WAIT` is how long `stop` waits for each thread to end (10 seconds). `SILENCE_CHECKS = 4` means the watchdog looks at the clients four times per silence limit.

```java
  private final List<FeedClient> clients;
  private final List<Thread> threads;
  private final HttpClient http;
  private final AtomicBoolean recording;
  private final Thread watchdog;

```

Fields: the clients, their threads, the shared HTTP client, the recording flag, and the watchdog thread.

```java
  private ClientFleet(
      List<FeedClient> clients,
      List<Thread> threads,
      HttpClient http,
      AtomicBoolean recording,
      Thread watchdog) {
    this.clients = List.copyOf(clients);
    this.threads = List.copyOf(threads);
    this.http = http;
    this.recording = recording;
    this.watchdog = watchdog;
  }

```

A **private** constructor: outside code cannot call `new ClientFleet(...)`; it must use `start`. `List.copyOf` makes an unchangeable copy of each list.

```java
  /**
   * Starts the clients, at the plan's rate.
   *
   * @param plan how many clients of which method, where, and how they back off
   * @return the running fleet
   * @throws InterruptedException if interrupted while starting them
   */
  public static ClientFleet start(FleetPlan plan) throws InterruptedException {
    Objects.requireNonNull(plan, "plan");
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(plan.connectTimeout())
            .build();
    FeedJson json = new FeedJson();
    AtomicBoolean recording = new AtomicBoolean();
```

`start(plan)` is a **static factory**: a method on the class that builds and returns an instance.

- `HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)` pins the protocol to HTTP/1.1, the version every route here uses and the one that works with an upgrade to WebSocket. `.connectTimeout(plan.connectTimeout())` sets how long a new connection may take to open. `.build()` creates the client. This single client is shared by all clients of the fleet.

```java
    SeatMapReader seatMaps = new SeatMapReader(http, plan.endpoints(), json, plan.requestTimeout());
    List<FeedClient> clients = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    Duration gap = Duration.ofSeconds(1).dividedBy(plan.connectsPerSecond());
```

`new FeedJson()` is the shared codec (Jackson inside, configured to refuse unknown fields). `new AtomicBoolean()` starts as false: not recording. `SeatMapReader` is shared too. Then two empty lists are made.

```java
    for (int clientIndex = 0; clientIndex < plan.clients(); clientIndex++) {
      FeedClient client =
          new FeedClient(
              connection(plan, http, json),
              seatMaps,
              new Backoff(plan.backoffBase(), plan.backoffCap(), plan.seed() + clientIndex),
              recording,
              new ClientStats(plan.delayCapacity()));
      clients.add(client);
      threads.add(Thread.ofVirtual().name("client-" + clientIndex).start(client));
      Thread.sleep(gap);
    }
```

The start loop.

- `Duration.ofSeconds(1).dividedBy(plan.connectsPerSecond())` is the gap between starts: 1 s divided by 250 is 4 ms.
- For each index, a `FeedClient` is built from the right connection (see `connection` below), the shared reader, a `Backoff` with its own seed (`plan.seed() + clientIndex`), the shared recording flag, and its own `ClientStats`.
- `Thread.ofVirtual().name("client-" + clientIndex).start(client)` starts the client on a new virtual thread with a name that appears in thread dumps. `FeedClient` implements `Runnable`, so it can be started this way.
- `Thread.sleep(gap)` waits 4 ms before the next one. This is the rate limit.

```java
    Thread watchdog =
        Thread.ofVirtual()
            .name("client-silence-watchdog")
            .start(() -> watchSilence(clients, plan.silenceLimit()));
    return new ClientFleet(clients, threads, http, recording, watchdog);
  }
```

After all clients are started, the **watchdog** thread starts. It runs `watchSilence(clients, plan.silenceLimit())` on a virtual thread. `() -> watchSilence(...)` is a lambda that becomes the thread's body. The method returns the new fleet.

```java

  /**
   * Turns delay recording on or off for every client.
   *
   * @param isRecording true to record
   */
  public void record(boolean isRecording) {
    recording.set(isRecording);
  }
```

`record(isRecording)` turns delay recording on or off for every client at once by flipping the shared flag. The benchmark switches it on after the warm-up.

```java

  /**
   * The clients.
   *
   * @return the fixed list
   */
  public List<FeedClient> clients() {
    return clients;
  }
```

`clients()` returns the fixed list.

```java

  /**
   * Counts the clients whose map has reached a sequence.
   *
   * @param sequence the sequence to reach
   * @return how many have it or later
   */
  public int countAtLeast(long sequence) {
    return (int) clients.stream().filter(client -> client.map().sequence() >= sequence).count();
  }

```

`countAtLeast(sequence)` counts the clients whose map is at that sequence or later. `clients.stream().filter(...).count()` runs through the list; the lambda `client -> client.map().sequence() >= sequence` is the test. The cast `(int)` narrows the `long` count to an `int`. The benchmark uses it to wait until every client has caught up.

```java
  /**
   * Counts the clients that have loaded a seat map.
   *
   * @return the count
   */
  public int countLoaded() {
    return (int) clients.stream().filter(client -> client.map().isLoaded()).count();
  }

```

`countLoaded()` counts the clients that have loaded their first seat map.

```java
  /**
   * Stops every client and waits for its thread to end, then shuts the HTTP client down.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  public void stop() throws InterruptedException {
    watchdog.interrupt();
    threads.forEach(Thread::interrupt);
    for (Thread thread : threads) {
      thread.join(STOP_WAIT);
    }
    http.shutdownNow();
    http.awaitTermination(STOP_WAIT);
  }
```

`stop()` ends the fleet. It interrupts the watchdog and every client thread. `thread.join(STOP_WAIT)` waits up to 10 seconds for each to end. Then `http.shutdownNow()` closes every connection the HTTP client still holds, and `http.awaitTermination(STOP_WAIT)` waits for its internal threads to finish.

```java

  // A link that says nothing for about three heartbeats is treated as dead, as a production client
  // must: a phone network or a proxy can drop it without either side hearing a close.
  private static void watchSilence(List<FeedClient> clients, Duration silenceLimit) {
    Duration checkEvery = silenceLimit.dividedBy(SILENCE_CHECKS);
    try {
      while (true) {
        Thread.sleep(checkEvery);
        clients.forEach(client -> client.abortIfSilent(silenceLimit));
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

```

`watchSilence` is the watchdog. Every `silenceLimit / 4` it calls `abortIfSilent` on every client. With a silence limit of 45,000 ms (three of the server's 15,000 ms heartbeats) it looks every 11,250 ms. `catch (InterruptedException stopped)` fires when `stop` interrupts it; `Thread.currentThread().interrupt()` restores the interrupted flag, which is good manners for code that catches that exception.

```java
  private static FeedConnection connection(FleetPlan plan, HttpClient http, FeedJson json) {
    return switch (plan.method()) {
      case LONG_POLLING ->
          new LongPollConnection(http, plan.endpoints(), json, plan.requestTimeout());
      case SERVER_SENT_EVENTS ->
          new EventStreamConnection(http, plan.endpoints(), json, plan.requestTimeout());
      case WEBSOCKET ->
          new WebSocketConnection(http, plan.endpoints(), json, plan.requestTimeout());
    };
  }
}
```

`connection(plan, http, json)` picks the delivery method with a **switch expression** over the enum. Each case returns a new connection. Note that long polling and the WebSocket both get `plan.requestTimeout()` for their last argument but use it for different things: the longest wait for a reply, the longest wait for the opening handshake. The event stream uses it for the wait for the response headers. The compiler checks that all three enum values are handled.

### `FeedClient.java`: one fan

This is the heart of the package. `FeedClient implements Runnable`, so it is a task a thread can run. Its `run` method is the loop from "How it works underneath", and its inner class `Sink` is what the connection calls.

**The silence deadline.** `Sink` stamps `lastHeardNanos` with `System.nanoTime()` whenever anything arrives: a connection, a message or a heartbeat. The fleet's watchdog calls `abortIfSilent(limit)`: if the client is following and more than `limit` has passed since `lastHeardNanos`, it calls `connection.abort()`. That makes the blocked `follow` return `DISCONNECTED`, and the loop backs off and reconnects. `System.nanoTime()` is a clock that only moves forward and is meant for measuring durations, unlike wall-clock time, which can jump.

**Rebuilding a correct seat map after a gap.** There are three routes to a reload:

1. The server sends `{"type":"reload"}` (its replay buffer no longer reaches back to the client's position, or the position belongs to another stream, or the client's queue overflowed). `accept` returns `RELOAD`.
2. The client finds a gap itself (`Applied.GAP`). `accept` returns `RELOAD`.
3. A long poll reply has `"reload": true`. The connection returns `Ending.RELOAD`.

In all three the loop counts a reload, reads the whole seat map with `SeatMapReader`, replaces the copy (`map.load`), and follows again from the map's new position. The result is a correct map, never a patched one. The lab `sseResume` shows it: a client away for 500 changes with a 200-change replay buffer ends with `reloads 1` and a map equal to the store's.

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.wire.MessageView;
import io.plainround.seatwave.live.wire.SnapshotView;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

```

Imports. `Instant` is a moment on the timeline; `DateTimeParseException` is thrown when a time string is malformed.

```java
/**
 * One fan's client: it loads the seat map, follows the feed with one delivery method, applies every
 * change in order, and after a dropped connection backs off with jitter and resumes from the last
 * change it applied. A reload instruction, or a gap it finds itself, makes it load the map again:
 * it fails safe, never applying a change it cannot place.
 *
 * <p>It records the delivery delay of every change, from the store's {@code changedAt} to the
 * moment the client has applied it, on the same machine's clock. Not thread-safe: it runs on one
 * thread; {@link #map()} positions may be read from others.
 */
public final class FeedClient implements Runnable {
```

The class comment states the whole behaviour, including "it fails safe, never applying a change it cannot place", and how delay is measured: "from the store's `changedAt` to the moment the client has applied it, on the same machine's clock".

```java

  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final long NANOS_PER_MICRO = 1_000;

```

`MICROS_PER_SECOND` and `NANOS_PER_MICRO` are the conversion factors used in `delayMicros`. `1_000_000` uses underscores for readability; they do not change the value.

```java
  private final FeedConnection connection;
  private final SeatMapReader seatMaps;
  private final Backoff backoff;
  private final AtomicBoolean recording;
  private final ClientSeatMap map = new ClientSeatMap();
  private final ClientStats stats;
  private final Sink sink = new Sink();
  private int failures;
  private long disconnectedAtNanos;
  private volatile long lastHeardNanos;
  private volatile boolean following;

```

Fields.

- `connection`: the delivery method. `seatMaps`: the reader. `backoff`: this client's waits. `recording`: the shared flag. `map`: the copy. `stats`: the counters. `sink`: the object the connection calls.
- `failures` counts consecutive failures; it picks the backoff attempt number and is reset to 0 on a good connection.
- `disconnectedAtNanos` is the clock reading of the first failure of an outage (0 means "not in an outage").
- `volatile long lastHeardNanos` and `volatile boolean following` are written by the client thread and read by the watchdog thread, so they are `volatile`.

```java
  /**
   * Creates a client; it does nothing until {@link #run()}.
   *
   * @param connection the delivery method
   * @param seatMaps where the seat map is read
   * @param backoff this client's reconnect waits
   * @param recording when true, delays are recorded (false during warm-up)
   * @param stats where the client counts what it saw
   */
  public FeedClient(
      FeedConnection connection,
      SeatMapReader seatMaps,
      Backoff backoff,
      AtomicBoolean recording,
      ClientStats stats) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.seatMaps = Objects.requireNonNull(seatMaps, "seatMaps");
    this.backoff = Objects.requireNonNull(backoff, "backoff");
    this.recording = Objects.requireNonNull(recording, "recording");
    this.stats = Objects.requireNonNull(stats, "stats");
  }

```

The constructor stores the five collaborators, each checked for null. "It does nothing until `run()`".

```java
  /** Follows the feed until the thread is interrupted. */
  @Override
  public void run() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        if (!map.isLoaded() && !loadMap()) {
          continue;
        }
        Ending ending;
        try {
          ending = connection.follow(map.position(), sink);
        } finally {
          following = false;
        }
        switch (ending) {
          case RELOAD -> {
            stats.recordReload();
            if (!loadMap()) {
              continue;
            }
          }
          case DISCONNECTED -> {
            if (disconnectedAtNanos == 0) {
              disconnectedAtNanos = System.nanoTime();
            }
            Thread.sleep(backoff.delay(failures++));
          }
        }
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

```

`run()` is the loop.

- `while (!Thread.currentThread().isInterrupted())`: run until the thread is told to stop.
- `if (!map.isLoaded() && !loadMap()) { continue; }`: no map yet and loading failed: go round again (`loadMap` already slept with backoff).
- `connection.follow(map.position(), sink)` follows from the map's position and returns the reason it ended. The `try ... finally` always sets `following = false` afterwards, even if `follow` throws, so the watchdog never aborts a connection that is not there.
- `switch (ending)` with arrows. `RELOAD`: count it, then `loadMap()`; if the load fails, `continue`. (The map object still says it is loaded, so the loop follows from its old position; the server answers with a reload again and the cycle repeats, each round after a backoff sleep inside `loadMap`. It repairs itself.) `DISCONNECTED`: remember when the outage began (only the first time, because `disconnectedAtNanos == 0`), then `Thread.sleep(backoff.delay(failures++))`: sleep a random time under the ceiling for this attempt, then increase the attempt counter.
- `catch (InterruptedException stopped)` ends the loop quietly; `interrupt()` puts the flag back.

```java
  /**
   * The client's seat map.
   *
   * @return the map
   */
  public ClientSeatMap map() {
    return map;
  }

  /**
   * What the client saw.
   *
   * @return the statistics; read them after the client's thread has stopped
   */
  public ClientStats stats() {
    return stats;
  }
```

Two accessors: `map()` returns the copy and `stats()` returns the counters (read them only after the client's thread has stopped).

```java

  /**
   * Ends the current connection if it has been silent longer than the limit; called by the fleet's
   * watchdog thread.
   *
   * @param silenceLimit the longest silence allowed, about three server heartbeats
   * @return true if the connection was aborted
   */
  public boolean abortIfSilent(Duration silenceLimit) {
    if (!following || System.nanoTime() - lastHeardNanos < silenceLimit.toNanos()) {
      return false;
    }
    connection.abort();
    return true;
  }

```

`abortIfSilent(silenceLimit)`: return false if the client is not following, or if the time since it last heard anything is under the limit. Otherwise call `connection.abort()` and return true. The watchdog runs it for every client every quarter of the limit.

```java
  private boolean loadMap() throws InterruptedException {
    Optional<SnapshotView> snapshot = seatMaps.read();
    if (snapshot.isEmpty()) {
      Thread.sleep(backoff.delay(failures++));
      return false;
    }
    map.load(snapshot.get());
    return true;
  }

```

`loadMap()` reads the seat map. On failure (`Optional` empty), sleep a backoff wait, count the failure and return false. On success, `map.load(snapshot.get())` and return true.

```java
  private long delayMicros(String changedAt) {
    if (changedAt == null) {
      throw new IllegalArgumentException("a seat change has a changedAt");
    }
    try {
      Instant changed = Instant.parse(changedAt);
      Duration delay = Duration.between(changed, Instant.now());
      return delay.getSeconds() * MICROS_PER_SECOND + delay.getNano() / NANOS_PER_MICRO;
    } catch (DateTimeParseException malformed) {
      throw new IllegalArgumentException("changedAt is not an ISO-8601 instant", malformed);
    }
  }

```

`delayMicros(changedAt)` turns the server's timestamp into a delay.

- A null `changedAt` is a bug, so it throws.
- `Instant.parse("2026-11-11T02:39:51.904878Z")` reads an ISO-8601 timestamp (the `Z` means UTC) with microsecond precision.
- `Duration.between(changed, Instant.now())` is the elapsed time. `delay.getSeconds() * 1_000_000 + delay.getNano() / 1_000` converts it to whole microseconds.
- A malformed string throws `IllegalArgumentException`.

This is the whole of the delivery delay measurement: stamp at the store, subtract at the client, same machine, same clock.

```java
  private final class Sink implements MessageSink {

    @Override
    public void heartbeat() {
      lastHeardNanos = System.nanoTime();
    }

    @Override
    public void connected() {
      lastHeardNanos = System.nanoTime();
      following = true;
      if (disconnectedAtNanos != 0) {
        stats.recordReconnect(Duration.ofNanos(System.nanoTime() - disconnectedAtNanos).toMillis());
        disconnectedAtNanos = 0;
      }
      failures = 0;
    }

```

`Sink` is an **inner class**: it lives inside `FeedClient` and can use its fields. `heartbeat()` stamps the time. `connected()` stamps the time, sets `following`, and if the client was in an outage records the reconnect with its length in milliseconds (`Duration.ofNanos(...).toMillis()`) and clears the outage marker. It resets `failures` to 0 so the next outage starts again from the shortest wait.

```java
    @Override
    public Verdict accept(MessageView message) {
      lastHeardNanos = System.nanoTime();
      if (MessageView.RELOAD.equals(message.type())) {
        return Verdict.RELOAD;
      }
      Applied applied = map.apply(message);
      stats.recordApplied(applied);
      if (applied == Applied.GAP) {
        // Fail safe: a map with a hole is reloaded, never patched over.
        return Verdict.RELOAD;
      }
      if (applied == Applied.APPLIED && recording.get()) {
        stats.recordDelay(delayMicros(message.changedAt()));
      }
      return Verdict.CONTINUE;
    }
  }
}
```

`accept(message)` handles each message.

- Stamp the time (any message proves the link is alive).
- A `reload` message returns `RELOAD` at once.
- Otherwise `map.apply(message)`, and count the result in the stats.
- A `GAP` returns `RELOAD`: "fail safe: a map with a hole is reloaded, never patched over".
- If the change was applied **and** recording is on (`recording.get()`; false during warm-up), record its delay.
- Return `CONTINUE`.

### `LongPollConnection.java`: delivery method 1

Long polling is ordinary HTTP used cleverly. The client asks "anything after position P?" The server does not answer "no" at once; it holds the request until there is a change or 25 seconds pass. So news arrives as soon as it exists, and a quiet period costs one request per 25 seconds instead of many. The JDK client reuses the same TCP connection for the next poll (keep-alive), so a poll costs a request and a reply, not a new handshake.

```java
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

```

Imports. `LongPollView` is the reply's JSON shape: the list of changes, the next position, and a reload flag.

```java
/**
 * Long polling with the JDK's HTTP client: ask for the changes after the position, apply them, ask
 * again from the new position. The connection is reused between polls (keep-alive, Module 5), so
 * one poll costs a request and a reply, not a handshake.
 *
 * <p>Thread-safe: no state; the shared HTTP client is safe to share.
 */
public final class LongPollConnection implements FeedConnection {

```

The comment. "Thread-safe: no state": it keeps nothing between calls, so the fleet could even share one, though each client gets its own.

```java
  private static final int OK = 200;

  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json;
  private final Duration requestTimeout;

```

`OK = 200`, and four fields: the shared HTTP client, the addresses, the codec and the request timeout. The timeout must be longer than the server's hold or the client would cut every quiet poll.

```java
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

```

Constructor with null checks.

```java
  @Override
  public Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException {
    FeedPosition position = from;
    boolean connected = false;
```

`follow` is the method the interface requires (`@Override` asks the compiler to check). `position` is a local copy of `from` that moves forward. `connected` records whether `sink.connected()` was called yet.

```java
    while (true) {
      HttpRequest request =
          HttpRequest.newBuilder(endpoints.longPoll(position))
              .timeout(requestTimeout)
              .GET()
              .build();
      LongPollView reply;
```

The loop `while (true)` runs until a `return`. Each round builds a GET request for `endpoints.longPoll(position)` with the request timeout, and declares `reply` to be filled in.

```java
      try {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != OK) {
          return Ending.DISCONNECTED;
        }
        reply = json.read(response.body(), LongPollView.class);
      } catch (IOException failed) {
        return Ending.DISCONNECTED;
      }
```

`http.send(...)` blocks for up to 25 seconds while the server holds the poll. Any status except 200 means the connection's story is over: return `DISCONNECTED` and let the client back off. A 503 from the connection limit is such a case. A reply body is parsed into a `LongPollView`. `IOException` (refused, reset, timed out, bad JSON) also means `DISCONNECTED`.

```java
      if (!connected) {
        sink.connected();
        connected = true;
      }
```

The first good reply calls `sink.connected()` once. A long poll is "connected" the moment the first reply arrives.

```java
      if (reply.reload()) {
        return Ending.RELOAD;
      }
```

If the server said `reload: true`, return `RELOAD`.

```java
      for (MessageView change : reply.changes()) {
        if (sink.accept(change) == Verdict.RELOAD) {
          return Ending.RELOAD;
        }
      }
```

Hand each change to the sink in order. If the client answers `RELOAD` (a gap), stop and return `RELOAD`.

```java
      position =
          FeedPosition.parse(String.valueOf(reply.next()))
              .orOnErrorThrow(IllegalArgumentException::new);
    }
  }
```

Move on: `reply.next()` is the position to ask from next. When there were no changes, the server sends back the position that was asked from, so the loop just asks again. `FeedPosition.parse(...)` returns a `Result`; `.orOnErrorThrow(IllegalArgumentException::new)` unwraps it or throws (a server bug, not an expected failure).

```java

  @Override
  public void abort() {
    // Every poll ends by itself within the request timeout, so there is nothing to cut.
  }
}
```

`abort()` does nothing, with the reason in a comment: every poll ends by itself within the request timeout, so there is nothing to cut. This means the silence watchdog has no effect on a long-polling client. A quiet poll returns after about 25 seconds, which is inside the 45-second silence limit of the comparison, and a poll that hangs is ended by the request timeout (30 seconds in `config/compare.properties`) instead.

### `EventStreamConnection.java`: delivery method 2

**The event-stream format.** A server-sent events response has the content type `text/event-stream` and never ends. It is plain text in UTF-8, made of lines. A blank line ends one event. Lines are fields: `id: ...`, `event: ...`, `data: ...`. A line that starts with `:` is a comment that clients ignore; this feed sends one every 15 seconds as a **heartbeat** so proxies do not close an idle link. The real output of `curl` against the server in the README looks like this:

```text
retry: 1000

id: 41550af9.368
event: seat
data: {"type":"seat","position":"41550af9.368","seat":"D-731","state":"held","changedAt":"2026-11-11T02:39:51.904878Z"}
```

A browser's `EventSource` reconnects by itself and sends the `Last-Event-ID` header with the last `id` it saw. This Java class behaves the same way.

```java
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

```

Imports. `InputStream` reads bytes; `BufferedReader` and `InputStreamReader` turn bytes into lines of text; `StandardCharsets` names UTF-8.

```java
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
```

The class comment: the first request carries `?after=` from the seat map; every later one goes to the same URL with a `Last-Event-ID` header. It lists the parts of the format it understands.

```java

  private static final int OK = 200;

```

`OK = 200`.

```java
  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json;
  private final Duration headersTimeout;
  private URI firstUrl;
  private String lastId = "";
  private volatile InputStream current;

```

Fields. `firstUrl` is the URL of the first request, remembered for resumes. `lastId` is the last position applied, as text. `volatile InputStream current` is the open response body, kept so `abort` (called from the watchdog thread) can close it.

```java
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

```

Constructor with null checks. `headersTimeout` is the longest wait for the response headers.

```java
  @Override
  public Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .timeout(headersTimeout)
            .header("Accept", "text/event-stream")
            .GET();
```

`follow` starts building the request: no URL yet, the timeout, and the header `Accept: text/event-stream` (the client says which format it wants), method GET.

```java
    if (firstUrl != null && from.written().equals(lastId)) {
      // A resume after a dropped stream: the same URL, and the newest id in the header.
      request.uri(firstUrl).header("Last-Event-ID", lastId);
    } else {
      // A fresh start from a seat map just loaded.
      firstUrl = endpoints.stream(from);
      request.uri(firstUrl);
    }
```

Two cases.

- **Resume**: `firstUrl != null && from.written().equals(lastId)`. The client has been here before and the map's position equals the last id this connection applied. Use the same URL and add `Last-Event-ID: <lastId>`.
- **Fresh start**: a seat map was just loaded, so the position is new. Build a new URL with `after=` and remember it as `firstUrl`.

```java
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
```

`http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())` returns as soon as the **headers** arrive. The body is a stream the client reads later. `try (InputStream body = response.body())` is **try-with-resources**: it closes the stream when the block ends, however it ends. A status other than 200 returns `DISCONNECTED`. Otherwise `current = body` (so `abort` can reach it), `sink.connected()` is called and `readEvents` runs. `return readEvents(...)` blocks here for as long as the stream lives.

```java
      } finally {
        current = null;
      }
    } catch (IOException failed) {
      return Ending.DISCONNECTED;
    }
  }
```

`finally { current = null; }` clears the field after the stream ends. `catch (IOException failed)` turns any network failure, including the stream being closed by `abort`, into `DISCONNECTED`.

```java

  private Ending readEvents(InputStream body, MessageSink sink) throws IOException {
    BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    StringBuilder data = new StringBuilder();
    String line;
    while ((line = lines.readLine()) != null) {
```

`readEvents` reads one line at a time. `InputStreamReader(body, UTF_8)` decodes the bytes. `BufferedReader.readLine()` blocks until a full line arrives and returns null at the end of the stream. `data` collects the data field of the event being read.

```java
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
```

A blank line (`line.isEmpty()`) ends an event. If `data` is not empty, parse it as a `MessageView` (JSON), clear the buffer (`setLength(0)`), and give it to the sink. If the sink says `RELOAD`, return `RELOAD`. Otherwise remember the message's position as `lastId`: this is what a resume will send.

```java
      } else if (line.startsWith(":")) {
        sink.heartbeat();
      } else if (line.startsWith("data: ")) {
        data.append(line, "data: ".length(), line.length());
      }
```

A line that starts with `:` is a comment, so call `sink.heartbeat()`: the link is alive. A line that starts with `data: ` appends the text after those six characters to the buffer. (`data.append(line, 6, length)` copies a slice.)

```java
      // id, event and retry lines repeat what the JSON already carries.
    }
    return Ending.DISCONNECTED;
  }
```

The comment says why `id`, `event` and `retry` lines are skipped: the JSON already carries the same facts. When `readLine` returns null, the server closed the stream: return `DISCONNECTED`.

```java

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
```

`abort()` is called from another thread. It reads `current`; if a stream is open, `body.close()` closes it. A thread blocked in `readLine` on that stream then fails with an `IOException`, which `follow` turns into `DISCONNECTED`. This is how a silent link is cut.

### `WebSocketConnection.java`: delivery method 3

**Why a queue.** The JDK WebSocket client works with callbacks. You give it a `Listener`, and the JDK calls the listener on one of its own threads whenever a frame arrives. But the rest of the client (the sink, the map) wants to run on the client's own thread, one message at a time. So the listener only hands each complete message to a small **blocking queue** (`BlockingQueue`: a list where `take()` waits until something is there), and the client thread takes from it.

**Why `request(1)`.** The listener is called only when you have asked for a message. This class asks for exactly one, applies it, then asks for the next. If the client is slow, it simply stops asking, the JDK stops reading the socket, and the operating system's TCP buffers fill. The server's write then blocks or times out. The slowness of one client pushes back on its own connection and uses no unbounded memory here.

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.MessageView;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

```

Imports. `WebSocket` is the JDK's client; `ArrayBlockingQueue` is a fixed-size blocking queue; `CompletionStage` is the type a listener may return to say "I am done with this buffer".

```java
/**
 * WebSockets with the JDK's own client, {@code java.net.http.WebSocket}. The client asks for one
 * message at a time ({@code request(1)}) and asks for the next only after the client thread has
 * applied it, so a slow client lets TCP push back on the server instead of buffering without bound.
 *
 * <p>Thread-safe in the way it is used: the listener runs on the HTTP client's threads and only
 * hands complete messages to a small queue that the client thread takes from.
 */
public final class WebSocketConnection implements FeedConnection {

```

The comment explains `request(1)` and backpressure. "Thread-safe in the way it is used": the listener runs on the HTTP client's threads and only hands finished messages over.

```java
  // The client requests one message at a time, so a few slots are enough for a message, a ping
  // marker and a close marker.
  private static final int INBOX_SLOTS = 8;
  private static final String CLOSED = "\u0000closed";
  private static final String PING = "\u0000ping";

```

`INBOX_SLOTS = 8`: only one message is asked for at a time, so eight slots are plenty for a message, a ping marker and a close marker. `CLOSED` and `PING` are two special strings that start with the character `\u0000` (the NUL character), which never appears in a JSON message, so they cannot be mistaken for one.

```java
  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json;
  private final Duration openTimeout;
  private volatile WebSocket current;
  private volatile BlockingQueue<String> currentInbox;

```

Fields. `volatile WebSocket current` and `volatile BlockingQueue<String> currentInbox` let `abort` (another thread) reach the open socket and its queue.

```java
  /**
   * Creates the connection.
   *
   * @param http the shared HTTP client
   * @param endpoints the feed's addresses
   * @param json the codec
   * @param openTimeout the longest wait for the opening handshake
   */
  public WebSocketConnection(
      HttpClient http, FeedEndpoints endpoints, FeedJson json, Duration openTimeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.json = Objects.requireNonNull(json, "json");
    this.openTimeout = Objects.requireNonNull(openTimeout, "openTimeout");
  }

```

Constructor with null checks. `openTimeout` is the longest wait for the opening handshake.

```java
  @Override
  public Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException {
    BlockingQueue<String> inbox = new ArrayBlockingQueue<>(INBOX_SLOTS);
    WebSocket socket;
    try {
```

A new inbox is made for every connection, so a late message from an old socket cannot reach a new one. `socket` will hold the open WebSocket.

```java
      socket =
          http.newWebSocketBuilder()
              .connectTimeout(openTimeout)
              .buildAsync(endpoints.socket(from), new Listener(inbox))
              .get(openTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException failed) {
      return Ending.DISCONNECTED;
    }
```

`http.newWebSocketBuilder()` starts building. `.connectTimeout(openTimeout)` limits the TCP connect. `.buildAsync(uri, listener)` starts the handshake and returns a `CompletableFuture` (a promise of a result later). `.get(timeout, MILLISECONDS)` waits for it. If the server refuses (for example a 403 or 503), the future fails and `get` throws `ExecutionException`; if it takes too long, `TimeoutException`. Both become `DISCONNECTED`. An `InterruptedException` is not caught and passes up.

```java
    current = socket;
    currentInbox = inbox;
    try {
      sink.connected();
```

Remember the socket and the inbox for `abort`, then call `sink.connected()`: the handshake succeeded.

```java
      while (true) {
        String text = inbox.take();
        if (CLOSED.equals(text)) {
          return Ending.DISCONNECTED;
        }
        if (PING.equals(text)) {
          sink.heartbeat();
          continue;
        }
```

The client loop. `inbox.take()` blocks until the listener has put something in. `CLOSED` returns `DISCONNECTED`. `PING` calls `sink.heartbeat()` and loops (`continue`).

```java
        MessageView message = json.read(text.getBytes(StandardCharsets.UTF_8), MessageView.class);
        if (sink.accept(message) == Verdict.RELOAD) {
          return Ending.RELOAD;
        }
        socket.request(1);
      }
```

A normal text message: parse the JSON into a `MessageView`, give it to the sink; on `RELOAD`, return `RELOAD`. Otherwise `socket.request(1)` asks for the next message. This is the only place the client asks for more, so the client takes messages exactly as fast as it applies them.

```java
    } catch (IOException malformed) {
      return Ending.DISCONNECTED;
    } finally {
      current = null;
      currentInbox = null;
      socket.abort();
    }
  }
```

`catch (IOException malformed)` covers bad JSON. The `finally` block always runs: it clears the fields and calls `socket.abort()`, which closes the connection abruptly (input and output) without a closing handshake. It makes sure no connection leaks, whichever way `follow` ends.

```java

  @Override
  public void abort() {
    BlockingQueue<String> inbox = currentInbox;
    WebSocket socket = current;
    if (socket != null) {
      socket.abort();
    }
    if (inbox != null) {
      // The listener may never hear of an abort; this wakes the client thread for certain.
      inbox.offer(CLOSED);
    }
  }

```

`abort()` is called from another thread. It aborts the socket and also puts `CLOSED` into the inbox: "the listener may never hear of an abort; this wakes the client thread for certain".

```java
  private static final class Listener implements WebSocket.Listener {

    private final BlockingQueue<String> inbox;
    private final StringBuilder partial = new StringBuilder();

    private Listener(BlockingQueue<String> inbox) {
      this.inbox = inbox;
    }

```

The inner class `Listener implements WebSocket.Listener`. It owns the inbox and a `StringBuilder partial` for messages that arrive in pieces.

```java
    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

```

`onOpen`: the connection is open; ask for the first message with `request(1)`. Without this call nothing would ever be delivered.

```java
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (!last) {
        // A message may arrive in parts; ask for the next part at once.
        webSocket.request(1);
        return null;
      }
      String message = partial.toString();
      partial.setLength(0);
      // At most one message is outstanding at a time, so the offer always finds a free slot.
      inbox.offer(message);
      return null;
    }

```

`onText(webSocket, data, last)`: the JDK gives a piece of a text message; `last` is true on the final piece. The piece is appended. If it was not the last, ask for the next piece at once (`request(1)`) and return `null` ("I am done with this data"). If it was the last, convert the buffer to a string, clear it and `inbox.offer(message)`. `offer` puts the string in the queue without waiting. The comment explains why that never fails: only one message is outstanding at a time. The listener does **not** ask for the next message; the client thread does that after applying this one.

```java
    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
      // The JDK client answers the ping with a pong by itself. The ping used up the request
      // count, so ask again; and tell the client thread the link is alive.
      inbox.offer(PING);
      webSocket.request(1);
      return null;
    }

```

`onPing`: the JDK has already answered with a pong by itself (its documentation says "the WebSocket implementation will automatically send a reciprocal pong when a ping is received"). The ping used one unit of demand, so ask for one more, and put the `PING` marker in the inbox so the client thread records a heartbeat.

```java
    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      inbox.offer(CLOSED);
      return null;
    }

```

`onClose`: the server sent a close frame. Put `CLOSED` in the inbox.

```java
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      inbox.offer(CLOSED);
    }
  }
}
```

`onError`: something failed on the connection. Put `CLOSED` in the inbox as well.

## Try it yourself

All commands run in a copy of the repository. Timings differ on every run.

1. See backoff and the seat map's three answers without any server. Save the program below as `Demo.java` outside the repository. Compile the repository once (`./gradlew -q compileJava`), then run it with the compiled classes on the class path. The two extra jars are result4j and Jackson's annotations; `find ~/.gradle -name 'result4j-1.2.0.jar'` and `find ~/.gradle -name 'jackson-annotations-2.23*.jar'` locate them:

```java
import io.plainround.seatwave.live.client.*;
import io.plainround.seatwave.live.bench.LatencySummary;
import io.plainround.seatwave.live.wire.*;
import java.time.Duration;

public class Demo {
  public static void main(String[] a) {
    Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(5), 1);
    StringBuilder sb = new StringBuilder();
    for (int n = 0; n < 9; n++) sb.append("attempt ").append(n).append(" ceiling ").append(Math.min(5000, 100L << n)).append(" ms, waited ").append(b.delay(n).toMillis()).append(" ms\n");
    System.out.print(sb);
    long[] micros = new long[100];
    for (int i = 0; i < 100; i++) micros[i] = (i + 1) * 1000L;
    micros[99] = 250_000;
    System.out.println(LatencySummary.ofMicros(micros));
    ClientSeatMap map = new ClientSeatMap();
    map.load(new SnapshotView(1, "abcd1234.5", 2, 3, "aaaaaa"));
    System.out.println("loaded at " + map.position().written());
    for (int seq : new int[] {6, 6, 8, 7}) {
      System.out.println("change " + seq + " -> " + map.apply(new MessageView("seat", "abcd1234." + seq, "A-1", "held", "2026-11-11T00:00:00Z")));
    }
    System.out.println("states " + map.states() + " at " + map.position().written());
  }
}
```

```text
java -cp "build/classes/java/main:<result4j-1.2.0.jar>:<jackson-annotations jar>" Demo.java
```

Output I got (the backoff numbers repeat for seed 1, the rest is fixed):

```text
attempt 0 ceiling 100 ms, waited 70 ms
attempt 1 ceiling 200 ms, waited 105 ms
attempt 2 ceiling 400 ms, waited 2 ms
...
change 6 -> APPLIED
change 6 -> DUPLICATE
change 8 -> GAP
change 7 -> APPLIED
```

2. Watch one client resume and reload against a real server (the lab; it starts and stops its own node):

```text
./gradlew -q sseResume --args='config/sse-resume.properties 500'
```

Real output from this machine (the stream id and the exact drop sequences differ on every run):

```text
replay buffer: 200 changes; on-sale 50 actions per second
drop 1: the server closed 1 stream(s) at sequence 49
drop 2: the server closed 1 stream(s) at sequence 99
scenario 1, resumed inside the buffer: applied 150, duplicates 0, gaps 0, reloads 0, reconnects 2; client at 3e042d4d.150, store at 3e042d4d.150; map equals store: yes
while the client was away: 500 holds, feed now at sequence 650
scenario 2, away longer than the buffer: applied 150, duplicates 0, gaps 0, reloads 1, reconnects 3; client at 3e042d4d.650, store at 3e042d4d.650; map equals store: yes
```

Read it: scenario 1 resumed twice with no reload and no duplicate. In scenario 2 the buffer holds 200 changes but 500 happened while the client was away, so the server answered with a reload (`reloads 1`), the client loaded the seat map, and ended equal to the store.

3. Run a small fleet of all three methods (50 clients, one run, a 1 s warm-up and 3 s measure; about 16 seconds in total). Copy `config/compare.properties`, change `compare.clients=50`, `compare.runs=1`, `compare.warmupSeconds=1`, `compare.measureSeconds=3`, and run `./gradlew -q compare --args='config/compare-small.properties'`. The `recov ms` and `reconn` columns show the clients recovering from a drop of every connection (full output in `java-bench-walkthrough.md`).

I did not run the 1,000-client comparison for these notes.

## Mistakes people make

- **Reconnecting at once, with no wait.** Every client retries together and the server gets a stampede. `Backoff` waits a random time under a doubling ceiling.
- **Retrying forever at full speed after a refusal.** A 503 or a reset counts as a failure and goes through `Backoff` too.
- **Applying a change without checking its number.** A missed change would be silent and the map would be wrong for good. `ClientSeatMap.apply` accepts only sequence + 1.
- **Trusting a link that says nothing.** A connection can die with no close signal. The watchdog cuts a link silent for three heartbeats.
- **Buffering without limit for a slow reader.** `WebSocketConnection` asks for one message at a time. `ClientStats` stops storing delays at its capacity.
- **Creating an HTTP client per request.** Each would open its own connection pool. This package builds one per fleet and shares it.
- **Forgetting `volatile` for a field another thread reads.** `lastHeardNanos`, `following`, `sequence` and `current` are `volatile`.

## Check yourself

1. What are the three possible answers of `ClientSeatMap.apply`, and what does the client do on each? (Applied: carry on. Duplicate: ignore. Gap: count it and reload the seat map.)
2. Why does backoff use a random wait rather than a fixed doubling? (So clients that failed together do not come back together.)
3. A WebSocket client calls `request(1)` only after applying a message. What does this do for a slow client? (The JDK stops reading, TCP fills, and the server sees a slow writer instead of the client buffering without limit.)
4. How does a client notice a link that was dropped silently? (No message or heartbeat for three heartbeats; the fleet's watchdog calls `abort`, the connection ends as `DISCONNECTED`, and the loop backs off and resumes from its position.)
5. Why does the fleet start 250 clients per second rather than all at once? (To stay inside the server's accept backlog; 1,000 connections at the same instant would overflow it.)

## Further reading

- Java SE 25 API, `java.net.http` package (`HttpClient`, `HttpRequest`, `HttpResponse`, `WebSocket`): https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html
- Java SE 25 API, `Thread.ofVirtual` and virtual threads: https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html
- WHATWG HTML Living Standard, "Server-sent events" (the event stream format and `Last-Event-ID`): https://html.spec.whatwg.org/multipage/server-sent-events.html
- RFC 6455, The WebSocket Protocol: https://www.rfc-editor.org/rfc/rfc6455
- RFC 9110, HTTP Semantics (status codes, GET): https://www.rfc-editor.org/rfc/rfc9110
