# The measuring tools: `bench/`, file by file

## What it is

The `bench` package is the repository's measuring bench. "Bench" is short for benchmark: a program that runs a piece of software under a known load and writes down numbers. This package has one main tool and four smaller ones:

- **The comparison** (`Comparison`, `ComparisonCli`, `ComparisonReport`, `CompareSettings`): starts the feed, connects 1,000 clients with one delivery method, and measures the delay of every change, the server's CPU, threads, memory and file descriptors, and what happens when every connection drops at once. It runs once per delivery method and a few times each, then prints a table and writes a CSV file.
- **The wire-bytes probe** (`WireBytesProbe`, `WireBytesCli`, `ByteCountingRelay`): counts the exact bytes on the network for one seat change, per method.
- **The slow-consumer experiment** (`SlowConsumer`, `SlowConsumerCli`): connects clients that never read, to show that one slow client cannot hurt the others or the server's memory.
- **Lab 1** (`PollingRates`, `PollingRatesCli`): arithmetic for how many requests short polling and long polling make, against push.
- **Lab 2** (`SseResume`, `SseResumeCli`): one event-stream client that is dropped and resumes, and that reloads when it was away too long.

The remaining files are helpers: `LatencySummary` (percentiles), `MethodResult` (one run's numbers), `MachineLine` (the first line every tool prints), `ProcessMemory` (resident memory from `ps`), `FeedAdmin` (the tool's admin client for the server), and `ServerHandle` with `InProcessServer` and `ChildProcessServer` (a server in this process, or in a process of its own).

A word on three terms used everywhere below.

- A **percentile** answers "what delay did most changes stay under?". p50 (the median) is the delay that half of all changes stayed under. p99 is the delay that 99 of every 100 stayed under. The 1 in 100 beyond p99 is the **tail**. The tail matters because a fan who sits in it sees a stale seat map, and with 1,000 clients each change is delivered 1,000 times, so some delivery lands in the tail every time. `measuring-a-server.md` explains this from zero.
- **Warm-up** is a period of running that is not measured. A Java program starts slow: the JIT compiler (the part of the JVM that turns hot code into fast machine code) has not yet compiled the busy methods, caches are cold, and buffers are not yet allocated. Measuring that start would measure the startup, not the steady state.
- **Loopback** is the network address `127.0.0.1`, which stays inside the computer. Client and server share one machine, so there is no network distance in any delay, and their CPU use competes.

## Why this repository uses it

Module 8 does not ask the learner to believe that WebSockets are cheaper than long polling. It asks for a measurement at 1,000 clients, with the correctness of every client's final map checked at the end. These tools are that measurement. Every number in the README comes from them.

## Where it appears

| File | What it does |
|---|---|
| `LatencySummary.java` | p50, p99 and max by the nearest-rank method. |
| `MachineLine.java` | The first line of every tool: JDK, system, processors, file limit. |
| `ProcessMemory.java` | A process's resident memory, from `ps`. |
| `ServerHandle.java` | What the comparison needs from a running server. |
| `InProcessServer.java` | A server in this JVM (tests). |
| `ChildProcessServer.java` | A server in a JVM of its own (real runs). |
| `FeedAdmin.java` | Metrics, seat map and admin routes over HTTP. |
| `MethodResult.java` | The numbers of one run. |
| `CompareSettings.java` | The settings of the comparison, read from a file. |
| `Comparison.java` | One measured run of one method, in phases. |
| `ComparisonReport.java` | The printed table, the medians and the CSV. |
| `ComparisonCli.java` | The `compare` command. |
| `ByteCountingRelay.java` | A TCP relay that counts bytes in each direction. |
| `WireBytesProbe.java` | Bytes on the wire per change. |
| `WireBytesCli.java` | The `wireBytes` command. |
| `SlowConsumer.java` | The slow-consumer experiment. |
| `SlowConsumerCli.java` | The `slowConsumer` command. |
| `PollingRates.java` | Lab 1 arithmetic. |
| `PollingRatesCli.java` | The `pollingRates` command. |
| `SseResume.java` | Lab 2. |
| `SseResumeCli.java` | The `sseResume` command. |

Their settings files are in `config/` and are explained in `java-properties.md`. The clients they start are explained in `java-client-walkthrough.md`.

## How it works underneath

**A comparison run has six phases.** The class comment of `Comparison` lists them; this is the same list in order.

1. Start a fresh server in its own JVM and **pause its on-sale** (the simulator that makes seat changes), so nothing changes while connections are made.
2. Connect a tenth of the clients, wait until they have loaded the seat map and the server counts them as open, force a garbage collection on the server and read its memory. This reading is "with few connections".
3. Connect the other nine tenths, wait again, and read the memory again. The difference divided by the number of added clients is the **cost of one idle open connection**. A tenth go first so that one-time costs (classes loaded, caches and buffers made once) are paid before the first reading and do not get blamed on the connections.
4. **Resume the on-sale**, run the warm-up (clients do not record delays), then switch recording on for the measured phase (delays are kept, and the server's counters are read before and after).
5. **Drop every connection at once** (an admin route) and time how long until every client has applied a change made after the drop. That is the recovery time.
6. **Pause the on-sale**, wait until every client has the final sequence, stop the clients and compare each client's map with the store's. A run is correct only if all 1,000 are equal.

**How delivery delay is measured.** The store stamps every change with `changedAt` (an ISO-8601 time, with microseconds). When a client has applied the change to its map, it subtracts: `delay = now - changedAt`. The stamp is made on the server, the subtraction on the client, both on this machine's clock. The delay therefore includes everything between the store and the client's map: the queue in the feed, the write to the socket, the read, the JSON parse, and the scheduling of the client's thread. Each client keeps its delays in an array. At the end all arrays are joined and sorted once, and the percentiles are read off.

**How server cost is read.** The server is a separate process. Its own `/metrics` route returns text lines such as `process_cpu_nanoseconds_total 3228677000` and `process_open_fds 43`. The comparison fetches them over HTTP before and after the measured phase and subtracts. Memory is read two ways, because there are two kinds: the **heap** (Java objects, asked of the JVM after a forced collection) and the **resident set** (everything the operating system holds in RAM for the process, asked of `ps`). `measuring-a-server.md` explains both.

**How 1,000 clients are started without overrunning the listen queue.** `ClientFleet` (see the client notes) starts `compare.connectsPerSecond` clients each second, 250 in `config/compare.properties`, so 1,000 take 4 seconds. The server asks for a backlog of 1,024 (`http.backlog`), but macOS lowers it to `kern.ipc.somaxconn`, which is 128 on this machine.

**How results are written.** `ComparisonReport.table` builds the text table; `writeCsv` writes one CSV line per run to `build/compare/live-feed-comparison.csv`. The node's own standard error goes to `build/compare/server-<method>-run<n>.log`.

## Walkthrough of this repository's use

Every non-blank line of the twenty-one files is quoted below, small helpers first, then the comparison, then the other tools.

### `LatencySummary.java`: percentiles by the nearest-rank method

**Nearest rank, with a worked example.** Take 100 delays and sort them. The p50 is the value at rank `ceil(50 / 100 * 100) = 50`, the 50th smallest. The p99 is the value at rank `ceil(99 / 100 * 100) = 99`, the 99th smallest. For 1,000 delays, p99 is rank 990: ten delays are larger. The method always returns a value that really occurred; it never invents an in-between number.

I ran the class with delays of 1 ms to 100 ms (one each) except that the last one is 250 ms:

```text
LatencySummary[count=100, p50Millis=50.0, p99Millis=99.0, maxMillis=250.0]
```

The 250 ms outlier does not move p99 (rank 99 is the 99 ms value) but it is the max. That is exactly why a table shows p50, p99 and max together.

```java
package io.plainround.seatwave.live.bench;

import java.util.Arrays;
import java.util.Objects;

```

`java.util.Arrays` provides `sort`; `Objects` provides `requireNonNull`.

```java
/**
 * The p50, p99 and largest of a set of delays, read by the nearest-rank method (Module 4's method):
 * the smallest value that at least that share of the values do not exceed.
 *
 * @param count how many delays were summarized; 0 means none, and every value is then 0
 * @param p50Millis the delay half of them did not exceed
 * @param p99Millis the delay 99 in 100 of them did not exceed
 * @param maxMillis the largest delay
 */
```

The comment explains the method and each field. "0 means none, and every value is then 0": an empty set of delays is a valid summary.

```java
public record LatencySummary(long count, double p50Millis, double p99Millis, double maxMillis) {
```

`public record LatencySummary(long count, double p50Millis, double p99Millis, double maxMillis)` is a record of four values: the number of delays and three percentile values in milliseconds (`double` is a number with a decimal point).

```java

  private static final double MICROS_PER_MILLI = 1_000.0;
  private static final int P50 = 50;
  private static final int P99 = 99;
  private static final double HUNDRED = 100.0;

```

Constants: `MICROS_PER_MILLI = 1_000.0` converts microseconds (what clients record) to milliseconds; `P50`, `P99` and `HUNDRED` are the percent values used in the rank formula.

```java
  /** Checks the order of the values. */
  public LatencySummary {
    if (count < 0 || p99Millis < p50Millis || maxMillis < p99Millis) {
      throw new IllegalArgumentException("impossible summary");
    }
  }
```

The compact constructor refuses impossible values: a negative count, a p99 below p50, or a max below p99. A summary that breaks the order of percentiles is a bug.

```java

  /**
   * Summarizes delays.
   *
   * @param delaysMicros the delays in microseconds, in any order; not changed
   * @return the summary
   */
  public static LatencySummary ofMicros(long[] delaysMicros) {
    Objects.requireNonNull(delaysMicros, "delaysMicros");
    if (delaysMicros.length == 0) {
      return new LatencySummary(0, 0, 0, 0);
    }
    long[] sorted = delaysMicros.clone();
    Arrays.sort(sorted);
```

`ofMicros(long[] delaysMicros)` summarizes. `Objects.requireNonNull` guards a null array. An empty array returns the all-zero summary. `delaysMicros.clone()` copies the array so that sorting does not change the caller's data ("not changed", says the Javadoc). `Arrays.sort(sorted)` sorts it from smallest to largest.

```java
    return new LatencySummary(
        sorted.length,
        nearestRank(sorted, P50) / MICROS_PER_MILLI,
        nearestRank(sorted, P99) / MICROS_PER_MILLI,
        sorted[sorted.length - 1] / MICROS_PER_MILLI);
  }

```

The summary is built from: the count (`sorted.length`), p50 and p99 (each rank converted from microseconds to milliseconds by dividing), and the largest value, which is the last element of the sorted array (`sorted[sorted.length - 1]`).

```java
  private static long nearestRank(long[] sorted, int percent) {
    int rank = (int) Math.ceil(percent / HUNDRED * sorted.length);
    return sorted[Math.max(rank, 1) - 1];
  }
}
```

`nearestRank(sorted, percent)`: `percent / HUNDRED * sorted.length` is the position as a fraction of the array; `Math.ceil` rounds up to a whole rank; `(int)` makes it an `int`. `Math.max(rank, 1) - 1` turns the 1-based rank into a 0-based array index and protects against rank 0. The value at that index is the answer.

### `MachineLine.java`: the first line every tool prints

```java
package io.plainround.seatwave.live.bench;

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Locale;

```

Imports. `UnixOperatingSystemMXBean` (from `com.sun.management`) is a JDK-specific view of a Unix process that knows its file-descriptor limit. `ManagementFactory` is the JDK's entry point to the JVM's built-in monitoring. `Locale` is used to format numbers the same way everywhere.

```java
/** The line every measuring tool prints first: the JDK, the system and this process's limits. */
public final class MachineLine {

  private MachineLine() {}
```

A **utility class**: only static methods, and a `private` constructor `private MachineLine() {}` that nobody can call, so nobody builds an instance.

```java

  /**
   * Describes this machine and process.
   *
   * @return for example {@code Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process
   *     may open 10240 files}
   */
```

The Javadoc shows the format of the line, for example `Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process may open 10240 files`.

```java
  public static String describe() {
    OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean();
    String files =
        system instanceof UnixOperatingSystemMXBean unix
            ? "; this process may open " + unix.getMaxFileDescriptorCount() + " files"
            : "";
```

`describe()` asks the JVM for the operating-system bean. `system instanceof UnixOperatingSystemMXBean unix` is a **pattern-matching `instanceof`**: it tests the type and, if it matches, names the object `unix` in one step. If true, the line gets `; this process may open N files`, where `getMaxFileDescriptorCount()` is the process's file-descriptor limit (the number `ulimit -n` shows for the shell that started it). Otherwise (Windows) the part is empty. `cond ? a : b` is the **conditional operator**: `a` if the condition is true, otherwise `b`.

```java
    return String.format(
        Locale.ROOT,
        "Java %s on %s %s, %d processors%s",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        Runtime.getRuntime().availableProcessors(),
        files);
  }
}
```

`String.format(Locale.ROOT, ...)` fills `%s` and `%d` placeholders: `Runtime.version()` (the JDK build, such as `25.0.2+10-LTS`), the operating-system name and architecture from system properties, `availableProcessors()` (the logical processors the JVM may use, 12 here), and the optional file-limit text.

### `ProcessMemory.java`: resident memory from `ps`

**Why not ask the JVM.** The JVM can report its heap. It cannot report the memory it uses outside the heap: thread stacks, socket buffers, the code cache, native libraries. The operating system knows the total. The command `ps -o rss= -p <pid>` prints the **resident set size** (RSS): the kilobytes of RAM the process currently holds. This class runs that command and reads the number. The `=` after `rss` removes the column title, so the output is just a number.

```java
package io.plainround.seatwave.live.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

```

Imports: `ProcessBuilder` and `Process` (in `java.lang`) start another program; `OptionalLong` is "a long, or nothing".

```java
/**
 * The resident memory of a process, from the operating system's {@code ps}, because the JVM reports
 * only its heap: thread stacks, socket buffers and other native memory are counted only here.
 * Stateless.
 */
public final class ProcessMemory {

  private static final long BYTES_PER_KIB = 1_024;
  private static final long WAIT_SECONDS = 5;

  private ProcessMemory() {}
```

The class comment gives the reason. Constants: `BYTES_PER_KIB = 1_024` (`ps` reports kibibytes) and a 5-second wait. The private constructor makes it a utility class.

```java

  /**
   * Reads a process's resident set size.
   *
   * @param processId the process
   * @return the bytes in memory, or empty if {@code ps} could not tell
   * @throws InterruptedException if interrupted while waiting for {@code ps}
   */
  public static OptionalLong residentBytes(long processId) throws InterruptedException {
```

`residentBytes(long processId)` returns the RSS in bytes, or empty if `ps` could not tell. It declares `InterruptedException` because it waits for the child program.

```java
    // The id is a number this program got from the JDK, passed as its own argument: no shell, no
    // text joined from input.
    List<String> command = List.of("ps", "-o", "rss=", "-p", Long.toString(processId));
```

The comment records a security decision: the id is a number from the JDK, and it is passed as its **own argument** to the program, never joined into a shell command line. With `List.of("ps", "-o", "rss=", "-p", id)` no shell reads the text, so nothing in it could be interpreted as a command.

```java
    try {
      Process ps = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output =
          new String(ps.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
```

`new ProcessBuilder(command).redirectErrorStream(true).start()` starts `ps`, merging its error output into its normal output. `ps.getInputStream().readAllBytes()` reads everything it prints. `new String(bytes, US_ASCII).strip()` turns the bytes into text and trims spaces and the newline.

```java
      if (!ps.waitFor(WAIT_SECONDS, TimeUnit.SECONDS) || ps.exitValue() != 0 || output.isEmpty()) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(Long.parseLong(output) * BYTES_PER_KIB);
```

If `ps` did not finish in 5 seconds, or its exit code is not 0, or it printed nothing, return empty. Otherwise parse the number and multiply by 1,024 to get bytes.

```java
    } catch (IOException | NumberFormatException unreadable) {
      return OptionalLong.empty();
    }
  }
}
```

`IOException` (`ps` could not start) and `NumberFormatException` (it printed something that is not a number) also return empty: a missing reading is a value, and the report shows `n/a`.

### `ServerHandle.java`, `InProcessServer.java`, `ChildProcessServer.java`: a server to measure

**Why two kinds.** If the server and the 1,000 clients ran in one JVM, the thread count, heap and CPU would be the sum of both and the server's own cost could not be read. So the real comparison starts the server as a **child process**: a second JVM that the comparison launches, with its own heap and its own operating-system process id. Tests use the in-process kind, because a second JVM per test costs seconds. The comparison talks to either through the interface `ServerHandle`.

```java
package io.plainround.seatwave.live.bench;

import java.io.IOException;
import java.util.OptionalLong;

```

Imports.

```java
/**
 * A running feed node the comparison measures: in this process (tests) or, for real measurements,
 * in a process of its own, so the clients' threads and heap are not counted as the server's.
 */
public interface ServerHandle extends AutoCloseable {
```

`public interface ServerHandle extends AutoCloseable` says that a handle can be used in a **try-with-resources** block and is closed when the block ends. The comment explains the two kinds.

```java

  /**
   * The HTTP port.
   *
   * @return the port
   */
  int httpPort();

  /**
   * The WebSocket port.
   *
   * @return the port
   */
  int webSocketPort();

  /**
   * The server's operating-system process id, when it runs alone.
   *
   * @return the id, or empty when the server shares this process
   */
  OptionalLong processId();
```

Three methods: `httpPort()`, `webSocketPort()`, and `processId()`, which is an `OptionalLong`: the server's operating-system process id when it runs alone, or empty when it shares the process.

```java

  @Override
  void close() throws IOException;
}
```

`close()` is declared again without the generic `Exception`, narrowing it to `IOException`, so callers have only one exception type to handle.

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.config.LiveSettings;
import java.io.IOException;
import java.time.Clock;
import java.util.OptionalLong;

```

Imports. `LiveFeedNode` is the class that wires and starts one feed node. `Clock` is the JDK's time source.

```java
/**
 * A feed node in this process, with its on-sale running. Used by tests, where a second JVM per run
 * would cost seconds; its process numbers include the clients, so the real comparison never uses
 * it.
 */
public final class InProcessServer implements ServerHandle {

  private final LiveFeedNode node;

  private InProcessServer(LiveFeedNode node) {
    this.node = node;
  }
```

A final class that wraps a node. The comment says why it is for tests only: "its process numbers include the clients". The constructor is private; `start` is the way in.

```java

  /**
   * Starts a node and its on-sale.
   *
   * @param settings the node's settings
   * @return the running server
   * @throws IOException if a port cannot be bound
   */
  public static InProcessServer start(LiveSettings settings) throws IOException {
    LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC());
    node.startOnSale();
    return new InProcessServer(node);
  }
```

`start(settings)`: `LiveFeedNode.start(settings, Clock.systemUTC())` builds and starts the node with the real clock (UTC). `node.startOnSale()` starts the simulator that makes seat changes.

```java

  /**
   * The node, for tools that act on it directly.
   *
   * @return the node
   */
  public LiveFeedNode node() {
    return node;
  }
```

`node()` exposes the node for tools that act on it directly (the tests).

```java

  @Override
  public int httpPort() {
    return node.httpPort();
  }

  @Override
  public int webSocketPort() {
    return node.webSocketPort();
  }

  @Override
  public OptionalLong processId() {
    return OptionalLong.empty();
  }

  @Override
  public void close() throws IOException {
    node.close();
  }
}
```

The interface methods: ports come from the node, `processId()` is empty (shared process), `close()` closes the node. `@Override` asks the compiler to check that each method really overrides one.

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.ServeCli;
import io.plainround.seatwave.live.config.LiveSettings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

```

