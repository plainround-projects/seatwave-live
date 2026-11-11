# Walkthrough of the `feed` tests

## What it is

This file walks through every test file in the `feed` test package, `src/test/java/io/plainround/seatwave/live/feed/`: seven files, 41 test results. The `feed` package of the main code is the heart of the project: the seat store, the publisher that numbers every change, the replay buffer, the per-connection queue and the connection limit. These tests use no network at all. They create the objects, call their methods and check the results.

A **test** is a method that runs some code and checks the result (`junit.md`). A **unit test** checks one small part, here one class or a pair of classes, without servers around it. Every file below is quoted whole, in pieces, and each piece is explained line by line.

| File | Results | What it proves |
| --- | --- | --- |
| `ConnectionLimitTest.java` | 2 | The limit refuses the connection over the limit, frees a slot exactly once, and drops every open connection. |
| `FeedPositionTest.java` | 13 | A position is written and read back; every malformed text is refused. |
| `OnSaleSimulatorTest.java` | 3 | The simulated on-sale makes the changes the store allows, stops when paused, and repeats itself for the same seed. |
| `ReplayBufferTest.java` | 6 | The buffer returns every change after a position it still holds and says "reload" otherwise. |
| `SeatFeedTest.java` | 6 | The publisher gives each subscriber every change exactly once, bounds a slow subscriber's queue, holds long polls, and finds stuck writers. |
| `SeatLayoutTest.java` | 4 | Seat labels, seat numbers and seat states convert both ways. |
| `SeatStoreTest.java` | 7 | The store's rules: hold, confirm, expire, one winner among racers, snapshot at the right position. |

## Why this repository uses it

The module's promise is that a client that names its last position receives every later change exactly once, or is told to reload the seat map, and never a silent gap. That promise is made by the `feed` package, so the strictest tests are here. They run in milliseconds (the whole package takes about 0.3 seconds of the 25 seconds the build needs), so every `check` proves the promise again.

## Where it appears

All seven files are in the test source set and run in `./gradlew test` and in `./gradlew check`. One helper from the root test package is used: `MutableClock`, a clock the test moves by hand. It is explained in `tests-other-packages-walkthrough.md`.

## How it works underneath

**How a test of a concurrent program stays deterministic.** *Deterministic* means: the same result every run. A program with several threads can interleave its steps in many orders, and a test that depends on one order fails sometimes. These tests use five techniques.

1. **No real clock.** The store reads the time from a `java.time.Clock` that it is given. The tests give it a `MutableClock`, so "the hold expires after 300 seconds" is tested by calling `clock.advance(...)`, not by waiting 300 seconds.
2. **Seeded randomness.** The simulated on-sale chooses seats with a `java.util.Random`. A `Random` made with a **seed** (a starting number) produces the same sequence every time. The tests pass seed 8, so "random" choices are the same on every run and every machine.
3. **No background threads unless the test needs one.** The simulator can run itself on a timer thread (`start()`). The tests never call `start()`; they call `tick()` by hand, one step at a time.
4. **Check what must be true under every interleaving, not one interleaving.** Where the test does race threads (`SeatFeedTest` joins a feed while a virtual thread publishes 5,000 changes; `SeatStoreTest` lets 50 fans race for one seat), it does not assert a particular order. It asserts the **invariant**: each subscriber sees each sequence number exactly once, in order; exactly one fan wins the seat. A correct program satisfies those under any schedule.
5. **Make all racers start together.** A `CountDownLatch` (a gate that opens when a counter reaches zero) holds the 50 fans until all of them are ready, then lets them go at once. That makes the race real instead of letting each fan finish before the next one starts.

**Which tests rely on real time.** Three tests in `SeatFeedTest` do, and only in small amounts. `answersALongPollAtTheNextChangeOrAtTheEndOfTheHold` waits 50 ms for a long poll to start waiting and has the first poll run its 50 ms hold to the end. `wakesAWaitingWriterAndSaysIdleWhenNothingHappens` lets a 50 ms wait run out. `interruptsOnlyAWriterStuckLongerThanTheTimeout` is the most time-sensitive: it needs one write to be older than 50 ms and another to be younger, so it sleeps 100 ms and then checks within microseconds; on a machine that stalls the test thread for more than 50 ms at the wrong moment it would fail. Every other test in the package runs without waiting. Each of these three also has the JUnit default timeout of 60 seconds as a safety net (`tests-other-packages-walkthrough.md`).

## Walkthrough of this repository's use

### `ConnectionLimitTest.java`

```java
package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
```

- `package io.plainround.seatwave.live.feed;` puts the test in the same package as the class it tests. That lets a test use classes and methods that are not `public`, such as the package-private `Subscriber` constructor or `ReplayBuffer`.
- `import static ...assertThat;` is a **static import**: it lets the test write `assertThat(...)` without the class name in front (`assertj.md`).
- `import java.util.Optional;` brings in `Optional`, a box that holds a value or nothing. `admit` returns one: a ticket if the connection was accepted, an empty box if it was refused.
- `import java.util.concurrent.atomic.AtomicInteger;` brings in a whole number that several threads may change safely. The second test uses it as a counter.
- `import org.junit.jupiter.api.Test;` brings in the `@Test` annotation.

```java
class ConnectionLimitTest {

  private final FeedMetrics metrics = new FeedMetrics();
  private final ConnectionLimit limit = new ConnectionLimit(2, metrics);
```

- `class ConnectionLimitTest {` is package-private, as JUnit allows.
- `private final FeedMetrics metrics = new FeedMetrics();` creates the counters object that the limit reports to. `final` means the field is set once. Because JUnit makes a new test object for every test method, each test has its own `metrics`.
- `private final ConnectionLimit limit = new ConnectionLimit(2, metrics);` creates a limit of two connections, wired to those counters.

```java
  @Test
  void refusesTheConnectionOverTheLimitAndFreesASlotOnce() {
    Optional<ConnectionLimit.Ticket> first = limit.admit(DeliveryMethod.WEBSOCKET, () -> {});
    Optional<ConnectionLimit.Ticket> second =
        limit.admit(DeliveryMethod.SERVER_SENT_EVENTS, () -> {});

    assertThat(limit.admit(DeliveryMethod.LONG_POLLING, () -> {})).isEmpty();
    first.orElseThrow().close();
    first.orElseThrow().close();
    assertThat(limit.openCount()).isEqualTo(1);
    assertThat(limit.admit(DeliveryMethod.LONG_POLLING, () -> {})).isPresent();
    assertThat(limit.admit(DeliveryMethod.LONG_POLLING, () -> {})).isEmpty();
    assertThat(second).isPresent();
    assertThat(metrics.render()).contains("seatwave_feed_refused_total{method=\"long-polling\"} 2");
  }
```

