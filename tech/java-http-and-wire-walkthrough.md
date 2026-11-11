# Walkthrough of the root package, `config`, `http` and `wire`

## What it is

This note covers four groups of Java files that sit around the `feed` package:

- the **root package** (`LiveFeedNode`, `ServeCli`): wires one node together and starts it;
- **`config`** (`LiveSettings`, `SettingsReader`): turns a settings file into checked values;
- **`http`** (nine files): the long-polling and server-sent-events routes on the JDK's built-in web server, the seat map, the page, health, metrics and the lab's admin routes;
- **`wire`** (four files): the JSON shapes, and the code that turns a change into the bytes a client reads.

A **web server** is a program that listens on a port, accepts HTTP requests and sends back replies. A **port** is a number (such as 8090) that tells the operating system which program a network message is for. The JDK ships a small web server in the package `com.sun.net.httpserver`; this repository uses it for everything except WebSockets.

## Why this repository uses it

The feed in `feed/` knows nothing about the network. These files are the edge: they read bytes from a client, turn them into feed values, ask the feed, and turn the answer back into bytes. Keeping them separate is what the module teaches: the delivery method is a detail at the edge, while the guarantee (every change exactly once, or reload) lives in the middle.

## Where it appears

| Files | What they do |
|---|---|
| `LiveFeedNode.java`, `ServeCli.java` | Build the store, feed, simulator, limit, metrics and both servers; the command that runs them. |
| `config/LiveSettings.java`, `config/SettingsReader.java` | Read `config/*.properties` with no defaults and report every problem at once. |
| `http/FeedHttpServer.java` | The JDK server, the routing, the page, health, metrics, admin. |
| `http/LongPollRoute.java`, `http/EventStreamRoute.java` | The two HTTP delivery methods. |
| `http/Replies.java`, `http/Problem.java`, `http/ProblemView.java`, `http/HttpStatus.java` | Complete replies and the one error shape. |
| `http/Query.java`, `http/Positions.java` | Query parameters and the `after` position. |
| `http/Parts.java` | The bundle of shared parts that the HTTP and WebSocket servers use. |
| `wire/FeedJson.java`, `wire/MessageView.java`, `wire/LongPollView.java`, `wire/SnapshotView.java` | JSON out and in. |

## How it works underneath

**A request, end to end.** A client opens a **TCP connection** to port 8090 (a reliable two-way stream of bytes between two programs) and writes a request such as `GET /v1/events/1/seat-changes?after=4d4f4a94.230 HTTP/1.1` followed by header lines. The JDK server reads it, makes an `HttpExchange` object (one request and its reply), and runs the one handler registered for `/`. That handler is `FeedHttpServer.serve`. It looks at the path and calls the right route.

**One virtual thread per request.** The server is told to run every handler on its own **virtual thread** (a thread the JDK schedules itself; when it waits for the network or a lock it is parked and costs a few kilobytes, not a whole operating-system thread). That is why a long poll or an event stream may wait for 25 seconds, or for ever, without using up a worker.

**From a change to bytes.** Take the change "seat E-741 became held". The store makes a `SeatChange` (a record). `FeedJson.message` turns it into a `MessageView` (another record with text fields). Jackson, the JSON library, writes that as 113 bytes of UTF-8 text:

```text
{"type":"seat","position":"4d4f4a94.231","seat":"E-741","state":"held","changedAt":"2026-11-11T03:00:27.484459Z"}
```

`EventStreamRoute` wraps those bytes in three lines and a blank line, which makes 150 bytes on the wire:

```text
id: 4d4f4a94.231
event: seat
data: {"type":"seat","position":"4d4f4a94.231","seat":"E-741","state":"held","changedAt":"2026-11-11T03:00:27.484459Z"}

```

`LongPollRoute` puts the same message inside `{"changes":[...],"next":"...","reload":false}`. The WebSocket session puts the 113 bytes after a 2-byte frame header, which makes 115 bytes. One JSON shape, three wrappers.

**Settings.** Nothing has a default in code. The file is read once at start, every key is checked, and the process refuses to start with one line per problem if anything is wrong.

## Walkthrough of this repository's use

The blocks are copied from the source files by a script. After each block comes the explanation.

### `LiveFeedNode.java`

```java
package io.plainround.seatwave.live;

import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.FeedMetrics;
import io.plainround.seatwave.live.feed.OnSaleSimulator;
import io.plainround.seatwave.live.feed.SeatFeed;
import io.plainround.seatwave.live.feed.SeatStore;
import io.plainround.seatwave.live.http.FeedHttpServer;
import io.plainround.seatwave.live.http.Parts;
import io.plainround.seatwave.live.websocket.WebSocketServer;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
```

The package line says which folder the file lives in. Each `import` names a class from another package so it can be used by its short name. The ones from `feed` are the domain; `http`, `websocket` and `wire` are the edges; `java.util.concurrent` has the scheduler used for the watchdog. `Clock` and `Duration` are time types.

```java
/**
 * One SeatWave live-feed node: the seat store, the feed, the simulated on-sale, the HTTP server
 * (long polling and server-sent events) and the WebSocket server, wired together and closed
 * together. The on-sale does not start until {@link #startOnSale()}, so tests can make every change
 * themselves.
 *
 * <p>Thread-safe: every part it holds documents its own thread-safety.
 */
public final class LiveFeedNode implements AutoCloseable {
```

A node is one feed process's worth of parts, started and closed together. `public final class` means anyone may use it and nobody may extend it. `implements AutoCloseable` promises a `close()` method, so it works in a `try (...)` block that closes it automatically. The on-sale does not start by itself: tests make their own changes.

```java
  // The stuck-writer check runs four times per write timeout, so a stuck write is found within
  // a quarter of the timeout after it expires.
  private static final int CHECKS_PER_WRITE_TIMEOUT = 4;

  private final Parts parts;
  private final WebSocketServer webSocketServer;
  private final FeedHttpServer httpServer;
  private final ScheduledExecutorService watchdog;
```

`CHECKS_PER_WRITE_TIMEOUT = 4` is a constant. The watchdog runs four times per write timeout. With a write timeout of 5,000 ms (`config/serve.properties`) it runs every 1,250 ms, so a write that is stuck is found within 1.25 seconds after the timeout passes. The fields are the four things the node must later stop.

```java
  private LiveFeedNode(
      Parts parts,
      WebSocketServer webSocketServer,
      FeedHttpServer httpServer,
      ScheduledExecutorService watchdog) {
    this.parts = parts;
    this.webSocketServer = webSocketServer;
    this.httpServer = httpServer;
    this.watchdog = watchdog;
  }
```

A private constructor. Only the static method `start` can make a node. This keeps half-built nodes from existing.

```java
  /**
   * Starts a node.
   *
   * @param settings every setting of the node
   * @param clock the store's clock
   * @return the running node
   * @throws IOException if a port cannot be bound
   */
  public static LiveFeedNode start(LiveSettings settings, Clock clock) throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(clock, "clock");
```

`start` is a **factory method**: it builds the parts in the right order and returns the running node. It throws `IOException` because binding a port can fail (for example if the port is already used). `Objects.requireNonNull` stops a `null` argument early with a clear message.

```java
    FeedMetrics metrics = new FeedMetrics();
    SeatFeed feed =
        new SeatFeed(
            settings.limits().replayCapacity(),
            settings.limits().clientQueueCapacity(),
            settings.limits().maxBatch(),
            metrics);
    SeatStore store = new SeatStore(settings.layout(), settings.onSale().holdTime(), clock, feed);
```

The metrics object is created first because the feed, the limit and the store all count into it. The feed gets four numbers from the settings: how many changes to remember, how long a client's queue may be, how many changes go in one write, and the metrics. The store gets the seat layout, the hold time, the clock and the feed. Because the store knows the feed and publishes into it, the feed must exist first.

```java
    OnSaleSimulator simulator =
        new OnSaleSimulator(
            store,
            settings.onSale().actionsPerSecond(),
            settings.onSale().confirmPercent(),
            settings.onSale().seed());
    Parts parts =
        new Parts(
            store,
            feed,
            simulator,
            new ConnectionLimit(settings.limits().maxConnections(), metrics),
            metrics,
            new FeedJson());
```

The simulator gets the store and the on-sale numbers. `Parts` is the record that carries the shared objects to both servers: the store, feed, simulator, the connection limit (made here from `maxConnections`), the metrics and a `FeedJson` for the JSON.

```java
    WebSocketServer webSocketServer = WebSocketServer.start(settings, parts);
    FeedHttpServer httpServer = FeedHttpServer.start(settings, parts, webSocketServer.port());
```

The WebSocket server starts first because the HTTP server needs its port: the page that the HTTP server sends names the WebSocket port in its security policy. With a settings port of 0 the system picks a free port, and `webSocketServer.port()` tells which.

```java
    Duration writeTimeout = settings.timing().writeTimeout();
    long periodNanos = Math.max(1, writeTimeout.toNanos() / CHECKS_PER_WRITE_TIMEOUT);
    ScheduledExecutorService watchdog =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().name("stuck-writers").daemon().unstarted(runnable));
    watchdog.scheduleAtFixedRate(
        () -> feed.disconnectStuckWriters(writeTimeout),
        periodNanos,
        periodNanos,
        TimeUnit.NANOSECONDS);
    return new LiveFeedNode(parts, webSocketServer, httpServer, watchdog);
```

The watchdog. `Duration writeTimeout` is read from the settings. `periodNanos` is a quarter of it in nanoseconds, never below 1. `Executors.newSingleThreadScheduledExecutor(...)` makes an executor that runs tasks later or repeatedly on one thread; the lambda is the thread factory, which makes a platform thread named `stuck-writers`, marked as a **daemon** thread so it does not keep the program alive. `scheduleAtFixedRate(task, firstDelay, period, unit)` runs `feed.disconnectStuckWriters(writeTimeout)` every `periodNanos`. The last line returns the node.