Imports. `CompletableFuture` is a promise of a value that another thread will produce. `ServeCli` is the program that runs a node from a settings file.

```java
/**
 * A feed node in a JVM of its own, started with the same {@code java} and class path as this one,
 * so its threads, heap and CPU time are the server's alone. It is ready when it prints its first
 * line.
 */
public final class ChildProcessServer implements ServerHandle {

  private static final String READY = "SeatWave live feed on";
  private static final Duration STOP_WAIT = Duration.ofSeconds(10);

```

The comment: same `java` and class path as this program, so the server's numbers are its own. "It is ready when it prints its first line." `READY` is the start of the line that `ServeCli` prints once it listens (`SeatWave live feed on http://...`). `STOP_WAIT` is 10 seconds.

```java
  private final Process process;
  private final LiveSettings settings;

  private ChildProcessServer(Process process, LiveSettings settings) {
    this.process = process;
    this.settings = settings;
  }
```

Fields: the `Process` object (a handle on the child) and the settings (for the ports). The constructor is private.

```java

  /**
   * Starts the node and waits until it is ready.
   *
   * @param settingsFile the node's settings file, which must enable the admin routes
   * @param settings the same settings, already read, for the ports
   * @param heapMegabytes the node's largest heap
   * @param logFile where the node's standard error goes
   * @return the running server
   * @throws IOException if the process cannot start or is not ready in time
   * @throws InterruptedException if interrupted while waiting
   */
  public static ChildProcessServer start(
      Path settingsFile, LiveSettings settings, int heapMegabytes, Path logFile)
      throws IOException, InterruptedException {
```

`start(settingsFile, settings, heapMegabytes, logFile)` launches the child and waits until it is ready. The Javadoc says the settings file must enable the admin routes, because the comparison pauses, settles and drops connections through them.

```java
    Objects.requireNonNull(settingsFile, "settingsFile");
    Objects.requireNonNull(settings, "settings");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
```

Null checks. Then the path of the `java` program that is running this one: `System.getProperty("java.home")` is the JDK's folder, and `bin/java` is the launcher inside it. Using the same JDK avoids measuring a different version.

```java
    List<String> command =
        List.of(
            java,
            // A fixed heap that is touched at start, so the resident memory measured later grows
            // only with what connections add outside the heap, not with the heap filling in.
            "-Xms" + heapMegabytes + "m",
            "-Xmx" + heapMegabytes + "m",
            "-XX:+AlwaysPreTouch",
            "-cp",
            System.getProperty("java.class.path"),
            ServeCli.class.getName(),
            settingsFile.toString());
```

The command, as a list (one list item per argument, never one string):

- `java` the launcher.
- `-Xms1024m` and `-Xmx1024m` set the **initial and maximum heap** to the same size, 1,024 MB (`compare.childHeapMegabytes`). A heap of fixed size means the JVM never resizes it during the run.
- `-XX:+AlwaysPreTouch` makes the JVM **touch every page of the heap at start**, so the operating system really assigns the memory then. Without it the resident set would grow as the heap filled, and the per-connection memory read would be mixed up with the heap filling in. The comment says this in the code.
- `-cp <class path>` passes the same class path as this program (`System.getProperty("java.class.path")`).
- `ServeCli.class.getName()` is the main class to run, as a full name.
- `settingsFile.toString()` is its one argument.

```java
    Process process =
        new ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            .start();
```

`new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile())).start()` starts the child with its standard error appended to a log file (`build/compare/server-<method>-run<n>.log`). Its standard output stays connected to this program so the first line can be read.

```java
    CompletableFuture<String> firstLine =
        CompletableFuture.supplyAsync(() -> readFirstLine(process));
```

`CompletableFuture.supplyAsync(() -> readFirstLine(process))` reads that first line on another thread, so the wait for it can have a time limit.

```java
    try {
      String line = firstLine.get(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
      if (!line.startsWith(READY)) {
        process.destroyForcibly();
        throw new IOException("the feed process did not start: " + line + "; see " + logFile);
      }
```

`firstLine.get(10 seconds)` waits at most 10 seconds. If the line does not start with `READY`, the child is killed (`destroyForcibly`) and an `IOException` with the line and the log path is thrown.

```java
    } catch (ExecutionException | TimeoutException notReady) {
      process.destroyForcibly();
      throw new IOException("the feed process was not ready in time; see " + logFile, notReady);
    }
    return new ChildProcessServer(process, settings);
  }
```

If the wait fails (the future failed or timed out), the child is killed and an `IOException` says it "was not ready in time". Otherwise a handle on the running child is returned.

```java

  @Override
  public int httpPort() {
    return settings.network().httpPort();
  }

  @Override
  public int webSocketPort() {
    return settings.network().websocketPort();
  }

  @Override
  public OptionalLong processId() {
    return OptionalLong.of(process.pid());
  }
```

The ports come from the settings (`settings.network().httpPort()`), and the process id from `process.pid()`.

```java

  @Override
  public void close() throws IOException {
    process.destroy();
    try {
      if (!process.waitFor(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
```

`close()` stops the child politely: `process.destroy()` sends it the termination signal; `waitFor` allows 10 seconds; if it is still alive, `destroyForcibly()` kills it. If this thread is interrupted while waiting, the child is killed and the interrupted flag is set again.

```java

  private static String readFirstLine(Process process) {
    try {
      BufferedReader output =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      String line = output.readLine();
      return line == null ? "(no output)" : line;
    } catch (IOException unreadable) {
      return "(unreadable output: " + unreadable.getMessage() + ")";
    }
  }
}
```

`readFirstLine` reads one line from the child's output with a `BufferedReader` over UTF-8. A null line (the child closed its output without printing) becomes the text `(no output)`; an `IOException` becomes a text with its message, so the caller's error message always says something.

### `FeedAdmin.java`: the bench's hands on the server

The comparison never touches the server's objects. It uses the server's HTTP routes, so the same code measures a node in this process or in a child. The routes used are `/metrics`, `/v1/events/1/seat-map`, `/health`, and the admin routes `POST /admin/pause`, `resume`, `drop-connections` and `settle`. The admin routes exist only when `admin.enabled=true` (see `config/compare-server.properties`); on a normal node they answer 404.

`/metrics` returns one sample per line: a name, a space, a number. For example `process_open_fds 43` or `seatwave_feed_open_connections{method="sse"} 20`. A name may carry a label in braces.

```java
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

```

Imports. `Pattern` and `Matcher` are the JDK's regular expressions (text patterns); `HashMap` and `Map` hold the samples.

```java
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
```

The comment says "all over HTTP on 127.0.0.1". `NUMBER` is the pattern `"[A-Za-z]+":([0-9]+)`: a quoted word, a colon, then digits captured in a group. It reads the one number out of replies like `{"dropped":1000}` or `{"heapUsedBytes":38758968}`. Fields: the shared HTTP client, the addresses, a JSON codec, the timeout.

```java

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
```

The constructor. The timeout comment says a collection can take a moment (the `settle` route runs a full garbage collection, so the timeout is 30 seconds in `Comparison`).

```java

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
```

`metrics()` fetches `/metrics`, splits the text into lines (`.lines().toList()`), and for each line finds the last space (`lastIndexOf(' ')`), so a label that contains spaces would still work. The text before it is the key, the number after it is parsed with `Double.parseDouble`. The result is a `Map<String, Double>` from sample name to value.

```java

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
```

`seatMap()` fetches the whole map and parses it into a `SnapshotView`. The bench uses it to learn the store's true states and last sequence.

```java

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
```

`admin(route)` sends `POST /admin/<route>` with no body, reads the reply and returns the first number in it (`number.find()` looks for the pattern; `number.group(1)` is the digits; 0 if there is none). `pause` and `resume` return 0, `drop-connections` returns how many connections were closed, `settle` returns the heap in use after a collection.

```java

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
```

`awaitHealthy(patience)` polls `/health` until it answers. It computes a deadline from `System.nanoTime()`. On failure it checks the deadline, and if there is time left sleeps one fiftieth of the patience (at least 1 ms) and tries again. After the deadline it rethrows the last `IOException`.

```java

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
```

`get(path)` and `send(request)`: send a request, ask for the body as a `String` (`BodyHandlers.ofString()`), and throw `IOException` for any status other than 200, with the path and the status in the message.

### `MethodResult.java`: the numbers of one run

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.util.Objects;

/**
 * Everything one run of one delivery method measured.
 *
 * @param method the delivery method
 * @param run the run's number, from 1
 * @param clients the clients started
 * @param connected the clients that had loaded the map when measuring began
 * @param delay the delivery delay of every change every client received while recording
 * @param load the rates and bytes during the measured phase
 * @param cost what the open connections cost the server
 * @param reconnect what happened when every connection was dropped at once
 * @param correctness whether every client ended with the store's seat map
 */
```

The Javadoc lists the nine parts of a result. `LatencySummary delay` is the percentiles; `Load` the rates; `ServerCost` the cost; `Reconnect` the drop test; `Correctness` the final check.

```java
public record MethodResult(
    DeliveryMethod method,
    int run,
    int clients,
    int connected,
    LatencySummary delay,
    Load load,
    ServerCost cost,
    Reconnect reconnect,
    Correctness correctness) {
```

The record has nine fields; most are other small records declared below.

```java

  /** Checks the fields. */
  public MethodResult {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(delay, "delay");
    Objects.requireNonNull(load, "load");
    Objects.requireNonNull(cost, "cost");
    Objects.requireNonNull(reconnect, "reconnect");
    Objects.requireNonNull(correctness, "correctness");
  }
```

The compact constructor refuses a null for any object field.

```java

  /**
   * Rates during the measured phase.
   *
   * @param changesPerSecond seat changes the store made per second
   * @param deliveriesPerSecond changes written to clients per second, all clients together
   * @param requestsPerSecond long-poll replies per second, or new push connections per second
   * @param bytesPerDelivery bytes the server wrote per delivered change (body and framing it writes
   *     itself; HTTP headers and chunk sizes added by the JDK server are not counted here)
   */
  public record Load(
      double changesPerSecond,
      double deliveriesPerSecond,
      double requestsPerSecond,
      double bytesPerDelivery) {}
```

`Load`: `changesPerSecond` (seat changes the store made per second), `deliveriesPerSecond` (changes written to clients per second, summed over all clients: with 1,000 clients and 69 changes per second, about 69,000), `requestsPerSecond` (long-poll replies per second for long polling, new connections per second for the push methods), and `bytesPerDelivery` (bytes the server wrote per delivered change). The note says HTTP headers and chunk sizes added by the JDK server are not counted in that last one.

```java

  /**
   * The server's cost.
   *
   * @param cpuCores the server process's CPU time per second of the measured phase
   * @param cpuMicrosPerDelivery that CPU time divided by the changes delivered
   * @param heapBytesPerConnection live heap with every client connected and idle, less the heap
   *     with none, per client
   * @param residentBytesPerConnection the same for the process's resident memory; -1 when the
   *     server shares the process with the clients
   * @param platformThreads the server's platform threads with every client connected
   * @param virtualThreads the server's connection threads with every client connected
   * @param openFileDescriptors the server's open file descriptors with every client connected
   * @param maxFileDescriptors the server process's file-descriptor limit
   */
  public record ServerCost(
      double cpuCores,
      double cpuMicrosPerDelivery,
      long heapBytesPerConnection,
      long residentBytesPerConnection,
      int platformThreads,
      int virtualThreads,
      long openFileDescriptors,
      long maxFileDescriptors) {}
```

`ServerCost`: `cpuCores` (CPU seconds used per second, see below), `cpuMicrosPerDelivery` (CPU time per delivered change), `heapBytesPerConnection` and `residentBytesPerConnection` (memory added per idle connection; -1 when the server shares the process), `platformThreads`, `virtualThreads`, and `openFileDescriptors` with `maxFileDescriptors` (its limit).

```java

  /**
   * The drop of every connection at once.
   *
   * @param dropped connections the server dropped
   * @param recoveryMillis from the drop until every client had applied a change made after it
   * @param serverReconnects connections the server accepted in that time, for every method
   * @param clientReconnects reconnects the clients counted themselves
   * @param reloads full seat-map reloads in the whole run
   */
  public record Reconnect(
      long dropped,
      long recoveryMillis,
      long serverReconnects,
      long clientReconnects,
      long reloads) {}
```

`Reconnect`: `dropped` (connections the server closed), `recoveryMillis` (from the drop until every client had applied a change made after it), `serverReconnects` (connections the server accepted in that time), `clientReconnects` (what the clients counted), `reloads`.

```java

  /**
   * The end-of-run check against the store.
   *
   * @param gaps changes that arrived after a missing one, all clients together
   * @param duplicates changes received twice
   * @param correctMaps clients whose final map equalled the store's
   */
  public record Correctness(long gaps, long duplicates, int correctMaps) {}
}
```

`Correctness`: `gaps` and `duplicates` summed over clients, and `correctMaps`: how many clients ended with exactly the store's map. The target is 1,000 of 1,000 and 0 gaps.

### `CompareSettings.java`: the settings of the comparison

The file `config/compare.properties` has no defaults in code. A missing or invalid key becomes a line of text, and **all problems are reported at once**. `from` returns a result4j `Result` (see `result4j.md`): either the settings or the list of problem lines. The keys are explained one by one in `java-properties.md`.

```java
package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.SettingsReader;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

```

Imports, including `Result` from result4j and `SettingsReader`, the class that reads typed values out of a properties file.

```java
/**
 * The comparison's settings, read from one file with no defaults in code.
 *
 * @param serverSettings the feed node's own settings file (admin routes on)
 * @param methods the delivery methods to measure, in order
 * @param clients how many clients per method
 * @param runs how many runs per method; the report shows each run and the median
 * @param phases how long each phase of a run lasts
 * @param fleet how clients connect, time out and back off
 * @param childProcess whether the node runs in a process of its own (true for real numbers)
 * @param childHeapMegabytes the node's largest heap when it runs alone
 * @param output the folder for the CSV file and the node's logs
 */