- `Optional<ConnectionLimit.Ticket> first = limit.admit(DeliveryMethod.WEBSOCKET, () -> {});` asks for a place for one WebSocket connection. The second argument is the **drop action**: what to run when the feed wants this connection closed. `() -> {}` is a **lambda**, a small unnamed function, that does nothing; this test does not need a real action. A `Ticket` is the receipt for a place; closing it gives the place back.
- The second `admit` takes the second place, for a server-sent events connection.
- `assertThat(limit.admit(DeliveryMethod.LONG_POLLING, () -> {})).isEmpty();` asks for a third place. The limit is two, so the answer is an empty `Optional`: refused.
- `first.orElseThrow().close();` takes the ticket out of the box (`orElseThrow` fails the test if the box were empty) and closes it, which frees one place. The next line does it again on purpose: closing a ticket twice must free the place only once, and this is the check.
- `assertThat(limit.openCount()).isEqualTo(1);` proves that: after two closes only one connection is gone, so one is open. If the second close freed a second place the count would be 0, and a third connection could get in beyond the limit.
- The next two lines show the freed place works: a long-polling connection is admitted (`isPresent()`), and the following one is refused again (`isEmpty()`). The admitted ticket is not closed in the test, because the object is thrown away at the end.
- `assertThat(second).isPresent();` makes the test use the second ticket, so nothing is left unchecked.
- `assertThat(metrics.render()).contains("seatwave_feed_refused_total{method=\"long-polling\"} 2");` reads the metrics text that the feed would serve at `/metrics`, and checks that exactly two long-polling connections were counted as refused: the third and the fifth admit. The `\"` is a quote character inside a Java string.

```java
  @Test
  void dropsEveryOpenConnection() {
    AtomicInteger dropped = new AtomicInteger();
    limit.admit(DeliveryMethod.WEBSOCKET, dropped::incrementAndGet);
    limit.admit(DeliveryMethod.WEBSOCKET, dropped::incrementAndGet);

    assertThat(limit.dropAll()).isEqualTo(2);
    assertThat(dropped).hasValue(2);
  }
}
```

- `AtomicInteger dropped = new AtomicInteger();` makes a counter that starts at 0.
- `limit.admit(DeliveryMethod.WEBSOCKET, dropped::incrementAndGet);` admits two connections whose drop action is `dropped::incrementAndGet`, a **method reference**: shorthand for a lambda that calls `incrementAndGet()` on `dropped`. So running the drop action adds 1.
- `assertThat(limit.dropAll()).isEqualTo(2);` runs every open connection's drop action and returns how many it ran.
- `assertThat(dropped).hasValue(2);` checks that the counter really reached 2: both drop actions ran. This is the function the comparison tool and the reconnect test use to cut every connection at once.

### `FeedPositionTest.java`

```java
package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedPositionTest {
```

- `import com.github.sviperll.result4j.Result;` brings in the success-or-error type (`result4j.md`). `FeedPosition.parse` returns a `Result<FeedPosition, String>`: a position, or a text that says what was wrong.
- `import org.junit.jupiter.params.ParameterizedTest;` and `import org.junit.jupiter.params.provider.ValueSource;` bring in the parameterized-test annotations (`junit.md`).
- `assertThatThrownBy` is imported for the third test.

```java
  @Test
  void readsBackWhatItWrites() {
    FeedPosition position = new FeedPosition("3f9a2c1e", 42);

    Result<FeedPosition, String> read = FeedPosition.parse(position.written());

    assertThat(position.written()).isEqualTo("3f9a2c1e.42");
    assertThat(read.discardError()).contains(position);
  }
```

- `new FeedPosition("3f9a2c1e", 42)` builds a position from a stream id (eight lowercase hexadecimal digits that name one run of the feed) and a sequence number. A `record` in the main code holds the two values.
- `position.written()` is the text form, `<stream>.<sequence>`. `FeedPosition.parse(...)` reads it back.
- `assertThat(position.written()).isEqualTo("3f9a2c1e.42");` fixes the format that clients depend on.
- `assertThat(read.discardError()).contains(position);` turns the `Result` into an `Optional` of the success value (`discardError` throws the error text away) and checks that it holds an object equal to the original. Records compare by their fields, so this proves a round trip.

```java
  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "3f9a2c1e",
        "3f9a2c1e.",
        "3F9A2C1E.1",
        "3f9a2c1.1",
        "3f9a2c1e.-1",
        "3f9a2c1e.01",
        "3f9a2c1e.1234567890123456789",
        "3f9a2c1e.1 ",
        "../etc.1"
      })
  void refusesAMalformedPosition(String written) {
    assertThat(FeedPosition.parse(written).isError()).isTrue();
  }
```

- `@ParameterizedTest` and `@ValueSource(strings = {...})` run the method once for each of the ten strings. Each string is a way a position can be wrong: the empty text; a stream id with no sequence; the dot with no sequence; an upper-case stream id; a stream id of seven digits; a negative sequence; a sequence with a leading zero; a sequence of 19 digits that does not fit a `long`; a trailing space; and `../etc.1`, which tries to smuggle a path through the position.
- `assertThat(FeedPosition.parse(written).isError()).isTrue();` demands an error `Result` for each. The parser is strict because a position travels in a URL and a header from a stranger.

```java
  @Test
  void refusesANegativeSequenceAndABadStream() {
    assertThatThrownBy(() -> new FeedPosition("3f9a2c1e", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FeedPosition("xyz", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- `assertThatThrownBy(() -> new FeedPosition("3f9a2c1e", -1)).isInstanceOf(IllegalArgumentException.class);` checks that the constructor itself refuses a negative sequence. The lambda delays the call so AssertJ can catch what it throws. The second check refuses the stream id `"xyz"`.

```java
  @Test
  void movesOneChangeForward() {
    assertThat(new FeedPosition("3f9a2c1e", 7).next()).isEqualTo(new FeedPosition("3f9a2c1e", 8));
  }