```java
  /** Starts the simulated on-sale at the configured rate. */
  public void startOnSale() {
    parts.simulator().start();
  }

  /**
   * The parts of the node, for tests and the lab tools.
   *
   * @return the store, feed, simulator, limit, metrics and codec
   */
  public Parts parts() {
    return parts;
  }
```

`startOnSale` begins the simulator. `parts()` gives tests and lab tools the parts. A getter with a Javadoc comment.

```java
  /**
   * The HTTP port.
   *
   * @return the port
   */
  public int httpPort() {
    return httpServer.port();
  }

  /**
   * The WebSocket port.
   *
   * @return the port
   */
  public int webSocketPort() {
    return webSocketServer.port();
  }
```

`httpPort` and `webSocketPort` report the ports actually in use.

```java
  @Override
  public void close() throws IOException {
    parts.simulator().close();
    watchdog.shutdownNow();
    parts.limit().dropAll();
    httpServer.close();
    webSocketServer.close();
  }
```

`close` stops everything. The order matters: the simulator first, so no new changes; the watchdog; then `limit().dropAll()`, which ends every open connection; then the two servers.

### `ServeCli.java`

```java
package io.plainround.seatwave.live;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
```

`Result` is the library type that holds either a success or an error (see `result4j.md`). `Path` names a file; `Clock.systemUTC()` is the real clock.

```java
/**
 * Runs one live-feed node with its simulated on-sale for the configured time: {@code ./gradlew -q
 * serve --args='config/serve.properties'}. It prints one line when it is ready, which the
 * comparison waits for when it runs the node as a separate process.
 */
public final class ServeCli {

  private static final int USAGE = 2;

  private ServeCli() {}
```

A **command-line interface** class is one with a `main` method, the entry point the JVM calls. `private ServeCli() {}` stops anyone from making an instance. `USAGE = 2` is the exit code for "the command was used wrongly". An **exit code** is a small number a program returns to the shell; 0 means success.

```java
  /**
   * Starts the node.
   *
   * @param arguments the settings file
   * @throws IOException if the file cannot be read or a port cannot be bound
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: serve <settings file>");
      System.exit(USAGE);
    }
```

`main` takes the words after the class name as an array. It wants exactly one: the settings file. Otherwise it prints a usage line on standard error (`System.err`, the channel for messages about problems) and ends with code 2.

```java
    switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
        problems.forEach(problem -> System.err.println("settings: " + problem));
        System.exit(USAGE);
      }
      case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> serve(settings);
    }
  }
```

This line does four things. `SettingsReader.load(Path.of(arguments[0]))` reads the file. `new SettingsReader(...)` wraps it. `LiveSettings.from(...)` returns a `Result`. The `switch` takes the result apart with **record patterns**: `case Result.Error<LiveSettings, List<String>>(List<String> problems)` matches an error and names its content `problems`; the other case matches a success and names the settings. If there are problems, each is printed as `settings: <problem>` and the program ends with 2. Otherwise it calls `serve`. The compiler checks that both cases are covered because `Result` is sealed.

```java
  private static void serve(LiveSettings settings) throws IOException, InterruptedException {
    try (LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC())) {
      node.startOnSale();
      System.out.println(
          String.format(
              Locale.ROOT,
              "SeatWave live feed on http://127.0.0.1:%d and ws://127.0.0.1:%d for %d s; "
                  + "on-sale at %d actions per second; at most %d connections",
              node.httpPort(),
              node.webSocketPort(),
              settings.serveTime().toSeconds(),
              settings.onSale().actionsPerSecond(),
              settings.limits().maxConnections()));
      System.out.flush();
      Thread.sleep(settings.serveTime());
    }
  }
```

`serve` starts the node in a `try (...)` so it is closed when the block ends. After `startOnSale` it prints one line that says where it listens. `String.format(Locale.ROOT, ...)` fills `%d` with numbers; the two string pieces joined with `+` are one template. `System.out.flush()` pushes the line out at once: the comparison tool starts this program as a child process and waits for that line, so it must not sit in a buffer. `Thread.sleep(settings.serveTime())` keeps the main thread alive for `serve.seconds` (600 in `config/serve.properties`); the servers run on their own threads. When the sleep ends the `try` closes the node and the program ends.

### `config/LiveSettings.java`

```java
package io.plainround.seatwave.live.config;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.feed.SeatLayout;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

```

Imports. `Pattern` is for the origin check. `SeatLayout` is the one class `config` uses from `feed`.

```java
/**
 * Everything a live-feed process needs that may differ between runs, read from one settings file
 * with no defaults in code: a missing key is a problem, never a silent fallback.
 *
 * @param network the ports and the allowed WebSocket origin
 * @param limits the bounds on connections, buffers and batches
 * @param timing heartbeats, timeouts and the long-poll hold
 * @param layout the event's seat map
 * @param onSale the simulated on-sale that changes seats
 * @param adminEnabled whether the lab's admin routes (pause, resume, drop connections, settle)
 *     exist
 * @param serveTime how long {@code serve} runs before it stops by itself
 */
public record LiveSettings(
    Network network,
    Limits limits,
    Timing timing,
    SeatLayout layout,
    OnSale onSale,
    boolean adminEnabled,
    Duration serveTime) {
```

The settings record has seven parts, five of them records of their own, defined further down. The Javadoc says there are no defaults: "a missing key is a problem, never a silent fallback". `adminEnabled` turns on the lab's `/admin/...` routes. `serveTime` is how long `serve` runs.

```java
  private static final int LOWEST_UNPRIVILEGED_PORT = 1_024;
  private static final int HIGHEST_PORT = 65_535;
  // macOS caps the accept backlog at kern.ipc.somaxconn; a larger request is silently lowered.
  private static final int MAX_BACKLOG = 4_096;
  private static final int MAX_CONNECTIONS = 100_000;
  private static final int MAX_REPLAY = 10_000_000;
  private static final int MAX_QUEUE = 100_000;
  private static final int MAX_BATCH = 10_000;
  private static final int MAX_TIMEOUT_MILLIS = 600_000;
  private static final int MAX_ACTIONS_PER_SECOND = 5_000;
  private static final int MAX_PERCENT = 100;
  private static final int MAX_SERVE_SECONDS = 86_400;
  // The page that may open a WebSocket is served by this feed on the loopback address.
  private static final Pattern LOOPBACK_ORIGIN =
      Pattern.compile("http://127\\.0\\.0\\.1:[0-9]{2,5}");
```

Constants for the allowed ranges. Ports start at 1,024 because lower ports need special rights on most systems. The backlog (how many connections may wait to be accepted) is capped at 4,096; the comment explains that macOS lowers a bigger request silently, so asking for more would only mislead. The origin pattern `http://127\.0\.0\.1:[0-9]{2,5}` allows only the loopback address with a port: the page this feed serves is the only page allowed to open its WebSocket.

```java
  /** Checks the fields. */
  public LiveSettings {
    Objects.requireNonNull(network, "network");
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(timing, "timing");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(onSale, "onSale");
    Objects.requireNonNull(serveTime, "serveTime");
    if (serveTime.isNegative() || serveTime.isZero()) {
      throw new IllegalArgumentException("serveTime must be positive");
    }
  }
```

The compact constructor makes a nonsense value impossible: nothing may be `null` and the serve time must be positive.

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings file
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<LiveSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
```

`from` is the reader. It returns a `Result` with a list of problem sentences as the error.

```java
    Network network =
        new Network(
            reader.intBetween("http.port", LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT),
            reader.intBetween("websocket.port", LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT),
            reader.intBetween("http.backlog", 1, MAX_BACKLOG),
            reader
                .matching("websocket.allowedOrigin", LOOPBACK_ORIGIN, "http://127.0.0.1:<port>")
                // A missing or invalid origin is already a problem line; this stand-in only lets
                // the reading go on, so every other problem is reported in the same run.
                .transform(text -> text.isEmpty() ? "http://127.0.0.1:10" : text));
```

The network part. `reader.intBetween("http.port", 1024, 65535)` reads the key `http.port` as a whole number inside that range. The origin goes through `matching(...)`, which checks the pattern. The `.transform(...)` call applies a function to the string (`String.transform` is Java 12). If the key was missing or wrong, the reader returns the empty string, and the stand-in `http://127.0.0.1:10` lets the `Network` constructor succeed so that every other problem is still found in the same run.

```java
    Limits limits =
        new Limits(
            reader.intBetween("feed.maxConnections", 1, MAX_CONNECTIONS),
            reader.intBetween("feed.replayCapacity", 1, MAX_REPLAY),
            reader.intBetween("feed.clientQueueCapacity", 1, MAX_QUEUE),
            reader.intBetween("feed.maxBatch", 1, MAX_BATCH));
```

The four limits. Each key has a range, and the upper bounds are the constants above.

```java
    Timing timing =
        new Timing(
            reader.millisBetween("feed.heartbeatMillis", 1, MAX_TIMEOUT_MILLIS),
            reader.millisBetween("feed.writeTimeoutMillis", 1, MAX_TIMEOUT_MILLIS),
            reader.millisBetween("feed.longPollHoldMillis", 1, MAX_TIMEOUT_MILLIS),
            reader.millisBetween("feed.retryMillis", 1, MAX_TIMEOUT_MILLIS));
```

The four timings are read in milliseconds with `millisBetween` and become `Duration`s. A range from 1 ms to 600,000 ms (ten minutes).