```

The Javadoc names every field of the settings.

```java
public record CompareSettings(
    Path serverSettings,
    List<DeliveryMethod> methods,
    int clients,
    int runs,
    Phases phases,
    Fleet fleet,
    boolean childProcess,
    int childHeapMegabytes,
    Path output) {
```

The record: the node's settings file path, the list of methods, clients per method, runs per method, the `Phases` (warm-up, measure, settle timeout), the `Fleet` (how clients connect), a flag for running the node as a child process, the child's heap, and the output folder.

```java

  private static final int MAX_CLIENTS = 50_000;
  private static final int MAX_RUNS = 20;
  private static final int MAX_SECONDS = 3_600;
  private static final int MAX_MILLIS = 600_000;
  private static final int MAX_HEAP_MEGABYTES = 32_768;
  private static final int MAX_CONNECTS_PER_SECOND = 10_000;
  private static final Pattern METHODS =
      Pattern.compile("(long-polling|sse|websocket)(,(long-polling|sse|websocket))*");
  private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_./-]{1,200}");

```

Constants that bound what a settings file may ask for: at most 50,000 clients, 20 runs, 3,600 seconds per phase, 600,000 ms per timeout, 32,768 MB of heap, 10,000 connects per second. `METHODS` is a regular expression for the method list: one of `long-polling`, `sse`, `websocket`, then any number of `,` plus one of them. `PATH` allows 1 to 200 characters from letters, digits, underscore, dot, slash and hyphen: no spaces, no `..` tricks beyond what those characters allow, and no shell characters.

```java
  /** Checks the fields. */
  public CompareSettings {
    Objects.requireNonNull(serverSettings, "serverSettings");
    methods = List.copyOf(methods);
    Objects.requireNonNull(phases, "phases");
    Objects.requireNonNull(fleet, "fleet");
    Objects.requireNonNull(output, "output");
    if (methods.isEmpty() || clients < 1 || runs < 1 || childHeapMegabytes < 1) {
      throw new IllegalArgumentException("methods, clients, runs and heap must be positive");
    }
  }
```

The compact constructor: `List.copyOf` makes the list unchangeable; objects must not be null; counts must be at least 1.

```java

  /**
   * Reads the settings.
   *
   * @param reader the settings file
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<CompareSettings, List<String>> from(SettingsReader reader) {
```

`from(SettingsReader reader)` is a **static factory** returning `Result<CompareSettings, List<String>>`.

```java
    String server = reader.matching("compare.serverSettings", PATH, "a relative file path");
    String methodList =
        reader.matching(
            "compare.methods", METHODS, "a comma-separated list of long-polling, sse, websocket");
    int clients = reader.intBetween("compare.clients", 1, MAX_CLIENTS);
    int runs = reader.intBetween("compare.runs", 1, MAX_RUNS);
```

Reading the keys. `reader.matching(key, pattern, description)` returns the text if it matches. `reader.intBetween(key, min, max)` returns a whole number in range. If a key is missing or out of range, the reader records a problem line and returns the minimum so the reading can go on and report the other problems too.

```java
    Phases phases =
        new Phases(
            Duration.ofSeconds(reader.intBetween("compare.warmupSeconds", 1, MAX_SECONDS)),
            Duration.ofSeconds(reader.intBetween("compare.measureSeconds", 1, MAX_SECONDS)),
            Duration.ofSeconds(reader.intBetween("compare.settleTimeoutSeconds", 1, MAX_SECONDS)));
```

The three phase lengths, read in seconds and turned into `Duration`s: warm-up, measure and settle timeout.

```java
    Fleet fleet =
        new Fleet(
            reader.intBetween("compare.connectsPerSecond", 1, MAX_CONNECTS_PER_SECOND),
            reader.millisBetween("compare.connectTimeoutMillis", 1, MAX_MILLIS),
            reader.millisBetween("compare.requestTimeoutMillis", 1, MAX_MILLIS),
            reader.millisBetween("compare.silenceLimitMillis", 1, MAX_MILLIS),
            reader.millisBetween("compare.backoffBaseMillis", 1, MAX_MILLIS),
            reader.millisBetween("compare.backoffCapMillis", 1, MAX_MILLIS));
```

The fleet values: connects per second, then five durations read with `reader.millisBetween` (milliseconds, each between 1 and 600,000): connect timeout, request timeout, silence limit, backoff base and backoff cap.

```java
    boolean child = reader.flag("compare.childProcess");
    int heap = reader.intBetween("compare.childHeapMegabytes", 1, MAX_HEAP_MEGABYTES);
    String output = reader.matching("compare.output", PATH, "a relative folder path");
```

`reader.flag` reads `true` or `false`. The heap and the output path follow.

```java
    List<String> problems = new ArrayList<>(reader.problems());
    if (fleet.backoffCap().compareTo(fleet.backoffBase()) < 0) {
      // Each key is in range on its own; the backoff needs them in order, so this is checked here
      // with the other problems instead of failing later inside a client.
      problems.add(
          "compare.backoffCapMillis must be at least compare.backoffBaseMillis ("
              + fleet.backoffBase().toMillis()
              + "), but was "
              + fleet.backoffCap().toMillis());
    }
    if (!problems.isEmpty()) {
      return Result.error(List.copyOf(problems));
    }
```

`problems` starts as a copy of the reader's problem lines, in a list that can still grow (`new ArrayList<>(...)`). Then one check that no single key can make: the backoff's cap must not be below its base. Each of `compare.backoffBaseMillis` and `compare.backoffCapMillis` is in range on its own, but `Backoff` needs them in order. `compareTo` compares two durations and is negative when the first is shorter. In version 1.0.0 nothing checked this here: a file with `compare.backoffCapMillis=50` (and a base of 100) passed, and the comparison later stopped with `IllegalArgumentException` printed as a raw stack trace, from the `Backoff` constructor inside a client. Now it is one more problem line, printed with the others before anything starts:

```text
settings: compare.backoffCapMillis must be at least compare.backoffBaseMillis (100), but was 50
```

If any problem was recorded, return `Result.error(...)` with an unmodifiable copy of the list: nothing is built from half-valid values.

```java
    List<DeliveryMethod> methods = new ArrayList<>();
    for (String label : methodList.split(",")) {
      methods.add(
          Arrays.stream(DeliveryMethod.values())
              .filter(method -> method.label().equals(label))
              .findFirst()
              .orElseThrow());
    }
```

Turn the method labels into enum values: split the text at commas and, for each label, search `DeliveryMethod.values()` for the one whose `label()` equals it. `.orElseThrow()` cannot fail because the pattern already allowed only valid labels.

```java
    return Result.success(
        new CompareSettings(
            Path.of(server), methods, clients, runs, phases, fleet, child, heap, Path.of(output)));
  }
```

`Result.success(new CompareSettings(...))` returns the settings, with `Path.of` turning the two text paths into `Path` objects.

```java

  /**
   * How long each phase of a run lasts.
   *
   * @param warmup changes flow but delays are not recorded, so the JIT and the caches settle
   * @param measure delays, CPU and rates are recorded
   * @param settleTimeout the longest wait for clients to connect or catch up
   */
  public record Phases(Duration warmup, Duration measure, Duration settleTimeout) {

    /** Checks the fields. */
    public Phases {
      Objects.requireNonNull(warmup, "warmup");
      Objects.requireNonNull(measure, "measure");
      Objects.requireNonNull(settleTimeout, "settleTimeout");
    }
  }
```

`Phases`: `warmup` (changes flow, delays are not recorded), `measure` (delays, CPU and rates are recorded), `settleTimeout` (the longest wait for clients to connect or catch up).

```java

  /**
   * How the clients connect.
   *
   * @param connectsPerSecond how many clients start per second
   * @param connectTimeout the TCP connect timeout
   * @param requestTimeout the longest wait for a reply or a stream's headers
   * @param silenceLimit the longest a push connection may stay silent before the client drops it
   * @param backoffBase the first reconnect wait's ceiling
   * @param backoffCap the largest reconnect wait's ceiling
   */
  public record Fleet(
      int connectsPerSecond,
      Duration connectTimeout,
      Duration requestTimeout,
      Duration silenceLimit,
      Duration backoffBase,
      Duration backoffCap) {

    /** Checks the fields. */
    public Fleet {
      Objects.requireNonNull(connectTimeout, "connectTimeout");
      Objects.requireNonNull(requestTimeout, "requestTimeout");
      Objects.requireNonNull(silenceLimit, "silenceLimit");
      Objects.requireNonNull(backoffBase, "backoffBase");
      Objects.requireNonNull(backoffCap, "backoffCap");
    }
  }
}
```

`Fleet`: how many clients start per second, the connect timeout, the request timeout, the silence limit, and the base and cap of the backoff.

### `Comparison.java`: one measured run

This is the longest file. Read it with the six phases above in mind.

**What "cores used" means.** The server process reports how much CPU time it has consumed in total, in nanoseconds, summed over all its threads. If the counter grows by 1.73 seconds of CPU during 1 second of wall-clock time, the server used 1.73 cores on average (1.73 processors' worth of work). In the code: `(cpuAfter - cpuBefore) / 1e9 / seconds`. This machine has 12 logical processors, so 12 cores is the most any process could use.

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.client.ClientFleet;
import io.plainround.seatwave.live.client.FeedClient;
import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.client.FleetPlan;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.wire.SnapshotView;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

```

Imports.

```java
/**
 * One measured run of one delivery method, in six phases: start a fresh server and pause its
 * on-sale; read its memory with no clients; connect every client and read it again (the cost of
 * idle open connections); run the on-sale through a warm-up and a measured phase (delay, CPU,
 * rates); drop every connection at once and time the recovery; pause and compare every client's
 * seat map with the store's.
 *
 * <p>Not thread-safe: one comparison runs at a time.
 */
```

The class comment lists the six phases (the same list as above).

```java
public final class Comparison {

  private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL = Duration.ofMillis(50);
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double MICROS_PER_SECOND = 1_000_000.0;
  // Each client keeps up to this many delays; more are counted but not kept.
  private static final int DELAYS_PER_CLIENT = 20_000;
  private static final long CLIENT_SEED = 8;
  private static final int FIRST_SHARE = 10;
```

Constants. `ADMIN_TIMEOUT` (30 s) for admin calls. `POLL` (50 ms) is how often waits re-check. `NANOS_PER_SECOND` and `MICROS_PER_SECOND` convert units. `DELAYS_PER_CLIENT = 20_000` is each client's delay array size. `CLIENT_SEED = 8` is the first backoff seed, so runs repeat. `FIRST_SHARE = 10` means the first fleet is one tenth of the clients.

```java

  private final CompareSettings settings;
  private final LiveSettings serverSettings;

  /**
   * Creates the comparison.
   *
   * @param settings the comparison's settings
   * @param serverSettings the node's settings, already read from {@code settings.serverSettings()}
   */
  public Comparison(CompareSettings settings, LiveSettings serverSettings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.serverSettings = Objects.requireNonNull(serverSettings, "serverSettings");
  }
```

Fields and constructor: the comparison's settings and the node's settings (needed for the ports and for starting an in-process node).

```java

  /**
   * Runs one method once.
   *
   * @param method the delivery method
   * @param run the run's number, from 1
   * @return what it measured
   * @throws IOException if the server cannot start or stops answering
   * @throws InterruptedException if interrupted
   */
  public MethodResult run(DeliveryMethod method, int run) throws IOException, InterruptedException {
    try (ServerHandle server = startServer(method, run);
```

`run(method, run)` is the entry for one run. The Javadoc says it returns what it measured.

`try (ServerHandle server = startServer(method, run); HttpClient http = HttpClient.newHttpClient())` opens two resources and closes both, in reverse order, however the block ends. This is what stops the child server: leaving the block calls `close()`.

```java
        HttpClient http = HttpClient.newHttpClient()) {
      FeedEndpoints endpoints = new FeedEndpoints(server.httpPort(), server.webSocketPort());
      FeedAdmin admin = new FeedAdmin(http, endpoints, ADMIN_TIMEOUT);
      admin.awaitHealthy(settings.phases().settleTimeout());
      admin.admin("pause");
```

Phase 1: build the endpoints from the server's ports, make an admin client, wait until the server answers `/health` (up to the settle timeout), and call `pause`, so no seat changes while connections are made.

```java
      // A tenth of the clients connect first, so one-time costs (classes, caches, buffers that are
      // made once) are paid before the first reading; the rest then cost only per connection.
      int firstClients = Math.max(1, settings.clients() / FIRST_SHARE);
      Fleets fleets = new Fleets(new ArrayList<>());
```

The comment says why a tenth go first. `firstClients = Math.max(1, clients / 10)` (100 of 1,000). `Fleets` is a small holder (below) that treats the two fleets as one.

```java
      try {
        fleets.add(ClientFleet.start(plan(method, endpoints, firstClients, 0)));
        Probe probe = new Probe(server, admin, fleets);
        awaitTrue(() ->
            fleets.countLoaded() == firstClients && openConnections(probe, method) >= firstClients);
```

Phase 2: start the first fleet (`plan(method, endpoints, firstClients, 0)`), build a `Probe` (server, admin, fleets), and `awaitTrue` until every first client has loaded its seat map **and** the server counts at least that many open connections for this method. The condition is a lambda: a function that returns true when the wait may end.

```java
        long heapFirst = admin.admin("settle");
        OptionalLong residentFirst = resident(server);
```

`admin.admin("settle")` makes the server run a garbage collection and return the heap in use, `heapFirst`. `resident(server)` reads its RSS from `ps`.

```java
        fleets.add(
            ClientFleet.start(
                plan(method, endpoints, settings.clients() - firstClients, firstClients)));
        return measure(method, run, probe, new Reading(firstClients, heapFirst, residentFirst));
```

Phase 3: start the remaining fleet; its client numbers start at `firstClients` so backoff seeds differ. Then `measure(...)` does phases 3 to 6 and returns the `MethodResult`.

```java
      } finally {
        fleets.stop();
      }
    }
  }
```

`finally { fleets.stop(); }` stops every client whatever happened. On a run that reaches the end, `finish` (below) has already stopped the fleets, so that it can read the clients' results; `ClientFleet.stop` does its work only on its first call (`java-client-walkthrough.md`), so the fleets are stopped exactly once on every path, a failed run included.

```java

  private MethodResult measure(DeliveryMethod method, int run, Probe probe, Reading first)
      throws IOException, InterruptedException {
```

The signature of `measure`.

```java
    awaitTrue(() ->
        probe.fleet.countLoaded() == settings.clients()
            && openConnections(probe, method) >= settings.clients());
```

Wait until all clients have loaded the map and the server shows all connections open. `awaitTrue` does not throw on timeout; the run goes on, and the result's `connected` column shows how many really connected.

```java
    int connected = probe.fleet.countLoaded();
    int added = Math.max(1, connected - first.clients());
    long heapConnected = probe.admin.admin("settle");
    OptionalLong residentConnected = resident(probe.server);
    Map<String, Double> idle = probe.admin.metrics();
```

`connected` is the number actually loaded. `added` is how many connections were added since the first reading. Then the second heap reading (`settle`), the second RSS reading, and the **idle metrics** (`metrics()`), which hold the thread and file-descriptor counts with every client connected and nothing happening yet.

```java
    probe.admin.admin("resume");
    Thread.sleep(settings.phases().warmup());
    probe.fleet.record(true);
```

Phase 4: `resume` the on-sale, sleep through the **warm-up**, then `record(true)` so clients start keeping delays.

```java
    Map<String, Double> before = probe.admin.metrics();
    long startNanos = System.nanoTime();
    Thread.sleep(settings.phases().measure());
    Map<String, Double> after = probe.admin.metrics();
    double seconds = (System.nanoTime() - startNanos) / NANOS_PER_SECOND;
    probe.fleet.record(false);
    MethodResult.Reconnect reconnect = dropAndRecover(probe, after);
```

The measured phase. `before` is the server's metrics, `startNanos` the clock, then sleep for the measure time, read `after`, compute the true elapsed `seconds` (the sleep is not exact), and `record(false)`. Phase 5, `dropAndRecover`, runs next.

```java
    Correctness correctness = finish(probe);
    MethodResult.Load load = load(method, before, after, seconds);
```

Phase 6, `finish`, pauses the on-sale, waits for all clients to catch up, stops them and compares maps. `load(...)` computes the rates from the before and after samples.

```java
    double cores =
        (value(after, "process_cpu_nanoseconds_total")
                - value(before, "process_cpu_nanoseconds_total"))
            / NANOS_PER_SECOND
            / seconds;
```

