# Walkthrough of the root, `bench` and `config` tests and the test resources

## What it is

This file covers every test source file that the other two test walkthroughs do not: the three helpers in the root test package, the two test classes of the `bench` package, the one test class of the `config` package, and the two files under `src/test/resources`. The `feed` package is in `tests-feed-walkthrough.md`; the `http`, `websocket` and `client` packages are in `tests-transports-walkthrough.md`. All Java paths are under `src/test/java/io/plainround/seatwave/live/` unless they start with `src/test/resources/`.

| File | Kind | Results | What it does |
| --- | --- | --- | --- |
| `MutableClock.java` | helper | 0 | A clock that moves only when a test moves it. |
| `Raw.java` | helper | 0 | A raw TCP client for tests that must see the exact bytes a server sends. |
| `TestSettings.java` | helper | 0 | Small, fast settings for tests: ports chosen by the system, a small map, short timings. |
| `bench/BenchReportsTest.java` | test | 3 | The arithmetic of the reports: percentiles, the lab's rates, the CSV and the median. |
| `bench/MeasuringToolsTest.java` | test | 5 | Each measuring tool runs at small scale, start to end, and prints the right shape of report. |
| `config/LiveSettingsTest.java` | test | 3 | Every shipped settings file is valid; every problem of a bad file is listed in one run. |
| `src/test/resources/junit-platform.properties` | resource | 0 | A 60-second limit for every test. |
| `src/test/resources/logback-test.xml` | resource | 0 | The tests' logging: warnings and errors only. |

## Why this repository uses it

The helpers make every other test short and strict. `MutableClock` makes time a value the test controls, so expiry is tested without waiting. `TestSettings` gives one place that says what a "small" node is. `Raw` lets a test read the exact bytes a server writes. The `bench` tests prove that the measuring tools, which the README calls the point of the module, run from start to end and report correct numbers, in seconds instead of the four minutes of the real comparison. The `config` test proves that every settings file in `config/` is valid and that a bad file reports every problem at once. The two resource files keep the build safe (no hanging test) and readable (no log noise).

## Where it appears

The tests run in `./gradlew test` and in `check`. The helpers are used by tests of several packages. Nothing here is shipped: it is all in the test source set.

## How it works underneath