```java
    int sections = reader.intBetween("store.sections", 1, SeatLayout.MAX_SECTIONS);
    int seatsPerSection =
        reader.intBetween("store.seatsPerSection", 1, SeatLayout.MAX_SEATS_PER_SECTION);
```

The layout. The largest values are `SeatLayout`'s own constants, so the number is written in one place.

```java
    OnSale onSale =
        new OnSale(
            Duration.ofSeconds(reader.intBetween("store.holdSeconds", 1, MAX_SERVE_SECONDS)),
            reader.intBetween("onSale.actionsPerSecond", 1, MAX_ACTIONS_PER_SECOND),
            reader.intBetween("onSale.confirmPercent", 0, MAX_PERCENT),
            reader.intBetween("onSale.seed", 0, Integer.MAX_VALUE));
```

The on-sale. The hold time is read in seconds and turned into a `Duration`. `confirmPercent` is 0 to 100. `seed` is 0 or more.

```java
    boolean admin = reader.flag("admin.enabled");
    Duration serve = Duration.ofSeconds(reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS));
```

The admin flag and the serve time in seconds.

```java
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new LiveSettings(
            network,
            limits,
            timing,
            new SeatLayout(sections, seatsPerSection),
            onSale,
            admin,
            serve));
  }
```

If any key had a problem, the whole call returns `Result.error(reader.problems())`. Only when the list is empty does it build the record. The order matters: every value was read first, so every problem was collected before this check.

```java
  /**
   * Where the feed listens.
   *
   * @param httpPort the JDK HTTP server's port for long polling, server-sent events and the page; 0
   *     lets the system pick one (tests only)
   * @param websocketPort the WebSocket server's port; 0 lets the system pick one (tests only)
   * @param backlog how many connections may wait to be accepted
   * @param allowedOrigin the one browser origin allowed to open a WebSocket
   */
  public record Network(int httpPort, int websocketPort, int backlog, String allowedOrigin) {

    /** Checks the fields. */
    public Network {
      if (httpPort < 0
          || httpPort > HIGHEST_PORT
          || websocketPort < 0
          || websocketPort > HIGHEST_PORT) {
        throw new IllegalArgumentException("ports must be from 0 to 65535");
      }
      if (backlog < 1 || backlog > MAX_BACKLOG) {
        throw new IllegalArgumentException("backlog out of range: " + backlog);
      }
      if (!LOOPBACK_ORIGIN
          .matcher(Objects.requireNonNull(allowedOrigin, "allowedOrigin"))
          .matches()) {
        throw new IllegalArgumentException("allowedOrigin must be http://127.0.0.1:<port>");
      }
    }
  }
```

`Network` is a nested record with its own checks: ports 0 to 65535 (0 lets the system choose, for tests), a backlog in range, and an origin that matches the loopback pattern.

```java
  /**
   * The bounds that keep memory and connections finite.
   *
   * @param maxConnections the most feed connections open at once, all methods together
   * @param replayCapacity how many recent changes are kept for clients that resume
   * @param clientQueueCapacity how many changes one push connection may have waiting
   * @param maxBatch the most changes one write or one long-poll reply carries
   */
  public record Limits(
      int maxConnections, int replayCapacity, int clientQueueCapacity, int maxBatch) {

    /** Checks the fields. */
    public Limits {
      if (maxConnections < 1 || replayCapacity < 1 || clientQueueCapacity < 1 || maxBatch < 1) {
        throw new IllegalArgumentException("every limit must be at least 1");
      }
    }
  }
```