**CPU cores**: the difference of `process_cpu_nanoseconds_total` between `after` and `before`, divided by 1e9 (to seconds) and by the elapsed seconds.

```java
    MethodResult.ServerCost cost =
        new MethodResult.ServerCost(
            cores,
            load.deliveriesPerSecond() == 0
                ? 0
                : cores * MICROS_PER_SECOND / load.deliveriesPerSecond(),
            (heapConnected - first.heapBytes()) / added,
            first.residentBytes().isPresent() && residentConnected.isPresent()
                ? (residentConnected.getAsLong() - first.residentBytes().getAsLong()) / added
                : -1,
            (int) value(idle, "process_platform_threads"),
            (int) value(idle, "seatwave_feed_handler_virtual_threads"),
            (long) value(idle, "process_open_fds"),
            (long) value(idle, "process_max_fds"));
```

The `ServerCost`: cores; **CPU microseconds per delivery** (cores times a million, divided by deliveries per second; 0 if nothing was delivered); **heap per connection** `(heapConnected - first.heapBytes()) / added`; **resident bytes per connection**, the same difference for RSS, or -1 if either reading is missing; the platform thread count and the handler virtual-thread count from the idle metrics; open and maximum file descriptors. Casts `(int)` and `(long)` narrow the `double` samples.

```java
    return new MethodResult(
        method,
        run,
        settings.clients(),
        connected,
        correctness.delay,
        load,
        cost,
        new MethodResult.Reconnect(
            reconnect.dropped(),
            reconnect.recoveryMillis(),
            reconnect.serverReconnects(),
            correctness.clientReconnects,
            correctness.reloads),
        new MethodResult.Correctness(
            correctness.gaps, correctness.duplicates, correctness.correctMaps));
  }
```

The result is assembled from the pieces. Note the `Reconnect` merges the server-side measurement (`reconnect`) with what the clients counted (`correctness.clientReconnects`, `correctness.reloads`).

```java

  private MethodResult.Reconnect dropAndRecover(Probe probe, Map<String, Double> before)
      throws IOException, InterruptedException {
    long published = (long) value(probe.admin.metrics(), "seatwave_feed_changes_published_total");
    long dropNanos = System.nanoTime();
    long dropped = probe.admin.admin("drop-connections");
    // Recovered: every client has applied a change made after the drop, so each one has
    // reconnected, resumed and caught up.
    awaitTrue(() -> probe.fleet.countAtLeast(published + 1) == probe.fleet.size());
    long recoveryMillis = Duration.ofNanos(System.nanoTime() - dropNanos).toMillis();
    Map<String, Double> after = probe.admin.metrics();
    long accepted = 0;
    for (DeliveryMethod each : DeliveryMethod.values()) {
      String name = "seatwave_feed_connections_total{method=\"" + each.label() + "\"}";
      accepted += (long) (value(after, name) - value(before, name));
    }
    return new MethodResult.Reconnect(dropped, recoveryMillis, accepted, 0, 0);
  }
```

`dropAndRecover` is phase 5.

- `published` is the number of changes the store has made so far.
- `dropNanos` starts the clock, then `admin("drop-connections")` closes every push connection at once on the server (returning how many).
- `awaitTrue(() -> countAtLeast(published + 1) == size())` waits until **every** client has applied a change numbered above `published`. A client can only do that after it reconnected and caught up, so this is the recovery time.
- `recoveryMillis` is the elapsed time. Then the server's metrics are read again, and for each delivery method the growth of `seatwave_feed_connections_total{method="..."}` is added up: how many connections the server accepted in that time. The label text is built with string joining, `\"` is a quote inside a Java string.

```java

  private Correctness finish(Probe probe) throws IOException, InterruptedException {
    probe.admin.admin("pause");
    SnapshotView store = probe.admin.seatMap();
    long finalSequence =
        Long.parseLong(store.position().substring(store.position().indexOf('.') + 1));
    awaitTrue(() -> probe.fleet.countAtLeast(finalSequence) == probe.fleet.size());
    probe.fleet.stop();
    Correctness correctness = new Correctness();
    List<long[]> delays = new ArrayList<>();
    for (FeedClient client : probe.fleet.clients()) {
      if (client.map().states().equals(store.states())) {
        correctness.correctMaps++;
      }
      correctness.gaps += client.stats().gaps();
      correctness.duplicates += client.stats().duplicates();
      correctness.reloads += client.stats().reloads();
      correctness.clientReconnects += client.stats().reconnects();
      delays.add(client.stats().delaysMicros());
    }
    long[] all = delays.stream().flatMapToLong(java.util.Arrays::stream).toArray();
    correctness.delay = LatencySummary.ofMicros(all);
    return correctness;
  }
```

`finish` is phase 6.

- `pause` stops the on-sale (the server's `/admin/pause` returns only after a tick that was running has finished, so no change follows), `seatMap()` reads the store's true map. `finalSequence` is the digits after the dot of its position (`indexOf('.') + 1`).
- Wait until all clients are at that sequence, then `stop` them (so no thread is still changing a map or its stats).
- For each client: if its `states()` string equals the store's, count a correct map; add up gaps, duplicates, reloads and reconnects; collect its delay array.
- `flatMapToLong(Arrays::stream).toArray()` joins all clients' arrays into one array of up to 20 million delays. `LatencySummary.ofMicros(all)` reads p50, p99 and max.

```java

  private static MethodResult.Load load(
      DeliveryMethod method,
      Map<String, Double> before,
      Map<String, Double> after,
      double seconds) {
    String label = "{method=\"" + method.label() + "\"}";
    double published = delta(before, after, "seatwave_feed_changes_published_total");
    double delivered = delta(before, after, "seatwave_feed_changes_delivered_total" + label);
    double bytes = delta(before, after, "seatwave_feed_bytes_written_total" + label);
    double requests =
        method == DeliveryMethod.LONG_POLLING
            ? delta(before, after, "seatwave_feed_long_poll_replies_total")
            : delta(before, after, "seatwave_feed_connections_total" + label);
    return new MethodResult.Load(
        published / seconds,
        delivered / seconds,
        requests / seconds,
        delivered == 0 ? 0 : bytes / delivered);
  }
```

`load` computes the rates between two samples: `delta` is `after - before`. Published changes, delivered changes and bytes written use the method's label in the sample name. For long polling the request count is `seatwave_feed_long_poll_replies_total`; for the push methods it is the growth of connections accepted. Rates are divided by the elapsed seconds; bytes per delivery is bytes written divided by changes delivered (0 if none).

```java

  private ServerHandle startServer(DeliveryMethod method, int run)
      throws IOException, InterruptedException {
    if (!settings.childProcess()) {
      return InProcessServer.start(serverSettings);
    }
    Files.createDirectories(settings.output());
    return ChildProcessServer.start(
        settings.serverSettings(),
        serverSettings,
        settings.childHeapMegabytes(),
        settings.output().resolve("server-" + method.label() + "-run" + run + ".log"));
  }
```

`startServer`: when `childProcess` is false, an in-process server; otherwise create the output folder (`Files.createDirectories`) and start a child with a log file named like `server-sse-run1.log`.

```java

  private FleetPlan plan(
      DeliveryMethod method, FeedEndpoints endpoints, int clients, int firstIndex) {
    CompareSettings.Fleet fleet = settings.fleet();
    return new FleetPlan(
        method,
        clients,
        endpoints,
        fleet.connectsPerSecond(),
        new FleetPlan.Timeouts(
            fleet.connectTimeout(), fleet.requestTimeout(), fleet.silenceLimit()),
        new FleetPlan.BackoffRange(fleet.backoffBase(), fleet.backoffCap()),
        DELAYS_PER_CLIENT,
        CLIENT_SEED + firstIndex);
  }
```

`plan(...)` builds the `FleetPlan` for one fleet from the comparison's settings, with `DELAYS_PER_CLIENT` and a seed `CLIENT_SEED + firstIndex`.

```java

  private int openConnections(Probe probe, DeliveryMethod method)
      throws IOException, InterruptedException {
    return (int)
        value(
            probe.admin.metrics(),
            "seatwave_feed_open_connections{method=\"" + method.label() + "\"}");
  }
```

`openConnections` reads the gauge `seatwave_feed_open_connections{method="<label>"}` from the server's metrics.

```java

  private static OptionalLong resident(ServerHandle server) throws InterruptedException {
    OptionalLong processId = server.processId();
    return processId.isPresent()
        ? ProcessMemory.residentBytes(processId.getAsLong())
        : OptionalLong.empty();
  }
```

`resident(server)` returns the child's RSS, or empty for an in-process server (it has no process of its own).

```java

  private void awaitTrue(Check check) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + settings.phases().settleTimeout().toNanos();
    while (!check.holds()) {
      if (System.nanoTime() > deadline) {
        // The run goes on; the report shows how many clients connected or caught up.
        return;
      }
      Thread.sleep(POLL);
    }
  }
```

`awaitTrue(check)` loops until the check holds or the settle timeout has passed, sleeping 50 ms between checks. On timeout it returns quietly; the comment says "the report shows how many clients connected or caught up".

```java

  private static double value(Map<String, Double> samples, String name) {
    return samples.getOrDefault(name, 0.0);
  }

  private static double delta(Map<String, Double> before, Map<String, Double> after, String name) {
    return value(after, name) - value(before, name);
  }
```

`value(samples, name)` reads a sample with a default of 0; `delta` subtracts two readings.

```java

  @FunctionalInterface
  private interface Check {
    boolean holds() throws IOException, InterruptedException;
  }
```

`Check` is a **functional interface** with one method, so a lambda can be passed where a `Check` is expected. Its method may throw `IOException` and `InterruptedException`.

```java

  private record Probe(ServerHandle server, FeedAdmin admin, Fleets fleet) {}

  private record Reading(int clients, long heapBytes, OptionalLong residentBytes) {}
```

`Probe` groups the server, the admin client and the fleets. `Reading` is the first heap and RSS reading with the number of clients then.

```java

  // The two fleets of a run, counted as one.
  private record Fleets(List<ClientFleet> parts) {

    void add(ClientFleet fleet) {
      parts.add(fleet);
    }

    int size() {
      return parts.stream().mapToInt(part -> part.clients().size()).sum();
    }

    int countLoaded() {
      return parts.stream().mapToInt(ClientFleet::countLoaded).sum();
    }

    int countAtLeast(long sequence) {
      return parts.stream().mapToInt(part -> part.countAtLeast(sequence)).sum();
    }

    void record(boolean isRecording) {
      parts.forEach(part -> part.record(isRecording));
    }

    List<FeedClient> clients() {
      return parts.stream().flatMap(part -> part.clients().stream()).toList();
    }

    void stop() throws InterruptedException {
      for (ClientFleet part : parts) {
        part.stop();
      }
    }
  }
```

`Fleets` presents the two fleets as one: `size`, `countLoaded`, `countAtLeast` add up the parts (`mapToInt(...).sum()`), `record` switches recording on all, `clients()` joins both lists, `stop()` stops both.

```java

  // Filled once at the end of a run by the thread that ran it.
  private static final class Correctness {
    private int correctMaps;
    private long gaps;
    private long duplicates;
    private long reloads;
    private long clientReconnects;
    private LatencySummary delay = LatencySummary.ofMicros(new long[0]);
  }
}
```

`Correctness` is a plain accumulator filled once at the end of a run by one thread; its fields are totals.

### `ComparisonReport.java`: the table, the medians and the CSV

**Why the median of runs.** One run can be unlucky: a garbage collection at the wrong moment, another program using the machine. The comparison runs each method 3 times and reports every run and the **median** of each column on its own. The median of three numbers is the middle one when sorted, so one bad run does not move it.

**What a CSV is.** A CSV (comma-separated values) file is plain text, one row per line, columns separated by commas. A spreadsheet or a plotting tool opens it directly. This one has a header line with 28 column names and one line per run.

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

```

Imports.

```java
/**
 * Turns the runs into the printed table and the comparison CSV: one line per run, then one line per
 * method with the median of its runs. Stateless.
 */
public final class ComparisonReport {

  private static final String CSV_HEADER =
```

The class comment: the printed table and the CSV, "one line per run, then one line per method with the median of its runs". The header text is assembled in the next constants.

```java
      "method,run,clients,connected,changes_per_s,deliveries_per_s,requests_per_s,"
          + "server_bytes_per_delivery,delay_p50_ms,delay_p99_ms,delay_max_ms,delay_samples,"
          + "cpu_cores,cpu_us_per_delivery,heap_bytes_per_connection,"
          + "resident_bytes_per_connection,platform_threads,virtual_threads,open_fds,max_fds,"
          + "dropped,recovery_ms,server_reconnects,client_reconnects,reloads,gaps,duplicates,"
          + "correct_maps";
```

`CSV_HEADER`: the 28 column names in one string, joined with `+` over lines. These names are the CSV's columns, for example `delay_p99_ms`, `cpu_cores`, `heap_bytes_per_connection`, `open_fds`, `recovery_ms`, `correct_maps`.

```java
  private static final String TABLE_HEADER =
      String.format(
          Locale.ROOT,
          "%-12s %3s %5s %8s %10s %9s %8s %8s %8s %6s %7s %8s %8s %4s %4s %4s %7s %6s %5s %4s %7s",
          "method",
          "run",
          "conn",
          "changes/s",
          "deliver/s",
          "req/s",
          "p50 ms",
          "p99 ms",
          "max ms",
          "cores",
          "us/del",
          "heap/c",
          "rss/c",
          "pthr",
          "vthr",
          "fds",
          "recov ms",
          "reconn",
          "reload",
          "gaps",
          "correct");

```

`TABLE_HEADER` is built with `String.format`: `%-12s` means "left-aligned text in 12 characters", `%3s` "right-aligned text in 3", `%8s` a right-aligned text in 8. The format string is one long line of 21 columns and the 21 titles follow, in order: method, run, conn, changes/s, deliver/s, req/s, p50 ms, p99 ms, max ms, cores, us/del, heap/c, rss/c, pthr, vthr, fds, recov ms, reconn, reload, gaps, correct. `Locale.ROOT` fixes the number format (a dot as the decimal point) regardless of the computer's language.

```java
  private ComparisonReport() {}
```

A private constructor: a utility class.

```java

  /**
   * The table: a header, one line per run, then the medians.
   *
   * @param results every run of every method
   * @return the lines to print
   */
  public static List<String> table(List<MethodResult> results) {
    List<String> lines = new ArrayList<>();
    lines.add(TABLE_HEADER);
    results.forEach(
        result -> lines.add(row(result.method().label(), Integer.toString(result.run()), result)));
    lines.add("median of the runs:");
    for (DeliveryMethod method : DeliveryMethod.values()) {
      List<MethodResult> runs =
          results.stream().filter(result -> result.method() == method).toList();
      if (!runs.isEmpty()) {
        lines.add(row(method.label(), "med", median(runs)));
      }
    }
    return List.copyOf(lines);
  }
```

`table(results)`: the header, then one `row` per result (the run number as text), then the line `median of the runs:`, then, for each method that has runs, a `row` with the label `med` and `median(runs)`. `List.copyOf` returns an unchangeable list.

```java

  /**
   * Writes the CSV file, one line per run.
   *
   * @param results every run
   * @param file where to write it
   * @throws IOException if the file cannot be written
   */
  public static void writeCsv(List<MethodResult> results, Path file) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add(CSV_HEADER);
    for (MethodResult result : results) {
      lines.add(
          String.join(
              ",",
              result.method().label(),
              Integer.toString(result.run()),
              Integer.toString(result.clients()),
              Integer.toString(result.connected()),
              decimal(result.load().changesPerSecond()),
              decimal(result.load().deliveriesPerSecond()),
              decimal(result.load().requestsPerSecond()),
              decimal(result.load().bytesPerDelivery()),
              decimal(result.delay().p50Millis()),
              decimal(result.delay().p99Millis()),
              decimal(result.delay().maxMillis()),
              Long.toString(result.delay().count()),
              decimal(result.cost().cpuCores()),
              decimal(result.cost().cpuMicrosPerDelivery()),
              Long.toString(result.cost().heapBytesPerConnection()),
              Long.toString(result.cost().residentBytesPerConnection()),
              Integer.toString(result.cost().platformThreads()),
              Integer.toString(result.cost().virtualThreads()),
              Long.toString(result.cost().openFileDescriptors()),
              Long.toString(result.cost().maxFileDescriptors()),
              Long.toString(result.reconnect().dropped()),
              Long.toString(result.reconnect().recoveryMillis()),
              Long.toString(result.reconnect().serverReconnects()),
              Long.toString(result.reconnect().clientReconnects()),
              Long.toString(result.reconnect().reloads()),
              Long.toString(result.correctness().gaps()),
              Long.toString(result.correctness().duplicates()),
              Integer.toString(result.correctness().correctMaps())));
    }
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.write(file, lines, StandardCharsets.UTF_8);
  }
```

`writeCsv(results, file)`: starts with the header, then for each result `String.join(",", ...)` of 28 values in the same order as the header: method label, run, clients, connected, then rates with `decimal(...)` (three decimals), percentiles, then costs and counts with `Long.toString` / `Integer.toString`. `Files.createDirectories(file.toAbsolutePath().getParent())` makes the folder if missing. `Files.write(file, lines, UTF_8)` writes one line per list item.

```java

  /**
   * The run whose values are each the median of the runs (for each column on its own).
   *
   * @param runs one or more runs of one method
   * @return a result made of the medians
   */
  public static MethodResult median(List<MethodResult> runs) {
    MethodResult first = runs.getFirst();
    return new MethodResult(
        first.method(),
        0,
        first.clients(),
        (int) median(runs, MethodResult::connected),
        new LatencySummary(
            (long) median(runs, result -> result.delay().count()),
            median(runs, result -> result.delay().p50Millis()),
            median(runs, result -> result.delay().p99Millis()),
            median(runs, result -> result.delay().maxMillis())),
        new MethodResult.Load(
            median(runs, result -> result.load().changesPerSecond()),
            median(runs, result -> result.load().deliveriesPerSecond()),
            median(runs, result -> result.load().requestsPerSecond()),
            median(runs, result -> result.load().bytesPerDelivery())),
        new MethodResult.ServerCost(
            median(runs, result -> result.cost().cpuCores()),
            median(runs, result -> result.cost().cpuMicrosPerDelivery()),
            (long) median(runs, result -> result.cost().heapBytesPerConnection()),
            (long) median(runs, result -> result.cost().residentBytesPerConnection()),
            (int) median(runs, result -> result.cost().platformThreads()),
            (int) median(runs, result -> result.cost().virtualThreads()),
            (long) median(runs, result -> result.cost().openFileDescriptors()),
            (long) median(runs, result -> result.cost().maxFileDescriptors())),
        new MethodResult.Reconnect(
            (long) median(runs, result -> result.reconnect().dropped()),
            (long) median(runs, result -> result.reconnect().recoveryMillis()),
            (long) median(runs, result -> result.reconnect().serverReconnects()),
            (long) median(runs, result -> result.reconnect().clientReconnects()),
            (long) median(runs, result -> result.reconnect().reloads())),
        new MethodResult.Correctness(
            (long) median(runs, result -> result.correctness().gaps()),
            (long) median(runs, result -> result.correctness().duplicates()),
            (int) median(runs, result -> result.correctness().correctMaps())));
  }
```

`median(runs)` builds a new `MethodResult` whose every field is the median of that field over the runs, **each column on its own**. So the "median row" may combine values that never happened in one run. The run number is 0. Counts are cast back to `int` or `long`. `runs.getFirst()` (Java 21 and later) is the first list element, used for the method and client count.

```java

  private static String row(String method, String run, MethodResult result) {
    return String.format(
        Locale.ROOT,
        "%-12s %3s %5d %8.1f %10.1f %9.1f %8.2f %8.2f %8.2f %6.3f %7.1f %8d %8s %4d %4d %4d %7d %6d %5d %4d %7d",
        method,
        run,
        result.connected(),
        result.load().changesPerSecond(),
        result.load().deliveriesPerSecond(),
        result.load().requestsPerSecond(),
        result.delay().p50Millis(),
        result.delay().p99Millis(),
        result.delay().maxMillis(),
        result.cost().cpuCores(),
        result.cost().cpuMicrosPerDelivery(),
        result.cost().heapBytesPerConnection(),
        result.cost().residentBytesPerConnection() < 0
            ? "n/a"
            : Long.toString(result.cost().residentBytesPerConnection()),
        result.cost().platformThreads(),
        result.cost().virtualThreads(),
        result.cost().openFileDescriptors(),
        result.reconnect().recoveryMillis(),
        result.reconnect().clientReconnects(),
        result.reconnect().reloads(),
        result.correctness().gaps(),
        result.correctness().correctMaps());
  }
```

`row(method, run, result)` formats one table line with the 21-column format: `%5d` a whole number in 5 characters, `%8.1f` a decimal with one digit after the point in 8 characters, `%8.2f` two digits, `%6.3f` three. The resident-memory column shows `n/a` when the value is negative (no child process). Each argument is read from the result in column order.

```java

  private static double median(List<MethodResult> runs, ToDoubleFunction<MethodResult> column) {
    List<Double> sorted =
        runs.stream().map(column::applyAsDouble).sorted(Comparator.naturalOrder()).toList();
    int middle = sorted.size() / 2;
    return sorted.size() % 2 == 1
        ? sorted.get(middle)
        : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
  }
```

`median(runs, column)` sorts one column's values and takes the middle one. `ToDoubleFunction<MethodResult>` is a function from a result to a number, passed as a lambda such as `result -> result.delay().p50Millis()`. For an odd count the middle element; for an even count the average of the two middle ones.

```java

  private static String decimal(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }
}
```

`decimal(value)` formats a CSV number with three decimals and a dot.

### `ComparisonCli.java`: the `compare` command

`CLI` means command-line interface: a class with a `main` method that Gradle (or the `java` launcher) starts. `./gradlew -q compare --args='config/compare.properties'` runs this class (the task is defined in `build.gradle.kts`, which the build notes explain). Only `*Cli` classes print or exit.

```java
package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

