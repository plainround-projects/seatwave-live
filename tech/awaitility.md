# Awaitility 4.3.0

## What it is

Some things in a program happen **later**, in another thread, after the line that started them has already returned. A test that wants to check the result must wait. The crude way is `Thread.sleep(2000)`: wait a fixed time and hope it was enough. It is slow when the thing finishes in a millisecond and flaky when the machine is busy and needs three seconds. **Awaitility** is a small library for the better way: you describe the condition you are waiting for, and Awaitility checks it again and again until it holds or a time limit passes.

A **flaky** test passes on some runs and fails on others without any change to the code. Fixed sleeps are a common cause.

## Why this repository uses it

Two test classes need to wait for something that happens in another thread, on its own schedule:
- `WebSocketServerTest.refusesAWebSocketOverTheConnectionLimit` opens a WebSocket and waits until the server's own counter says that connection is open, and only then opens the second one that must be refused. The server counts the connection on its own thread, so the test reads the counter instead of assuming the order of two threads.
- `ReconnectCorrectnessTest` starts ten clients, lets the simulated on-sale run, cuts every connection, and must wait until every client has caught up before it looks at the clients' seat maps. How long the clients take depends on their random reconnect waits, on the machine and on the moment.

The README lists Awaitility with the other test libraries: "The tests, their assertions, and waiting for a condition instead of sleeping."

## Where it appears

| Path | What it does with Awaitility |
| --- | --- |
| `gradle/libs.versions.toml` | `awaitility = "4.3.0"` and the entry `awaitility`. |
| `build.gradle.kts` | `testImplementation(libs.awaitility)`. |
| `gradle.lockfile` | Locks `org.awaitility:awaitility:4.3.0` and its dependency `org.hamcrest:hamcrest:2.1` (`dependency-locking.md`). |
| `src/test/java/.../websocket/WebSocketServerTest.java` | One `await()` call (line 119). |
| `src/test/java/.../client/ReconnectCorrectnessTest.java` | Three `await()` calls (lines 27, 29 and 36). |