`Limits`: `maxConnections` (all methods together), `replayCapacity` (how many changes the ring keeps), `clientQueueCapacity` (the bound of one connection's queue) and `maxBatch` (the most changes one write or one long-poll reply carries). All must be at least 1.

```java
  /**
   * The feed's clocks.
   *
   * @param heartbeat how long a push connection may stay silent before the feed sends a heartbeat
   * @param writeTimeout how long one write may block before the connection is dropped
   * @param longPollHold how long a long poll is held when there is no news
   * @param retry the reconnection delay sent to server-sent events clients
   */
  public record Timing(
      Duration heartbeat, Duration writeTimeout, Duration longPollHold, Duration retry) {

    /** Checks the fields. */
    public Timing {
      for (Duration duration : List.of(heartbeat, writeTimeout, longPollHold, retry)) {
        if (duration.isNegative() || duration.isZero()) {
          throw new IllegalArgumentException("every timing must be positive");
        }
      }
    }
  }
```

`Timing`: heartbeat, write timeout, long-poll hold and the retry delay sent to event-stream clients. The loop `for (Duration duration : List.of(...))` checks each is positive. `List.of(...)` builds a fixed list of the four.

```java
  /**
   * The simulated on-sale.
   *
   * @param holdTime how long a hold lasts
   * @param actionsPerSecond holds and confirms per second
   * @param confirmPercent the share of actions that confirm a hold
   * @param seed the seed of the simulator's random choices
   */
  public record OnSale(Duration holdTime, int actionsPerSecond, int confirmPercent, int seed) {

    /** Checks the fields. */
    public OnSale {
      Objects.requireNonNull(holdTime, "holdTime");
      if (actionsPerSecond < 1 || actionsPerSecond > MAX_ACTIONS_PER_SECOND) {
        throw new IllegalArgumentException("actionsPerSecond out of range: " + actionsPerSecond);
      }
      if (confirmPercent < 0 || confirmPercent > MAX_PERCENT || seed < 0) {
        throw new IllegalArgumentException("confirmPercent or seed out of range");
      }
    }
  }
}
```

`OnSale`: hold time, actions per second, confirm percentage and seed, each checked.

### `config/SettingsReader.java`

```java
package io.plainround.seatwave.live.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
```

`Properties` is the JDK class for `key=value` files. `Reader`, `Files` and `StandardCharsets` read the file as UTF-8 text.

```java
/**
 * Reads typed values from a settings file and collects one problem line per missing or invalid key,
 * so a reader sees every mistake in one run.
 *
 * <p>There are no defaults: a key that is absent is a problem, never a silent fallback. When a key
 * is invalid, the method records the problem and returns a harmless stand-in value; the caller must
 * check {@link #problems()} before using any value. Not thread-safe: one reader is used by one
 * thread while it builds one settings record.
 */
public final class SettingsReader {

  private final Properties properties;
  private final List<String> problems = new ArrayList<>();
```

The reader collects problems in a list instead of stopping at the first. When a key is bad it records the problem and returns a **stand-in** (the minimum, or the empty string). The Javadoc warns that the caller must check `problems()` before using any value. It is not thread-safe, and says so: one thread builds one settings record.

```java
  /**
   * Wraps loaded properties.
   *
   * @param properties the settings, already loaded
   */
  public SettingsReader(Properties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }
```

The constructor stores the already loaded properties.

```java
  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file cannot be read
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
```

`load` opens the file with a `try`-with-resources block, which closes the reader whatever happens, and `properties.load(reader)` parses the lines. Comment lines starting with `#` are skipped by `Properties`.

```java
  /**
   * Reads a whole number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public int intBetween(String key, int minimum, int maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      int value = Integer.parseInt(text);
      if (value < minimum || value > maximum) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      // A typo is an expected failure of a settings file, so it becomes a problem line, not a
      // crash.
      problems.add(key + " must be a whole number, but was " + text);
      return minimum;
    }
  }
```

`intBetween` reads the text with `text(key)`. If it came back empty the key is missing (and `text` already recorded that), so it returns the minimum. `Integer.parseInt` turns the text into a number; a `NumberFormatException` (a typo such as `80x`) becomes the problem line "must be a whole number". A value outside the range becomes "must be from X to Y, but was Z". The `Locale.ROOT` in `String.format` keeps digits in the ASCII form.

```java
  /**
   * Reads a duration written in milliseconds, inside a range.
   *
   * @param key the key, which by convention ends in {@code Millis}
   * @param minimumMillis the smallest allowed value
   * @param maximumMillis the largest allowed value
   * @return the duration, or the minimum if the key is missing or invalid
   */
  public Duration millisBetween(String key, int minimumMillis, int maximumMillis) {
    return Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis));
  }
```

`millisBetween` reads whole milliseconds and wraps them in a `Duration`.

```java
  /**
   * Reads a yes-or-no value, written {@code true} or {@code false}.
   *
   * @param key the key
   * @return the value, or false if the key is missing or invalid
   */
  public boolean flag(String key) {
    String text = text(key);
    return switch (text) {
      case "true" -> true;
      case "false" -> false;
      case "" -> false;
      default -> {
        problems.add(key + " must be true or false, but was " + text);
        yield false;
      }
    };
  }
```

`flag` reads `true` or `false`. This is a **switch expression**: `return switch (text) { case "true" -> true; ... }` picks a value. The block for `default` records a problem and uses `yield` to give the value of that branch. A missing key was already reported, so `""` returns `false` quietly.

```java
  /**
   * Reads a text value that must match a pattern.
   *
   * @param key the key
   * @param allowed the pattern the whole value must match
   * @param description what the value must look like, for the problem line
   * @return the value, or an empty string if the key is missing or invalid
   */
  public String matching(String key, Pattern allowed, String description) {
    Objects.requireNonNull(allowed, "allowed");
    String text = text(key);
    if (text.isEmpty()) {
      return "";
    }
    if (!allowed.matcher(text).matches()) {
      problems.add(key + " must be " + description + ", but was " + text);
      return "";
    }
    return text;
  }
```

`matching` reads a text value that must match a pattern as a whole. It returns the empty string when the key is missing or does not fit.

```java
  /**
   * Reads a text value.
   *
   * @param key the key
   * @return the trimmed value, or an empty string if the key is missing or blank
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      problems.add(key + " is missing");
      return "";
    }
    return value.strip();
  }
```

`text` is the base reader. A missing or blank value adds "`key` is missing". Otherwise it returns the value with spaces removed from both ends (`strip`).

```java
  /**
   * Returns every problem found so far.
   *
   * @return the problems, in the order they were found; empty when every key was valid
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }
}
```

`problems()` returns an unmodifiable copy, so a caller cannot change the reader's list.

### `http/Parts.java`

```java
package io.plainround.seatwave.live.http;

import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.FeedMetrics;
import io.plainround.seatwave.live.feed.OnSaleSimulator;
import io.plainround.seatwave.live.feed.SeatFeed;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.feed.SeatStore;
import io.plainround.seatwave.live.wire.FeedJson;
import java.util.Objects;

/**
 * The shared parts of one feed node that the HTTP and WebSocket servers use.
 *
 * @param store the seat store
 * @param feed the publisher
 * @param simulator the simulated on-sale, for the admin routes
 * @param limit the connection limit
 * @param metrics the counters
 * @param json the JSON codec
 */
public record Parts(
    SeatStore store,
    SeatFeed feed,
    OnSaleSimulator simulator,
    ConnectionLimit limit,
    FeedMetrics metrics,
    FeedJson json) {
```

A record that holds the six shared parts. Both the HTTP server and the WebSocket server take one, so they use the same store, feed, limit, metrics and codec.

```java
  /** Checks the fields. */
  public Parts {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(feed, "feed");
    Objects.requireNonNull(simulator, "simulator");
    Objects.requireNonNull(limit, "limit");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(json, "json");
  }

  /**
   * The seat map's shape.
   *
   * @return the store's layout
   */
  public SeatLayout layout() {
    return store.layout();
  }
}
```

The constructor refuses any `null`. `layout()` is a shortcut to `store.layout()`.

### `http/HttpStatus.java` and `http/Problem.java`

```java
package io.plainround.seatwave.live.http;

/** The status codes the feed sends that have no {@link Problem}. */
final class HttpStatus {

  static final int OK = 200;

  private HttpStatus() {}
}
```

A holder of the one status code that is not a problem: 200, "OK". The private constructor means nobody makes an instance.

```java
package io.plainround.seatwave.live.http;

/** The kinds of problem the feed answers with, each with its status and title. */
public enum Problem {
  /** A parameter or header breaks the feed's contract. */
  INVALID_REQUEST(400, "invalid-request", "The request does not match the feed's contract"),
  /** No such event, or no such path. */
  NOT_FOUND(404, "not-found", "Nothing is published at this path"),
  /** The path exists but not for this method. */
  METHOD_NOT_ALLOWED(405, "method-not-allowed", "This path does not allow the method"),
  /** The connection limit is reached; try again after Retry-After. */
  FEED_FULL(503, "feed-full", "The feed has as many open connections as it allows");
```

The kinds of error reply, each with an HTTP status, a short name for its URL and a title. 400 means the request is wrong, 404 means nothing is here, 405 means the method is not allowed on this path, and 503 means the feed is full.

```java
  private static final String BASE = "https://seatwave.example/problems/";

  private final int status;
  private final String slug;
  private final String title;

  Problem(int status, String slug, String title) {
    this.status = status;
    this.slug = slug;
    this.title = title;
  }
```

`BASE` is the start of the problem-type URL. The enum constructor stores the three parts.

```java
  /**
   * The HTTP status code.
   *
   * @return the code
   */
  public int status() {
    return status;
  }

  /**
   * The problem's type URI.
   *
   * @return the URI as text
   */
  public String type() {
    return BASE + slug;
  }

  /**
   * The problem's title.
   *
   * @return the title
   */
  public String title() {
    return title;
  }
}
```

Three getters. `type()` joins `BASE` and the slug: `https://seatwave.example/problems/invalid-request`. The host `seatwave.example` is a made-up name that only serves as an identifier.

### `http/ProblemView.java`

```java
package io.plainround.seatwave.live.http;

/**
 * An error body in the problem details format (RFC 9457), the one error shape of Module 7's version
 * 1 API.
 *
 * @param type a URI naming the kind of problem, under {@code https://seatwave.example/problems/}
 * @param title a short summary for people
 * @param status the HTTP status code
 * @param detail what went wrong with this request
 * @param instance a URN that also appears in the server's log line for the request
 */
public record ProblemView(String type, String title, int status, String detail, String instance) {}
```

The error body, in the format of RFC 9457 ("Problem Details for HTTP APIs"). Five fields: a type URI, a title, the status, a detail sentence and an `instance` (a URN that also appears in the server's log line for the request, so a person can find the log line from the reply).

### `http/Query.java`

```java
package io.plainround.seatwave.live.http;

import com.github.sviperll.result4j.Result;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The query parameters of one request, checked against an allow-list: an unknown or repeated
 * parameter is refused, so a typo is visible instead of ignored.
 *
 * @param values the parameters by name
 */
record Query(Map<String, String> values) {
```

A record that holds the query parameters of one request. The **query** is the part of a URL after `?`, such as `after=4d4f4a94.230`. `URLDecoder` turns `%2E`-style escapes back into characters.

```java
  // A query longer than this cannot be a valid feed request.
  private static final int MAX_LENGTH = 256;

  Query {
    values = Map.copyOf(values);
  }
```

The compact constructor replaces the map with `Map.copyOf(values)`, an unmodifiable copy.

```java
  static Result<Query, String> parse(String rawQuery, Set<String> allowed) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return Result.success(new Query(Map.of()));
    }
    if (rawQuery.length() > MAX_LENGTH) {
      return Result.error("the query is longer than " + MAX_LENGTH + " characters");
    }
    Map<String, String> values = new HashMap<>();
    for (String pair : rawQuery.split("&", -1)) {
      int equals = pair.indexOf('=');
      String name =
          URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
      String value =
          equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
      if (!allowed.contains(name)) {
        return Result.error("parameter " + name + " is not part of this request");
      }
      if (values.put(name, value) != null) {
        return Result.error("parameter " + name + " is repeated");
      }
    }
    return Result.success(new Query(values));
  }
```

`parse` takes the raw query and an allow-list. An empty query is fine. A query over 256 characters is refused. `rawQuery.split("&", -1)` cuts at every `&`; the `-1` keeps empty pieces. For each piece, it finds the first `=`, decodes the name and the value as UTF-8, refuses a name that is not on the allow-list ("a typo is visible instead of ignored"), and refuses a name that appears twice (`values.put` returns the old value, or `null` if there was none). Strictness here is on purpose: a client that mistypes `afer=` gets a clear 400 instead of the feed silently starting from the newest position.

```java
  Optional<String> get(String name) {
    return Optional.ofNullable(values.get(name));
  }
}
```

`get` returns an `Optional`.

### `http/Positions.java`

```java
package io.plainround.seatwave.live.http;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.feed.FeedPosition;

/** Reads the {@code after} parameter that names a client's position. */
final class Positions {

  private Positions() {}

  static Result<FeedPosition, String> after(Query query) {
    return query
        .get("after")
        .map(written -> FeedPosition.parse(written).mapError(detail -> "after " + detail))
        .orElse(Result.error("after is required: the position of the last change you have"));
  }
}
```

`after` reads the one parameter that matters. `query.get("after")` is an `Optional<String>`. `.map(written -> FeedPosition.parse(written).mapError(detail -> "after " + detail))` turns a present value into a `Result`, and prefixes any error sentence with the parameter name. `.orElse(Result.error(...))` is the answer when the parameter is absent. The messages seen by clients are "after must be <8 hex digits>.<sequence>, as the feed sent it" and "after is required: the position of the last change you have".

### `http/Replies.java`

```java
package io.plainround.seatwave.live.http;

import com.sun.net.httpserver.HttpExchange;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;

/**
 * Writes complete replies: JSON bodies, plain text and problem details. Every refusal goes through
 * {@link #problem}, so the feed has one error shape.
 *
 * <p>Thread-safe: no state besides the shared codec.
 */
final class Replies {
```

`HttpExchange` is the JDK server's object for one request and its reply. This class writes whole replies. Every refusal goes through `problem`, so the feed has one error shape.

```java
  private static final String JSON = "application/json";
  private static final String PROBLEM_JSON = "application/problem+json";

  private final FeedJson json;
  private final Duration retryAfter;

  Replies(FeedJson json, Duration retryAfter) {
    this.json = Objects.requireNonNull(json, "json");
    this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
  }
```

The two content types. `application/problem+json` is the registered type for problem details. The class keeps the shared JSON codec and the retry delay for 503.

```java
  void json(HttpExchange exchange, int status, byte[] body) throws IOException {
    send(exchange, status, JSON, body);
  }

  void text(HttpExchange exchange, String contentType, byte[] body) throws IOException {
    send(exchange, HttpStatus.OK, contentType, body);
  }
```

`json` and `text` are short wrappers around `send`. `text` always uses status 200.

```java
  void problem(HttpExchange exchange, Problem problem, String detail, String correlationId)
      throws IOException {
    if (problem == Problem.FEED_FULL) {
      // Module 4's rule: a refusal says when to come back, so clients do not retry at once.
      exchange
          .getResponseHeaders()
          .set("Retry-After", Long.toString(Math.max(1, retryAfter.toSeconds())));
    }
    if (problem == Problem.METHOD_NOT_ALLOWED
        && !exchange.getResponseHeaders().containsKey("Allow")) {
      exchange.getResponseHeaders().set("Allow", "GET");
    }
    ProblemView view =
        new ProblemView(
            problem.type(), problem.title(), problem.status(), detail, "urn:uuid:" + correlationId);
    send(exchange, problem.status(), PROBLEM_JSON, json.bytes(view));
  }
```

`problem` first adds headers where the problem needs them. A full feed adds `Retry-After`, the number of seconds the client should wait (at least 1); the comment names Module 4's rule: a refusal says when to come back, so clients do not retry at once. A 405 gets an `Allow: GET` header unless a route already set one. Then it builds the `ProblemView` with the instance `urn:uuid:` plus the correlation id, and sends it with the problem's status.

```java
  private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    // Every feed reply is live state; a cache in between would serve an old seat map.
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
```

`send` sets `Content-Type` and `Cache-Control: no-store` (every feed reply is live state, and a cache between client and server would serve an old seat map). `sendResponseHeaders(status, body.length)` writes the status line and headers and promises exactly that many body bytes. The `try (OutputStream out = ...)` writes the body and closes the stream, which finishes the exchange.

### `http/LongPollRoute.java`

```java
package io.plainround.seatwave.live.http;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import io.plainround.seatwave.live.feed.Catchup;
import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedMetrics;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatFeed;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * {@code GET /v1/events/1/seat-changes?after=<position>}: long polling. The request is held until
 * the feed has a change after the client's position or the hold ends, then answered with every
 * change after it (up to one batch), or with an instruction to reload.
 *
 * <p>A held request costs a parked virtual thread and an open connection, and counts against the
 * connection limit like a push connection. Thread-safe: no state of its own.
 */
final class LongPollRoute {
```

A **long poll** is a request the server holds until it has news. The Javadoc defines this route: `GET /v1/events/1/seat-changes?after=<position>`. A held request costs a parked virtual thread and an open connection, and it counts against the connection limit like a push connection.

```java
  private final SeatFeed feed;
  private final SeatLayout layout;
  private final ConnectionLimit limit;
  private final FeedMetrics metrics;
  private final FeedJson json;
  private final Replies replies;
  private final Duration hold;

  LongPollRoute(Parts parts, Replies replies, Duration hold) {
    this.feed = parts.feed();
    this.layout = parts.layout();
    this.limit = parts.limit();
    this.metrics = parts.metrics();
    this.json = parts.json();
    this.replies = replies;
    this.hold = hold;
  }
```

The route copies what it needs from `Parts` and takes the hold time.

```java
  void serve(HttpExchange exchange, String correlationId) throws IOException {
    Result<FeedPosition, String> asked = positionFrom(exchange.getRequestURI().getRawQuery());
    if (asked instanceof Result.Error<FeedPosition, String>(String detail)) {
      replies.problem(exchange, Problem.INVALID_REQUEST, detail, correlationId);
      return;
    }
```

`serve` first reads the position. `positionFrom` returns a `Result`. The `if` uses the record pattern `asked instanceof Result.Error<FeedPosition, String>(String detail)`: if it is an error, `detail` is the sentence, and the route answers 400 with it and returns.

```java
    FeedPosition position = asked.recoverError(detail -> feed.position());
    Thread handler = Thread.currentThread();
    Optional<ConnectionLimit.Ticket> admitted =
        limit.admit(DeliveryMethod.LONG_POLLING, handler::interrupt);
    if (admitted.isEmpty()) {
      replies.problem(exchange, Problem.FEED_FULL, "too many open feed connections", correlationId);
      return;
    }
```

`asked.recoverError(detail -> feed.position())` turns the `Result` into a plain `FeedPosition`: it is the success value (the fallback function is never used here, since errors returned above). `Thread.currentThread()` is the virtual thread serving this request. `limit.admit(DeliveryMethod.LONG_POLLING, handler::interrupt)` asks for a slot; the second argument is what the limit will run to drop this connection (interrupt the thread). If there is no slot, the route answers 503 with `Retry-After`.

```java
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      Catchup catchup = feed.awaitChangesAfter(position, hold);
      byte[] body = json.longPoll(catchup, layout, position);
      replies.json(exchange, HttpStatus.OK, body);
      metrics.recordLongPollReply();
      switch (catchup) {
        case Catchup.Changes changes ->
            metrics.recordDelivered(
                DeliveryMethod.LONG_POLLING, changes.changes().size(), body.length);
        case Catchup.ReloadRequired reload -> metrics.recordReloadSent(DeliveryMethod.LONG_POLLING);
      }
```

`try (ConnectionLimit.Ticket _ = admitted.get())` holds the slot for the length of the block. The name `_` is an **unnamed variable** (Java 22): the ticket is needed only to be closed, so it gets no name. Inside: `feed.awaitChangesAfter(position, hold)` waits up to `hold` for news. `json.longPoll(...)` builds the body, `replies.json(...)` sends it, and the metrics count the reply. The `switch` over `catchup` has two cases and no default, because `Catchup` is sealed. Changes are counted with their number and the body size; a reload is counted as a reload.

```java
    } catch (InterruptedException dropped) {
      // The connection is being dropped (the lab's drop, or shutdown): stop and send nothing.
      Thread.currentThread().interrupt();
    }
  }
```

If the thread is interrupted (the lab dropped the connection, or the node is closing) the wait throws `InterruptedException`. The code restores the thread's interrupt flag with `Thread.currentThread().interrupt()` and sends nothing. Restoring the flag is the rule: whoever catches an interrupt without acting on it must put it back for the code above.

```java
  private static Result<FeedPosition, String> positionFrom(String rawQuery) {
    return switch (Query.parse(rawQuery, Set.of("after"))) {
      case Result.Error<Query, String>(String detail) -> Result.error(detail);
      case Result.Success<Query, String>(Query query) -> Positions.after(query);
    };
  }
}
```

`positionFrom` parses the query with an allow-list of one name, `after`, then reads the position. The `switch` expression returns a `Result` in both cases.

### `http/EventStreamRoute.java`

```java
package io.plainround.seatwave.live.http;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.Delivery;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedMetrics;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatChange;
import io.plainround.seatwave.live.feed.SeatFeed;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.feed.Subscriber;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

```

Imports. `ByteArrayOutputStream` builds bytes in memory. `Logger` and `LoggerFactory` are SLF4J, the logging interface (see `web-page-and-logging.md`).

```java
/**
 * {@code GET /v1/events/1/seat-changes/stream}: server-sent events. One long response in the {@code
 * text/event-stream} format of the WHATWG HTML Living Standard: a {@code retry} line, then one
 * event per change with the change's position as its {@code id}, a comment line as a heartbeat when
 * nothing happens, and a {@code reload} event when the client fell too far behind.
 *
 * <p>The resume position comes from the {@code Last-Event-ID} header when present, else from the
 * {@code after} query parameter. The header wins because a browser reconnects to the same URL with
 * the old {@code after} but sends the newest id it saw in the header.
 *
 * <p>Thread-safe: each stream is served by its own virtual thread, the only writer to its response.
 */
final class EventStreamRoute {
```

The Javadoc defines **server-sent events**: one long response in the `text/event-stream` format of the WHATWG HTML Living Standard. A `retry` line, one event per change with the position as its `id`, a comment line as a heartbeat, and a `reload` event for a client that fell behind. The resume position comes from the `Last-Event-ID` header when it is present, otherwise from `after`. The header wins because a browser reconnects to the same URL it first used (with the old `after`) but sends the newest id it saw in the header.

```java
  private static final Logger LOG = LoggerFactory.getLogger(EventStreamRoute.class);
  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
  // Streaming responses have no length; the JDK server then uses chunked transfer coding.
  private static final long STREAMING = 0;
```

`LOG` is this class's logger. `HEARTBEAT` is the bytes of a comment line: a line that starts with `:` is ignored by the client, but its arrival keeps the connection alive through middleboxes that close idle links. `STREAMING = 0` is the length value that makes the JDK server use **chunked transfer coding**: the reply has no fixed length and ends when the server closes it.

```java
  private final SeatFeed feed;
  private final SeatLayout layout;
  private final ConnectionLimit limit;
  private final FeedMetrics metrics;
  private final FeedJson json;
  private final Replies replies;
  private final Duration heartbeat;
  private final Duration retry;

  EventStreamRoute(Parts parts, Replies replies, Duration heartbeat, Duration retry) {
    this.feed = parts.feed();
    this.layout = parts.layout();
    this.limit = parts.limit();
    this.metrics = parts.metrics();
    this.json = parts.json();
    this.replies = replies;
    this.heartbeat = heartbeat;
    this.retry = retry;
  }
```

Fields and constructor, as in the long-poll route, plus the heartbeat and retry times.

```java
  void serve(HttpExchange exchange, String correlationId) throws IOException {
    Result<FeedPosition, String> asked = positionFrom(exchange);
    if (asked instanceof Result.Error<FeedPosition, String>(String detail)) {
      replies.problem(exchange, Problem.INVALID_REQUEST, detail, correlationId);
      return;
    }
```

The same position check as the long poll, but `positionFrom(exchange)` also looks at the header.

```java
    FeedPosition position = asked.recoverError(detail -> feed.position());
    Thread writer = Thread.currentThread();
    Optional<ConnectionLimit.Ticket> admitted =
        limit.admit(DeliveryMethod.SERVER_SENT_EVENTS, writer::interrupt);
    if (admitted.isEmpty()) {
      replies.problem(exchange, Problem.FEED_FULL, "too many open feed connections", correlationId);
      return;
    }
```

The same fallback, the same slot request. The drop action is `writer::interrupt`.

```java
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      Subscriber subscriber = feed.subscribe(position);
      try {
        stream(exchange, subscriber);
      } finally {
        feed.unsubscribe(subscriber);
      }
    } catch (InterruptedException dropped) {
      Thread.currentThread().interrupt();
    } catch (IOException clientGone) {
      // A client that closes its stream, or a write the watchdog interrupted, ends here.
      LOG.debug("event stream ended: {}", clientGone.toString());
    }
  }
```

With a ticket, the route calls `feed.subscribe(position)`, which returns a subscriber that already holds the catch-up. `stream(...)` runs until the connection ends. The inner `finally` always unsubscribes. The outer `catch` blocks: an `InterruptedException` restores the flag; an `IOException` means the client went away or the watchdog interrupted a stuck write, which is normal, so it is logged at debug level only, with the exception's text.

```java
  private void stream(HttpExchange exchange, Subscriber subscriber)
      throws IOException, InterruptedException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(HttpStatus.OK, STREAMING);
    OutputStream out = exchange.getResponseBody();
    write(
        subscriber,
        out,
        ("retry: " + retry.toMillis() + "\n\n").getBytes(StandardCharsets.UTF_8),
        0);
```

`stream` sets `Content-Type: text/event-stream` and `Cache-Control: no-store`, then `sendResponseHeaders(200, 0)` starts the chunked reply. It writes the first thing every stream gets: `retry: 1000` and a blank line, which tells the client how many milliseconds to wait before it reconnects. The `write` helper (below) takes the subscriber so it can mark the write for the watchdog.

```java
    while (true) {
      switch (subscriber.next(heartbeat)) {
        case Delivery.Batch batch -> write(subscriber, out, events(batch), batch.changes().size());
        case Delivery.Idle idle -> write(subscriber, out, HEARTBEAT, 0);
        case Delivery.Woken woken -> {
          // Nobody wakes an event stream; nothing to send.
        }
        case Delivery.Reload reload -> {
          byte[] event = event("reload", null, json.bytes(json.reload(feed.position())));
          write(subscriber, out, event, 0);
          metrics.recordReloadSent(DeliveryMethod.SERVER_SENT_EVENTS);
          out.close();
          return;
        }
      }
    }
  }
```

The main loop. `subscriber.next(heartbeat)` blocks until there is something to send. The `switch` has four cases, one per `Delivery`. A `Batch` is written as events. `Idle` writes the comment-line heartbeat. `Woken` does nothing (nobody wakes an event stream). `Reload` builds a `reload` event with the feed's current position, writes it, counts it, closes the output and returns: the client is expected to load the seat map and start again.

```java
  private byte[] events(Delivery.Batch batch) {
    ByteArrayOutputStream events = new ByteArrayOutputStream();
    for (SeatChange change : batch.changes()) {
      events.writeBytes(
          event("seat", change.position().written(), json.bytes(json.message(change, layout))));
    }
    return events.toByteArray();
  }
```

`events` turns a batch into bytes: one event per change, with the position as the id, joined in one array so the whole batch goes out in one write.

```java
  private static byte[] event(String type, String id, byte[] data) {
    StringBuilder event = new StringBuilder();
    if (id != null) {
      event.append("id: ").append(id).append('\n');
    }
    event.append("event: ").append(type).append('\n');
    event.append("data: ").append(new String(data, StandardCharsets.UTF_8)).append("\n\n");
    return event.toString().getBytes(StandardCharsets.UTF_8);
  }
```

`event` builds one event. If there is an id it adds `id: <id>`; then `event: <type>`; then `data: <json>` and a blank line, which ends the event. JSON from Jackson has no raw line break in it, so one `data:` line is enough. The event type lets the page use `addEventListener("seat", ...)` and `addEventListener("reload", ...)`.

```java
  private void write(Subscriber subscriber, OutputStream out, byte[] bytes, int changes)
      throws IOException {
    subscriber.beginWrite(Thread.currentThread());
    try {
      out.write(bytes);
      // One flush per batch: the changes that queued while the last write ran leave together.
      out.flush();
    } finally {
      subscriber.endWrite();
    }
    metrics.recordDelivered(DeliveryMethod.SERVER_SENT_EVENTS, changes, bytes.length);
  }
```

`write` is the only place bytes leave. `beginWrite` marks the start for the watchdog, `out.write(bytes)` and `out.flush()` send, and `endWrite` in `finally` marks the end. One flush per batch means that changes that queued while the last write was running go out together. The delivered count and byte count go to the metrics.

```java
  private static Result<FeedPosition, String> positionFrom(HttpExchange exchange) {
    return switch (Query.parse(exchange.getRequestURI().getRawQuery(), Set.of("after"))) {
      case Result.Error<Query, String>(String detail) -> Result.error(detail);
      case Result.Success<Query, String>(Query query) -> {
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        yield lastEventId != null
            ? FeedPosition.parse(lastEventId).mapError(detail -> "Last-Event-ID " + detail)
            : Positions.after(query);
      }
    };
  }
}
```

`positionFrom` returns an error for a bad query. Otherwise it takes the `Last-Event-ID` header (`getFirst` returns the first value or `null`). If present, it is parsed and a bad one reports "Last-Event-ID must be ...". If absent, the `after` parameter is used. The `yield` in the block gives the value of the `case`.

### `http/FeedHttpServer.java`

```java
package io.plainround.seatwave.live.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.live.config.LiveSettings;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

```

Imports. `HttpServer` is the JDK server. `MDC` is SLF4J's per-thread map of logging fields, used for the correlation id. `ManagementFactory` and `MemoryMXBean` read the JVM's memory.

```java
/**
 * The feed's HTTP side, on the JDK's own server ({@code com.sun.net.httpserver}, an exported and
 * supported JDK module) bound to 127.0.0.1 only, with one virtual thread per request: long polling,
 * server-sent events, the seat map, the browser page, health, metrics and the lab's admin routes.
 *
 * <p>A held long poll or an open event stream parks its virtual thread, which costs about a
 * kilobyte of heap (Module 4), not a platform thread. Thread-safe: the routes hold no mutable
 * state.
 */
public final class FeedHttpServer implements AutoCloseable {

  /** The one event the feed serves: Module 1's "Arena Night". */
  public static final int EVENT_ID = 1;

```

The Javadoc says it all: the JDK's own server, bound to 127.0.0.1 only, one virtual thread per request. A held long poll or open stream parks a virtual thread, which costs about a kilobyte of heap, not a platform thread. `EVENT_ID = 1` is the one event the feed serves.

```java
  private static final Logger LOG = LoggerFactory.getLogger(FeedHttpServer.class);
  private static final Pattern EVENT_PATH =
      Pattern.compile("/v1/events/([0-9]{1,9})/(seat-map|seat-changes|seat-changes/stream)");
  private static final int STOP_DELAY_SECONDS = 0;
  private static final Map<String, String> PAGES =
      Map.of(
          "/", "web/live-seat-map.html",
          "/live-seat-map.js", "web/live-seat-map.js");
```

`EVENT_PATH` matches `/v1/events/<1 to 9 digits>/seat-map`, `/seat-changes` or `/seat-changes/stream`. `STOP_DELAY_SECONDS = 0` stops the server without waiting. `PAGES` is a map from a URL path to a file inside the program's resources: `/` is the page and `/live-seat-map.js` its script.

```java
  private final HttpServer server;
  private final ExecutorService workers;
  private final Parts parts;
  private final Replies replies;
  private final LongPollRoute longPoll;
  private final EventStreamRoute eventStream;
  private final boolean adminEnabled;
  private final String contentSecurityPolicy;

  private FeedHttpServer(HttpServer server, Parts parts, LiveSettings settings, int webSocketPort) {
```

Fields. The constructor is private; `start` is the way in.

```java
    this.server = server;
    // Virtual threads make a held long poll or an open stream cost a parked thread, not a platform
    // thread (Module 4); the connection limit, not the thread count, bounds the work.
    this.workers = Executors.newVirtualThreadPerTaskExecutor();
    this.parts = parts;
    this.replies = new Replies(parts.json(), settings.timing().retry());
    this.longPoll = new LongPollRoute(parts, replies, settings.timing().longPollHold());
    this.eventStream =
        new EventStreamRoute(
            parts, replies, settings.timing().heartbeat(), settings.timing().retry());
    this.adminEnabled = settings.adminEnabled();
    // The page may load only its own script and talk only to this feed and its WebSocket port.
    this.contentSecurityPolicy =
        "default-src 'self'; connect-src 'self' ws://127.0.0.1:" + webSocketPort;
  }
```

`Executors.newVirtualThreadPerTaskExecutor()` makes an executor that starts a new virtual thread for each task. The comment says the connection limit, not the thread count, bounds the work. Then the replies, the two routes and the admin flag are built. The **Content-Security-Policy** is a header the page's response carries: `default-src 'self'; connect-src 'self' ws://127.0.0.1:<port>` tells the browser the page may load only from its own origin and may connect only to its own origin and the WebSocket port.

```java
  /**
   * Starts the server on 127.0.0.1.
   *
   * @param settings the port, backlog and timings
   * @param parts the store, feed, limit, metrics and codec
   * @param webSocketPort the WebSocket server's port, named in the page's security policy
   * @return the running server
   * @throws IOException if the port cannot be bound
   */
  public static FeedHttpServer start(LiveSettings settings, Parts parts, int webSocketPort)
      throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(parts, "parts");
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.network().httpPort()),
            settings.network().backlog());
    FeedHttpServer feedServer = new FeedHttpServer(server, parts, settings, webSocketPort);
    server.setExecutor(feedServer.workers);
    server.createContext("/", feedServer::serve);
    server.start();
    return feedServer;
  }
```

`start` binds the server. `InetAddress.getLoopbackAddress()` is 127.0.0.1, so the server cannot be reached from another machine. The backlog comes from the settings. `server.setExecutor(...)` gives it the virtual-thread executor. `server.createContext("/", feedServer::serve)` registers `serve` for every path that starts with `/`, which is all of them; the routing is done by hand. `server.start()` begins accepting.

```java
  /**
   * The port the server listens on.
   *
   * @return the port
   */
  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(STOP_DELAY_SECONDS);
    workers.shutdownNow();
  }
```

`port()` reports the port actually bound. `close()` stops the server at once and shuts down the executor.

```java
  private void serve(HttpExchange exchange) {
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);
    parts.metrics().recordHandlerThread(true);
    try (exchange) {
      route(exchange, correlationId);
    } catch (IOException clientGone) {
      LOG.debug("request ended early: {}", clientGone.toString());
    } catch (RuntimeException bug) {
      LOG.error("unexpected failure", bug);
    } finally {
      parts.metrics().recordHandlerThread(false);
      MDC.remove("correlationId");
    }
  }
```

`serve` is the entry for every request. It makes a random correlation id (`UUID`), puts it in the MDC so every log line on this thread carries it, and counts the handler thread. `try (exchange)` closes the exchange at the end. The three handlers: an `IOException` means the client went away (debug log); a `RuntimeException` is a bug (error log with the stack trace); `finally` always uncounts the thread and clears the MDC.

```java
  private void route(HttpExchange exchange, String correlationId) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    if (path.startsWith("/admin/")) {
      admin(exchange, path, method, correlationId);
      return;
    }
    if (!"GET".equals(method)) {
      replies.problem(
          exchange, Problem.METHOD_NOT_ALLOWED, method + " is not allowed", correlationId);
      return;
    }
```

`route` reads the path and method. Anything under `/admin/` goes to `admin`. Any method other than GET gets 405.

```java
    Matcher event = EVENT_PATH.matcher(path);
    if (event.matches()) {
      if (Integer.parseInt(event.group(1)) != EVENT_ID) {
        replies.problem(exchange, Problem.NOT_FOUND, "no event " + event.group(1), correlationId);
        return;
      }
      switch (event.group(2)) {
        case "seat-map" ->
            replies.json(
                exchange, HttpStatus.OK, parts.json().snapshot(parts.store().snapshot(), EVENT_ID));
        case "seat-changes" -> longPoll.serve(exchange, correlationId);
        default -> eventStream.serve(exchange, correlationId);
      }
      return;
    }
```

If the path fits `EVENT_PATH`, the event number is checked first (`Integer.parseInt` of group 1; nine digits cannot overflow an `int`). An event other than 1 gets 404. Then the `switch` on the last part picks the seat map (a JSON reply from `store.snapshot()`), the long-poll route or, for anything else, the event stream.

```java
    switch (path) {
      case "/health" ->
          replies.text(exchange, "text/plain", "ok\n".getBytes(StandardCharsets.UTF_8));
      case "/metrics" ->
          replies.text(
              exchange,
              "text/plain; version=0.0.4",
              parts.metrics().render().getBytes(StandardCharsets.UTF_8));
      default -> page(exchange, path, correlationId);
    }
  }
```

Plain paths: `/health` replies `ok`, `/metrics` the Prometheus text with content type `text/plain; version=0.0.4`. Every other path goes to `page`.

```java
  private void page(HttpExchange exchange, String path, String correlationId) throws IOException {
    String resource = PAGES.get(path);
    if (resource == null) {
      replies.problem(exchange, Problem.NOT_FOUND, "nothing at " + path, correlationId);
      return;
    }
    byte[] body;
    try (InputStream in = FeedHttpServer.class.getClassLoader().getResourceAsStream(resource)) {
      body = Objects.requireNonNull(in, resource).readAllBytes();
    }
    exchange.getResponseHeaders().set("Content-Security-Policy", contentSecurityPolicy);
    String type =
        resource.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
    replies.text(exchange, type, body);
  }
```

`page` looks the path up in `PAGES`. No entry gives 404. Otherwise it reads the file from the classpath (`getResourceAsStream`), adds the Content-Security-Policy header, picks the content type by the file name's end, and sends it.

```java
  // The lab's control routes: they exist only when admin.enabled=true, on 127.0.0.1, so the
  // comparison can pause the on-sale, drop every connection and read the heap after a collection.
  private void admin(HttpExchange exchange, String path, String method, String correlationId)
      throws IOException {
    if (!adminEnabled) {
      replies.problem(exchange, Problem.NOT_FOUND, "nothing at " + path, correlationId);
      return;
    }
    if (!"POST".equals(method)) {
      exchange.getResponseHeaders().set("Allow", "POST");
      replies.problem(
          exchange, Problem.METHOD_NOT_ALLOWED, method + " is not allowed", correlationId);
      return;
    }
    String reply =
        switch (path) {
          case "/admin/pause" -> {
            parts.simulator().pause();
            yield "{\"onSale\":\"paused\"}";
          }
          case "/admin/resume" -> {
            parts.simulator().resume();
            yield "{\"onSale\":\"running\"}";
          }
          case "/admin/drop-connections" -> "{\"dropped\":" + parts.limit().dropAll() + "}";
          case "/admin/settle" -> "{\"heapUsedBytes\":" + settledHeapBytes() + "}";
          default -> "";
        };
    if (reply.isEmpty()) {
      replies.problem(exchange, Problem.NOT_FOUND, "nothing at " + path, correlationId);
      return;
    }
    replies.json(exchange, HttpStatus.OK, reply.getBytes(StandardCharsets.UTF_8));
  }
```

`admin` exists for the lab. When `admin.enabled` is false every admin path is a 404, as if it did not exist (`config/serve.properties` sets `false`). When true, only POST is accepted. The `switch` expression builds a JSON reply: `pause`, `resume`, `drop-connections` (ends every open connection, returns how many) and `settle` (a full garbage collection, then the heap in use). An unknown admin path produces an empty reply, which is turned into 404.

```java
  // A full collection first, so the heap number counts live objects, not garbage waiting to go.
  private static long settledHeapBytes() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    memory.gc();
    return memory.getHeapMemoryUsage().getUsed();
  }
}
```

`settledHeapBytes` asks the JVM to collect garbage first, so the number counts live objects only.

### `wire/FeedJson.java`

```java
package io.plainround.seatwave.live.wire;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.plainround.seatwave.live.feed.Catchup;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatChange;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.feed.SeatMapSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

```

**JSON** is a text format for data: objects in braces, lists in brackets, text in quotes. **Jackson** (`ObjectMapper`) converts Java records to JSON and back. See `jackson.md`.

```java
/**
 * Turns feed values into JSON and back. Incoming JSON binds only to the known records of this
 * package, and an unknown field is an error, so nothing a server sends can build an arbitrary type.
 *
 * <p>Thread-safe: Jackson's {@link ObjectMapper} is safe to share once configured.
 */
public final class FeedJson {

  private final ObjectMapper mapper =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
```

One mapper, configured so that unknown fields are an error when reading. The comment explains the safety point: incoming JSON binds only to the records of this package, and extra fields are refused, so nothing a server sends can build an arbitrary type. An `ObjectMapper` is safe to share between threads once configured.

```java
  /**
   * One seat change as a message.
   *
   * @param change the change
   * @param layout the layout that names its seat
   * @return the message
   */
  public MessageView message(SeatChange change, SeatLayout layout) {
    return new MessageView(
        MessageView.SEAT,
        change.position().written(),
        layout.label(change.seatIndex()),
        change.state().wireName(),
        change.changedAt().toString());
  }
```

`message` turns a `SeatChange` into a `MessageView`: the type `seat`, the position written, the seat label (`layout.label(change.seatIndex())`), the state's JSON name, and the time as ISO-8601 text (`Instant.toString()`).

```java
  /**
   * A reload instruction as a message.
   *
   * @param current the feed's current position
   * @return the message
   */
  public MessageView reload(FeedPosition current) {
    return new MessageView(MessageView.RELOAD, current.written(), null, null, null);
  }
```

`reload` makes a message of type `reload` with only the position; the other three are `null`, and `@JsonInclude(NON_NULL)` on `MessageView` drops `null` fields.

```java
  /**
   * The reply to a long poll.
   *
   * @param catchup what the feed had after the client's position
   * @param layout the layout that names the seats
   * @param asked the position the client asked from
   * @return the reply as JSON bytes
   */
  public byte[] longPoll(Catchup catchup, SeatLayout layout, FeedPosition asked) {
    LongPollView view =
        switch (catchup) {
          case Catchup.Changes changes ->
              new LongPollView(
                  changes.changes().stream().map(change -> message(change, layout)).toList(),
                  (changes.changes().isEmpty() ? asked : changes.position()).written(),
                  false);
          case Catchup.ReloadRequired reload ->
              new LongPollView(List.of(), reload.position().written(), true);
        };
    return bytes(view);
  }
```

`longPoll` builds the reply. The `switch` expression over the sealed `Catchup` returns a `LongPollView`. For changes it maps each to a message with a stream `.map(...).toList()` and sets `next` to the position after the last change, or, when there are none, the position the client asked from (`(cond ? asked : changes.position()).written()`); `reload` is false. For a reload it returns an empty list, the feed's current position and `reload` true. The `Catchup.Changes changes ->` syntax is a **type pattern**: it tests the case and names the value.

```java
  /**
   * The seat map.
   *
   * @param snapshot the map
   * @param eventId its event
   * @return the map as JSON bytes
   */
  public byte[] snapshot(SeatMapSnapshot snapshot, int eventId) {
    return bytes(
        new SnapshotView(
            eventId,
            snapshot.position().written(),
            snapshot.layout().sections(),
            snapshot.layout().seatsPerSection(),
            snapshot.states()));
  }
```

`snapshot` builds the seat map reply: event id, the position, the layout's two numbers and the string of states.

```java
  /**
   * Any of this package's records as JSON.
   *
   * @param value the record
   * @return UTF-8 JSON bytes
   */
  public byte[] bytes(Object value) {
    try {
      return mapper.writeValueAsBytes(Objects.requireNonNull(value, "value"));
    } catch (IOException notSerializable) {
      // Only this package's records are written, so this is a programming error.
      throw new UncheckedIOException(
          "cannot write " + value.getClass().getSimpleName(), notSerializable);
    }
  }
```

`bytes` writes any of this package's records as UTF-8 JSON. `mapper.writeValueAsBytes` can throw `IOException` in general; for these records it cannot, so the code wraps it in an unchecked `UncheckedIOException`, saying that a failure is a programming error.

```java
  /**
   * Reads JSON into one of this package's records.
   *
   * @param json the bytes
   * @param type the record type expected
   * @param <T> the type
   * @return the record
   * @throws IOException if the JSON is malformed or does not match the record exactly
   */
  public <T> T read(byte[] json, Class<T> type) throws IOException {
    return mapper.readValue(Objects.requireNonNull(json, "json"), type);
  }
}
```

`read` goes the other way: bytes in, a record of the requested class out. The generic method `<T> T read(byte[] json, Class<T> type)` returns whatever `type` names.

### `wire/LongPollView.java`, `wire/MessageView.java`, `wire/SnapshotView.java`

```java
package io.plainround.seatwave.live.wire;

import java.util.List;

/**
 * The reply to one long poll.
 *
 * @param changes the changes after the position asked for, in order; empty when the hold ended with
 *     no news or when {@code reload} is true
 * @param next the position to ask from next time
 * @param reload true when the client must load the seat map again before asking
 */
public record LongPollView(List<MessageView> changes, String next, boolean reload) {

  /** Copies the list; a missing list is read as empty. */
  public LongPollView {
    changes = changes == null ? List.of() : List.copyOf(changes);
  }
}
```

The long-poll reply: the changes, the position to ask from next time, and whether to reload. A missing list reads as empty, so a reply without `changes` is still valid.

```java
package io.plainround.seatwave.live.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One feed message as JSON, the same shape for all three methods: a seat change ({@code type} is
 * {@code seat}) or an instruction to reload the seat map ({@code type} is {@code reload}, and only
 * {@code position} is present).
 *
 * @param type {@code seat} or {@code reload}
 * @param position the feed position written as {@code <stream>.<sequence>}
 * @param seat the seat's label, such as {@code A-7}; absent in a reload
 * @param state {@code available}, {@code held} or {@code sold}; absent in a reload
 * @param changedAt when the store made the change, ISO-8601 in UTC; absent in a reload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageView(
    String type, String position, String seat, String state, String changedAt) {

  /** The type of a seat change. */
  public static final String SEAT = "seat";

  /** The type of a reload instruction. */
  public static final String RELOAD = "reload";
}
```

One message. `@JsonInclude(JsonInclude.Include.NON_NULL)` is an **annotation**: a marker that a library reads. It says "leave out fields that are `null`". The two constants are the only values `type` takes. The order of the record's components is the order of the keys in the JSON.

```java
package io.plainround.seatwave.live.wire;

/**
 * The whole seat map as JSON.
 *
 * @param eventId the event
 * @param position the feed position of the last change the map includes
 * @param sections how many sections, lettered from A
 * @param seatsPerSection how many seats each section has
 * @param states one letter per seat ({@code a} available, {@code h} held, {@code s} sold), section
 *     by section, then by seat number
 */
public record SnapshotView(
    int eventId, String position, int sections, int seatsPerSection, String states) {}
```

The seat map reply.

## Try it yourself

I ran these in a copy of the repository with the node on ports 18190 and 18191 (other work was using 8090 and 8091). Replace the port if you use `config/serve.properties` as it is. Positions, times and ids differ on every run. Start the node in one terminal: `./gradlew -q serve --args='config/serve.properties'`; it prints one line like `SeatWave live feed on http://127.0.0.1:8090 and ws://127.0.0.1:8091 for 600 s; on-sale at 20 actions per second; at most 5000 connections`.

**1. Health and the seat map.**

```bash
curl -s http://127.0.0.1:18190/health
curl -s http://127.0.0.1:18190/v1/events/1/seat-map | cut -c1-110
```

Output:

```text
ok
{"eventId":1,"position":"4d4f4a94.229","sections":20,"seatsPerSection":1000,"states":"aaaaaaaaaaaaaaaaaaaaaaaa
```

**2. A server-sent events stream.** `-N` turns off curl's own buffering and `--max-time 2` ends it after two seconds:

```bash
curl -s -N --max-time 2 "http://127.0.0.1:18190/v1/events/1/seat-changes/stream?after=4d4f4a94.230" | head -8
```

```text
retry: 1000

id: 4d4f4a94.231
event: seat
data: {"type":"seat","position":"4d4f4a94.231","seat":"E-741","state":"held","changedAt":"2026-11-11T03:00:27.484459Z"}

id: 4d4f4a94.232
event: seat
```

**3. The header wins over the query.** The query says `.5` but the header says `.300`, and the stream starts at 301:

```bash
curl -s -N --max-time 1 -H 'Last-Event-ID: 4d4f4a94.300' "http://127.0.0.1:18190/v1/events/1/seat-changes/stream?after=4d4f4a94.5" | sed -n 1,5p
```

```text
retry: 1000

id: 4d4f4a94.301
event: seat
data: {"type":"seat","position":"4d4f4a94.301","seat":"A-802","state":"held","changedAt":"2026-11-11T03:00:30.989824Z"}
```

**4. A position from another stream means reload.** The long-poll reply and the event stream:

```bash
curl -s --max-time 3 "http://127.0.0.1:18190/v1/events/1/seat-changes?after=deadbeef.5"
curl -s -N --max-time 1 "http://127.0.0.1:18190/v1/events/1/seat-changes/stream?after=deadbeef.5"
```

```text
{"changes":[],"next":"4d4f4a94.470","reload":true}
retry: 1000

event: reload
data: {"type":"reload","position":"4d4f4a94.470"}

```

**5. Strict queries and the error shape.**

```bash
curl -s -i "http://127.0.0.1:18190/v1/events/1/seat-changes?after=7&x=1" | grep -v Date
```

```text
HTTP/1.1 400 Bad Request
Content-type: application/problem+json
Content-length: 240
Cache-control: no-store

{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the feed's contract","status":400,"detail":"parameter x is not part of this request","instance":"urn:uuid:b871d681-da65-4050-92b1-fd938c9498f3"}
```

The JDK server writes header names as `Content-type`; HTTP header names are not case sensitive. With `after=7` alone the detail is `after must be <8 hex digits>.<sequence>, as the feed sent it`.

**6. Other refusals.**

```bash
curl -s -i -X POST http://127.0.0.1:18190/health | head -1
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18190/admin/pause
```

```text
HTTP/1.1 405 Method Not Allowed
404
```

The second is 404 because `admin.enabled=false` makes the admin routes not exist.

**7. The Content-Security-Policy header of the page.**

```bash
curl -s -i http://127.0.0.1:18190/ | head -4
```

```text
HTTP/1.1 200 OK
Date: Wed, 11 Nov 2026 03:00:39 GMT
Content-security-policy: default-src 'self'; connect-src 'self' ws://127.0.0.1:18191
Content-type: text/html; charset=utf-8
```

Stop the node with Ctrl+C (or let `serve.seconds` pass).

## Mistakes people make

- **Reading a settings file with silent defaults.** A typo in a key then runs the program with a value nobody chose. Here every key is required and every problem is listed.
- **Throwing on a typo in a query string.** An unknown parameter should be a 400, not a crash and not a quiet pass. `Query` refuses unknown and repeated names.
- **Forgetting to restore the interrupt flag.** Catching `InterruptedException` and carrying on hides the interrupt from the code above. Both routes call `Thread.currentThread().interrupt()`.
- **Writing a raw line break inside an event's `data:` line.** It would end the event early. Jackson writes compact JSON with no line break.
- **Letting a proxy or a browser cache live data.** Every reply carries `Cache-Control: no-store`.
- **Serving the feed on every network interface.** The server binds to the loopback address only.

## Check yourself

1. **Which header does the event-stream route prefer for the resume position, and why?** `Last-Event-ID`. A browser reconnects to the same URL with the old `after`, but sends the newest id it saw in the header.
2. **What happens if `feed.maxConnections` is reached?** The route answers 503 with `Retry-After`, counts a refusal, and the extra connection fails alone.
3. **Why does `LiveSettings.from` read every key before it checks the problems?** So that one run reports every mistake, not only the first.
4. **What does `STREAMING = 0` do in `sendResponseHeaders`?** It makes the JDK server use chunked transfer coding, so the reply has no fixed length and can go on for as long as the stream lasts.
5. **What is the difference between the 150-byte event and the 113-byte JSON inside it?** The 37 extra bytes are the `id:`, `event:` and `data:` line labels, the line breaks and the blank line that ends the event.

## Further reading

- WHATWG HTML Living Standard, server-sent events: https://html.spec.whatwg.org/multipage/server-sent-events.html
- RFC 9110, HTTP Semantics: https://www.rfc-editor.org/rfc/rfc9110
- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457
- Java SE 25 API, `com.sun.net.httpserver` (module `jdk.httpserver`): https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- JEP 456, Unnamed Variables and Patterns: https://openjdk.org/jeps/456
- Jackson databind 2.23: https://github.com/FasterXML/jackson-databind