```

Imports.

```java
/**
 * Runs the live-feed comparison: {@code ./gradlew -q compare --args='config/compare.properties'}.
 * It prints the machine, the settings, one line per run and the medians, and writes the CSV file.
 */
public final class ComparisonCli {

  private static final int USAGE = 2;

  private ComparisonCli() {}

```

The comment shows the command. `USAGE = 2` is the exit code for a usage error. The constructor is private.

```java
  /**
   * Runs the comparison.
   *
   * @param arguments the comparison's settings file
   * @throws IOException if a file cannot be read or written, or the server fails
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: compare <settings file>");
      System.exit(USAGE);
    }
```

`main` needs exactly one argument, the settings file. Otherwise it prints `usage: compare <settings file>` to standard error (`System.err`) and calls `System.exit(2)`, which ends the program with that **exit code**. An exit code is the number a program returns to whoever started it; 0 means success, anything else a failure.

```java
    switch (CompareSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<CompareSettings, List<String>>(List<String> problems) -> fail(problems);
      case Result.Success<CompareSettings, List<String>>(CompareSettings settings) -> {
        switch (LiveSettings.from(
            new SettingsReader(SettingsReader.load(settings.serverSettings())))) {
          case Result.Error<LiveSettings, List<String>>(List<String> problems) -> fail(problems);
          case Result.Success<LiveSettings, List<String>>(LiveSettings server) ->
              run(settings, server).forEach(System.out::println);
        }
      }
    }
  }
```

A **switch over the result** of reading the comparison settings. `SettingsReader.load(Path.of(arguments[0]))` reads the file as UTF-8 properties. The two cases:

- `Result.Error<...>(List<String> problems)` calls `fail(problems)`, which prints every problem and exits.
- `Result.Success<...>(CompareSettings settings)` is a **record pattern**: it takes the success apart and names the settings. Then it reads the node's settings the same way, with another two-case switch, and finally calls `run(settings, server)` and prints each line it returns (`forEach(System.out::println)`).

The compiler requires both cases to be handled; leaving one out is an error.

```java

  private static void fail(List<String> problems) {
    problems.forEach(problem -> System.err.println("settings: " + problem));
    System.exit(USAGE);
  }
```

`fail` prints each problem with the prefix `settings: ` on standard error and exits with code 2.

```java

  /**
   * Runs every method and run, and returns the printed lines.
   *
   * @param settings the comparison's settings
   * @param server the node's settings
   * @return the lines of the report
   * @throws IOException if the server fails or the CSV cannot be written
   * @throws InterruptedException if interrupted
   */
  public static List<String> run(CompareSettings settings, LiveSettings server)
      throws IOException, InterruptedException {
    List<String> lines = new ArrayList<>();
    lines.add(MachineLine.describe());
    lines.add(
        String.format(
```

`run(settings, server)` builds the report lines. The first line is `MachineLine.describe()`.

```java
            Locale.ROOT,
            "feed %s; %d clients per method, %d run(s) each; warm-up %d s, measured %d s; "
                + "on-sale %d actions per second over %d seats",
            settings.childProcess()
                ? "in its own JVM (fixed, pre-touched heap of "
                    + settings.childHeapMegabytes()
                    + " MB)"
                : "in this JVM (process numbers include the clients)",
            settings.clients(),
            settings.runs(),
            settings.phases().warmup().toSeconds(),
            settings.phases().measure().toSeconds(),
            server.onSale().actionsPerSecond(),
            server.layout().seatCount()));
```

The second line is made by `String.format`. The text `feed in its own JVM (fixed, pre-touched heap of 1024 MB)` or `in this JVM (process numbers include the clients)` depends on the `childProcess` flag, followed by the clients, runs, warm-up and measure seconds, the on-sale rate and the number of seats. For example (from the real run in the README): `feed in its own JVM (fixed, pre-touched heap of 1024 MB); 1000 clients per method, 3 run(s) each; warm-up 5 s, measured 15 s; on-sale 50 actions per second over 20000 seats`.

```java
    Comparison comparison = new Comparison(settings, server);
    List<MethodResult> results = new ArrayList<>();
    for (DeliveryMethod method : settings.methods()) {
      for (int run = 1; run <= settings.runs(); run++) {
        results.add(comparison.run(method, run));
      }
    }
```

The loops: for each method in the settings, and for each run number from 1 to `runs`, run one comparison and collect the result. The order is all runs of the first method, then the next.

```java
    lines.addAll(ComparisonReport.table(results));
    Path csv = settings.output().resolve("live-feed-comparison.csv");
    ComparisonReport.writeCsv(results, csv);
    lines.add("csv: " + csv);
    return List.copyOf(lines);
  }
}
```

Add the table lines, build the CSV path `<output>/live-feed-comparison.csv`, write it, add the line `csv: <path>`, and return an unchangeable list.

### `ByteCountingRelay.java`: exact bytes on the wire

**The problem.** Neither the JDK's HTTP client nor its server tells you how many bytes crossed the network, headers and framing included. A **relay** (also called a proxy) fixes it: put a small program between the client and the server, let it copy every byte through, and count them. The client connects to the relay's port; the relay opens its own connection to the real server. Two copiers run for each connection: one from client to server, one back. Each adds to a counter.

**TCP and sockets in one paragraph.** TCP is the protocol that gives two programs a reliable stream of bytes. A **socket** is one end of such a connection. A **server socket** listens on a port and `accept`s connections. Reading a socket blocks until bytes arrive. `setTcpNoDelay(true)` turns off **Nagle's algorithm**, which holds small writes briefly to combine them; with it off, each flush is sent at once, so the relay adds no delay of its own.

```java
package io.plainround.seatwave.live.bench;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

```

Imports, including `LongAdder` (a counter that many threads can add to without slowing each other) and the logging types of SLF4J.

```java
/**
 * A TCP relay on 127.0.0.1 that copies bytes between a client and the feed and counts them in each
 * direction: the exact bytes on the wire, HTTP headers, chunk sizes and WebSocket frame headers
 * included, which neither the JDK's HTTP client nor its server reports.
 *
 * <p>Thread-safe: the counters are adders; each relayed connection has its own two threads.
 */
public final class ByteCountingRelay implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ByteCountingRelay.class);
  private static final int BUFFER_BYTES = 16_384;

```

The comment. `LOG` is the class's logger. `BUFFER_BYTES` is 16,384, the chunk size of each copy.

```java
  private final ServerSocket listener;
  private final int targetPort;
  private final LongAdder towardServer = new LongAdder();
  private final LongAdder towardClient = new LongAdder();

  private ByteCountingRelay(ServerSocket listener, int targetPort) {
    this.listener = listener;
    this.targetPort = targetPort;
  }
```

Fields: the listening socket, the real server's port, and the two counters, `towardServer` and `towardClient`. The constructor is private.

```java

  /**
   * Starts a relay to a port on 127.0.0.1.
   *
   * @param targetPort the feed's port
   * @return the relay, listening on a port the system chose
   * @throws IOException if it cannot listen
   */
  public static ByteCountingRelay start(int targetPort) throws IOException {
    ServerSocket listener = new ServerSocket();
    listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    ByteCountingRelay relay = new ByteCountingRelay(listener, targetPort);
    Thread.ofVirtual().name("relay-accept").start(relay::acceptLoop);
    return relay;
  }
```

`start(targetPort)` opens a `ServerSocket`, binds it to `InetAddress.getLoopbackAddress()` with port 0 (the system picks a free port), builds the relay, and starts its `acceptLoop` on a named virtual thread (`relay::acceptLoop` is a method reference). It returns the relay.

```java

  /**
   * The port clients connect to.
   *
   * @return the port
   */
  public int port() {
    return listener.getLocalPort();
  }

```

`port()` is the port the system chose (`listener.getLocalPort()`), which clients connect to.

```java
  /**
   * Bytes sent from clients to the feed since the last reset.
   *
   * @return the count
   */
  public long bytesTowardServer() {
    return towardServer.sum();
  }

  /**
   * Bytes sent from the feed to clients since the last reset.
   *
   * @return the count
   */
  public long bytesTowardClient() {
    return towardClient.sum();
  }
```

`bytesTowardServer()` and `bytesTowardClient()` return the counters' sums.

```java

  /** Sets both counts to zero. */
  public void reset() {
    towardServer.reset();
    towardClient.reset();
  }
```

`reset()` sets both counters to zero. The probe resets after set-up so that the seat-map request and the handshake do not count.

```java

  @Override
  public void close() throws IOException {
    listener.close();
  }
```

`close()` closes the listening socket, which also ends the accept loop.

```java

  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        Socket server = new Socket(InetAddress.getLoopbackAddress(), targetPort);
        client.setTcpNoDelay(true);
        server.setTcpNoDelay(true);
        Thread.ofVirtual().start(() -> pump(client, server, towardServer));
        Thread.ofVirtual().start(() -> pump(server, client, towardClient));
      } catch (IOException closed) {
        // The relay was closed, or the feed refused this one connection.
        LOG.debug("relay connection not made: {}", closed.toString());
      }
    }
  }
```

`acceptLoop` runs until the listener is closed. `listener.accept()` blocks until a client connects. The relay then connects to the real server (`new Socket(loopback, targetPort)`), turns off Nagle's algorithm on both sockets and starts two virtual threads, each running `pump`. If the server refuses or the relay is closed, an `IOException` is logged at debug level and the loop continues or ends.

```java

  private static void pump(Socket from, Socket to, LongAdder counted) {
    byte[] buffer = new byte[BUFFER_BYTES];
    try (from;
        to) {
      InputStream in = from.getInputStream();
      OutputStream out = to.getOutputStream();
      int read;
      while ((read = in.read(buffer)) >= 0) {
        out.write(buffer, 0, read);
        out.flush();
        counted.add(read);
      }
    } catch (IOException ended) {
      // One side closed; closing both (by the try) ends the other direction too.
      LOG.debug("relay direction ended: {}", ended.toString());
    }
  }
}
```

`pump(from, to, counted)` copies bytes one way. `try (from; to)` puts both sockets in the try-with-resources, so **both** are closed when either direction ends; the comment says closing both ends the other direction too. It reads into a 16 KB buffer: `in.read(buffer)` returns how many bytes it got, or -1 at the end. For each chunk it writes the same bytes out, flushes, and adds the count to the counter. An `IOException` just ends this direction (logged).

### `WireBytesProbe.java` and `WireBytesCli.java`: bytes per seat change

The probe makes **one change at a time** and waits for the client to have it before the next. That is the worst case for long polling: each poll carries one change. Under load, long polling batches many changes into one reply and the per-change cost falls. The probe measures one method after another, each with a fresh node and a single client behind two relays (one for the HTTP port, one for the WebSocket port).

Real output from this machine, 50 changes (the README's run used 200; numbers differ slightly on every run):

```text
method          client->server B   server->client B   total B/change   (50 changes, one at a time, one client)
long-polling               119.8              294.5            414.3
sse                          0.0              152.5            152.5
websocket                    0.0              112.6            112.6
```

How to read it. Long polling sends about 120 bytes up for every change, because each poll is a new HTTP request with headers, and about 295 down (response headers plus the JSON wrapper). SSE sends nothing up after the first request, and about 152 bytes down per change: the `id:`, `event:` and `data:` lines, one blank line and the HTTP chunk framing around about 113 bytes of JSON. WebSocket sends about 113 bytes down: the JSON plus a frame header of 2 bytes.

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.client.ClientFleet;
import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.client.FleetPlan;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.SeatFeed;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

```