```

- `next()` moves one change forward and keeps the stream id: sequence 7 becomes 8.

### `OnSaleSimulatorTest.java`

```java
package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
```

- `import io.plainround.seatwave.live.MutableClock;` is the helper clock from the root test package.
- `java.time.Duration` is a length of time; `java.time.Instant` is a point in time.

```java
class OnSaleSimulatorTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
  private final SeatFeed feed = new SeatFeed(10_000, 100, 64, new FeedMetrics());
  private final SeatStore store =
      new SeatStore(new SeatLayout(1, 100), Duration.ofSeconds(4), clock, feed);
```

- `new MutableClock(Instant.parse("2026-11-11T10:00:00Z"))` is a clock that stands still at 10:00 UTC on 11 November 2026 and moves only when the test calls `advance`.
- `new SeatFeed(10_000, 100, 64, new FeedMetrics())` makes a publisher: it keeps the last 10,000 changes for resuming, lets each subscriber queue 100, and carries up to 64 changes per batch. (`10_000` is Java's way to write 10000 with a separator.)
- `new SeatStore(new SeatLayout(1, 100), Duration.ofSeconds(4), clock, feed)` makes a store of one section of 100 seats, in which a hold lasts 4 seconds, reading the fake clock and publishing to the feed.

```java
  @Test
  void makesHoldsConfirmsAndExpiriesThatTheStoreAllows() {
    try (OnSaleSimulator simulator = new OnSaleSimulator(store, 50, 30, 8)) {
      for (int tick = 0; tick < 200; tick++) {
        simulator.tick();
        clock.advance(Duration.ofMillis(20));
      }
    }

    String states = store.snapshot().states();

    assertThat(states).contains("s");
    assertThat(feed.position().sequence()).isGreaterThan(100);
  }