I searched the test sources: `await()` appears four times, in those two files (a fifth `await`, `start.await()` in `SeatStoreTest`, is the JDK's `CountDownLatch`, unrelated). The tests are not free of `Thread.sleep`: it appears seven times, listed at the end of this file with the reason for each. They are short pauses with a stated purpose, never a wait for a result.

## How it works underneath

1. `await()` returns a **condition factory**. You describe the wait on it, and the last call, here `until(...)`, runs it.
2. `until(condition)` takes a **lambda** that returns `true` or `false` (a `Callable<Boolean>`). Awaitility calls it, and if the answer is `false` it waits one **poll interval** and calls it again. The default poll interval is 100 milliseconds, and the default poll delay (the wait before the first check) is also 100 milliseconds.
3. When the lambda returns `true`, `until` returns and the test goes on.
4. When the time limit passes first, `until` throws a `ConditionTimeoutException`, and the test fails with a message that names the condition. The default limit is 10 seconds. `atMost(...)` sets it. This repository sets 5 seconds in one place and 10 seconds in the other.
5. By default Awaitility evaluates the condition on a separate thread that it starts, while the thread that called `await()` waits for the answer. The lambdas here only read a counter, which is safe from any thread: `ClientSeatMap.sequence()` is a `volatile` field, and `FeedMetrics.openConnections` reads an atomic counter.
6. If the lambda itself throws, the exception is not hidden: the wait ends and the test fails with it. The tests here do not ask Awaitility to ignore exceptions.

The limit is a **ceiling**, not a delay. A condition that becomes true after 10 milliseconds ends the wait after about 100 milliseconds (the first poll), not after 5 seconds. A passing test is therefore fast, and only a broken one waits the full limit.

## Walkthrough of this repository's use

### The static import

```java
import static org.awaitility.Awaitility.await;
```

This is the `import static` line from `ReconnectCorrectnessTest` (line 4). It lets the test write `await()` instead of `Awaitility.await()`. `WebSocketServerTest` has the same line.

### Waiting for the server to count a connection

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

- `TestSettings.withConnectionLimit(1)` starts a node that admits only one open feed connection.
- `open(http, node, ..., new Recorder())` opens the first WebSocket with the JDK's client. The client's call returns when it has the `101` reply. In the server's code (`WebSocketSession.open`) the connection is admitted to the limit before the `101` is written, so the counter already says 1 when the client's call returns and the wait normally ends at its first poll. The wait is a guard: the test does not depend on that ordering, and a future change to the server that counted later would not make the test flaky.
- `await().atMost(WAIT).until(() -> node.parts().metrics().openConnections(DeliveryMethod.WEBSOCKET) == 1)` waits until the server's own counter says that exactly one WebSocket is open. `WAIT` is `Duration.ofSeconds(5)`.
- Only then does the test open a raw second connection with `Raw`, send the upgrade request and expect `HTTP/1.1 503` with `Retry-After: 1`. Without a guard of this kind, a server that counted the connection a moment late would let the second request in, and the test would sometimes see `101` instead of `503`.

### Waiting for ten clients to catch up

```java
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC())) {
      ClientFleet fleet = ClientFleet.start(plan(method, node));
      try {
        node.startOnSale();
        await().atMost(Duration.ofSeconds(10)).until(() -> fleet.countAtLeast(20) == CLIENTS);
        node.parts().limit().dropAll();
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> fleet.countAtLeast(node.parts().feed().position().sequence()) == CLIENTS);
        node.parts().limit().dropAll();
        Thread.sleep(300);
        node.parts().simulator().pause();
        SeatMapSnapshot store = node.parts().store().snapshot();
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> fleet.countAtLeast(store.position().sequence()) == CLIENTS);
        fleet.stop();
```

- `await().atMost(Duration.ofSeconds(10)).until(() -> fleet.countAtLeast(20) == CLIENTS)` waits until all ten clients have applied at least 20 changes. `countAtLeast(20)` counts the clients whose map has reached sequence 20. This makes sure that the on-sale is really changing seats, and that every client is following, before the first drop.
- `node.parts().limit().dropAll()` closes every connection from the server's side. The second wait is `fleet.countAtLeast(node.parts().feed().position().sequence()) == CLIENTS`: it waits until every client has reached the position the feed has *now*. The lambda reads the feed's position again on every poll, so the target moves with the on-sale; the wait ends when every client has caught up at some poll. That proves each client reconnected and resumed.
- After the second drop the test pauses the on-sale, takes the store's snapshot, and the third wait is `fleet.countAtLeast(store.position().sequence()) == CLIENTS`: every client has reached the sequence of that snapshot. Then `fleet.stop()` and the assertions on each client's map follow (`tests-transports-walkthrough.md`).
- The three waits all have a 10-second ceiling. Healthy runs take about two seconds for the whole test (the whole class took 2.161 seconds for its three delivery methods in the run for these notes).

### The sleeps that remain

| Place | Sleep | Why it is there |
| --- | --- | --- |
| `FeedHttpServerTest` line 72 | 200 ms | Lets a long poll reach the server and start waiting before the test publishes the change that should wake it. |
| `FeedHttpServerTest` line 133 | 200 ms | Lets the first long poll take its slot in the connection limit before the second request is sent. |
| `SeatFeedTest` line 92 | 50 ms | Lets a long poll started on another thread begin to wait before the test publishes. |
| `SeatFeedTest` line 121 | 20 s ceiling | The body of a thread that stands in for a writer stuck in a write; it ends when the test interrupts it. |
| `SeatFeedTest` line 127 | 100 ms | Makes the first writer's write older than the stuck-writer timeout (50 ms) while the second writer's is new. |
| `WebSocketServerTest` line 56 | 500 ms | Gives the idle server time to send more pings, to prove the connection stays open. |
| `ReconnectCorrectnessTest` line 33 | 300 ms | Lets the clients start reconnecting after the second drop before the on-sale is paused. |

These are the tests that depend on real time, and `tests-feed-walkthrough.md`, `tests-transports-walkthrough.md` and `tests-other-packages-walkthrough.md` say more about each. A sleep that only gives the other side a head start can, on a very slow machine, be too short; the tests then either pass anyway (a long poll that arrives late still gets the change) or fail with a message, so the cost is a rare red run, never a wrong green one.

### The build wiring

`testImplementation(libs.awaitility)` puts the library on the test classpath only (`gradle.md`). It brings Hamcrest 2.1, a matcher library that Awaitility is built on; no test imports Hamcrest.

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Find every wait in the tests:

```
grep -rn "await()" src/test
```

   Output: five lines: `SeatStoreTest.java:93` (the JDK latch, not Awaitility), `WebSocketServerTest.java:119` and `ReconnectCorrectnessTest.java` lines 27, 29 and 36, each with its folder in front.

2. List the sleeps:

```
grep -rn "Thread.sleep" src/test
```

   Output: seven lines, in the files named in the table above.

3. Run the two classes that wait:

```
./gradlew --offline cleanTest test --tests '*ReconnectCorrectnessTest' --tests '*WebSocketServerTest'
```

   Output: nine `PASSED` lines (three delivery methods and six WebSocket tests), then `BUILD SUCCESSFUL`.

4. See the library on the classpath:

```
./gradlew --offline -q dependencyInsight --dependency awaitility --configuration testRuntimeClasspath
```

   Output begins with `org.awaitility:awaitility:4.3.0`.

## Mistakes people make

- **Using a fixed sleep to wait for a result.** It is either too short (flaky) or too long (slow). Describe the condition instead.
- **Waiting without a limit.** `atMost(...)` here keeps a broken test from running for the 10-second default, and says how long the author thinks the thing may take.
- **A condition with a side effect.** The lambda runs many times. These only read counters.
- **Waiting on a thing the test itself needs to cause.** `ReconnectCorrectnessTest` starts the on-sale before it waits for the clients to reach sequence 20; waiting first would wait forever.
- **Putting the assertion inside `until`.** `until` wants a true or false answer. The checks come after the wait, with AssertJ (`assertj.md`).

## Check yourself

1. *Why is `Thread.sleep(5000)` a poor way to wait for the server?* It always waits the full time, and it still fails if the server needs longer.
2. *What does `await().atMost(Duration.ofSeconds(5)).until(...)` do when the condition never holds?* After 5 seconds it throws a `ConditionTimeoutException` and the test fails.
3. *How often does Awaitility check by default?* Every 100 milliseconds, after a first wait of 100 milliseconds.
4. *What is the condition in `WebSocketServerTest`?* That the server's counter shows exactly one open WebSocket connection.
5. *Why does `ReconnectCorrectnessTest` wait for `countAtLeast(...)` and not sleep?* The clients' catch-up time varies with their random backoff and the machine; the condition ends the wait as soon as every client has the sequence, however long that takes.

## Further reading

- Awaitility 4.3.0 usage guide: <https://github.com/awaitility/awaitility/wiki/Usage>
- Awaitility getting started: <https://github.com/awaitility/awaitility/wiki/Getting_started>
- Javadoc: <https://javadoc.io/doc/org.awaitility/awaitility/4.3.0>