Imports.

```java
/**
 * Measures the bytes on the wire per seat change for each method: one client through a {@link
 * ByteCountingRelay}, and changes made one at a time, each waited for before the next, so every
 * long poll carries exactly one change (the worst case for long polling; under load it batches).
 */
public final class WireBytesProbe {

```

The class comment states the method and the worst-case remark.

```java
  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Duration POLL = Duration.ofMillis(2);
```

`WAIT` (10 s) is the longest wait for any condition; `POLL` (2 ms) is how often to check.

```java

  private final LiveSettings settings;
  private final int changes;

  /**
   * Creates the probe.
   *
   * @param settings the node's settings
   * @param changes how many changes to measure per method
   */
  public WireBytesProbe(LiveSettings settings, int changes) {
    this.settings = Objects.requireNonNull(settings, "settings");
    if (changes < 1) {
      throw new IllegalArgumentException("changes must be at least 1");
    }
    this.changes = changes;
  }
```

Fields and constructor: the node's settings and the number of changes (at least 1).

```java

  /**
   * Measures every method.
   *
   * @return one line per method, after a header
   * @throws IOException if the node cannot start
   * @throws InterruptedException if interrupted
   */
  public List<String> measure() throws IOException, InterruptedException {
    List<String> lines = new ArrayList<>();
    lines.add(
        String.format(
            Locale.ROOT,
            "%-13s %18s %18s %16s   (%d changes, one at a time, one client)",
            "method",
            "client->server B",
            "server->client B",
            "total B/change",
            changes));
    for (DeliveryMethod method : DeliveryMethod.values()) {
      lines.add(measure(method));
    }
    return List.copyOf(lines);
  }
```

`measure()` builds the table: a header made with `String.format` (the column titles and the change count), then one line per delivery method from `measure(method)`.

```java

  private String measure(DeliveryMethod method) throws IOException, InterruptedException {
    try (LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC());
        ByteCountingRelay http = ByteCountingRelay.start(node.httpPort());
        ByteCountingRelay socket = ByteCountingRelay.start(node.webSocketPort())) {
```

`measure(method)` starts a fresh node and two relays, one pointing at the node's HTTP port and one at its WebSocket port, in a try-with-resources: all three close at the end.

```java
      ClientFleet fleet =
          ClientFleet.start(
              new FleetPlan(
                  method,
                  1,
                  new FeedEndpoints(http.port(), socket.port()),
                  1,
                  new FleetPlan.Timeouts(WAIT, WAIT.multipliedBy(4), WAIT.multipliedBy(9)),
                  new FleetPlan.BackoffRange(Duration.ofMillis(50), Duration.ofSeconds(1)),
                  changes,
                  1));
```

It starts a one-client fleet whose endpoints are the **relays' ports**, so everything the client sends and receives goes through the counters. The timeouts are 10 s, 40 s and 90 s; the backoff 50 ms to 1 s; the delay array holds `changes` delays; the seed is 1.

```java
      try {
        awaitTrue(
            () -> node.parts().metrics().openConnections(method) == 1 && fleet.countLoaded() == 1);
```

`awaitTrue` until the server shows exactly one connection of this method and the client has loaded the seat map.

```java
        // Setup (the seat map, the handshake) is not part of the cost of one update.
        http.reset();
        socket.reset();
        SeatFeed feed = node.parts().feed();
```

The comment: set-up bytes (seat map, handshake) are not the cost of one update, so both relays are reset. `feed` is the node's publisher.

```java
        for (int change = 0; change < changes; change++) {
          node.parts().store().hold(change);
          long sequence = feed.position().sequence();
          awaitTrue(() -> fleet.countAtLeast(sequence) == 1);
        }
```

For each change: `node.parts().store().hold(change)` holds seat number `change` in the store (this makes one seat change), `feed.position().sequence()` is its number, and the probe waits until the client has applied it.

```java
        // The next long poll is already on its way when the last change arrives; let it land.
        awaitTrue(() ->
            method != DeliveryMethod.LONG_POLLING
                || node.parts().metrics().openConnections(method) == 1);
```

The comment says the next long poll is already on its way when the last change arrives. For long polling the probe waits until the server shows one open connection again, so that request is counted too.

```java
        long up = http.bytesTowardServer() + socket.bytesTowardServer();
        long down = http.bytesTowardClient() + socket.bytesTowardClient();
        return String.format(
            Locale.ROOT,
            "%-13s %18.1f %18.1f %16.1f",
            method.label(),
            up / (double) changes,
            down / (double) changes,
            (up + down) / (double) changes);
```

`up` is the bytes toward the server over both relays; `down` the bytes toward the client. The line shows `up / changes`, `down / changes` and the total, each with one decimal.

```java
      } finally {
        fleet.stop();
      }
    }
  }
```

The `finally` stops the fleet.

```java

  private static void awaitTrue(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(POLL);
    }
  }
}
```

`awaitTrue` polls a condition every 2 ms until it holds or 10 s pass.

```java
package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

```

Imports.

```java
/**
 * Prints the bytes on the wire per seat change for each method: {@code ./gradlew -q wireBytes
 * --args='config/wire-bytes.properties 200'}.
 */
public final class WireBytesCli {

  private static final int USAGE = 2;
  private static final int MAX_CHANGES = 100_000;

  private WireBytesCli() {}

```

The comment shows the command. `MAX_CHANGES` is 100,000.

```java
  /**
   * Runs the probe.
   *
   * @param arguments the node's settings file and the number of changes
   * @throws IOException if the file cannot be read or the node fails
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 2
        || !arguments[1].matches("[1-9][0-9]{0,5}")
        || Integer.parseInt(arguments[1]) > MAX_CHANGES) {
      System.err.println("usage: wireBytes <settings file> <changes, 1 to " + MAX_CHANGES + ">");
      System.exit(USAGE);
    }
```

`main` requires exactly two arguments: the settings file and a count. `arguments[1].matches("[1-9][0-9]{0,5}")` checks that the count is a whole number of 1 to 6 digits with no leading zero (a text check **before** converting, so a bad value never reaches `parseInt`), and then the number must not exceed the maximum. Otherwise it prints the usage and exits with code 2.

```java
    switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
        problems.forEach(problem -> System.err.println("settings: " + problem));
        System.exit(USAGE);
      }
```

Read the node's settings; on an error, print each problem and exit.

```java
      case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> {
        System.out.println(MachineLine.describe());
        new WireBytesProbe(settings, Integer.parseInt(arguments[1]))
            .measure()
            .forEach(System.out::println);
      }
    }
  }
}
```

On success: print the machine line, run the probe, print its lines.

### `SlowConsumer.java` and `SlowConsumerCli.java`: a client that never reads

**The problem.** A push server writes to many sockets. If one client stops reading, the kernel buffers on both ends fill, and the server's write to that socket **blocks**. If the server then kept queuing changes for that client, memory would grow without limit. The feed's design: each push connection has a bounded queue; when it is full, the queue is dropped and the client is told to reload; a write that stays blocked longer than the write timeout is cut. This experiment proves it.

**How the slow clients are made.** Two raw sockets are opened by hand: one sends a server-sent-events request, one sends a WebSocket handshake. After sending the request neither ever reads. Each has a tiny receive buffer (1,024 bytes), so its kernel buffer fills quickly and the server's writes block sooner.

Real output from this machine (`slowConsumer config/slow-consumer.properties 10 10`: 10 normal clients, 10 seconds; the README ran 50 clients; numbers differ on every run):

```text
Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process may open 1048576 files
settings: client queue 256 changes, write timeout 2000 ms, on-sale 500 actions per second for 10 s
changes published: 6297
deepest client queue: 256 (bound 256)
queue overflows (client told to reload): 2
stuck writes cut after the write timeout: 2
open connections at the end: sse 10, websocket 0 (the 10 normal clients are sse)
slow clients' connections ended by the server (each read to its end): yes
heap after a collection: 17001 KB before, 18109 KB after
normal clients: delay p50 0.19 ms, p99 0.73 ms, max 11.41 ms (62970 samples); 10 of 10 match the store
```

What each line shows. The deepest queue reached its bound, 256. The queue overflowed twice (one per slow client) and each slow client was told to reload. Two blocked writes were cut after the 2,000 ms write timeout. While the normal clients were still connected, only their 10 connections were open. At the end each slow client read what was left in its buffers and reached the end of the stream: the server, not the client, closed both connections. The heap after a collection barely moved (17,001 KB to 18,109 KB): a slow client costs bounded memory. The normal clients kept a p99 of 0.73 ms and all 10 of 10 ended with the store's map.

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.client.ClientFleet;
import io.plainround.seatwave.live.client.FeedClient;
import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.client.FleetPlan;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatMapSnapshot;
import io.plainround.seatwave.live.websocket.Handshake;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

```

Imports, including `ManagementFactory` for the memory reading and `Handshake` from the WebSocket server (only for its `PATH` constant).

```java
/**
 * The slow-consumer experiment: normal event-stream clients beside one event-stream client and one
 * WebSocket client that connect and then never read, while the on-sale runs fast. It shows the
 * bound: each slow client's queue stops at its capacity, it is told to reload, its blocked write is
 * cut after the write timeout, and the normal clients keep their delay and end with the store's
 * map.
 */
public final class SlowConsumer {

```

The class comment lists what the experiment shows.

```java
  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final Duration POLL = Duration.ofMillis(20);
  // A small receive buffer fills the kernel's buffers sooner, so the server's write blocks sooner.
  private static final int SLOW_RECEIVE_BUFFER_BYTES = 1_024;
  private static final int DELAYS_PER_CLIENT = 100_000;
  // Long enough to read a few hundred kilobytes left in the buffers of a closed connection.
  private static final Duration END_OF_STREAM_WAIT = Duration.ofSeconds(5);

```

Constants: waits of 20 s with 20 ms polling; `SLOW_RECEIVE_BUFFER_BYTES = 1_024` (the comment explains: a small receive buffer fills the kernel's buffers sooner); 100,000 delay slots per normal client; and `END_OF_STREAM_WAIT`, 5 seconds, the longest the experiment spends reading what is left in one slow client's buffers at the end.

```java
  private final LiveSettings settings;
  private final int normalClients;
  private final Duration runTime;

  /**
   * Creates the experiment.
   *
   * @param settings the node's settings (a small client queue and a short write timeout show the
   *     bound quickly)
   * @param normalClients how many clients read normally
   * @param runTime how long the on-sale runs with the slow clients connected
   */
  public SlowConsumer(LiveSettings settings, int normalClients, Duration runTime) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.normalClients = normalClients;
    this.runTime = Objects.requireNonNull(runTime, "runTime");
  }
```

Fields and constructor. The Javadoc says to use settings with a small client queue and a short write timeout (`config/slow-consumer.properties` has 256 and 2,000 ms).

```java

  /**
   * Runs it.
   *
   * @return the report's lines
   * @throws IOException if the node cannot start
   * @throws InterruptedException if interrupted
   */
  public List<String> run() throws IOException, InterruptedException {
    try (LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC())) {
      FeedEndpoints endpoints = new FeedEndpoints(node.httpPort(), node.webSocketPort());
```

`run()` starts a node, builds the endpoints, and starts a fleet of normal event-stream clients from `plan(endpoints)`. The next try-with-resources opens the two slow sockets and closes them at the end.

```java
      ClientFleet fleet = ClientFleet.start(plan(endpoints));
      try (Socket slowStream = openSlowStream(node);
          Socket slowSocket = openSlowSocket(node)) {
        awaitTrue(() -> fleet.countLoaded() == normalClients);
        long heapBefore = settledHeap();
        fleet.record(true);
        node.startOnSale();
        Thread.sleep(runTime);
        long heapAfter = settledHeap();
        // Pausing waits for a tick already running, so no change can follow the snapshot.
        node.parts().simulator().pause();
        SeatMapSnapshot store = node.parts().store().snapshot();
        awaitTrue(() -> fleet.countAtLeast(store.position().sequence()) == normalClients);
        // Read while the normal clients are still connected, so the open counts show who is left.
        Map<String, Long> metrics = metrics(node);
        fleet.stop();
```

The steps: wait until all normal clients are loaded; read the heap after a collection (`settledHeap`); switch recording on and **start the on-sale** (`node.startOnSale()`), which is fast here (500 actions per second); sleep for the run time; read the heap again; pause the simulator; take the store's snapshot; wait until the normal clients reach its sequence; read the server's metrics while the normal clients are still connected; stop them. The comment before `pause()` says why the snapshot is final: `pause` waits for a tick that is already running (`java-feed-walkthrough.md`). In version 1.0.0 a tick could still change a seat after `pause` returned, so the snapshot could be one change old. The metrics are read before `fleet.stop()` so that the "open connections at the end" line counts the connections that are still open because they are healthy, not the ones the server has not yet noticed closing.

```java
        return report(
            metrics,
            fleet,
            store,
            heapBefore,
            heapAfter,
            endedByServer(slowStream) && endedByServer(slowSocket));
      } finally {
        fleet.stop();
      }
    }
  }