```

- `try (OnSaleSimulator simulator = new OnSaleSimulator(store, 50, 30, 8)) {` creates the simulator with 50 actions per second, a 30 percent chance that an action confirms an earlier hold, and the seed 8. It is closed at the end of the block because it implements `AutoCloseable`.
- The `for` loop runs 200 ticks. `simulator.tick()` performs one action by hand; `clock.advance(Duration.ofMillis(20))` moves the fake clock 20 ms, which is one tick at 50 per second. So the loop plays 4 seconds of on-sale in a few milliseconds, exactly the length of a hold. That is why holds expire during the loop.
- `store.snapshot().states()` is the seat map as one letter per seat: `a` available, `h` held, `s` sold.
- `assertThat(states).contains("s");` checks that at least one seat was sold: some confirms succeeded.
- `assertThat(feed.position().sequence()).isGreaterThan(100);` checks that more than 100 changes were published (holds, confirms and expiries all count). Together the two checks show the simulator did real work through the store's rules.

```java
  @Test
  void changesNothingWhilePaused() {
    try (OnSaleSimulator simulator = new OnSaleSimulator(store, 50, 30, 8)) {
      simulator.tick();
      simulator.pause();
      clock.advance(Duration.ofSeconds(10));

      assertThat(simulator.tick()).isFalse();
      assertThat(feed.position().sequence()).isEqualTo(1);
      simulator.resume();
      assertThat(simulator.tick()).isTrue();
    }
  }
```

- One tick, then `simulator.pause()`, then the clock jumps 10 seconds.
- `assertThat(simulator.tick()).isFalse();` a tick while paused does nothing and says so.
- `assertThat(feed.position().sequence()).isEqualTo(1);` proves nothing was published during the pause: the first tick published exactly one change, and the paused tick did not even expire the hold that is now 10 seconds old.
- After `resume()`, `tick()` returns `true`: the simulator is working again. (With seed 8 the random choices are always the same, so this is not luck.)

```java
  @Test
  void repeatsTheSameChoicesForTheSameSeed() {
    SeatFeed otherFeed = new SeatFeed(10_000, 100, 64, new FeedMetrics());
    SeatStore other =
        new SeatStore(new SeatLayout(1, 100), Duration.ofSeconds(4), clock, otherFeed);
    try (OnSaleSimulator first = new OnSaleSimulator(store, 50, 30, 8);
        OnSaleSimulator second = new OnSaleSimulator(other, 50, 30, 8)) {
      for (int tick = 0; tick < 50; tick++) {
        first.tick();
        second.tick();
      }
    }

    assertThat(other.snapshot().states()).isEqualTo(store.snapshot().states());
  }
```

- A second feed and a second store are built on the **same clock**. The `try` has two resources, closed in reverse order.
- Both simulators have the same seed 8, and they tick 50 times in step.
- `assertThat(other.snapshot().states()).isEqualTo(store.snapshot().states());` proves the two maps are identical. This is the determinism technique 2 above, proved by a test: the same seed gives the same on-sale, which is what makes a comparison run repeatable.

### `ReplayBufferTest.java`

```java
package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReplayBufferTest {

  private static final String STREAM = "0000abcd";
```

- `STREAM = "0000abcd"` is a valid stream id used by all the tests. `static final` means one constant for the class.

```java
  private static ReplayBuffer filled(int capacity, int changes) {
    ReplayBuffer buffer = new ReplayBuffer(STREAM, capacity);
    for (long sequence = 1; sequence <= changes; sequence++) {
      buffer.append(change(sequence));
    }
    return buffer;
  }

  private static SeatChange change(long sequence) {
    return new SeatChange(new FeedPosition(STREAM, sequence), 0, SeatState.HELD, Instant.EPOCH);
  }
```

These are the two helper methods, shown first because every test uses them.
- `filled(int capacity, int changes)` makes a buffer that has room for `capacity` changes and appends changes numbered 1 to `changes`. When `changes` is larger than `capacity`, the oldest are overwritten, because the buffer is a **ring**: a fixed array in which the next change replaces the oldest one. `filled(5, 10)` therefore holds changes 6 to 10.
- `change(long sequence)` makes a `SeatChange` at that position: seat 0, state `HELD`, time `Instant.EPOCH` (1 January 1970; the time is not what these tests are about).

```java
  @Test
  void returnsEveryChangeAfterAPositionItStillHolds() {
    ReplayBuffer buffer = filled(5, 10);

    Catchup catchup = buffer.after(new FeedPosition(STREAM, 7), 100);

    assertThat(catchup).isInstanceOf(Catchup.Changes.class);
    Catchup.Changes changes = (Catchup.Changes) catchup;
    assertThat(changes.changes())
        .extracting(change -> change.position().sequence())
        .containsExactly(8L, 9L, 10L);
    assertThat(changes.position().sequence()).isEqualTo(10);
  }
```

- The buffer holds 6 to 10. A client at position 7 asks for what is after it, at most 100 changes.
- `assertThat(catchup).isInstanceOf(Catchup.Changes.class);` the answer is the "here are the changes" kind. `Catchup` is a **sealed interface**: it has exactly two kinds, `Changes` and `ReloadRequired`, and no third. That is the code form of the promise "changes or reload, never a gap".
- The cast `(Catchup.Changes) catchup` lets the test read the changes. `extracting(change -> change.position().sequence())` turns the list into its sequence numbers and `containsExactly(8L, 9L, 10L)` demands those, in order. `changes.position().sequence()` is 10: the position the client reaches.

```java
  @Test
  void returnsNothingToAClientThatIsCurrent() {
    Catchup catchup = filled(5, 10).after(new FeedPosition(STREAM, 10), 100);

    assertThat(((Catchup.Changes) catchup).changes()).isEmpty();
  }
```

- A client already at position 10 is current. The answer is a `Changes` with an empty list.

```java
  @Test
  void stopsAtTheBatchSize() {
    Catchup.Changes changes =
        (Catchup.Changes) filled(10, 10).after(new FeedPosition(STREAM, 0), 4);

    assertThat(changes.changes()).hasSize(4);
    assertThat(changes.position().sequence()).isEqualTo(4);
  }
```

- The buffer holds 1 to 10; the client at 0 asks with a batch size of 4. It gets 4 changes and its new position is 4: the batch size is a limit on one answer, and the client asks again.

```java
  @Test
  void tellsAClientWhoseNextChangeWasOverwrittenToReload() {
    ReplayBuffer buffer = filled(5, 10);

    assertThat(buffer.after(new FeedPosition(STREAM, 5), 100)).isInstanceOf(Catchup.Changes.class);
    assertThat(buffer.after(new FeedPosition(STREAM, 4), 100))
        .isInstanceOf(Catchup.ReloadRequired.class);
  }
```

- The buffer holds 6 to 10. A client at 5 needs change 6, which is still there: a `Changes` answer. A client at 4 needs change 5, which was overwritten: `ReloadRequired`. This is the edge of the window, tested at exactly the boundary where an off-by-one mistake would hide.

```java
  @Test
  void tellsAClientFromAnotherStreamOrFromTheFutureToReload() {
    ReplayBuffer buffer = filled(5, 3);

    assertThat(buffer.after(new FeedPosition("ffffffff", 2), 100))
        .isInstanceOf(Catchup.ReloadRequired.class);
    assertThat(buffer.after(new FeedPosition(STREAM, 4), 100))
        .isInstanceOf(Catchup.ReloadRequired.class);
  }
```

- The buffer holds only 1 to 3 (`filled(5, 3)`).
- A position of another stream (`ffffffff`) must reload: the client's map belongs to an earlier run of the feed, and replaying this stream's history on it would be wrong.
- A position in the future (sequence 4 in a stream that has only reached 3) must reload too. It cannot come from this stream.

```java
  @Test
  void refusesAChangeOutOfOrder() {
    ReplayBuffer buffer = filled(5, 2);

    assertThatThrownBy(() -> buffer.append(change(4))).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ReplayBuffer(STREAM, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- `buffer.append(change(4))` on a buffer that has changes 1 and 2 skips number 3. The buffer refuses with `IllegalStateException`: a hole in the numbering is a bug in the feed, never a client's mistake, so the buffer will not store one.
- `new ReplayBuffer(STREAM, 0)` is a buffer with no room: `IllegalArgumentException`.

### `SeatLayoutTest.java`

```java
package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SeatLayoutTest {

  private final SeatLayout arena = new SeatLayout(20, 1_000);
```

- `new SeatLayout(20, 1_000)` is Module 1's arena: 20 sections of 1,000 seats.

```java
  @Test
  void namesSeatsSectionBySectionAsModuleOneDoes() {
    assertThat(arena.seatCount()).isEqualTo(20_000);
    assertThat(arena.label(0)).isEqualTo("A-1");
    assertThat(arena.label(1_006)).isEqualTo("B-7");
    assertThat(arena.label(19_999)).isEqualTo("T-1000");
    assertThat(arena.indexOf("B-7")).contains(1_006);
  }
```

- Seats are numbered 0 to 19,999 inside the code and named by section letter and number for people. Index 0 is `A-1`. Index 1,006 is in section 2 (1,006 divided by 1,000 is 1, and section B is the second), at number 7 (the remainder 6, plus 1): `B-7`. Index 19,999 is the last seat, `T-1000`.
- `indexOf("B-7")` goes the other way and returns an `Optional` that contains 1,006.

```java
  @Test
  void findsNoSeatForAMalformedOrMissingLabel() {
    SeatLayout small = new SeatLayout(2, 50);

    assertThat(small.indexOf("C-1")).isEmpty();
    assertThat(small.indexOf("A-51")).isEmpty();
    assertThat(small.indexOf("A-0")).isEmpty();
    assertThat(small.indexOf("a-1")).isEmpty();
    assertThatThrownBy(() -> small.label(100)).isInstanceOf(IllegalArgumentException.class);
  }
```

- A layout of 2 sections of 50 seats. `indexOf` returns an empty `Optional` for: a section that does not exist (`C-1`), a number past the end (`A-51`), seat number zero (`A-0`), and a lower-case letter (`a-1`).
- `small.label(100)` asks for the name of seat 100 in a layout that has seats 0 to 99: `IllegalArgumentException`.

```java
  @Test
  void refusesAnImpossibleLayout() {
    assertThatThrownBy(() -> new SeatLayout(0, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SeatLayout(21, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SeatLayout(1, 1_001)).isInstanceOf(IllegalArgumentException.class);
  }
```

- Zero sections, 21 sections (the labels stop at `T`, 20 letters) and 1,001 seats per section are all refused by the constructor.

```java
  @Test
  void readsEveryStateByItsNameAndCode() {
    for (SeatState state : SeatState.values()) {
      assertThat(SeatState.fromWireName(state.wireName())).contains(state);
      assertThat(SeatState.fromCode(state.code())).contains(state);
    }
    assertThat(SeatState.fromWireName("taken")).isEmpty();
    assertThat(SeatState.fromCode('x')).isEmpty();
  }
```

- `SeatState` is an `enum`, a fixed list of named values: `AVAILABLE`, `HELD`, `SOLD`. Each has a **wire name** (`available`, `held`, `sold`) used in JSON and a one-letter **code** used in the compact seat map.
- The loop checks both conversions for every state: name to state, and code to state. `contains(state)` checks the `Optional` holds that state.
- `fromWireName("taken")` and `fromCode('x')` are not states: both are empty. A client's unknown state name must never become a state.

### `SeatStoreTest.java`

```java
package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
```

- `CountDownLatch`, `ExecutorService`, `Executors` and `Future` are the JDK's tools for running work on several threads and waiting for it. They are used by the race test.
- `Result` is the success-or-error type again.

```java
class SeatStoreTest {

  private static final Duration HOLD = Duration.ofSeconds(300);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
  private final SeatFeed feed = new SeatFeed(100, 10, 10, new FeedMetrics());
  private final SeatStore store = new SeatStore(new SeatLayout(1, 10), HOLD, clock, feed);
```

- `HOLD` is a constant of 300 seconds.
- A fake clock, a feed (100 changes kept, queue 10, batch 10) and a store of one section of 10 seats with that hold time. The tests below are about seat numbers 0 to 9.

```java
  @Test
  void holdsAnAvailableSeatAndPublishesTheChange() {
    Result<SeatChange, SeatRefusal> held = store.hold(3);

    assertThat(held.discardError())
        .hasValueSatisfying(change -> {
          assertThat(change.state()).isEqualTo(SeatState.HELD);
          assertThat(change.position().sequence()).isEqualTo(1);
          assertThat(change.changedAt()).isEqualTo(clock.instant());
        });
    assertThat(store.snapshot().states()).isEqualTo("aaahaaaaaa");
  }
```

- `store.hold(3)` holds the fourth seat (index 3, which is `A-4`). It returns a `Result<SeatChange, SeatRefusal>`.
- `hasValueSatisfying(change -> { ... })` runs three checks on the success value: its state is `HELD`, its position has sequence 1 (the first change of the feed), and its time is the fake clock's time.
- `store.snapshot().states()` is `"aaahaaaaaa"`: ten seats, the fourth is `h`.

```java
  @Test
  void refusesToHoldATakenOrUnknownSeat() {
    store.hold(3);

    assertThat(store.hold(3)).isEqualTo(Result.error(SeatRefusal.SEAT_TAKEN));
    assertThat(store.hold(10)).isEqualTo(Result.error(SeatRefusal.UNKNOWN_SEAT));
    assertThat(store.hold(-1)).isEqualTo(Result.error(SeatRefusal.UNKNOWN_SEAT));
    assertThat(feed.position().sequence()).isEqualTo(1);
  }
```

- Holding seat 3 a second time returns `Result.error(SeatRefusal.SEAT_TAKEN)`. Seat 10 and seat -1 are outside the 10 seats: `UNKNOWN_SEAT`.
- `assertThat(feed.position().sequence()).isEqualTo(1);` proves that the three refusals published nothing. Only successful changes are numbered.

```java
  @Test
  void confirmsAHoldThatHasNotExpired() {
    store.hold(3);
    clock.advance(HOLD.minusSeconds(1));

    assertThat(store.confirm(3).discardError())
        .hasValueSatisfying(change -> assertThat(change.state()).isEqualTo(SeatState.SOLD));
    assertThat(store.confirm(3)).isEqualTo(Result.error(SeatRefusal.NOT_HELD));
    assertThat(store.confirm(11)).isEqualTo(Result.error(SeatRefusal.UNKNOWN_SEAT));
  }
```

- Hold, then move the clock to one second before the hold ends (`HOLD.minusSeconds(1)`). The confirm succeeds and the state is `SOLD`.
- A second confirm of the same seat is `NOT_HELD`: it is sold now, not held. A confirm of seat 11 is `UNKNOWN_SEAT`.

```java
  @Test
  void refusesToConfirmAnExpiredHoldAndExpiresIt() {
    store.hold(3);
    clock.advance(HOLD);

    assertThat(store.confirm(3)).isEqualTo(Result.error(SeatRefusal.NOT_HELD));
    assertThat(store.expireDue()).isEqualTo(1);
    assertThat(store.snapshot().states()).isEqualTo("aaaaaaaaaa");
    assertThat(feed.position().sequence()).isEqualTo(2);
  }
```

- Hold, then move the clock by exactly the hold time. At that exact moment the hold has ended: `confirm` returns `NOT_HELD`. This tests the boundary: the rule is "a hold is valid before its expiry time, not at it".
- `store.expireDue()` returns how many holds it expired, 1. The map is all available again, and the feed's sequence is 2: the hold was change 1, the expiry change 2.

```java
  @Test
  void neverExpiresAConfirmedSeatOrANewerHoldOfTheSameSeat() {
    store.hold(1);
    store.hold(2);
    store.confirm(2);
    clock.advance(HOLD);
    store.expireDue();
    store.hold(1);
    clock.advance(Duration.ofSeconds(1));

    assertThat(store.expireDue()).isZero();
    assertThat(store.snapshot().states()).isEqualTo("ahsaaaaaaa");
  }
```

- Seats 1 and 2 are held; seat 2 is confirmed (sold). The clock moves 300 seconds. `expireDue()` expires seat 1's hold and must leave seat 2 alone: a sold seat never expires.
- Seat 1 is held again by a new hold at the new time, and the clock moves 1 second. The old hold's entry in the store's expiry queue is **stale**: it names seat 1, but it is not that seat's current hold. `expireDue()` must return 0.
- The map is `"ahsaaaaaaa"`: seat 0 available, seat 1 held (the new hold), seat 2 sold. This prevents a real mistake: expiring a new hold with the old hold's timer.

```java
  @Test
  void letsExactlyOneOfManyRacingFansHoldASeat() throws Exception {
    int fans = 50;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try (ExecutorService racers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int fan = 0; fan < fans; fan++) {
        results.add(
            racers.submit(() -> {
              start.await();
              return !store.hold(5).isError();
            }));
      }
      start.countDown();
    }

    long winners = results.stream().filter(result -> result.resultNow()).count();

    assertThat(winners).isEqualTo(1);
    assertThat(feed.position().sequence()).isEqualTo(1);
  }
```

- `int fans = 50;` fifty racers. `CountDownLatch start = new CountDownLatch(1);` a gate with a count of 1.
- `Executors.newVirtualThreadPerTaskExecutor()` gives each task its own **virtual thread**, a light thread that costs little, so fifty at once are cheap. The `try (...)` closes the executor at the end of the block, and closing waits for every submitted task to finish.
- Each task is a lambda: `start.await();` waits at the gate, then `return !store.hold(5).isError();` tries to hold seat 5 and returns `true` if it won. `racers.submit(...)` returns a `Future`, a promise of the result, which is kept in `results`.
- `start.countDown();` opens the gate after all fifty are submitted. All fifty then call `hold(5)` at about the same moment.
- `results.stream().filter(result -> result.resultNow()).count()` counts the `true` results. `resultNow()` reads a finished `Future`'s value.
- `assertThat(winners).isEqualTo(1);` exactly one fan got the seat, whatever the thread schedule. `assertThat(feed.position().sequence()).isEqualTo(1);` only one change was published: the 49 refusals published nothing.

```java
  @Test
  void takesASnapshotAtExactlyThePositionOfItsLastChange() {
    store.hold(0);
    store.hold(9);

    SeatMapSnapshot snapshot = store.snapshot();

    assertThat(snapshot.position()).isEqualTo(feed.position());
    assertThat(snapshot.position().sequence()).isEqualTo(2);
    assertThat(snapshot.states()).isEqualTo("haaaaaaaah");
  }
}
```

- Two holds, at the first and the last seat. The snapshot is a copy of the map taken under the store's lock together with the feed's position. The test checks that `snapshot.position()` equals `feed.position()` and has sequence 2, and the map is `"haaaaaaaah"`. This is what lets a client load the map and then follow the feed from exactly that position with no gap and no repeat.

### `SeatFeedTest.java`

```java
package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SeatFeedTest {

  private static final Duration SHORT = Duration.ofMillis(50);
  private static final Duration LONG = Duration.ofSeconds(5);

  private final FeedMetrics metrics = new FeedMetrics();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
```

- `SHORT` (50 ms) and `LONG` (5 s) are two waits used throughout. `LONG` is a ceiling for "the thing must happen"; `SHORT` is a wait for "nothing should happen".
- `CompletableFuture` is a promise that is filled by work on another thread. `TimeUnit` names units of time for a timeout.
- A `FeedMetrics` and a `MutableClock` are shared by the tests as fields (a new pair for each test).

```java
  @Test
  void givesASubscriberEveryChangeExactlyOnceWhileChangesArePublishedAsItJoins() throws Exception {
    SeatFeed feed = new SeatFeed(100_000, 100_000, 64, metrics);
    SeatStore store = new SeatStore(new SeatLayout(20, 1_000), Duration.ofMinutes(5), clock, feed);
    int holds = 5_000;
    Thread publisher =
        Thread.ofVirtual()
            .start(() -> {
              for (int seat = 0; seat < holds; seat++) {
                store.hold(seat);
              }
            });
    List<Long> seen = new ArrayList<>();
    for (int joiner = 0; joiner < 20; joiner++) {
      FeedPosition known = feed.position();
      Subscriber subscriber =
          feed.subscribe(new FeedPosition(feed.streamId(), Math.max(0, known.sequence() - 3)));
      seen.add(drainUntil(subscriber, known.sequence() + 1));
    }
    publisher.join();
    Subscriber last = feed.subscribe(new FeedPosition(feed.streamId(), 0));

    assertThat(seen).allMatch(gapless -> gapless == 1L);
    assertThat(sequencesIn(last, holds)).isEqualTo(holds);
  }
```

This is the key concurrency test of the project: a subscriber that joins **while changes are being published** still sees every change exactly once.
- The feed keeps 100,000 changes, lets a subscriber queue 100,000 and carries 64 per batch. The store is the full arena (20 sections of 1,000 seats) with a hold time of 5 minutes, so no hold expires during the test.
- `Thread.ofVirtual().start(() -> { for ... store.hold(seat); })` starts a virtual thread that holds 5,000 different seats, one after the other, as fast as it can. Each hold is published to the feed.
- The main thread then joins the feed 20 times in a loop. Each time: `known = feed.position()` reads where the feed is now; `feed.subscribe(new FeedPosition(feed.streamId(), Math.max(0, known.sequence() - 3)))` subscribes with a position three changes behind (but not below 0). The subscriber's starting queue then holds the three catch-up changes from the buffer, and every change published afterwards is added as it arrives. Reading the catch-up and registering the subscriber happen under one lock in the feed, and this test hammers that.
- `drainUntil(subscriber, known.sequence() + 1)` reads from the subscriber until it has seen the change after `known`, and returns 1 if every sequence number arrived exactly once, in order, with no gap and no repeat, or 0 if not. The result goes into `seen`.
- `publisher.join()` waits for the virtual thread to finish. `last = feed.subscribe(new FeedPosition(feed.streamId(), 0))` is one more subscriber that starts at the very beginning and reads all 5,000.
- `assertThat(seen).allMatch(gapless -> gapless == 1L);` all 20 joiners were gapless. `assertThat(sequencesIn(last, holds)).isEqualTo(holds);` the last subscriber saw sequences 1 to 5,000, each once, in order.
- This test never asserts which change a joiner saw first, because that depends on the thread schedule. It asserts what must hold under every schedule (technique 4 above).
- **A weakness of this test.** The 20 joins must all happen while the publisher is still publishing. If the publisher finishes its 5,000 holds first, a joiner computes `known` as 5,000 and `drainUntil` then waits for change 5,001, which never comes; after the 5-second `LONG` wait it returns 0 and the test fails. I ran this class together with `ReconnectCorrectnessTest` 65 times in a scratch copy while other builds were running on the machine (load average about 2.5). The test failed once with exactly this signature: the first 17 values of `seen` were 1, the last three were 0, and the build took 19 seconds (three waits of 5 seconds). It is a test flaw, not a feed flaw: the invariant it checks was never broken.

```java
  @Test
  void tellsASubscriberWhoseQueueFillsToReloadAndKeepsTheQueueBounded() throws Exception {
    SeatFeed feed = new SeatFeed(1_000, 4, 64, metrics);
    SeatStore store = new SeatStore(new SeatLayout(1, 100), Duration.ofMinutes(5), clock, feed);
    Subscriber slow = feed.subscribe(feed.position());

    for (int seat = 0; seat < 50; seat++) {
      store.hold(seat);
    }

    assertThat(slow.next(SHORT)).isInstanceOf(Delivery.Reload.class);
    assertThat(metrics.render())
        .contains("seatwave_feed_queue_overflows_total 1", "seatwave_feed_deepest_client_queue 4");
  }
```

- A feed whose subscribers may queue only 4 changes (the second argument). A subscriber `slow` joins at the current position and then **never reads**.
- 50 seats are held. The fifth change finds the queue full: the subscriber drops its queue and marks itself for reload. The publisher never blocked.
- `slow.next(SHORT)` returns `Delivery.Reload`: the first thing the slow client is told is to reload.
- The metrics text contains `seatwave_feed_queue_overflows_total 1` (one overflow, not one per later change: once marked, the subscriber ignores more) and `seatwave_feed_deepest_client_queue 4` (the queue never held more than its bound). That is the memory bound per slow client, proved.

```java
  @Test
  void tellsASubscriberWithAnOldPositionToReloadAtOnce() throws Exception {
    SeatFeed feed = new SeatFeed(3, 100, 64, metrics);
    SeatStore store = new SeatStore(new SeatLayout(1, 10), Duration.ofMinutes(5), clock, feed);
    for (int seat = 0; seat < 6; seat++) {
      store.hold(seat);
    }

    Subscriber late = feed.subscribe(new FeedPosition(feed.streamId(), 1));

    assertThat(late.next(SHORT)).isInstanceOf(Delivery.Reload.class);
  }
```

- A feed that keeps only 3 changes; 6 are published. A subscriber that arrives with position 1 needs change 2, which is gone. `feed.subscribe` marks it for reload at once, and `late.next(SHORT)` is a `Delivery.Reload` without any wait.

```java
  @Test
  void answersALongPollAtTheNextChangeOrAtTheEndOfTheHold() throws Exception {
    SeatFeed feed = new SeatFeed(100, 100, 64, metrics);
    SeatStore store = new SeatStore(new SeatLayout(1, 10), Duration.ofMinutes(5), clock, feed);
    FeedPosition start = feed.position();

    Catchup empty = feed.awaitChangesAfter(start, SHORT);
    CompletableFuture<Catchup> waiting =
        CompletableFuture.supplyAsync(() -> {
          try {
            return feed.awaitChangesAfter(start, LONG);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
        });
    Thread.sleep(SHORT);
    store.hold(1);

    assertThat(((Catchup.Changes) empty).changes()).isEmpty();
    assertThat(((Catchup.Changes) waiting.get(LONG.toMillis(), TimeUnit.MILLISECONDS)).changes())
        .hasSize(1);
    assertThat(((Catchup.Changes) feed.changesAfter(start)).changes()).hasSize(1);
  }
```

This is the long poll test: a request that waits on the server for the next change.
- `feed.awaitChangesAfter(start, SHORT)` with nothing published waits 50 ms and returns a `Changes` answer with an empty list: the **end of the hold**. This part uses real time, 50 ms.
- `CompletableFuture.supplyAsync(() -> { try { return feed.awaitChangesAfter(start, LONG); } catch (InterruptedException interrupted) { ... } })` starts a second poll on another thread with a 5-second hold. The lambda needs `try` and `catch` because `awaitChangesAfter` can throw the checked `InterruptedException`, which a plain lambda here cannot pass on. The catch restores the interrupt flag (`Thread.currentThread().interrupt()`) and rethrows as `IllegalStateException`.
- `Thread.sleep(SHORT);` gives that poll 50 ms to start waiting. Then `store.hold(1)` publishes a change, which wakes the poll.
- The checks: the first answer was empty; `waiting.get(LONG.toMillis(), TimeUnit.MILLISECONDS)` fetches the second poll's answer and it holds 1 change; `feed.changesAfter(start)` (the no-wait form) also finds 1.
- If the machine is so slow that the hold happens before the poll starts waiting, the poll finds the change at once and the test still passes. The sleep only makes sure the waiting path is the one exercised; the test cannot fail because of it.

```java
  @Test
  void wakesAWaitingWriterAndSaysIdleWhenNothingHappens() throws Exception {
    SeatFeed feed = new SeatFeed(100, 100, 64, metrics);
    Subscriber subscriber = feed.subscribe(feed.position());

    assertThat(subscriber.next(SHORT)).isInstanceOf(Delivery.Idle.class);
    subscriber.wake();
    assertThat(subscriber.next(LONG)).isInstanceOf(Delivery.Woken.class);
    feed.unsubscribe(subscriber);
  }
```

- A subscriber with nothing to read: `next(SHORT)` returns `Delivery.Idle` after 50 ms, the signal that the writer should send a heartbeat. This is a real 50 ms wait.
- `subscriber.wake()` asks the waiting writer to wake without a change. `next(LONG)` returns `Delivery.Woken` at once; the 5 seconds is only a ceiling. A writer uses this to send a pong or a close frame that another thread asked for.
- `feed.unsubscribe(subscriber)` takes the subscriber out of the feed.

```java
  @Test
  void interruptsOnlyAWriterStuckLongerThanTheTimeout() throws Exception {
    SeatFeed feed = new SeatFeed(100, 100, 64, metrics);
    Subscriber stuck = feed.subscribe(feed.position());
    Subscriber writing = feed.subscribe(feed.position());
    Thread blocked =
        Thread.ofVirtual()
            .start(() -> {
              try {
                Thread.sleep(LONG.multipliedBy(4));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    stuck.beginWrite(blocked);
    Thread.sleep(SHORT.multipliedBy(2));
    writing.beginWrite(Thread.currentThread());

    int interrupted = feed.disconnectStuckWriters(SHORT);
    blocked.join(LONG);

    assertThat(interrupted).isEqualTo(1);
    assertThat(blocked.isAlive()).isFalse();
    writing.endWrite();
    assertThat(metrics.render()).contains("seatwave_feed_stuck_writer_disconnects_total 1");
  }
```

- Two subscribers: `stuck` and `writing`. A write is the act of sending bytes to a client's socket; if the client does not read, the write blocks. The feed finds writers that have been blocked too long and interrupts them.
- A virtual thread `blocked` stands in for the stuck writer: it sleeps for `LONG.multipliedBy(4)` (20 seconds) unless interrupted. When interrupted it sets the interrupt flag again and ends.
- `stuck.beginWrite(blocked)` records "this thread started a write now". The test sleeps 100 ms. Then `writing.beginWrite(Thread.currentThread())` records a second write, started now, by the test thread itself.
- `feed.disconnectStuckWriters(SHORT)` interrupts every writer whose write started more than 50 ms ago, and returns how many. The first started 100 ms ago and the second microseconds ago. `blocked.join(LONG)` waits for the interrupted thread to end.
- The checks: exactly 1 writer was interrupted; the `blocked` thread is no longer alive; after `writing.endWrite()` the metrics say `seatwave_feed_stuck_writer_disconnects_total 1`.
- This is the most time-dependent test of the package: it needs the gap between the two `beginWrite` calls and the check to behave as described. If the test thread were paused for more than 50 ms between `writing.beginWrite` and the check, `writing` would be interrupted too and the test would fail. The margin is 50 ms against a normal gap of microseconds.

```java
  // Takes changes until the given sequence has arrived; returns 1 if every sequence came once.
  private static long drainUntil(Subscriber subscriber, long target) throws InterruptedException {
    long expected = -1;
    while (true) {
      Delivery delivery = subscriber.next(LONG);
      if (!(delivery instanceof Delivery.Batch batch)) {
        return 0;
      }
      for (SeatChange change : batch.changes()) {
        long sequence = change.position().sequence();
        if (expected != -1 && sequence != expected) {
          return 0;
        }
        expected = sequence + 1;
        if (sequence >= target) {
          return 1;
        }
      }
    }
  }
```

`drainUntil` is a helper that reads a subscriber until a target sequence arrives.
- `long expected = -1;` means "the first sequence is not known yet".
- `while (true)` reads `subscriber.next(LONG)` repeatedly. The comment above the method says what it returns: 1 if every sequence came once.
- `if (!(delivery instanceof Delivery.Batch batch)) { return 0; }` is a **pattern match**: if the delivery is not a batch (it is a reload, an idle tick or a wake) the function gives up with 0, because a gapless stream must be all batches here. If it is a batch, the variable `batch` holds it, already cast.
- For each change: `sequence` is its number. If `expected` is known and the number is not the expected one, there is a gap or a repeat: return 0.
- `expected = sequence + 1;` is the number that must come next.
- `if (sequence >= target) { return 1; }` reaching the target means success.

```java
  private static long sequencesIn(Subscriber subscriber, int total) throws InterruptedException {
    long next = 1;
    while (next <= total) {
      Delivery delivery = subscriber.next(LONG);
      if (!(delivery instanceof Delivery.Batch batch)) {
        return -1;
      }
      for (SeatChange change : batch.changes()) {
        if (change.position().sequence() != next) {
          return -1;
        }
        next++;
      }
    }
    return next - 1;
  }
```

`sequencesIn` is the stricter helper for the last subscriber: it demands the numbers 1, 2, 3 and every next number in turn, with no other kind of delivery, until `total` have been read. It returns `-1` for a wrong number or a non-batch delivery and `next - 1` (the total read) at the end. The test compares it with `holds`.

## Try it yourself

All commands ran in a scratch copy of this repository on 11 November 2026. Timings differ on every run.

1. Run the whole `feed` package:

```
./gradlew --offline cleanTest test --tests 'io.plainround.seatwave.live.feed.*'
```

   Expected: 41 `PASSED` lines (2 + 13 + 3 + 6 + 6 + 4 + 7), then `BUILD SUCCESSFUL`.

2. Run only the concurrency test and look at its time:

```
./gradlew --offline cleanTest test --tests '*SeatFeedTest.givesASubscriberEveryChangeExactlyOnceWhileChangesArePublishedAsItJoins'
```

   Expected: one `PASSED` line. The XML result `build/test-results/test/TEST-io.plainround.seatwave.live.feed.SeatFeedTest.xml` lists each test with its time; the whole class took 0.296 seconds in the run for these notes.

3. Break the invariant on purpose, in a scratch copy only. In `ReplayBuffer.after` (main code) change `known.sequence() + 1 < oldestKept` to `known.sequence() + 1 < oldestKept - 1` and run `./gradlew --offline test --tests '*ReplayBufferTest'`. Expect `tellsAClientWhoseNextChangeWasOverwrittenToReload() FAILED`: the boundary test catches the off-by-one.

4. Count the tests with the real-time sleeps:

```
grep -n "Thread.sleep" src/test/java/io/plainround/seatwave/live/feed/*.java
```

   Expected: three lines, all in `SeatFeedTest.java` (lines 92, 121 and 127).

## Mistakes people make

- **Asserting an interleaving.** A test that expects thread A to finish before thread B passes on your machine and fails on a busy one. These tests assert invariants.
- **Using the real clock for expiry.** A test that waits 300 seconds is useless. Give the code a clock you can move.
- **Starting a background timer in a unit test.** The simulator's `start()` would race the test's own `tick()` calls. The tests never call it.
- **Forgetting that `Random` without a seed changes every run.** The tests pass a seed.
- **Testing only the middle.** The boundary tests (hold time exactly reached, position exactly at the edge of the buffer) are where mistakes live.
- **Closing a resource twice and counting twice.** The first `ConnectionLimitTest` test closes one ticket twice on purpose.

## Check yourself

1. *Why does `OnSaleSimulatorTest` call `tick()` instead of `start()`?* `start()` runs the simulator on a timer thread, so the result would depend on real time and thread scheduling. `tick()` runs one step at a time, under the test's control.
2. *How does `SeatStoreTest` make 50 fans start at the same moment?* Each waits on a `CountDownLatch`; the test counts it down once after all are submitted.
3. *What does the `Catchup` sealed interface say about gaps?* It has only `Changes` and `ReloadRequired`, so no code path can return a gap.
4. *Which test depends most on real time, and why?* `interruptsOnlyAWriterStuckLongerThanTheTimeout`: one write must be older than 50 ms and another younger.
5. *Why is the confirm of a hold at exactly the hold time refused?* The rule is "valid before the expiry time"; the test checks the boundary.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- AssertJ Core 3.27.7: <https://assertj.github.io/doc/>
- Java 25 API, `java.util.concurrent` (latches, executors, futures): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/package-summary.html>
- Java 25 API, `java.time.Clock`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html>