**How a test of a concurrent, networked program stays deterministic** (this file's share of the answer).
- `MutableClock` is the **fake clock** technique: code that reads the time from a `java.time.Clock` it is given can be run with a clock the test moves by hand.
- `TestSettings` gives port 0 (any free port), a small seat map and short timings, so two tests never fight for a port and nothing needs a long wait.
- `BenchReportsTest` and `LiveSettingsTest` are plain: no threads, no network, no clock. Same input, same output.
- `MeasuringToolsTest` is the opposite: it runs real nodes and real clients for a few seconds each. It therefore asserts only on the **shape** of each report (which line starts with which words, which count equals the number of clients, "map equals store: yes"), never on a delay or a rate, because those change on every run.

**Which tests rely on real time.** All five tests of `MeasuringToolsTest` do, and they are the slowest tests of the build: the comparison test takes about 6.6 seconds (it plays a 1-second warm-up and a 1-second measured phase for each of three methods), the slow-consumer test about 3.1 seconds (a 2-second run), the resume test about 3.1 seconds, the wire-bytes test about 3.4 seconds, and the test that starts a child JVM about 0.5 seconds; the class takes 16.7 of the build's 25 seconds. `BenchReportsTest` and `LiveSettingsTest` use no time.

**One test that uses fixed ports.** `startsTheFeedInAProcessOfItsOwnAndReadsItsMemory` reads `config/compare-server.properties`, which names ports 8092 and 8093, and starts a child process that binds them. If another program already uses those ports, the test fails. This is the one place in the suite that does not use port 0.

## Walkthrough of this repository's use

### `MutableClock.java`

```java
package io.plainround.seatwave.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand, so expiries happen exactly when the test says. */
public final class MutableClock extends Clock {
```

- `public final class MutableClock extends Clock` makes a class that **is a** `java.time.Clock`. `Clock` is the JDK's source of "now". Anything that accepts a `Clock` accepts this one. `final` forbids subclasses.
- The comment (line 9) says what it is for: a clock the test moves by hand, so expiries happen exactly when the test says. `public` because tests in several packages use it.

```java
  private volatile Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  public void advance(Duration step) {
    now = now.plus(step);
  }
```

- `private volatile Instant now;` is the current time. `volatile` means every thread sees the latest value. The node's threads read the clock while the test thread moves it, so without `volatile` a thread could keep seeing an old time.
- The constructor stores the start time. `advance(Duration step)` sets `now` to `now.plus(step)`. `Instant` is immutable, so `plus` returns a new one. The two steps (read, then write) are not atomic, which is fine because only the test thread calls `advance`.

```java
  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
```

- `@Override public Instant instant() { return now; }` is the one method that matters: it answers "what time is it?" with the stored time.
- `getZone()` returns UTC; `withZone(ZoneId zone)` returns `this`. `Clock` requires both; this clock has no time zone to change. They exist so the class satisfies `Clock`'s contract.

### `Raw.java`

```java
package io.plainround.seatwave.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** A raw TCP client for tests that must see the exact bytes a server sends. */
public final class Raw implements AutoCloseable {

  private final Socket socket;

  private Raw(Socket socket) {
    this.socket = socket;
  }

  public static Raw connect(int port) throws IOException {
    Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
    socket.setSoTimeout(5_000);
    return new Raw(socket);
  }
```

- `Raw implements AutoCloseable` so a test can open it in a `try (...)` and have it closed (`junit.md`).
- The imports are the JDK's socket and stream classes: `Socket` is a TCP connection; `InetAddress.getLoopbackAddress()` is 127.0.0.1; `SocketTimeoutException` is what a read throws when no byte arrives in time.
- The constructor is `private`; tests call the static factory `connect(int port)`. It opens a socket to the loopback address and sets `setSoTimeout(5_000)`: a read that waits more than five seconds throws instead of waiting for ever. That is the safety net for every test that reads bytes.

```java
  public void send(String text) throws IOException {
    socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  public void sendBytes(byte[] bytes) throws IOException {
    socket.getOutputStream().write(bytes);
    socket.getOutputStream().flush();
  }
```

- `send(String text)` writes the text as US-ASCII bytes and **flushes** (pushes the bytes onto the network now instead of keeping them in a buffer). HTTP request heads are ASCII.
- `sendBytes(byte[])` writes any bytes, for a WebSocket frame.

```java
  /** Reads until the text appears or the stream ends; returns everything read. */
  public String readUntil(String text) throws IOException {
    ByteArrayOutputStream read = new ByteArrayOutputStream();
    InputStream in = socket.getInputStream();
    try {
      while (!read.toString(StandardCharsets.UTF_8).contains(text)) {
        int value = in.read();
        if (value < 0) {
          break;
        }
        read.write(value);
      }
    } catch (SocketTimeoutException timedOut) {
      read.writeBytes(("<timeout>").getBytes(StandardCharsets.US_ASCII));
    }
    return read.toString(StandardCharsets.UTF_8);
  }
```

- `readUntil(String text)` reads one byte at a time into a `ByteArrayOutputStream` and stops when the bytes read so far, decoded as UTF-8, contain `text`. It returns everything read, so the test can assert on more than the marker.
- `in.read()` returns -1 when the server closed the connection; the loop then stops (`break`).
- `catch (SocketTimeoutException timedOut)` turns a five-second silence into the extra text `<timeout>` at the end of what was read. The test's assertion then fails with the real data in the message, not with a hang or a stack trace.
- Reading one byte at a time is slow, but the messages are small and the code is simple.

```java
  public InputStream input() throws IOException {
    return socket.getInputStream();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
```

- `input()` hands out the socket's input stream for a test that wants to read a fixed number of bytes (`readNBytes(4)` in `WebSocketServerTest`). `close()` closes the socket.

### `TestSettings.java`

```java
package io.plainround.seatwave.live;

import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.SeatLayout;
import java.time.Duration;

/** Small, fast settings for tests: ports chosen by the system, a small map, short timings. */
public final class TestSettings {

  public static final int SECTIONS = 2;
  public static final int SEATS_PER_SECTION = 50;

  private TestSettings() {}

  public static LiveSettings small() {
    return with(1_000, 64, Duration.ofSeconds(2));
  }
```

- `LiveSettings` is the record of everything a node needs (`LiveSettings` in the main code). It has no defaults in the main code, so a test must give every value; this helper gives them in one place.
- `SECTIONS = 2` and `SEATS_PER_SECTION = 50` make a map of 100 seats: small enough to print, big enough to see variety.
- `private TestSettings() {}` hides the constructor: the class only holds static methods.
- `small()` is the setting almost every test uses: a replay buffer of 1,000 changes, a client queue of 64 and a heartbeat of 2 seconds.

```java
  public static LiveSettings with(int replayCapacity, int clientQueueCapacity, Duration heartbeat) {
    return new LiveSettings(
        new LiveSettings.Network(0, 0, 128, "http://127.0.0.1:8090"),
        new LiveSettings.Limits(200, replayCapacity, clientQueueCapacity, 32),
        new LiveSettings.Timing(
            heartbeat, Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(1)),
        new SeatLayout(SECTIONS, SEATS_PER_SECTION),
        new LiveSettings.OnSale(Duration.ofMillis(300), 200, 30, 8),
        true,
        Duration.ofSeconds(1));
  }
```

`with(replayCapacity, clientQueueCapacity, heartbeat)` fills the seven parts of `LiveSettings`:
- `new LiveSettings.Network(0, 0, 128, "http://127.0.0.1:8090")`: HTTP port 0 and WebSocket port 0 (the system picks free ones), an accept backlog of 128 connections, and the one origin allowed to open a WebSocket.
- `new LiveSettings.Limits(200, replayCapacity, clientQueueCapacity, 32)`: at most 200 open connections, the replay size, the per-client queue size, and at most 32 changes per batch.
- `new LiveSettings.Timing(heartbeat, Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(1))`: the heartbeat; a write timeout of 500 ms; a long-poll hold of 2 seconds; and a reconnect advice (`retry`) of 1 second.
- `new SeatLayout(SECTIONS, SEATS_PER_SECTION)`: the map.
- `new LiveSettings.OnSale(Duration.ofMillis(300), 200, 30, 8)`: a hold lasts 300 ms, 200 actions per second, 30 percent of actions confirm, seed 8.
- `true`: the admin routes are on, so the tests can call them.
- `Duration.ofSeconds(1)`: the serve time (used by `ServeCli`, not by the tests).

```java
  /** A fast on-sale over Module 1's 20,000 seats, with a small client queue and write timeout. */
  public static LiveSettings busy(int replayCapacity) {
    LiveSettings base = small();
    return new LiveSettings(
        base.network(),
        new LiveSettings.Limits(200, replayCapacity, 64, 32),
        base.timing(),
        new SeatLayout(20, 1_000),
        new LiveSettings.OnSale(Duration.ofSeconds(2), 2_000, 30, 8),
        true,
        base.serveTime());
  }
```

`busy(replayCapacity)` keeps the network and timing of `small()` and changes the rest: a client queue of 64 and 32 per batch, the **full arena** of 20 sections of 1,000 seats (the comment says Module 1's 20,000 seats), and a fast on-sale: a hold lasts 2 seconds and 2,000 actions per second. The slow-consumer and resume tests use it, because they need many changes in a short time to overflow a queue or a buffer.

```java
  public static LiveSettings withConnectionLimit(int maxConnections) {
    LiveSettings base = small();
    return new LiveSettings(
        base.network(),
        new LiveSettings.Limits(maxConnections, 1_000, 64, 32),
        base.timing(),
        base.layout(),
        base.onSale(),
        base.adminEnabled(),
        base.serveTime());
  }
```

`withConnectionLimit(maxConnections)` changes only the first limit, to a small number. The connection-limit tests of the `http` and `websocket` packages use it with 1.

### `bench/BenchReportsTest.java`

```java
package io.plainround.seatwave.live.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchReportsTest {

  @TempDir Path folder;
```

- `import static ...within;` brings in the tolerance helper for `isCloseTo`.
- `Files`, `Path` and `StandardCharsets` read and write files; `List` is the list type.
- `@TempDir Path folder;` is a new empty folder for each test (`junit.md`).

```java
  @Test
  void readsPercentilesByNearestRank() {
    long[] delays = new long[100];
    for (int index = 0; index < delays.length; index++) {
      delays[index] = (index + 1) * 1_000L;
    }

    LatencySummary summary = LatencySummary.ofMicros(delays);

    assertThat(summary.p50Millis()).isEqualTo(50.0);
    assertThat(summary.p99Millis()).isEqualTo(99.0);
    assertThat(summary.maxMillis()).isEqualTo(100.0);
    assertThat(LatencySummary.ofMicros(new long[0]).count()).isZero();
  }
```

- `delays` holds 100 values: 1,000, 2,000, up to 100,000 **microseconds** (millionths of a second), which is 1 to 100 ms. The loop fills them: `(index + 1) * 1_000L` (the `L` makes it a `long`).
- `LatencySummary.ofMicros(delays)` sorts them and reads percentiles by **nearest rank**: the p50 is the value at rank ceil(0.50 × 100) = 50, which is 50,000 µs = 50.0 ms; the p99 is rank 99, 99.0 ms; the maximum is 100.0 ms. The test checks all three.
- An empty array gives a summary with count 0.

```java
  @Test
  void computesTheRatesOfLabOne() {
    PollingRates rates =
        new PollingRates(
            200_000, Duration.ofSeconds(5), Duration.ofMillis(80), Duration.ofSeconds(25));

    assertThat(rates.shortPollingRequestsPerSecond()).isEqualTo(40_000.0);
    assertThat(rates.emptyShare(1 / 60.0)).isCloseTo(0.920, within(0.001));
    assertThat(rates.longPollingRequestsPerSecond(3_000)).isCloseTo(2_489_627, within(1.0));
    assertThat(rates.pushMessagesPerSecond(3_000)).isEqualTo(600_000_000.0);
  }
```

This tests the arithmetic of lab 1 (`pollingRates`): 200,000 fans, short polling every 5 seconds, a round trip of 80 ms, a long poll held up to 25 seconds.
- Short polling makes 200,000 / 5 = 40,000 requests per second, whatever happens.
- With one change every 60 seconds (`1 / 60.0` changes per second), the share of polls that find nothing is about 0.920 (the chance that no change happens in 5 seconds is e to the power of minus one twelfth). The check allows 0.001 either way.
- With 3,000 changes per second, long polling needs about 2,489,627 requests per second (every held request ends at the next change and is replaced), and push sends 600,000,000 messages per second (3,000 changes times 200,000 connections). These match the README's table of the lab.

```java
  @Test
  void writesOneCsvLinePerRunAndTakesTheMedianOfEachColumn() throws IOException {
    List<MethodResult> runs = List.of(result(1, 3.0), result(2, 1.0), result(3, 2.0));

    ComparisonReport.writeCsv(runs, folder.resolve("out.csv"));
    MethodResult median = ComparisonReport.median(runs);
    List<String> table = ComparisonReport.table(runs);

    assertThat(Files.readAllLines(folder.resolve("out.csv"), StandardCharsets.UTF_8)).hasSize(4);
    assertThat(median.delay().p99Millis()).isEqualTo(2.0);
    assertThat(table).hasSize(6).last().asString().startsWith("sse          med");
    assertThat(ComparisonReport.median(runs.subList(0, 2)).delay().p99Millis()).isEqualTo(2.0);
  }
```

- Three results for the event-stream method with `p99` values 3.0, 1.0 and 2.0 (built by the helper below).
- `ComparisonReport.writeCsv(runs, ...)` writes a CSV file: a header and one line per run, so 4 lines (`hasSize(4)`).
- `ComparisonReport.median(runs)` takes the median of each column. The median of the p99 values 3, 1 and 2 is 2.0.
- `ComparisonReport.table(runs)` gives the printed table: a header, three run lines, the line `median of the runs:` and a median line, so 6 lines; `.last().asString().startsWith("sse          med")` checks the last is the event-stream median.
- With only two runs (`runs.subList(0, 2)`) the median of 3.0 and 1.0 is 2.0, the average of the two middle values.

```java
  private static MethodResult result(int run, double p99) {
    return new MethodResult(
        DeliveryMethod.SERVER_SENT_EVENTS,
        run,
        10,
        10,
        new LatencySummary(100, 1.0, p99, p99),
        new MethodResult.Load(50, 500, 0, 150),
        new MethodResult.ServerCost(0.1, 10, 20_000, -1, 20, 10, 40, 10_240),
        new MethodResult.Reconnect(10, 100, 10, 10, 0),
        new MethodResult.Correctness(0, 0, 10));
  }
```

`result(run, p99)` builds a `MethodResult` of made-up numbers: 10 clients started and 10 connected; a delay summary of 100 samples with the given p99; a load of 50 changes and 500 deliveries per second; a server cost of 0.1 cores, 10 µs per delivery, 20,000 heap bytes per connection, -1 resident bytes (the sentinel for "server shares the process"), 20 platform threads, 10 virtual threads, 40 open descriptors of 10,240 allowed; a reconnect record; and a correctness record of 0 gaps, 0 duplicates, 10 correct maps. Because the numbers are made up, the test checks only the report code, not any measuring.

### `bench/MeasuringToolsTest.java`

```java
package io.plainround.seatwave.live.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeasuringToolsTest {

  @TempDir Path folder;
```

- The imports bring in the settings classes and `Path`, `Duration`, `List`.
- `@TempDir Path folder;` is where the comparison writes its CSV and logs.

```java
  @Test
  void runsTheComparisonForEveryMethodAndEveryClientEndsWithTheStoresMap() throws Exception {
    CompareSettings settings =
        new CompareSettings(
            Path.of("config/compare-server.properties"),
            List.of(DeliveryMethod.values()),
            10,
            1,
            new CompareSettings.Phases(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(10)),
            new CompareSettings.Fleet(
                200,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(6),
                Duration.ofMillis(20),
                Duration.ofMillis(200)),
            false,
            256,
            folder);

    List<String> lines = ComparisonCli.run(settings, TestSettings.small());

    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("long-polling med    10"));
    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("sse          med    10"));
    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("websocket    med    10"));
    assertThat(
            lines.stream()
                .filter(line -> line.contains(" med "))
                .map(line -> line.substring(line.length() - 7).strip()))
        .containsOnly("10");
    assertThat(folder.resolve("live-feed-comparison.csv")).exists();
  }
```

This is the real comparison at small scale, for all three methods.
- `new CompareSettings(...)` builds the settings by hand: the node's settings file `config/compare-server.properties`; all three methods (`List.of(DeliveryMethod.values())`); 10 clients; 1 run; phases of a 1-second warm-up, a 1-second measured phase and a 10-second ceiling for settling; a fleet that starts 200 clients per second with a 2-second connect timeout, a 5-second request timeout, 6 seconds of silence before a drop, and a backoff of 20 to 200 ms; `false` for "run the node in a child process" (so the node runs in this JVM, which is also how JaCoCo can see it); a child heap of 256 MB (unused here); and the temporary folder for output.
- `ComparisonCli.run(settings, TestSettings.small())` runs the whole comparison: for each method it starts a node, connects 10 clients, warms up, measures, drops every connection, and compares each client's map with the store's. It returns the report as a list of lines.
- The first three checks find the median line of each method: `anySatisfy(line -> assertThat(line).startsWith("long-polling med    10"))` and the same for `sse` and `websocket`. The `10` is the number of connected clients.
- The long fourth check takes the lines that contain ` med `, cuts the last seven characters of each (the last column, the number of clients whose map equals the store's), trims them and demands that every one is `"10"`: for every method, all 10 clients ended with the store's map.
- `assertThat(folder.resolve("live-feed-comparison.csv")).exists();` the CSV file was written.

```java
  @Test
  void startsTheFeedInAProcessOfItsOwnAndReadsItsMemory() throws Exception {
    Path file = Path.of("config/compare-server.properties");
    LiveSettings settings =
        LiveSettings.from(new SettingsReader(SettingsReader.load(file)))
            .discardError()
            .orElseThrow();

    try (ChildProcessServer server =
        ChildProcessServer.start(file, settings, 128, folder.resolve("server.log"))) {
      assertThat(server.httpPort()).isEqualTo(8092);
      assertThat(server.webSocketPort()).isEqualTo(8093);
      assertThat(ProcessMemory.residentBytes(server.processId().orElseThrow()).orElseThrow())
          .isPositive();
    }
  }
```

- `SettingsReader.load(file)` reads a properties file; `LiveSettings.from(new SettingsReader(...))` turns it into settings, and `.discardError().orElseThrow()` fails the test with an exception if the file has a problem.
- `ChildProcessServer.start(file, settings, 128, folder.resolve("server.log"))` starts a **separate Java process** with a 128 MB heap that runs `ServeCli` with the settings file, writes its error output to `server.log`, and waits until the child prints that it is ready. The `try (...)` stops the child at the end.
- The checks: the ports are 8092 and 8093, as the file says; and `ProcessMemory.residentBytes(pid)` (the child's resident memory, read with the `ps` command) is positive. `orElseThrow()` fails if `ps` could not tell. This test proves the child-process path of the real comparison, which the first test skips. It needs ports 8092 and 8093 and a Unix-like system with `ps`.

```java
  @Test
  void showsTheSlowConsumersQueueStoppingAtItsBound() throws Exception {
    List<String> lines =
        new SlowConsumer(TestSettings.busy(10_000), 3, Duration.ofSeconds(2)).run();

    assertThat(lines).contains("deepest client queue: 64 (bound 64)");
    assertThat(lines)
        .anySatisfy(line ->
            assertThat(line)
                .startsWith("queue overflows (client told to reload): ")
                .doesNotEndWith(": 0"));
    assertThat(lines).anySatisfy(line -> assertThat(line).endsWith("3 of 3 match the store"));
  }
```

- `new SlowConsumer(TestSettings.busy(10_000), 3, Duration.ofSeconds(2)).run()` runs the slow-consumer experiment: a node with the fast on-sale and a client queue of 64, 3 normal clients, two slow clients that stop reading, and an on-sale for 2 seconds.
- `contains("deepest client queue: 64 (bound 64)")`: the deepest queue reached exactly its bound, no more.
- The line starting `queue overflows (client told to reload): ` must not end in `: 0`: the slow clients overflowed their queues and were told to reload.
- A line ends with `3 of 3 match the store`: the three normal clients were not disturbed and ended with the store's map.

```java
  @Test
  void resumesAnEventStreamWithoutAGapAndReloadsAfterALongAbsence() throws Exception {
    List<String> lines = new SseResume(TestSettings.busy(2_000), 3_000).run();

    assertThat(lines)
        .anySatisfy(line ->
            assertThat(line)
                .startsWith("scenario 1")
                .contains("duplicates 0, gaps 0, reloads 0")
                .endsWith("map equals store: yes"));
    assertThat(lines)
        .anySatisfy(line ->
            assertThat(line)
                .startsWith("scenario 2")
                .contains("gaps 0, reloads 1")
                .endsWith("map equals store: yes"));
  }
```

- `new SseResume(TestSettings.busy(2_000), 3_000).run()` runs the resume lab: a replay buffer of 2,000 changes, and 3,000 changes made while the client is away in the second scenario.
- Scenario 1 (a short drop, inside the buffer): its line says `duplicates 0, gaps 0, reloads 0` and ends `map equals store: yes`.
- Scenario 2 (away for longer than the buffer holds): `gaps 0, reloads 1` and `map equals store: yes`. The client was told to reload exactly once and ended correct.

```java
  @Test
  void countsTheBytesOnTheWireOfEveryMethod() throws Exception {
    List<String> lines = new WireBytesProbe(TestSettings.small(), 5).measure();

    assertThat(lines).hasSize(4);
    assertThat(lines.get(1)).startsWith("long-polling");
    assertThat(lines.get(3)).startsWith("websocket");
    assertThat(MachineLine.describe()).startsWith("Java 25");
  }
```

- `new WireBytesProbe(TestSettings.small(), 5).measure()` measures the bytes on the wire for 5 changes with one client of each method. It returns a header line and three method lines, so `hasSize(4)`. Line 1 starts `long-polling` and line 3 starts `websocket`.
- `MachineLine.describe()` is the first line of every tool's output ("Java 25.0.2+10-LTS on Mac OS X aarch64, ..."). The test checks it starts with `Java 25`.

### `config/LiveSettingsTest.java`

```java
package io.plainround.seatwave.live.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LiveSettingsTest {
```

- `Properties` is the JDK's class for `key=value` settings files. `Result` is the success-or-error type (`result4j.md`). `IOException` is what reading a file can throw.

```java
  @Test
  void readsEveryShippedSettingsFile() throws IOException {
    for (String file :
        List.of("serve", "compare-server", "sse-resume", "slow-consumer", "wire-bytes")) {
      Result<LiveSettings, List<String>> settings =
          LiveSettings.from(
              new SettingsReader(SettingsReader.load(Path.of("config", file + ".properties"))));

      assertThat(settings.discardError()).as(file).isPresent();
    }
  }
```

- The loop takes five of the seven files under `config/`: `serve`, `compare-server`, `sse-resume`, `slow-consumer` and `wire-bytes`. For each it reads the file (`Path.of("config", file + ".properties")`, relative to the project folder, which is the test's working directory) and builds `LiveSettings`.
- `assertThat(settings.discardError()).as(file).isPresent();` demands that building succeeded. `.as(file)` names the file in a failure message. The other two files are checked in the third test.

```java
  @Test
  void listsEveryMissingAndInvalidKeyInOneRun() throws IOException {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.remove("feed.replayCapacity");
    properties.setProperty("http.port", "80");
    properties.setProperty("websocket.allowedOrigin", "https://evil.example");
    properties.setProperty("admin.enabled", "yes");
    properties.setProperty("feed.maxBatch", "many");

    Result<LiveSettings, List<String>> settings = LiveSettings.from(new SettingsReader(properties));

    assertThat(problemsOf(settings))
        .containsExactlyInAnyOrder(
            "http.port must be from 1024 to 65535, but was 80",
            "websocket.allowedOrigin must be http://127.0.0.1:<port>, but was https://evil.example",
            "feed.replayCapacity is missing",
            "feed.maxBatch must be a whole number, but was many",
            "admin.enabled must be true or false, but was yes");
  }
```

- The test loads the valid `serve.properties` into a `Properties` object and spoils it in four ways: it **removes** `feed.replayCapacity`; sets `http.port` to 80 (below 1,024, a privileged port); sets the allowed origin to `https://evil.example`; sets `admin.enabled` to `yes` (not `true` or `false`); and sets `feed.maxBatch` to `many` (not a number).
- `LiveSettings.from(new SettingsReader(properties))` must fail with a **list** of problems, and `problemsOf(settings)` pulls the list out of the `Result`.
- `containsExactlyInAnyOrder(...)` demands exactly these five lines, in any order: `http.port must be from 1024 to 65535, but was 80`; `websocket.allowedOrigin must be http://127.0.0.1:<port>, but was https://evil.example`; `feed.replayCapacity is missing`; `feed.maxBatch must be a whole number, but was many`; `admin.enabled must be true or false, but was yes`. This is the promise of the settings reader: every problem in one run, each naming the key and the value, never one at a time.

```java
  @Test
  void readsTheCompareAndLabSettings() throws IOException {
    SettingsReader rates =
        new SettingsReader(SettingsReader.load(Path.of("config/polling-rates.properties")));

    assertThat(
            io.plainround.seatwave.live.bench.CompareSettings.from(
                    new SettingsReader(SettingsReader.load(Path.of("config/compare.properties"))))
                .discardError())
        .isPresent();
    assertThat(io.plainround.seatwave.live.bench.PollingRatesCli.report(rates)).hasSize(9);
    assertThat(rates.problems()).isEmpty();
  }
```

- `polling-rates.properties` is read by the lab-1 tool; `compare.properties` by the comparison. `io.plainround.seatwave.live.bench.CompareSettings.from(...)` is written with the full package name instead of an `import`. The first `assertThat` demands it succeeds.
- `PollingRatesCli.report(rates)` returns the lab's printed lines, and `hasSize(9)` fixes their number: the 9 lines shown in the README. `rates.problems()` must be empty: the file had no missing or invalid key.

```java
  private static List<String> problemsOf(Result<LiveSettings, List<String>> settings) {
    return switch (settings) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> problems;
      case Result.Success<LiveSettings, List<String>> success -> List.of();
    };
  }
```

`problemsOf` is the helper that reads the error list out of a `Result`. It uses a `switch` with **record patterns**: `case Result.Error<LiveSettings, List<String>>(List<String> problems) -> problems;` takes the list out of the error, and the success case returns an empty list. A `switch` over a sealed type like `Result` must cover both cases, so the compiler proves none is forgotten.

### `src/test/resources/junit-platform.properties`

```properties
# No test may hang the build: each one fails after 60 seconds.
junit.jupiter.execution.timeout.default = 60 s
```

- Line 1 is a comment: no test may hang the build, each one fails after 60 seconds.
- Line 2 is the JUnit setting `junit.jupiter.execution.timeout.default = 60 s`. A test or lifecycle method that runs longer is stopped with a timeout failure. The longest test in a healthy run takes about 6.6 seconds. This is the net under every test that waits for a thread or a socket (`junit.md`).

### `src/test/resources/logback-test.xml`

```xml
<configuration>
  <!-- Tests print only warnings and errors, so the build output stays readable. -->
  <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-none}] %logger{0} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="WARN">
    <appender-ref ref="console"/>
  </root>
</configuration>
```

Logback is the logging library behind SLF4J. When it starts it looks for `logback-test.xml` on the classpath first, and only then for `logback.xml`; the test source set's file therefore wins over the one in `src/main/resources`.
- `<configuration>` is the root element of the file.
- The comment says: tests print only warnings and errors, so the build output stays readable.
- `<appender name="console" class="ch.qos.logback.core.ConsoleAppender">` defines a place to write log lines, named `console`: the process's standard output.
- `<encoder>` and `<pattern>` say what a line looks like: `%d{HH:mm:ss.SSS}` the time of day with milliseconds; `%-5level` the level (INFO, WARN) padded to 5 characters; `[%X{correlationId:-none}]` the value `correlationId` from the thread's **MDC** (a small map of context that the HTTP server fills with a request id), or `none` if there is none; `%logger{0}` the logger's class name without its package; `-` a dash; `%msg` the message; `%n` a new line.
- `<root level="WARN">` sets the default level for every logger to WARN, so the INFO and DEBUG lines of the servers are not printed. `<appender-ref ref="console"/>` sends the lines to the appender above.
- The effect can be seen in a run: the build output is the list of `PASSED` lines and nothing from the servers.

## Try it yourself

All commands ran in a scratch copy of this repository on 11 November 2026. Timings differ on every run.

1. Run the three test classes of this file:

```
./gradlew --offline cleanTest test --tests '*BenchReportsTest' --tests '*MeasuringToolsTest' --tests '*LiveSettingsTest'
```

   Expected: 11 `PASSED` lines (3 + 5 + 3), then `BUILD SUCCESSFUL`. The run takes about 17 seconds, nearly all of it `MeasuringToolsTest`.

2. See how long each test took:

```
grep -o 'testcase name="[^"]*" classname="[^"]*" time="[^"]*"' build/test-results/test/TEST-io.plainround.seatwave.live.bench.MeasuringToolsTest.xml
```

   Expected: five lines, with times of about 6.6, 3.4, 3.1 and 3.1 seconds and a short one for the child-process test.

3. Run the lab arithmetic that the report test checks:

```
./gradlew --offline -q pollingRates --args='config/polling-rates.properties'
```

   Expected: nine lines; the row `short polling, requests per second         40,000         40,000` is the 40,000 of the test.

4. Spoil a settings file in a scratch copy. Change `http.port=8090` in `config/serve.properties` to `http.port=80` and run `./gradlew --offline test --tests '*LiveSettingsTest'`. Expect `readsEveryShippedSettingsFile()` to fail, with the failure message naming `serve` (the `.as(file)` text).

5. Show the logging file in use: run `./gradlew --offline cleanTest test --tests '*WebSocketServerTest'` and look at the output. Only `PASSED` lines appear. The servers log at INFO and DEBUG, which `logback-test.xml` hides.

## Mistakes people make

- **Asserting a measured number.** A delay or a rate changes on every run. `MeasuringToolsTest` asserts only shapes and counts.
- **Fixed ports in tests.** Port 0 avoids collisions; the one test that needs 8092 and 8093 fails if they are taken.
- **A fake clock that is not `volatile`.** Another thread could keep reading an old time.
- **Reading a file with a path that depends on the folder.** The settings files are found by relative paths because Gradle runs the tests from the project folder.
- **Reporting one problem at a time.** `LiveSettingsTest` pins the opposite: every problem in one run.
- **Letting a test wait for ever.** The 60-second limit and `Raw`'s 5-second read timeout turn a hang into a failure.

## Check yourself

1. *Why is `now` in `MutableClock` `volatile`?* The node's threads read the clock while the test thread moves it; `volatile` makes the latest value visible to all.
2. *What does `Raw.readUntil` return when the server says nothing for five seconds?* Everything read so far plus the text `<timeout>`.
3. *Why does `MeasuringToolsTest` not assert delays?* They change on every run; the test checks the report's shape and that every client's map equals the store's.
4. *What does `LiveSettingsTest.listsEveryMissingAndInvalidKeyInOneRun` prove?* A bad file produces one line per problem, all in one run, each naming the key and the value.
5. *Why does `logback-test.xml` set the root level to WARN?* So the servers' INFO and DEBUG lines do not clutter the build output.

## Further reading

- JUnit 6 user guide, configuration parameters (6.1.3): <https://docs.junit.org/6.1.3/running-tests/configuration-parameters.html>
- Logback 1.6 manual, configuration and layouts: <https://logback.qos.ch/manual/index.html>
- Java 25 API, `java.time.Clock`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html>
- Java 25 API, `java.net.Socket`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/Socket.html>
- Java 25 API, `java.util.Properties`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html>