```

Build the report. `endedByServer(slowStream) && endedByServer(slowSocket)` feeds the line about the slow clients: true only if the server ended both connections. Version 1.0.0 passed `slowStream.isConnected() && slowSocket.isConnected()` and printed "slow clients' own sockets still open on their side". `isConnected()` only says that the socket was once connected; it stays true after the other side closes, so the line said "yes" whatever happened. `finally` stops the fleet again; `ClientFleet.stop` does its work only the first time, so this second call returns at once.

```java

  private List<String> report(
      Map<String, Long> metrics,
      ClientFleet fleet,
      SeatMapSnapshot store,
      long heapBefore,
      long heapAfter,
      boolean slowClientsCutByServer) {
    long correct =
        fleet.clients().stream()
            .filter(client -> client.map().states().equals(store.states()))
            .count();
    long[] delays =
        fleet.clients().stream()
            .map(FeedClient::stats)
            .flatMapToLong(stats -> Arrays.stream(stats.delaysMicros()))
            .toArray();
    LatencySummary delay = LatencySummary.ofMicros(delays);
```

`report(...)`: read the server's metrics into a map, count the normal clients whose map equals the store's, collect all delays and summarize them.

```java
    List<String> lines = new ArrayList<>();
    lines.add(
        String.format(
            Locale.ROOT,
            "settings: client queue %d changes, write timeout %d ms, on-sale %d actions per second for %d s",
            settings.limits().clientQueueCapacity(),
            settings.timing().writeTimeout().toMillis(),
            settings.onSale().actionsPerSecond(),
            runTime.toSeconds()));
```

The first report line repeats the settings in use (queue bound, write timeout, on-sale rate, seconds).

```java
    lines.add(
        String.format(
            Locale.ROOT,
            "changes published: %d",
            metrics.get("seatwave_feed_changes_published_total")));
    lines.add(
        String.format(
            Locale.ROOT,
            "deepest client queue: %d (bound %d)",
            metrics.get("seatwave_feed_deepest_client_queue"),
            settings.limits().clientQueueCapacity()));
    lines.add(
        String.format(
            Locale.ROOT,
            "queue overflows (client told to reload): %d",
            metrics.get("seatwave_feed_queue_overflows_total")));
    lines.add(
        String.format(
            Locale.ROOT,
            "stuck writes cut after the write timeout: %d",
            metrics.get("seatwave_feed_stuck_writer_disconnects_total")));
    lines.add(
        String.format(
            Locale.ROOT,
            "open connections at the end: sse %d, websocket %d (the %d normal clients are sse)",
            metrics.get("seatwave_feed_open_connections{method=\"sse\"}"),
            metrics.get("seatwave_feed_open_connections{method=\"websocket\"}"),
            normalClients));
```

The next lines come straight from the metrics: changes published, the deepest client queue seen (against its bound), queue overflows (`seatwave_feed_queue_overflows_total`: a client told to reload), stuck writes cut (`seatwave_feed_stuck_writer_disconnects_total`), and the open SSE and WebSocket connections at the end, read from the metrics taken before the normal clients stopped. `metrics.get(...)` looks a sample up by its full name, labels included; `\"` writes a double quote inside a Java string.

```java
    lines.add(
        String.format(
            Locale.ROOT,
            "slow clients' connections ended by the server (each read to its end): %s",
            slowClientsCutByServer ? "yes" : "no"));
    lines.add(
        String.format(
            Locale.ROOT,
            "heap after a collection: %d KB before, %d KB after",
            heapBefore / 1_024,
            heapAfter / 1_024));
```

The slow clients' sockets line, and the heap before and after in KB (`bytes / 1_024`).

```java
    lines.add(
        String.format(
            Locale.ROOT,
            "normal clients: delay p50 %.2f ms, p99 %.2f ms, max %.2f ms (%d samples); %d of %d match the store",
            delay.p50Millis(),
            delay.p99Millis(),
            delay.maxMillis(),
            delay.count(),
            correct,
            normalClients));
    return List.copyOf(lines);
  }
```

The last line: p50, p99 and max of the normal clients' delays, the sample count, and how many of the normal clients match the store.

```java

  private FleetPlan plan(FeedEndpoints endpoints) {
    return new FleetPlan(
        DeliveryMethod.SERVER_SENT_EVENTS,
        normalClients,
        endpoints,
        normalClients,
        new FleetPlan.Timeouts(
            WAIT, WAIT.multipliedBy(2), settings.timing().heartbeat().multipliedBy(3)),
        new FleetPlan.BackoffRange(Duration.ofMillis(100), Duration.ofSeconds(2)),
        DELAYS_PER_CLIENT,
        1);
  }
```

`plan(endpoints)`: a fleet of `normalClients` SSE clients, all started at once (connects per second equals the client count), with 20 s, 40 s and three-heartbeat timeouts, a 100 ms to 2 s backoff, 100,000 delays each, seed 1.

```java

  // An event-stream request sent by hand, after which nothing is ever read.
  private Socket openSlowStream(LiveFeedNode node) throws IOException {
    Socket socket = slowSocket(node.httpPort());
    FeedPosition now = node.parts().feed().position();
    send(
        socket,
        "GET /v1/events/1/seat-changes/stream?after="
            + now.written()
            + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n");
    return socket;
  }
```

`openSlowStream`: open a socket with the tiny buffer, read the feed's current position, and send by hand a text request: `GET /v1/events/1/seat-changes/stream?after=<position> HTTP/1.1`, a `Host` header and `Accept: text/event-stream`, ending with a blank line (`\r\n\r\n`; `\r\n` is a carriage return and a line feed, the line ending HTTP uses). Then it never reads.

```java

  // A WebSocket handshake sent by hand, after which nothing is ever read (not even the 101).
  private Socket openSlowSocket(LiveFeedNode node) throws IOException {
    Socket socket = slowSocket(node.webSocketPort());
    FeedPosition now = node.parts().feed().position();
    send(
        socket,
        "GET "
            + Handshake.PATH
            + "?after="
            + now.written()
            + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
    return socket;
  }
```

`openSlowSocket`: the same for a WebSocket: `GET <path>?after=<position>` with `Upgrade: websocket`, `Connection: Upgrade`, a `Sec-WebSocket-Key` and `Sec-WebSocket-Version: 13`. The key `dGhlIHNhbXBsZSBub25jZQ==` is the example value from RFC 6455. The comment notes that it does not even read the `101` answer.

```java

  private static Socket slowSocket(int port) throws IOException {
    Socket socket = new Socket();
    socket.setReceiveBufferSize(SLOW_RECEIVE_BUFFER_BYTES);
    socket.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    return socket;
  }
```

`slowSocket(port)`: an unconnected `Socket`, then `setReceiveBufferSize(1_024)` **before** connecting (the buffer size must be set first to take effect), then `connect` to loopback.

```java

  // The slow client finally reads what is left in its buffers: the end of the stream, or a reset,
  // proves the server closed the connection. A connection still open keeps the reads waiting (or
  // receiving heartbeats) until the deadline.
  private static boolean endedByServer(Socket socket) throws IOException {
    socket.setSoTimeout((int) END_OF_STREAM_WAIT.toMillis());
    InputStream in = socket.getInputStream();
    byte[] chunk = new byte[SLOW_RECEIVE_BUFFER_BYTES];
    long deadlineNanos = System.nanoTime() + END_OF_STREAM_WAIT.toNanos();
    try {
      // Only the end matters; the bytes the client never read are dropped here.
      while (System.nanoTime() < deadlineNanos) {
        if (in.read(chunk) < 0) {
          return true;
        }
      }
      return false;
    } catch (SocketTimeoutException stillOpen) {
      return false;
    } catch (SocketException reset) {
      // A reset is the server closing a connection that still had unread bytes in flight.
      return true;
    }
  }

  private static void send(Socket socket, String request) throws IOException {
    OutputStream out = socket.getOutputStream();
    out.write(request.getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }
```

`endedByServer(socket)` is how the experiment proves, from the slow client's side, that the server closed the connection. At the end the slow client finally reads. `setSoTimeout` makes each `read` give up after 5 seconds with a `SocketTimeoutException`. The loop reads 1,024 bytes at a time and throws them away: they are the changes and heartbeats that waited in the buffers. `read` returns -1 when it reaches the end of the stream, which happens only after the other side closed the connection, so that is a "yes". A `SocketException` (usually "Connection reset") also means the server closed the connection, with bytes still unread; also "yes". A read that times out, or a connection that keeps sending until the 5-second deadline (an open event stream sends a heartbeat every heartbeat interval), means the connection is still open: "no".

`send(socket, request)` writes the text as ASCII bytes and flushes.

```java

  private static Map<String, Long> metrics(LiveFeedNode node) {
    Map<String, Long> values = new java.util.HashMap<>();
    node.parts()
        .metrics()
        .render()
        .lines()
        .forEach(line -> {
          int space = line.lastIndexOf(' ');
          values.put(line.substring(0, space), Long.parseLong(line.substring(space + 1)));
        });
    return values;
  }
```

`metrics(node)` reads the node's metrics text directly from its object (`render()`; there is no HTTP here because the node is in this process) and parses each line into a name and a `long`. `run` calls it once, before the normal clients stop, and passes the map to `report`.

```java

  private static long settledHeap() {
    ManagementFactory.getMemoryMXBean().gc();
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }
```

`settledHeap()`: `ManagementFactory.getMemoryMXBean().gc()` asks the JVM for a full collection; then `getHeapMemoryUsage().getUsed()` is the heap in use. Reading after a collection leaves out garbage that is not yet cleared.

```java

  private static void awaitTrue(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(POLL);
    }
  }
}
```

`awaitTrue` polls a condition every 20 ms for up to 20 s.

```java
package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

```

Imports.

```java
/**
 * Runs the slow-consumer experiment: {@code ./gradlew -q slowConsumer
 * --args='config/slow-consumer.properties 50 10'} (the node's settings, the normal clients, the
 * seconds to run).
 */
public final class SlowConsumerCli {

  private static final int USAGE = 2;
  private static final String SMALL_NUMBER = "[1-9][0-9]{0,3}";

  private SlowConsumerCli() {}
```

The comment shows the command and its three arguments. `SMALL_NUMBER` is the pattern `[1-9][0-9]{0,3}`: a whole number from 1 to 9,999.

```java

  /**
   * Runs the experiment.
   *
   * @param arguments the settings file, the normal clients (1 to 9,999), the seconds (1 to 9,999)
   * @throws IOException if the file cannot be read or the node fails
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 3
        || !arguments[1].matches(SMALL_NUMBER)
        || !arguments[2].matches(SMALL_NUMBER)) {
      System.err.println("usage: slowConsumer <settings file> <normal clients> <seconds>");
      System.exit(USAGE);
    }
```

Checks that there are three arguments and that the second and third match; otherwise usage and exit code 2.

```java
    switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
        problems.forEach(problem -> System.err.println("settings: " + problem));
        System.exit(USAGE);
      }
      case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> {
        System.out.println(MachineLine.describe());
        new SlowConsumer(
                settings,
                Integer.parseInt(arguments[1]),
                Duration.ofSeconds(Integer.parseInt(arguments[2])))
            .run()
            .forEach(System.out::println);
      }
    }
  }
}
```

The same switch on the settings result as the other tools. On success it prints the machine line, builds a `SlowConsumer` with the normal clients and the seconds (`Duration.ofSeconds`), runs it and prints each line.

### `PollingRates.java` and `PollingRatesCli.java`: Lab 1

Lab 1 is arithmetic, not a measurement. It compares how many requests the three approaches make for 200,000 fans.

- **Short polling** asks every fixed interval, always: requests per second = fans / interval.
- **Long polling** sends the next request one round trip after the previous reply, and the server answers at the next change or at the end of the hold, whichever comes first.
- **Push** sends one message per fan per change.

**The Poisson idea.** If changes arrive at random with an average rate r per second, the chance that an interval of t seconds holds no change is `e^(-r * t)`. Here `e` is Euler's number (about 2.718). With 1 change a minute (r = 1/60) and a 5-second interval, the chance is `e^(-5/60)`, about 92 percent, matching the table below: 92 percent of short polls find nothing.

Real output (this is a calculation, so it is the same on every run):

```text
200,000 fans; short poll every 5000 ms; long poll held up to 25000 ms; round trip 80 ms
                                     busy on-sale  quiet evening
seat changes per second                 3,000.000          0.017
short polling, requests per second         40,000         40,000
short polls that find nothing, %              0.0           92.0
long polling, requests per second       2,489,627          9,744
long polling, connections held            200,000        200,000
push, messages per second             600,000,000          3,333
push, connections held                    200,000        200,000
```

Reading it. On a quiet evening push is cheapest (3,333 messages a second, against 40,000 short polls). During a busy on-sale a constant stream of changes makes long polling re-ask after every reply, 2.49 million requests a second, while push sends 600 million messages a second: one change times 200,000 fans times 3,000 changes. The point of the module is that **no method is free**; at that load the answer is batching and fan-out design, which later modules cover.

```java
package io.plainround.seatwave.live.bench;

import java.time.Duration;
import java.util.Objects;

```

Imports.

```java
/**
 * Lab 1's arithmetic: the request rate of short polling and of long polling for many fans, and the
 * messages a push method sends instead. Changes are assumed to arrive at random, at a steady
 * average rate (a Poisson process), so the chance that an interval of t seconds holds no change is
 * e^(−rate × t).
 *
 * @param fans the clients watching
 * @param pollInterval how often a short-polling client asks
 * @param roundTrip the network round trip of one request (Module 5)
 * @param hold how long the server holds a long poll with no news
 */
public record PollingRates(int fans, Duration pollInterval, Duration roundTrip, Duration hold) {
```

The comment explains the Poisson assumption. `public record PollingRates(int fans, Duration pollInterval, Duration roundTrip, Duration hold)`: the four inputs.

```java

  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  /** Checks the fields. */
  public PollingRates {
    if (fans < 1) {
      throw new IllegalArgumentException("fans must be at least 1");
    }
    for (Duration duration :
        java.util.List.of(
            Objects.requireNonNull(pollInterval, "pollInterval"),
            Objects.requireNonNull(roundTrip, "roundTrip"),
            Objects.requireNonNull(hold, "hold"))) {
      if (duration.isNegative() || duration.isZero()) {
        throw new IllegalArgumentException("every duration must be positive");
      }
    }
  }
```

`NANOS_PER_SECOND` converts durations to seconds. The constructor requires at least one fan and checks every duration is positive: `java.util.List.of(requireNonNull(...), ...)` makes a list of the three, and a loop over it rejects zero or negative values.

```java

  /**
   * Short polling: every fan asks once per interval, whatever happens.
   *
   * @return requests per second
   */
  public double shortPollingRequestsPerSecond() {
    return fans / seconds(pollInterval);
  }
```

Short polling: `fans / seconds(pollInterval)`. With 200,000 fans and 5 s, 40,000 requests per second, whatever the changes.

```java

  /**
   * The share of short polls that find nothing new.
   *
   * @param changesPerSecond the average rate of seat changes
   * @return a number from 0 to 1
   */
  public double emptyShare(double changesPerSecond) {
    return Math.exp(-changesPerSecond * seconds(pollInterval));
  }
```

`emptyShare(changesPerSecond)` is `Math.exp(-rate * seconds(interval))`: the share of polls that find nothing.

```java

  /**
   * Long polling: each fan's next request leaves one round trip after the previous one was
   * answered, and the server answers at the next change or at the end of the hold, whichever is
   * first. The expected wait for the next change, cut at the hold, is (1 − e^(−rate × hold)) ÷
   * rate.
   *
   * @param changesPerSecond the average rate of seat changes
   * @return requests per second, all fans together
   */
  public double longPollingRequestsPerSecond(double changesPerSecond) {
    double holdSeconds = seconds(hold);
    double expectedWait = (1 - Math.exp(-changesPerSecond * holdSeconds)) / changesPerSecond;
    return fans / (expectedWait + seconds(roundTrip));
  }
```

Long polling. The expected wait for the next change, cut off at the hold, is `(1 - e^(-r*hold)) / r`. Each fan makes one request per `expectedWait + roundTrip`, so the total rate is `fans / (expectedWait + roundTrip)`. The comment above the method states the formula.

```java

  /**
   * A push method that sends every change to every fan.
   *
   * @param changesPerSecond the average rate of seat changes
   * @return messages per second, all fans together
   */
  public double pushMessagesPerSecond(double changesPerSecond) {
    return fans * changesPerSecond;
  }
```

Push: every change goes to every fan, `fans * changesPerSecond`.

```java

  private static double seconds(Duration duration) {
    return duration.toNanos() / NANOS_PER_SECOND;
  }
}
```

`seconds(duration)` converts a `Duration` to a `double` number of seconds.

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

```

Imports.

```java
/**
 * Lab 1: prints the request rates of short and long polling against a push method, for a busy
 * on-sale and a quiet evening: {@code ./gradlew -q pollingRates
 * --args='config/polling-rates.properties'}.
 */
public final class PollingRatesCli {

  private static final int USAGE = 2;
  private static final int MAX_FANS = 100_000_000;
  private static final int MAX_MILLIS = 3_600_000;
  private static final int MAX_CHANGES_PER_MINUTE = 10_000_000;
  private static final double SECONDS_PER_MINUTE = 60.0;
  private static final double PERCENT = 100.0;

  private PollingRatesCli() {}
```

The comment shows the command. Constants: upper bounds for each setting (100 million fans, 3,600,000 ms, 10 million changes per minute), `SECONDS_PER_MINUTE` and `PERCENT`.

```java

  /**
   * Prints the table.
   *
   * @param arguments the settings file
   * @throws IOException if it cannot be read
   */
  public static void main(String[] arguments) throws IOException {
    if (arguments.length != 1) {
      System.err.println("usage: pollingRates <settings file>");
      System.exit(USAGE);
    }
    SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
    List<String> lines = report(reader);
    if (!reader.problems().isEmpty()) {
      reader.problems().forEach(problem -> System.err.println("settings: " + problem));
      System.exit(USAGE);
    }
    lines.forEach(System.out::println);
  }
```

`main` needs one argument, loads the properties, calls `report(reader)` and then looks at `reader.problems()`: if any, prints them and exits with code 2; else prints the lines. (Problems are checked after `report` because `report` reads all keys first.)

```java

  /**
   * Builds the table from the settings.
   *
   * @param reader the settings; problems are left in it for the caller
   * @return the lines, or an empty list if a setting is missing or invalid
   */
  public static List<String> report(SettingsReader reader) {
    int fans = reader.intBetween("rates.fans", 1, MAX_FANS);
    Duration interval = reader.millisBetween("rates.pollIntervalMillis", 1, MAX_MILLIS);
    Duration roundTrip = reader.millisBetween("rates.roundTripMillis", 1, MAX_MILLIS);
    Duration hold = reader.millisBetween("rates.longPollHoldMillis", 1, MAX_MILLIS);
    int busyPerMinute = reader.intBetween("rates.busyChangesPerMinute", 1, MAX_CHANGES_PER_MINUTE);
    int quietPerMinute =
        reader.intBetween("rates.quietChangesPerMinute", 1, MAX_CHANGES_PER_MINUTE);
    if (!reader.problems().isEmpty()) {
      return List.of();
    }
    PollingRates rates = new PollingRates(fans, interval, roundTrip, hold);
```

`report(reader)` reads the six keys with bounds (`rates.fans`, `rates.pollIntervalMillis`, `rates.roundTripMillis`, `rates.longPollHoldMillis`, `rates.busyChangesPerMinute`, `rates.quietChangesPerMinute`). If any problem was recorded it returns an empty list.

```java
    List<String> lines = new ArrayList<>();
    lines.add(
        String.format(
            Locale.ROOT,
            "%,d fans; short poll every %d ms; long poll held up to %d ms; round trip %d ms",
            fans,
            interval.toMillis(),
            hold.toMillis(),
            roundTrip.toMillis()));
```

It builds `PollingRates` and the first line. `%,d` prints a whole number with thousands separators.

```java
    lines.add(String.format(Locale.ROOT, "%-34s %14s %14s", "", "busy on-sale", "quiet evening"));
    double busy = busyPerMinute / SECONDS_PER_MINUTE;
    double quiet = quietPerMinute / SECONDS_PER_MINUTE;
    lines.add(row("seat changes per second", busy, quiet, "%,14.3f"));
```

The header line with `busy on-sale` and `quiet evening`; then the changes per second: `busyPerMinute / 60.0` (180,000 a minute is 3,000 a second) and `quietPerMinute / 60.0` (1 a minute is 0.017). The row uses `%,14.3f`: three decimals, thousands separators, 14 characters wide.

```java
    lines.add(
        row(
            "short polling, requests per second",
            rates.shortPollingRequestsPerSecond(),
            rates.shortPollingRequestsPerSecond(),
            "%,14.0f"));
    lines.add(
        row(
            "short polls that find nothing, %",
            PERCENT * rates.emptyShare(busy), PERCENT * rates.emptyShare(quiet), "%14.1f"));
    lines.add(
        row(
            "long polling, requests per second",
            rates.longPollingRequestsPerSecond(busy),
            rates.longPollingRequestsPerSecond(quiet),
            "%,14.0f"));
    lines.add(row("long polling, connections held", fans, fans, "%,14.0f"));
    lines.add(
        row(
            "push, messages per second",
            rates.pushMessagesPerSecond(busy),
            rates.pushMessagesPerSecond(quiet),
            "%,14.0f"));
    lines.add(row("push, connections held", fans, fans, "%,14.0f"));
    return List.copyOf(lines);
  }
```

One row per quantity, for the busy and the quiet case: short polling, the share of empty polls (as a percent), long polling requests, connections held (always the fans), push messages, push connections.

```java

  private static String row(String label, double busy, double quiet, String cell) {
    return String.format(Locale.ROOT, "%-34s " + cell + " " + cell, label, busy, quiet);
  }
}
```

`row(label, busy, quiet, cell)` builds a line: the label left-aligned in 34 characters, then two cells with the same format.

### `SseResume.java` and `SseResumeCli.java`: Lab 2

Lab 2 follows one event-stream client through two kinds of trouble and shows both end in a correct map (real output is in `java-client-walkthrough.md`).

- **Scenario 1.** The server drops the client's stream twice while the on-sale runs. The client reconnects with `Last-Event-ID`, the replay buffer (200 changes) still has what it missed, and it receives each change exactly once: `applied 150, duplicates 0, gaps 0, reloads 0, reconnects 2`.
- **Scenario 2.** The client is stopped, 500 holds happen (more than the buffer's 200), and the client starts again. The server cannot replay, so it sends a reload; the client loads the seat map and follows on: `reloads 1`, and the map equals the store.

```java
package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.client.Backoff;
import io.plainround.seatwave.live.client.ClientStats;
import io.plainround.seatwave.live.client.EventStreamConnection;
import io.plainround.seatwave.live.client.FeedClient;
import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.client.SeatMapReader;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.SeatMapSnapshot;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

```

Imports, including the client classes that this lab assembles by hand.

```java
/**
 * Lab 2: one server-sent events client, resumed with {@code Last-Event-ID}. In the first scenario
 * the server drops the stream twice while the on-sale runs, inside the replay buffer's window; the
 * client resumes and ends with every change exactly once. In the second, the client is away while
 * more changes happen than the buffer holds; the server tells it to reload, and it still ends with
 * the store's map.
 */
public final class SseResume {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL = Duration.ofMillis(10);
  private static final Duration BETWEEN_DROPS = Duration.ofSeconds(1);
  private static final int DROPS = 2;
  private static final int DELAYS = 100_000;

```

The comment describes the two scenarios. Constants: a 10 s timeout, 10 ms polling, 1 s between drops, 2 drops, and 100,000 delay slots.

```java
  private final LiveSettings settings;
  private final int changesWhileAway;

  /**
   * Creates the lab run.
   *
   * @param settings the node's settings; the replay capacity must be below {@code changesWhileAway}
   * @param changesWhileAway how many changes the second scenario makes while the client is away
   */
  public SseResume(LiveSettings settings, int changesWhileAway) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.changesWhileAway = changesWhileAway;
  }
```

Fields and constructor. The Javadoc notes that the replay capacity must be below `changesWhileAway`, or scenario 2 would not overflow.

```java

  /**
   * Runs both scenarios.
   *
   * @return the report's lines
   * @throws IOException if the node cannot start
   * @throws InterruptedException if interrupted
   */
  public List<String> run() throws IOException, InterruptedException {
    List<String> lines = new ArrayList<>();
    lines.add(
        String.format(
            Locale.ROOT,
            "replay buffer: %d changes; on-sale %d actions per second",
            settings.limits().replayCapacity(),
            settings.onSale().actionsPerSecond()));
```

`run()` starts with the first report line: the replay buffer size and the on-sale rate, read from the settings.

```java
    try (LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC());
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      FeedClient client = client(node, http);
      Thread follower = Thread.ofVirtual().start(client);
      awaitTrue(() -> client.map().isLoaded());
      node.startOnSale();
```

Start a node and an HTTP client in a try-with-resources. Build one `FeedClient` (below) and run it on a virtual thread (`Thread.ofVirtual().start(client)`). Wait until it has loaded its map, then start the on-sale.

```java
      for (int drop = 0; drop < DROPS; drop++) {
        Thread.sleep(BETWEEN_DROPS);
        lines.add(
            String.format(
                Locale.ROOT,
                "drop %d: the server closed %d stream(s) at sequence %d",
                drop + 1,
                node.parts().limit().dropAll(),
                node.parts().feed().position().sequence()));
      }
      Thread.sleep(BETWEEN_DROPS);
      node.parts().simulator().pause();
      lines.add(result("scenario 1, resumed inside the buffer", node, client, follower));
```

Two drops. After each 1-second sleep, `node.parts().limit().dropAll()` closes every push connection (returning the count) and the line reports the feed's sequence. After a last second, pause the on-sale and print scenario 1's result.

```java
      // Scenario 2: the client is away while more changes happen than the buffer holds.
      for (int seat = 0, made = 0;
          made < changesWhileAway && seat < settings.layout().seatCount();
          seat++) {
        if (!node.parts().store().hold(seat).isError()) {
          made++;
        }
      }
```

Scenario 2. The loop holds seats directly in the store, one after another, until `changesWhileAway` holds have succeeded. `store.hold(seat)` returns a result4j `Result`; `.isError()` is true for a refused hold, which is not counted.

```java
      lines.add(
          String.format(
              Locale.ROOT,
              "while the client was away: %d holds, feed now at sequence %d",
              changesWhileAway,
              node.parts().feed().position().sequence()));
```

Report how many holds were made and where the feed is now.

```java
      Thread back = Thread.ofVirtual().start(client);
      lines.add(result("scenario 2, away longer than the buffer", node, client, back));
    }
    return List.copyOf(lines);
  }
```

Start the **same client again** on a new thread (`back`). Its map is old, so it follows from its old position and the server answers with a reload. Print scenario 2's result.

```java

  private String result(String scenario, LiveFeedNode node, FeedClient client, Thread follower)
      throws InterruptedException {
    SeatMapSnapshot store = node.parts().store().snapshot();
    awaitTrue(() -> client.map().sequence() >= store.position().sequence());
    follower.interrupt();
    follower.join(TIMEOUT);
    ClientStats stats = client.stats();
    return String.format(
        Locale.ROOT,
        "%s: applied %d, duplicates %d, gaps %d, reloads %d, reconnects %d; client at %s, store at %s; map equals store: %s",
        scenario,
        stats.changesApplied(),
        stats.duplicates(),
        stats.gaps(),
        stats.reloads(),
        stats.reconnects(),
        client.map().position().written(),
        store.position().written(),
        client.map().states().equals(store.states()) ? "yes" : "no");
  }
```

`result(...)` takes the store's snapshot, waits until the client's sequence has reached it, interrupts the client thread and waits up to 10 s for it to end (`follower.join`), then formats the client's counters and compares `client.map().states()` with `store.states()`.

```java

  private static FeedClient client(LiveFeedNode node, HttpClient http) {
    FeedEndpoints endpoints = new FeedEndpoints(node.httpPort(), node.webSocketPort());
    FeedJson json = new FeedJson();
    return new FeedClient(
        new EventStreamConnection(http, endpoints, json, TIMEOUT),
        new SeatMapReader(http, endpoints, json, TIMEOUT),
        new Backoff(Duration.ofMillis(50), Duration.ofSeconds(1), 1),
        new AtomicBoolean(),
        new ClientStats(DELAYS));
  }
```

`client(node, http)` builds the lab's client: an `EventStreamConnection`, a `SeatMapReader`, a `Backoff` from 50 ms to 1 s with seed 1, a fresh `AtomicBoolean` (recording off) and `ClientStats`.

```java

  private static void awaitTrue(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(POLL);
    }
  }
}
```

`awaitTrue` polls every 10 ms for up to 10 s.

```java
package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

```

Imports.

```java
/**
 * Lab 2: {@code ./gradlew -q sseResume --args='config/sse-resume.properties 500'} (the node's
 * settings and the changes made while the client is away).
 */
public final class SseResumeCli {

  private static final int USAGE = 2;

  private SseResumeCli() {}

```

The comment shows the command.

```java
  /**
   * Runs the lab.
   *
   * @param arguments the settings file and the changes made while away (1 to 9,999)
   * @throws IOException if the file cannot be read or the node fails
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 2 || !arguments[1].matches("[1-9][0-9]{0,3}")) {
      System.err.println("usage: sseResume <settings file> <changes while away>");
      System.exit(USAGE);
    }
    switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
        problems.forEach(problem -> System.err.println("settings: " + problem));
        System.exit(USAGE);
      }
      case Result.Success<LiveSettings, List<String>>(LiveSettings settings) ->
          new SseResume(settings, Integer.parseInt(arguments[1]))
              .run()
              .forEach(System.out::println);
    }
  }
}
```

`main` checks two arguments, the second a whole number from 1 to 9,999, then switches on the settings result as the others do and runs the lab, printing its lines.

## Try it yourself

All commands ran in a copy of the repository on this machine (Eclipse Temurin 25.0.2, Apple silicon, 12 processors). **Numbers differ on every run**; the README's numbers came from the full 1,000-client comparison, which I did not rerun for these notes.

1. Lab 1, the arithmetic (about 2 seconds):

```text
./gradlew -q pollingRates --args='config/polling-rates.properties'
```

The output is the table shown above.

2. Bytes per change:

```text
./gradlew -q wireBytes --args='config/wire-bytes.properties 50'
```

The output is the table shown above.

3. The slow consumer, 10 normal clients for 10 seconds:

```text
./gradlew -q slowConsumer --args='config/slow-consumer.properties 10 10'
```

The output is shown above. With only 5 seconds on the same settings the queue had not yet filled (`deepest client queue: 11 (bound 256)`, `queue overflows: 0`), so the run time matters.

4. The shortest comparison, which shows every idea of the file: 50 clients per method, 1 run, 1 s warm-up, 3 s measure. Copy `config/compare.properties` to `config/compare-small.properties`, change `compare.clients=50`, `compare.runs=1`, `compare.warmupSeconds=1`, `compare.measureSeconds=3`, and run:

```text
./gradlew -q compare --args='config/compare-small.properties'
```

It took about 16 seconds. Real output:

```text
Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process may open 1048576 files
feed in its own JVM (fixed, pre-touched heap of 1024 MB); 50 clients per method, 1 run(s) each; warm-up 1 s, measured 3 s; on-sale 50 actions per second over 20000 seats
method       run  conn changes/s  deliver/s     req/s   p50 ms   p99 ms   max ms  cores  us/del   heap/c    rss/c pthr vthr  fds recov ms reconn reload gaps correct
long-polling   1    50     49.8     2491.0    2491.0     0.91     8.67    12.56  0.638   256.2    69960    44054   19   51   72      62      0     0    0      50
sse            1    50     50.2     2511.6       0.0     0.77     4.44     5.46  0.291   115.8    79675    32039   21   51   72     112     50     0    0      50
websocket      1    50     50.2     2508.1       0.0     0.62     2.98     9.83  0.278   111.0    23200    20024   23   51   78     123     50     0    0      50
median of the runs:
long-polling med    50     49.8     2491.0    2491.0     0.91     8.67    12.56  0.638   256.2    69960    44054   19   51   72      62      0     0    0      50
sse          med    50     50.2     2511.6       0.0     0.77     4.44     5.46  0.291   115.8    79675    32039   21   51   72     112     50     0    0      50
websocket    med    50     50.2     2508.1       0.0     0.62     2.98     9.83  0.278   111.0    23200    20024   23   51   78     123     50     0    0      50
csv: build/compare/live-feed-comparison.csv
```

Read it by column. `conn` is how many clients had loaded the map (50 of 50). `changes/s` is the store's rate (about 50). `deliver/s` is about 2,500 (50 changes times 50 clients). `req/s` is nonzero only for long polling: each reply starts a new request. `p50 ms`, `p99 ms` and `max ms` are the delivery-delay percentiles. `cores` is the server's CPU, and `us/del` the CPU microseconds per delivery. `heap/c` and `rss/c` are the memory added per connection, in bytes. `pthr` is the server's platform threads and `vthr` its connection threads (50 clients plus one for the metrics request itself, 51). `fds` is the server's open file descriptors. `recov ms` is the recovery time after dropping every connection. `reconn` and `reload` count reconnects and reloads. `gaps` must be 0 and `correct` must equal `conn`.

At this size the three methods are close; the differences the README shows (long polling's median p99 of 114 ms against 14 to 16 ms for the push methods at 1,000 clients) appear only under load. One row deserves a remark: long polling shows `reconn 0`. A dropped long poll is only a closed connection between two requests, and the JDK client may simply open a new one for the next request without the client ever seeing an error. I did not confirm that inside the JDK, so treat it as a likely reason, not a fact.

The CSV it wrote (`build/compare/live-feed-comparison.csv`), first two lines:

```text
method,run,clients,connected,changes_per_s,deliveries_per_s,requests_per_s,server_bytes_per_delivery,delay_p50_ms,delay_p99_ms,delay_max_ms,delay_samples,cpu_cores,cpu_us_per_delivery,heap_bytes_per_connection,resident_bytes_per_connection,platform_threads,virtual_threads,open_fds,max_fds,dropped,recovery_ms,server_reconnects,client_reconnects,reloads,gaps,duplicates,correct_maps
long-polling,1,50,50,49.819,2490.963,2490.963,163.360,0.914,8.669,12.560,7500,0.638,256.243,69960,44054,19,51,72,1048576,50,62,200,0,0,0,0,50
```

## Mistakes people make

- **Measuring the server inside the client's JVM.** Threads, heap and CPU would be summed. `ChildProcessServer` gives the server its own process.
- **Measuring without warm-up.** The first seconds are slow because the JIT has not compiled the hot code. The comparison discards the warm-up.
- **Reading the heap without a collection.** Garbage that has not been collected is counted as use. `settle` forces a collection first.
- **Letting the heap grow during the measurement.** `-Xms` equal to `-Xmx` with `-XX:+AlwaysPreTouch` fixes it.
- **Connecting everyone in one instant.** The listen queue overflows. The fleet starts 250 per second.
- **Reporting only an average.** An average hides the tail. The table shows p50, p99 and max.
- **Trusting one run.** The comparison does three and reports each and their median.
- **Building a shell command from text.** `ProcessMemory` passes the process id as a separate argument, never through a shell.

## Check yourself

1. In `LatencySummary`, what is the p99 of 1,000 sorted delays? (The value at rank 990, the 990th smallest.)
2. Why does the comparison connect a tenth of the clients first and read memory twice? (To pay one-time costs before the first reading, so the difference measures only what the added connections cost.)
3. What does `cores = 1.73` mean? (The server process used 1.73 seconds of CPU for every second of real time.)
4. How is the delivery delay of one change measured? (The store stamps `changedAt`; the client subtracts it from the time it has applied the change; both on one machine's clock.)
5. What would make a comparison run "incorrect" even if it was fast? (Any client whose final map differs from the store's, or any gap: `correct` below `conn`, `gaps` above 0.)

## Further reading

- Java SE 25 API, `java.lang.ProcessBuilder`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessBuilder.html
- Java SE 25 API, `java.lang.management` (`ManagementFactory`, `MemoryMXBean`, `OperatingSystemMXBean`): https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/package-summary.html
- Java SE 25 API, `java.util.concurrent.atomic.LongAdder`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/LongAdder.html
- The `java` command, `-Xms`, `-Xmx` and `-XX:+AlwaysPreTouch`: https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html
- RFC 6455, The WebSocket Protocol: https://www.rfc-editor.org/rfc/rfc6455
- WHATWG HTML Living Standard, "Server-sent events": https://html.spec.whatwg.org/multipage/server-sent-events.html
- RFC 4180, Common Format and MIME Type for CSV Files: https://www.rfc-editor.org/rfc/rfc4180
