# Walkthrough of the `feed` package

## What it is

The `feed` package is the heart of `seatwave-live`. It holds the seat map in memory, gives every change of a seat a number, remembers the most recent changes, and keeps one small waiting line of changes for every connected client. It is plain Java. It knows nothing about HTTP, sockets or JSON.

A **package** is a named folder of Java files. The package is `io.plainround.seatwave.live.feed`, which is the folder `src/main/java/io/plainround/seatwave/live/feed/`. It has 16 files. This note goes through them one by one and explains every line that is not obvious. The language words (record, sealed interface, enum, generic, lambda) are taught from zero in `java.md`; each is explained again, briefly, where it first appears here. The threading words (lock, condition, queue, atomic) are taught in `java-concurrency-in-the-feed.md`.

## Why this repository uses it

The lesson of Module 8 is that a live feed must be correct before it is fast. A client that names the last change it saw must receive every later change exactly once, or be told to load the whole seat map again. It must never have a silent gap. It must also never let one slow client slow down the others. All of that is decided in this package. The three delivery methods (long polling, server-sent events and WebSockets) are only three ways to carry what this package produces.

## Where it appears

| File | What it is |
|---|---|
| `SeatState.java` | The three states of a seat and their JSON names and one-letter codes. |
| `SeatLayout.java` | The shape of the seat map and the seat labels such as `A-7`. |
| `FeedPosition.java` | A place in the feed, written `<stream>.<sequence>`. |
| `SeatChange.java` | One numbered change of one seat. |
| `SeatMapSnapshot.java` | The whole map at one position. |
| `SeatRefusal.java` | Why the store said no. |
| `Catchup.java` | The answer to "what did I miss": changes, or reload. |
| `Delivery.java` | What a connection's writer should send next. |
| `DeliveryMethod.java` | The three methods and their labels. |
| `ReplayBuffer.java` | The ring of recent changes. |
| `Subscriber.java` | One push connection's bounded queue. |
| `SeatFeed.java` | The publisher: numbers, remembers, hands out. |
| `SeatStore.java` | The seats, holds and confirms, in memory. |
| `OnSaleSimulator.java` | A fake on-sale that makes real changes. |
| `ConnectionLimit.java` | The hard limit on open connections. |
| `FeedMetrics.java` | Counters for `/metrics`. |

## How it works underneath

Picture the data in four layers.

1. **Values.** `SeatState`, `SeatLayout`, `FeedPosition`, `SeatChange`, `SeatMapSnapshot`, `Catchup`, `Delivery`. They never change after they are made (they are immutable). Immutable values can be shared between threads with no lock.
2. **The record of what happened.** `ReplayBuffer` is a fixed-size ring. When it is full, the newest change overwrites the oldest. Its size is the setting `feed.replayCapacity` (50,000 in `config/serve.properties`).
3. **The people waiting.** Every push connection (server-sent events or WebSocket) owns one `Subscriber`: a bounded queue of changes plus the rules for what happens when it fills. The setting `feed.clientQueueCapacity` (1,024 in `config/serve.properties`) is its bound.
4. **The one who numbers.** `SeatFeed` takes a change from the store, gives it the next number, writes it into the ring, and offers it to every subscriber's queue. It does these steps under one lock, so the order of the feed is the order of the store.

A **thread** is one line of execution inside the program. Many threads run at the same time. A **lock** lets one thread at a time into a piece of code. The store has a lock and the feed has a lock. The rule, written in the code, is that a thread that needs both takes the store's lock first and the feed's lock second, never the other way round. That fixed order is what prevents a deadlock (two threads each holding one lock and waiting for the other's).

## Walkthrough of this repository's use

Every block below is copied from the source file by a script, so the lines are exact. The files are taken in the order that builds up the ideas: values first, then the ring and the queue, then the publisher, the store and the rest.

### `SeatState.java`

```java
package io.plainround.seatwave.live.feed;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three states a seat shows on the seat map, with the names Module 7's version 1 API uses for
 * them ({@code available}, {@code held}, {@code sold}) and a one-letter code for the compact seat
 * map.
 */
```

`package` names the folder. `import java.util.Arrays;` and `import java.util.Optional;` let the file write the short names `Arrays` and `Optional`. An `Optional<T>` is a box that holds either one value or nothing; it is how this code says "maybe there is an answer" without using `null`. The Javadoc comment (between `/**` and `*/`) is documentation that tools read; `{@code available}` marks a piece of code inside it.

```java
public enum SeatState {
  /** Nobody holds or owns the seat. */
  AVAILABLE("available", 'a'),
  /** A fan holds the seat until the hold expires or is confirmed. */
  HELD("held", 'h'),
  /** The seat is booked. */
  SOLD("sold", 's');
```

An **enum** is a type with a fixed list of values. Here there are exactly three: `AVAILABLE`, `HELD` and `SOLD`. Each value is created with two arguments in brackets: a text name for JSON (`"available"`) and a one-letter code (`'a'`). Double quotes make a `String`; single quotes make a `char`, one character. The names are those of Module 7's API, so the feed and the API agree.

```java
  private final String wireName;
  private final char code;

  SeatState(String wireName, char code) {
    this.wireName = wireName;
    this.code = code;
  }
```

Each value carries two fields. `private` means only this file may read them; `final` means they are set once. The constructor has no `public` or `private` word: an enum constructor is always private to the enum, and only the three lines above can call it.

```java
  /**
   * The name used in JSON, as in Module 7's contract.
   *
   * @return {@code available}, {@code held} or {@code sold}
   */
  public String wireName() {
    return wireName;
  }
```

A getter. It returns the JSON name. The two getters exist because the fields are private.

```java
  /**
   * The one-letter code used in the compact seat map.
   *
   * @return {@code a}, {@code h} or {@code s}
   */
  public char code() {
    return code;
  }
```

The second getter, for the one-letter code used in the compact seat map (a string with one letter per seat).

```java
  /**
   * Finds the state with a JSON name.
   *
   * @param wireName the name
   * @return the state, or empty for any other text
   */
  public static Optional<SeatState> fromWireName(String wireName) {
    return Arrays.stream(values()).filter(state -> state.wireName.equals(wireName)).findFirst();
  }
```

`fromWireName` finds the state with a given JSON name. `values()` is a method every enum has: it returns an array of all its values. `Arrays.stream(...)` turns the array into a **stream**, a pipeline of items. `.filter(state -> state.wireName.equals(wireName))` keeps the items for which the **lambda** (a small unnamed function, written `parameter -> result`) is true. `.findFirst()` takes the first one and wraps it in an `Optional`; an empty stream gives an empty `Optional`. For an unknown name the answer is "empty", not an exception.

```java
  /**
   * Finds the state with a one-letter code.
   *
   * @param code the code
   * @return the state, or empty for any other character
   */
  public static Optional<SeatState> fromCode(char code) {
    return Arrays.stream(values()).filter(state -> state.code == code).findFirst();
  }
}
```

`fromCode` is the same search by code. In the lambda `state.code == code` uses `==` because both sides are `char`, a number-like type that is compared by value.

### `SeatLayout.java`

```java
package io.plainround.seatwave.live.feed;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shape of the event's seat map: sections lettered from A, each with seats numbered from 1, as
 * in Module 1 (20 sections of 1,000 seats, labels such as {@code A-7}). Every seat also has an
 * index, section by section and then by number, which is its place in the compact seat map.
 *
 * @param sections how many sections, 1 to 20 (A to T)
 * @param seatsPerSection how many seats each section has, 1 to 1,000
 */
public record SeatLayout(int sections, int seatsPerSection) {
```

A **record** is a short way to write a class that only carries data. `record SeatLayout(int sections, int seatsPerSection)` makes a class with two private final fields, a constructor, getters named `sections()` and `seatsPerSection()`, and `equals`, `hashCode` and `toString`. `int` is a 32-bit whole number. The imports bring in `Pattern` and `Matcher` (regular expressions, patterns for matching text), `Locale` (a language and region setting), and `Objects`.

```java
  /** Module 1's arena: sections A to T. */
  public static final int MAX_SECTIONS = 20;

  /** Module 1's arena: 1,000 seats per section. */
  public static final int MAX_SEATS_PER_SECTION = 1_000;

  private static final Pattern LABEL = Pattern.compile("([A-T])-([1-9][0-9]{0,3})");
  private static final char FIRST_SECTION = 'A';
```

Constants. `public static final` means one shared value that never changes and anyone may read: `SeatLayout.MAX_SECTIONS`. The underscore in `1_000` is ignored by the compiler and only helps the eye. The pattern `([A-T])-([1-9][0-9]{0,3})` reads: one capital letter from A to T (group 1), a hyphen, then a number with no leading zero and one to four digits (group 2). A **regular expression** is a compact description of a set of strings; the round brackets mark **groups** that can be read out afterwards. `FIRST_SECTION` is the character `'A'`.

```java
  /** Checks the fields, so an impossible layout cannot exist. */
  public SeatLayout {
    if (sections < 1 || sections > MAX_SECTIONS) {
      throw new IllegalArgumentException("sections out of range: " + sections);
    }
    if (seatsPerSection < 1 || seatsPerSection > MAX_SEATS_PER_SECTION) {
      throw new IllegalArgumentException("seatsPerSection out of range: " + seatsPerSection);
    }
  }
```

A **compact constructor**: the record's constructor without a parameter list. It runs before the fields are set, and a thrown `IllegalArgumentException` stops the object from existing. So a `SeatLayout` of 0 sections, or of 21, can never exist.

```java
  /**
   * How many seats the event has.
   *
   * @return sections times seats per section
   */
  public int seatCount() {
    return sections * seatsPerSection;
  }
```

`seatCount` multiplies. With the standard layout of 20 sections of 1,000 seats it is 20,000.

```java
  /**
   * The label of a seat, such as {@code A-7}.
   *
   * @param seatIndex the seat's index, from 0
   * @return the label
   * @throws IllegalArgumentException if the index is outside the map
   */
  public String label(int seatIndex) {
    if (!contains(seatIndex)) {
      throw new IllegalArgumentException("no seat at index " + seatIndex);
    }
    char section = (char) (FIRST_SECTION + seatIndex / seatsPerSection);
    return String.format(Locale.ROOT, "%c-%d", section, seatIndex % seatsPerSection + 1);
  }
```

`label` turns an index into a label. The seats are numbered from 0, section by section. Integer division drops the fraction: with 1,000 seats per section, index 6 gives `6 / 1000 = 0`, so the section is `'A' + 0`, which is `'A'`, and `6 % 1000 + 1 = 7`, so the label is `A-7`. The `%` sign is the remainder. A `char` plus an `int` is an `int`, so the cast `(char)` turns it back into a letter. `String.format(Locale.ROOT, "%c-%d", ...)` fills a template: `%c` is a character and `%d` a whole number. `Locale.ROOT` means "no local customs", so digits are never written in another script.

```java
  /**
   * The index of a labelled seat.
   *
   * @param label a label such as {@code A-7}
   * @return the index, or empty if the label is malformed or names no seat of this layout
   */
  public Optional<Integer> indexOf(String label) {
    Matcher matcher = LABEL.matcher(Objects.requireNonNull(label, "label"));
    if (!matcher.matches()) {
      return Optional.empty();
    }
    int section = matcher.group(1).charAt(0) - FIRST_SECTION;
    int number = Integer.parseInt(matcher.group(2));
    if (section >= sections || number > seatsPerSection) {
      return Optional.empty();
    }
    return Optional.of(section * seatsPerSection + number - 1);
  }
```

`indexOf` goes the other way. `matcher.matches()` is true only if the whole text fits the pattern. `matcher.group(1).charAt(0) - FIRST_SECTION` is the letter's distance from `A` (for `D`, 3). `Integer.parseInt` turns the digits into a number; the pattern has already guaranteed they are digits. The two bounds checks refuse a label such as `Z-1` when there are only 20 sections, or `A-1001` when a section has 1,000 seats. The index is `section * seatsPerSection + number - 1`. Both failures return `Optional.empty()`.

```java
  /**
   * Tells whether an index names a seat of this layout.
   *
   * @param seatIndex the index
   * @return true when it is from 0 to the seat count less one
   */
  public boolean contains(int seatIndex) {
    return seatIndex >= 0 && seatIndex < seatCount();
  }
}
```

`contains` is the one place that says what a valid index is. `&&` means "and", and it stops at the first false.

### `FeedPosition.java`

```java
package io.plainround.seatwave.live.feed;

import com.github.sviperll.result4j.Result;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A place in the feed: the stream it belongs to and the sequence number of the last change a client
 * has. It is the feed's cursor (Module 7's idea: a cursor names a place instead of counting items),
 * written as {@code <stream>.<sequence>}, for example {@code 3f9a2c1e.42}.
 *
 * <p>The stream identifier changes every time the feed starts, because the replay buffer lives in
 * memory: a position from an earlier process would otherwise point at changes that never happened
 * in this one, a silent gap. A client with another stream's position is told to reload.
 *
 * @param streamId eight lowercase hexadecimal digits, chosen at random when the feed starts
 * @param sequence the number of the last change applied; 0 means before the first change
 */
public record FeedPosition(String streamId, long sequence) {
```

The position is the feed's **cursor**: a value that names a place in the feed. It has two parts. The **stream identifier** is eight hex digits chosen at random when the feed starts. The **sequence** is the number of the last change. A written position looks like `4d4f4a94.231`. The stream identifier exists because the ring lives in memory: after a restart the numbering starts again, and a position from before would silently name different changes. `Result<FeedPosition, String>` comes from the library `result4j` (explained in `result4j.md`): it is either a success holding a position or an error holding a sentence. `long` is a 64-bit whole number.

```java
  private static final Pattern STREAM_ID = Pattern.compile("[0-9a-f]{8}");
  // A sequence is at most 18 digits, so it always fits in a long.
  private static final Pattern WRITTEN = Pattern.compile("([0-9a-f]{8})\\.(0|[1-9][0-9]{0,17})");

  /** Checks the fields. */
  public FeedPosition {
    if (!STREAM_ID.matcher(Objects.requireNonNull(streamId, "streamId")).matches()) {
      throw new IllegalArgumentException("streamId must be eight lowercase hex digits");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative: " + sequence);
    }
  }
```

`STREAM_ID` is exactly eight characters from `0-9a-f`. `WRITTEN` is that, a literal dot (`\\.` in the Java string, because Java needs a second backslash to write one), then the sequence: either `0` or a number that does not start with 0 and has at most 18 digits. 18 digits cannot overflow a `long`, which tops out near 9.2 times ten to the 18th power. The compact constructor checks both fields, so a bad position cannot be built.

```java
  /**
   * Reads a position as a client sent it, in a query parameter or a {@code Last-Event-ID} header.
   *
   * @param written the text
   * @return the position, or a sentence saying what is wrong
   */
  public static Result<FeedPosition, String> parse(String written) {
    Objects.requireNonNull(written, "written");
    Matcher matcher = WRITTEN.matcher(written);
    if (!matcher.matches()) {
      return Result.error("must be <8 hex digits>.<sequence>, as the feed sent it");
    }
    return Result.success(new FeedPosition(matcher.group(1), Long.parseLong(matcher.group(2))));
  }
```

`parse` is how text from a client becomes a position. It never throws for bad input: it returns `Result.error(...)` with a sentence that the HTTP layer puts in the error body. `matcher.group(1)` is the stream identifier and `matcher.group(2)` the digits, which `Long.parseLong` converts.

```java
  /**
   * The position as clients see it.
   *
   * @return {@code <stream>.<sequence>}
   */
  public String written() {
    return streamId + "." + sequence;
  }
```

`written` is the reverse: the text a client sees. Joining text with `+` is called concatenation.

```java
  /**
   * The position one change later in the same stream.
   *
   * @return the next position
   */
  public FeedPosition next() {
    return new FeedPosition(streamId, Math.addExact(sequence, 1));
  }
}
```

`next` makes the position one change later. `Math.addExact` adds and throws `ArithmeticException` instead of wrapping around if the sum would overflow, so a counter can never silently go negative.

### `SeatChange.java`

```java
package io.plainround.seatwave.live.feed;

import java.time.Instant;
import java.util.Objects;

/**
 * One change of one seat's state, numbered by the feed in the order the store made it.
 *
 * @param position the feed position of this change; its sequence is one more than the change before
 *     it
 * @param seatIndex the seat, as an index into the seat layout
 * @param state the seat's new state
 * @param changedAt when the store made the change, in UTC; the delivery delay is measured from here
 */
public record SeatChange(FeedPosition position, int seatIndex, SeatState state, Instant changedAt) {
```

One change. Four parts: where it is in the feed, which seat (as an index), its new state, and when the store made it. `Instant` is a point on the time line in UTC. The comment says the delivery delay is measured from `changedAt`: the load client subtracts it from its own clock when the change arrives.

```java
  /** Checks the fields. */
  public SeatChange {
    Objects.requireNonNull(position, "position");
    if (position.sequence() < 1) {
      throw new IllegalArgumentException("a change has a sequence of 1 or more");
    }
    if (seatIndex < 0) {
      throw new IllegalArgumentException("seatIndex must not be negative: " + seatIndex);
    }
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(changedAt, "changedAt");
  }
}
```

The compact constructor refuses nonsense: a change numbered 0 cannot exist (sequence 0 means "before the first change"), and a seat index cannot be negative. `Objects.requireNonNull(x, "name")` throws `NullPointerException` with that name if `x` is `null`.

### `SeatMapSnapshot.java`

```java
package io.plainround.seatwave.live.feed;

import java.util.Objects;

/**
 * The whole seat map at one feed position: every seat's state, and the position of the last change
 * it includes. A client that loads it and then asks for the changes after its position has every
 * change exactly once.
 *
 * @param position the position of the last change included
 * @param layout the shape of the map
 * @param states one {@link SeatState#code()} per seat, in index order
 */
public record SeatMapSnapshot(FeedPosition position, SeatLayout layout, String states) {
```

A snapshot is the whole map as a string of letters (one per seat) plus the position of the last change that string includes. A client that loads it and then asks for the changes after that position sees every change exactly once.

```java
  /** Checks that the states match the layout. */
  public SeatMapSnapshot {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(states, "states");
    if (states.length() != layout.seatCount()) {
      throw new IllegalArgumentException("one state per seat is required");
    }
    if (!states.chars().allMatch(code -> SeatState.fromCode((char) code).isPresent())) {
      throw new IllegalArgumentException("states may hold only a, h and s");
    }
  }
}
```

The constructor checks that the string has one letter per seat. `states.chars()` is a stream of the string's characters as numbers. `allMatch` is true when every one passes the test; the lambda casts each number back to a `char` and asks `SeatState.fromCode` whether it is `a`, `h` or `s`.

### `SeatRefusal.java`

```java
package io.plainround.seatwave.live.feed;

/** Why the seat store refused a change; expected outcomes, not errors. */
public enum SeatRefusal {
  /** The seat is held or sold, so it cannot be held. */
  SEAT_TAKEN,
  /** The seat is not held, or its hold has expired, so it cannot be confirmed. */
  NOT_HELD,
  /** No seat has that index. */
  UNKNOWN_SEAT
}
```

An enum of three outcomes that are normal, not errors: the seat is already taken, there is no live hold to confirm, or there is no such seat. The store returns them inside a `Result` instead of throwing, because "someone else was faster" happens all the time in an on-sale.

### `Catchup.java`

```java
package io.plainround.seatwave.live.feed;

import java.util.List;
import java.util.Objects;

/**
 * What the feed can give a client that names the position it has: the changes after it, or an
 * instruction to reload the whole seat map. There is no third answer, so no code path can hand a
 * client a gap.
 */
public sealed interface Catchup {
```

A **sealed interface** lists, or implies, all the types that may implement it. Here the two nested records `Changes` and `ReloadRequired` are the only ones, because they are in the same file and nobody else may add one. That is what the comment means: "There is no third answer, so no code path can hand a client a gap." A `switch` over a `Catchup` is checked by the compiler to cover both.

```java
  /**
   * Every change after the client's position, in order, with none missing.
   *
   * @param changes the changes, possibly none
   * @param position the position after the last of them (the client's own if there are none)
   */
  record Changes(List<SeatChange> changes, FeedPosition position) implements Catchup {

    /** Copies the list, so it cannot change later. */
    public Changes {
      changes = List.copyOf(changes);
      Objects.requireNonNull(position, "position");
    }
  }
```

`Changes` carries the changes in order and the position after the last of them (or the client's own if there are none). The compact constructor replaces the list with `List.copyOf(changes)`, an unmodifiable copy, so nobody can change it later. It throws `NullPointerException` if the list is `null` or holds a `null`.

```java
  /**
   * The feed no longer holds every change after the client's position (or the position belongs to
   * another stream), so the client must load the whole seat map again.
   *
   * @param position the feed's current position, for information
   */
  record ReloadRequired(FeedPosition position) implements Catchup {

    /** Checks the field. */
    public ReloadRequired {
      Objects.requireNonNull(position, "position");
    }
  }
}
```

`ReloadRequired` carries the feed's current position, for information. It is the answer when the ring no longer reaches back to the client's position, or the position belongs to another stream.

### `Delivery.java`

```java
package io.plainround.seatwave.live.feed;

import java.util.List;

/** What a connection's writer should send next, as {@link Subscriber#next} decides it. */
public sealed interface Delivery {

  /**
   * Changes to send, in order.
   *
   * @param changes one or more changes
```

Another sealed interface: the four things a connection's writer can be told to do next. `import java.util.List;` is for the batch.

```java
   */
  record Batch(List<SeatChange> changes) implements Delivery {

    /** Copies the list. */
    public Batch {
      changes = List.copyOf(changes);
    }
  }
```

`Batch` is one or more changes to write. It copies the list as `Catchup.Changes` does.

```java
  /** The client fell too far behind: tell it to reload the seat map, then end the connection. */
  record Reload() implements Delivery {}

  /** Nothing happened for a heartbeat interval: send a heartbeat so idle links stay open. */
  record Idle() implements Delivery {}

  /** Another part of the connection asked the writer to wake, for example to answer a ping. */
  record Woken() implements Delivery {}
}
```

`Reload`, `Idle` and `Woken` carry no data, so their braces are empty. `Reload` means the client fell too far behind. `Idle` means "nothing happened for a heartbeat interval, send a heartbeat". `Woken` means another thread asked the writer to wake, for example to answer a WebSocket ping.

### `DeliveryMethod.java`

```java
package io.plainround.seatwave.live.feed;

/** The three ways the feed delivers seat changes, with the label used in metrics and reports. */
public enum DeliveryMethod {
  /** A request held open until there is news or a timeout, then asked again. */
  LONG_POLLING("long-polling"),
  /** One long HTTP response that carries events (the WHATWG event stream format). */
  SERVER_SENT_EVENTS("sse"),
  /** An upgraded two-way connection carrying frames (RFC 6455). */
  WEBSOCKET("websocket");
```

Three enum values with a label each. The labels `long-polling`, `sse` and `websocket` are what appears in `/metrics` and in the reports. They are the only label values, so the number of metric series stays fixed.

```java
  private final String label;

  DeliveryMethod(String label) {
    this.label = label;
  }

  /**
   * The low-cardinality label used in metric names and reports.
   *
   * @return {@code long-polling}, {@code sse} or {@code websocket}
   */
  public String label() {
    return label;
  }
}
```

The usual enum private field, constructor and getter.

### `ReplayBuffer.java`

```java
package io.plainround.seatwave.live.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The most recent changes of the feed, in a fixed-size ring: when it is full, the oldest change is
 * overwritten. A client that reconnects within the buffer's window resumes from it; one that has
 * been away longer is told to reload.
 *
 * <p>Not thread-safe: {@link SeatFeed} uses it only while holding its lock.
 */
final class ReplayBuffer {
```

The class has no `public`, so only the `feed` package can use it. The comment says it is not thread-safe on its own: `SeatFeed` only touches it while holding the feed's lock. `ArrayList` is a growable list.

```java
  private final SeatChange[] ring;
  private final String streamId;
  private long lastSequence;

  ReplayBuffer(String streamId, int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    this.streamId = Objects.requireNonNull(streamId, "streamId");
    this.ring = new SeatChange[capacity];
  }
```

`SeatChange[] ring` is an **array**: a fixed-size row of slots, here `capacity` long. `lastSequence` is the number of the newest change, and starts at 0. The constructor refuses a capacity below 1.

```java
  void append(SeatChange change) {
    // The feed numbers changes; a hole here would be a bug, never a client's mistake.
    if (change.position().sequence() != lastSequence + 1) {
      throw new IllegalStateException("changes must arrive in order without gaps");
    }
    lastSequence = change.position().sequence();
    ring[slot(lastSequence)] = change;
  }
```

`append` first checks that the new change is exactly one more than the last. A gap is a bug in the feed, not a client mistake, so it throws `IllegalStateException`. Then it stores the change in slot `slot(sequence)`.

```java
  private int slot(long sequence) {
    return (int) (sequence % ring.length);
  }
}
```

`slot` is `sequence % ring.length`: the remainder after dividing. Take a ring of 4 slots. Sequence 1 goes to slot 1, 2 to slot 2, 3 to slot 3, 4 to slot 0, and 5 to slot 1, overwriting 1. The `(int)` cast turns the `long` remainder into an `int`; it is always smaller than the ring length, so nothing is lost.

```java
  FeedPosition position() {
    return new FeedPosition(streamId, lastSequence);
  }
```

`position` is the stream identifier and the newest sequence.

```java
  Catchup after(FeedPosition known, int maxChanges) {
    if (!known.streamId().equals(streamId) || known.sequence() > lastSequence) {
      // Another stream, or a sequence this stream never reached: the client's map came from an
      // earlier process, and replaying from it would apply the wrong history.
      return new Catchup.ReloadRequired(position());
    }
    long oldestKept = Math.max(1, lastSequence - ring.length + 1);
    if (known.sequence() + 1 < oldestKept) {
```

`after` answers "what did I miss". The first check says reload for two cases. A different stream identifier means the client's number came from an earlier process. A sequence beyond `lastSequence` is a number this stream never reached, which can only be a wrong or invented position. Replaying history from there would apply the wrong changes, so the answer is reload. Then `oldestKept` is the lowest sequence still in the ring: the newest sequence minus the ring length plus one, but never below 1.

```java
      return new Catchup.ReloadRequired(position());
    }
    long lastToSend = Math.min(lastSequence, known.sequence() + maxChanges);
    List<SeatChange> changes = new ArrayList<>();
    for (long sequence = known.sequence() + 1; sequence <= lastToSend; sequence++) {
      changes.add(ring[slot(sequence)]);
    }
    return new Catchup.Changes(changes, new FeedPosition(streamId, lastToSend));
  }
```

If the first change the client needs (`known.sequence() + 1`) is older than `oldestKept`, it has been overwritten: reload. Example with a ring of 4 and newest sequence 10: the ring holds 7, 8, 9, 10, so `oldestKept` is 7. A client at 6 needs 7, which is there. A client at 5 needs 6, which is gone: reload. Otherwise `lastToSend` is the smaller of the newest sequence and the client's sequence plus `maxChanges`, so one answer carries at most one batch. The loop copies the changes one by one from the ring, in order, and the answer holds them with the position of the last. If the client is already up to date the list is empty and the position is its own. There are only two ways out of this method, `ReloadRequired` or `Changes`, which is how "never a silent gap" is enforced.

### `Subscriber.java`

```java
package io.plainround.seatwave.live.feed;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
```

`ArrayDeque` is a queue you can add to at the back and take from the front. `ReentrantLock` and `Condition` are explained in `java-concurrency-in-the-feed.md`: the lock lets one thread at a time into the guarded code, and the condition lets a thread wait until another says something has changed.

```java
 * One open push connection's place in the feed: a bounded queue of changes waiting to be written,
 * filled by the feed and drained by the connection's single writer thread.
 *
 * <p><b>The slow-consumer rule.</b> The queue holds at most {@code capacity} changes. When a change
 * arrives and the queue is full, the subscriber drops every queued change and marks itself for
 * reload: the writer's next delivery is {@link Delivery.Reload}, after which the connection ends
 * and the client loads the seat map again. Memory per slow client is therefore bounded by the
 * capacity, never by how slow it is. A writer that stays blocked in one write longer than the write
 * timeout is disconnected by {@link SeatFeed#disconnectStuckWriters}.
 *
 * <p>Thread-safe: the feed offers under its own lock, the writer polls, and any thread may wake the
 * writer; the internal lock is held only to move references, never across I/O.
 */
public final class Subscriber {
```

The Javadoc is the rule of this class. The queue holds at most `capacity` changes. When one more arrives and the queue is full, the subscriber throws away everything it holds and sets a flag, so the writer's next delivery is `Reload`. A slow client therefore costs a bounded amount of memory, however slow it is.

```java
  private static final long NOT_WRITING = 0;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition ready = lock.newCondition();
  private final ArrayDeque<SeatChange> queue;
  private final int capacity;
  private final int maxBatch;
  private final FeedMetrics metrics;
  private boolean reloadPending;
  private boolean woken;
  private volatile long writeStartedNanos = NOT_WRITING;
  private volatile Thread writer;
```

`NOT_WRITING` is 0, the value of `writeStartedNanos` when no write is running. The lock and its condition `ready` guard `queue`, `reloadPending` and `woken`. `volatile` on `writeStartedNanos` and `writer` means a change by one thread is visible at once to the others, with no lock; these two are read by the watchdog thread and written by the writer thread.

```java
  Subscriber(int capacity, int maxBatch, FeedMetrics metrics) {
    if (capacity < 1 || maxBatch < 1) {
      throw new IllegalArgumentException("capacity and maxBatch must be at least 1");
    }
    this.capacity = capacity;
    this.maxBatch = maxBatch;
    this.queue = new ArrayDeque<>(capacity);
    this.metrics = metrics;
  }
```

The constructor has no `public`, so only `SeatFeed` can make a subscriber. `new ArrayDeque<>(capacity)` pre-sizes the queue's storage for the bound. The empty `<>` is the diamond: the compiler fills in the type from the left-hand side.

```java
  /**
   * Waits for the next thing to send.
   *
   * @param heartbeat how long to wait before answering {@link Delivery.Idle}
   * @return a batch of up to the batch size, a reload, an idle tick, or a wake-up
   * @throws InterruptedException if the connection is being dropped
   */
  public Delivery next(Duration heartbeat) throws InterruptedException {
```

`next` is called by the connection's writer thread. `Duration heartbeat` is how long to wait. `remainingNanos` counts down the wait in nanoseconds (billionths of a second). `throws InterruptedException` is a promise that this method can end early if another thread interrupts the waiting thread.

```java
    long remainingNanos = heartbeat.toNanos();
    lock.lockInterruptibly();
    try {
      while (queue.isEmpty() && !reloadPending && !woken) {
        if (remainingNanos <= 0) {
          return new Delivery.Idle();
        }
        remainingNanos = ready.awaitNanos(remainingNanos);
      }
```

`lockInterruptibly` takes the lock, and can be interrupted while waiting for it. The `while` loop waits as long as there is nothing to do: the queue is empty, no reload is pending and nobody woke us. If the time is up (`remainingNanos <= 0`) it returns `Idle`. `ready.awaitNanos(remainingNanos)` does three things at once: it gives up the lock, sleeps until signalled or the time passes, and takes the lock back before it returns. It returns the time still left. It is in a loop because a thread can wake without being signalled (a **spurious wakeup**), so the condition is always checked again.

```java
      if (reloadPending) {
        return new Delivery.Reload();
      }
      if (queue.isEmpty()) {
        woken = false;
        return new Delivery.Woken();
      }
      List<SeatChange> batch = new ArrayList<>(Math.min(maxBatch, queue.size()));
      while (!queue.isEmpty() && batch.size() < maxBatch) {
        batch.add(queue.poll());
      }
      return new Delivery.Batch(batch);
```

After the loop, the order of the checks matters. A pending reload wins over everything: it returns `Reload`. If the queue is empty, the only reason to be awake is that someone called `wake()`, so the flag is cleared and `Woken` is returned. Otherwise a batch is built: `new ArrayList<>(Math.min(maxBatch, queue.size()))` sizes the list, and the loop polls up to `maxBatch` changes off the front. `queue.poll()` removes and returns the first item.

```java
    } finally {
      lock.unlock();
    }
  }
```

`finally` always runs, even after a `return` or a thrown exception. The lock is therefore always released.

```java
  /**
   * Makes a waiting {@link #next} return {@link Delivery.Woken} at once (or after the queued
   * changes), so the writer can send a control message that another thread asked for.
   */
  public void wake() {
    lock.lock();
    try {
      woken = true;
      ready.signal();
    } finally {
      lock.unlock();
    }
  }
```

`wake` is for other threads. It sets the flag and signals the condition. The WebSocket reader uses it to tell the writer "a ping arrived, send a pong".

```java
  /**
   * Marks the start of one write, so a stuck writer can be found and disconnected.
   *
   * @param writerThread the thread doing the write
   */
  public void beginWrite(Thread writerThread) {
    writer = writerThread;
    writeStartedNanos = System.nanoTime();
  }

  /** Marks the end of the current write. */
  public void endWrite() {
    writeStartedNanos = NOT_WRITING;
  }
```

`beginWrite` and `endWrite` bracket every socket write. They store the writing thread and the time (`System.nanoTime()`, a clock that only goes forward and is meant for measuring durations, not for telling the date). The watchdog reads these two values. A write that is blocked because the client stopped reading shows up as a start time that gets older.

```java
  void offer(SeatChange change) {
    lock.lock();
    try {
      if (reloadPending) {
        // The client will reload anyway; queuing more would only use memory.
        return;
      }
      if (queue.size() == capacity) {
        queue.clear();
        reloadPending = true;
        metrics.recordOverflow();
      } else {
        queue.add(change);
        metrics.recordQueueDepth(queue.size());
      }
      ready.signal();
    } finally {
      lock.unlock();
    }
  }
```

`offer` is called by the feed, under the feed's lock, once per change per subscriber. If a reload is already pending it returns at once: more changes would only use memory. If the queue is full it clears the queue, sets `reloadPending` and counts an overflow. Otherwise it adds the change and records the queue depth. In both cases it signals `ready` so a waiting writer wakes. `offer` never blocks for I/O; its lock is held for a few instructions. That is why a slow client cannot slow the publisher.

```java
  void markForReload() {
    lock.lock();
    try {
      queue.clear();
      reloadPending = true;
      ready.signal();
    } finally {
      lock.unlock();
    }
  }

  void addAll(List<SeatChange> changes) {
    changes.forEach(this::offer);
  }
```

`markForReload` is the same ending without the overflow count; `SeatFeed.subscribe` uses it when the ring cannot cover the client's position. `addAll` offers each change in turn, with the method reference `this::offer` (a short way to write the lambda `change -> this.offer(change)`).

```java
  boolean interruptIfStuck(long nowNanos, Duration writeTimeout) {
    long started = writeStartedNanos;
    Thread stuck = writer;
    if (started == NOT_WRITING || stuck == null) {
      return false;
    }
    if (nowNanos - started < writeTimeout.toNanos()) {
      return false;
    }
    // Interrupting a thread blocked in a socket write closes the socket (an interruptible channel
    // on the JDK's HTTP server, a virtual thread's socket on the WebSocket server), which frees the
    // thread and the connection.
    stuck.interrupt();
    return true;
  }
}
```

`interruptIfStuck` is called by the watchdog. It reads the two volatile values. If there is no write in progress it does nothing. If the write has run for less than the timeout it does nothing. Otherwise `stuck.interrupt()` sets the writer thread's interrupt flag. A virtual thread (a lightweight thread managed by the JDK) that is blocked in a socket write when interrupted closes the socket and gets an exception, which frees the thread and the connection. That is how a client that stopped reading is cut off.

### `SeatFeed.java`

```java
package io.plainround.seatwave.live.feed;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

```

`SecureRandom` makes the stream identifier. `HexFormat` (Java 17) writes bytes as hex digits. `Condition` and `ReentrantLock` are the wait-and-lock pair.

```java
/**
 * The seat-change publisher: it numbers every change the store makes, keeps the recent ones in a
 * bounded replay buffer, and hands each one to every subscribed connection.
 *
 * <p><b>The invariant.</b> A client that names its last position receives every later change
 * exactly once, or is told to reload the seat map; never a silent gap. It holds because three
 * things happen under one lock: numbering a change and appending it to the buffer ({@link
 * #publish}); reading a catch-up from the buffer ({@link #awaitChangesAfter}); and, for a push
 * connection, reading its catch-up and registering it for live changes in the same step ({@link
 * #subscribe}). No change can fall between the catch-up and the live stream, and none can be in
 * both.
 *
 * <p>Thread-safe. The lock is held only to number, copy references and offer to bounded queues,
 * never across I/O: writers run on their own threads and take changes from their subscriber.
 */
```

The Javadoc states the invariant. The sentence worth remembering: three things happen under one lock, which are numbering and appending a change, reading a catch-up, and, for a push connection, reading its catch-up and registering it for live changes in a single step. If registering happened after reading, a change published between the two would be lost. If it happened before, the change could arrive both ways. One lock makes the two steps one.

```java
public final class SeatFeed {

  private static final int STREAM_ID_BYTES = 4;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition published = lock.newCondition();
  private final List<Subscriber> subscribers = new ArrayList<>();
  private final ReplayBuffer buffer;
  private final String streamId;
  private final FeedMetrics metrics;
  private final int queueCapacity;
  private final int maxBatch;
```

The fields. `published` is the condition that long polls wait on. `subscribers` is the list of open push connections.

```java
  /**
   * Creates an empty feed with a new random stream identifier.
   *
   * @param replayCapacity how many recent changes to keep for clients that resume
   * @param queueCapacity how many changes one push connection may have waiting before it is told to
   *     reload
   * @param maxBatch the most changes one write or one long-poll reply carries
   * @param metrics where the feed counts what it does
   */
  public SeatFeed(int replayCapacity, int queueCapacity, int maxBatch, FeedMetrics metrics) {
    this.streamId = HexFormat.of().formatHex(new SecureRandom().generateSeed(STREAM_ID_BYTES));
    this.buffer = new ReplayBuffer(streamId, replayCapacity);
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.queueCapacity = queueCapacity;
    this.maxBatch = maxBatch;
  }
```

The constructor makes the stream identifier: `new SecureRandom().generateSeed(4)` gives 4 random bytes and `HexFormat.of().formatHex(...)` writes them as 8 hex digits. It then creates the ring with the replay capacity and keeps the other limits.

```java
  /**
   * The position of the newest change.
   *
   * @return the position; sequence 0 before the first change
   */
  public FeedPosition position() {
    lock.lock();
    try {
      return buffer.position();
    } finally {
      lock.unlock();
    }
  }
```

`position` reads the newest position under the lock. The pattern `lock.lock(); try { ... } finally { lock.unlock(); }` is how every method here guards its work.

Version 1.0.0 had one more method here, `changesAfter`, a one-shot catch-up that did not wait. Nothing in the main code called it, only a test, so version 1.1.0 removed it: main code that exists only for a test is code a reader has to understand for nothing. The test now calls `awaitChangesAfter` with a wait of zero, which gives the same answer.

```java
  /**
   * The changes after a client's position, waiting until there is at least one or the wait ends:
   * the heart of a long poll.
   *
   * @param known the client's position
   * @param wait the longest time to hold the request
   * @return changes (none if the wait ended first), or a reload instruction
   * @throws InterruptedException if the request is being dropped
   */
  public Catchup awaitChangesAfter(FeedPosition known, Duration wait) throws InterruptedException {
    Objects.requireNonNull(known, "known");
    long remainingNanos = wait.toNanos();
    lock.lockInterruptibly();
```

`awaitChangesAfter` is the long poll's heart. `wait.toNanos()` converts the duration. `lockInterruptibly` lets a dropped connection end the wait.

```java
    try {
      Catchup catchup = buffer.after(known, maxBatch);
      while (catchup instanceof Catchup.Changes changes
          && changes.changes().isEmpty()
          && remainingNanos > 0) {
        remainingNanos = published.awaitNanos(remainingNanos);
        catchup = buffer.after(known, maxBatch);
      }
      return catchup;
    } finally {
      lock.unlock();
    }
  }
```

First it asks the ring. The `while` condition uses a **pattern**: `catchup instanceof Catchup.Changes changes` tests the type and, if it matches, names the value `changes` in the same step. The loop continues only if the answer is "changes", the list is empty (nothing new) and time remains. Then `published.awaitNanos(remainingNanos)` waits. While it waits it does not hold the lock, so `publish` can run. `publish` calls `signalAll`, every waiting long poll wakes, takes the lock again and asks the ring again. When it gets a non-empty list, a reload, or runs out of time, the loop ends and the method returns. A timeout therefore returns an empty list: "no news".

```java
  /**
   * Registers a push connection that already has the seat map up to {@code known}: its subscriber
   * starts with every change after that position, then receives every new change.
   *
   * @param known the client's position
   * @return the subscriber; if the buffer no longer covers the position, it is already marked for
   *     reload
   */
  public Subscriber subscribe(FeedPosition known) {
```

`subscribe` is for push connections.

```java
    Objects.requireNonNull(known, "known");
    Subscriber subscriber = new Subscriber(queueCapacity, maxBatch, metrics);
    lock.lock();
    try {
      FeedPosition reached = known;
      // A catch-up longer than one batch is read in batches; all of it is read under this one
      // lock, so no change can be published in between.
      while (true) {
        Catchup catchup = buffer.after(reached, maxBatch);
        if (catchup instanceof Catchup.ReloadRequired) {
          subscriber.markForReload();
          break;
        }
```

A new `Subscriber` is created, then the lock is taken. `reached` is how far the catch-up has got; it starts at the client's position. The loop reads the ring one batch at a time. If the ring says reload, the subscriber is marked and the loop ends. Otherwise the batch is added to the subscriber's queue.

```java
        Catchup.Changes changes = (Catchup.Changes) catchup;
        subscriber.addAll(changes.changes());
        if (changes.changes().isEmpty()) {
          break;
        }
        reached = changes.position();
      }
      subscribers.add(subscriber);
      return subscriber;
    } finally {
      lock.unlock();
    }
  }
```

The cast `(Catchup.Changes) catchup` is safe because the other case was handled above, and the sealed interface has only two cases. An empty batch means the catch-up is done. Otherwise `reached` moves on. After the loop the subscriber joins `subscribers`, still under the same lock. From now on `publish` will offer it every new change. Nothing could be published between the last catch-up read and `subscribers.add`, because the lock was held throughout. The subscriber has a queue of `queueCapacity` places, so a catch-up longer than that overflows at once and ends in a reload: the bound applies to catch-up too.

```java
  /**
   * Removes a push connection that ended.
   *
   * @param subscriber the subscriber {@link #subscribe} returned
   */
  public void unsubscribe(Subscriber subscriber) {
    lock.lock();
    try {
      subscribers.remove(subscriber);
    } finally {
      lock.unlock();
    }
  }
```

`unsubscribe` removes it when the connection ends. The route calls it in a `finally` block, so it runs on every path.

```java
  /**
   * Interrupts the writer of every subscriber whose current write has lasted longer than the
   * timeout, which closes that connection.
   *
   * @param writeTimeout the longest one write may take
   * @return how many writers were interrupted
   */
  public int disconnectStuckWriters(Duration writeTimeout) {
    return disconnectStuckWriters(writeTimeout, System.nanoTime());
  }

  // The time of the check is a parameter, so whether a write counts as stuck depends only on the
  // two readings of System.nanoTime, never on how long the check itself takes to run.
  int disconnectStuckWriters(Duration writeTimeout, long nowNanos) {
    List<Subscriber> current;
    lock.lock();
    try {
      current = List.copyOf(subscribers);
    } finally {
      lock.unlock();
    }
    int interrupted = 0;
    for (Subscriber subscriber : current) {
      if (subscriber.interruptIfStuck(nowNanos, writeTimeout)) {
        metrics.recordStuckDisconnect();
        interrupted++;
      }
    }
    return interrupted;
  }
```

`disconnectStuckWriters` is called by the watchdog from `LiveFeedNode`. The public method reads `System.nanoTime()` once and passes the reading to a package-private method with the same name. That method copies the subscriber list under the lock (`List.copyOf`), then releases the lock before it does anything else, so the interrupts happen with no lock held. For each subscriber, `interruptIfStuck` decides by comparing the reading with the time its write began, and the feed counts the disconnects. The return value is how many writers were interrupted. The reading is a parameter so that the answer depends only on the two clock readings and never on how long the check takes; `SeatFeedTest` passes its own reading and so gets the same answer on any machine (`tests-feed-walkthrough.md`).

```java
  /**
   * The stream identifier of this feed, part of every position.
   *
   * @return eight lowercase hexadecimal digits
   */
  public String streamId() {
    return streamId;
  }
```

`streamId` returns the identifier, for tests and tools.

```java
  // Called by SeatStore while it holds its own lock, so changes are numbered in the order the store
  // made them. The lock order is always store, then feed.
  SeatChange publish(int seatIndex, SeatState state, Instant changedAt) {
    lock.lock();
    try {
      SeatChange change = new SeatChange(buffer.position().next(), seatIndex, state, changedAt);
      buffer.append(change);
      for (Subscriber subscriber : subscribers) {
        // A bounded, non-blocking offer: a slow client can fill only its own queue, never stop
        // this loop or the store behind it.
        subscriber.offer(change);
      }
      published.signalAll();
      metrics.recordPublished();
      return change;
    } finally {
      lock.unlock();
    }
  }
```

`publish` has no `public`, so only `SeatStore` in the same package can call it. The comment states the lock order: store, then feed. Inside the lock: `buffer.position().next()` is the number for the new change; `buffer.append(change)` stores it and refuses a gap; the loop offers the change to every subscriber (a bounded, non-blocking offer); `published.signalAll()` wakes every long poll; the metric is counted. The method returns the numbered change so the store can hand it back to its caller. The held lock is short: numbering, one array store, and one small queue insert per subscriber.

### `SeatStore.java`

```java
package io.plainround.seatwave.live.feed;

import com.github.sviperll.result4j.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The event's seats and their states, in memory: holds that expire, and confirms. Every state
 * change is published to the feed inside the store's lock, so the feed's order is the store's
 * order.
 *
 * <p>This store stands in for Module 7's database tables to keep the module about delivery. Its
 * rules are Module 1's: a seat is held only if it is available, and confirmed only by a hold that
 * has not expired. One lock guards the whole map; in production the database's conditional {@code
 * UPDATE} enforces the rule and an outbox or change stream feeds the publisher (Module 27).
 *
 * <p>Thread-safe: every method takes the lock.
 */
public final class SeatStore {
```

The store is the seat map. `Clock` is an object that tells the time; a test can give it a fake. `ArrayDeque` here holds holds in the order they were made. The Javadoc says its rules are Module 1's: a seat is held only if available, and confirmed only by a hold that has not expired.

```java
  private final ReentrantLock lock = new ReentrantLock();
  private final SeatLayout layout;
  private final SeatState[] states;
  private final Instant[] holdExpiry;
  // Holds in the order they were made; with one hold time for all, that is also expiry order.
  private final ArrayDeque<HoldEntry> holdsByExpiry = new ArrayDeque<>();
  private final Duration holdTime;
  private final Clock clock;
  private final SeatFeed feed;
```

`states` is one `SeatState` per seat. `holdExpiry` is the instant each hold ends (or `null`). `holdsByExpiry` is a queue of `HoldEntry` records in the order the holds were made. Because every hold lasts the same time, that is also the order in which they expire, so the front of the queue is always the next to expire.

```java
  /**
   * Creates a store with every seat available.
   *
   * @param layout the seat map's shape
   * @param holdTime how long a hold lasts before it expires
   * @param clock the source of the time, injected so tests control it
   * @param feed where every change is published
   */
  public SeatStore(SeatLayout layout, Duration holdTime, Clock clock, SeatFeed feed) {
    this.layout = Objects.requireNonNull(layout, "layout");
    this.holdTime = Objects.requireNonNull(holdTime, "holdTime");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.feed = Objects.requireNonNull(feed, "feed");
    if (holdTime.isNegative() || holdTime.isZero()) {
      throw new IllegalArgumentException("holdTime must be positive");
    }
    this.states = new SeatState[layout.seatCount()];
    this.holdExpiry = new Instant[layout.seatCount()];
    Arrays.fill(states, SeatState.AVAILABLE);
  }
```

The constructor checks its inputs and fills every seat with `AVAILABLE` using `Arrays.fill`. Note that it checks `holdTime` after assigning it; that is fine because nothing escapes the constructor before it throws.

```java
  /**
   * Holds an available seat.
   *
   * @param seatIndex the seat
   * @return the change made, or why not
   */
  public Result<SeatChange, SeatRefusal> hold(int seatIndex) {
    if (!layout.contains(seatIndex)) {
      return Result.error(SeatRefusal.UNKNOWN_SEAT);
    }
    lock.lock();
    try {
      if (states[seatIndex] != SeatState.AVAILABLE) {
        return Result.error(SeatRefusal.SEAT_TAKEN);
      }
      Instant now = clock.instant();
      holdExpiry[seatIndex] = now.plus(holdTime);
      holdsByExpiry.add(new HoldEntry(seatIndex, holdExpiry[seatIndex]));
      return Result.success(change(seatIndex, SeatState.HELD, now));
    } finally {
      lock.unlock();
    }
  }
```

`hold`. The result type `Result<SeatChange, SeatRefusal>` says: success with the numbered change, or a refusal. First the index check, before the lock (the layout never changes, so no lock is needed to read it). Under the lock: if the seat is not available the answer is `SEAT_TAKEN`. Otherwise the hold expires at `now.plus(holdTime)`, a `HoldEntry` is queued, and `change(...)` sets the state and publishes. Everything happens before the lock is released, so no two threads can hold the same seat, and the feed sees the change at the moment it happens.

```java
  /**
   * Confirms a held seat whose hold has not expired.
   *
   * @param seatIndex the seat
   * @return the change made, or why not
   */
  public Result<SeatChange, SeatRefusal> confirm(int seatIndex) {
    if (!layout.contains(seatIndex)) {
      return Result.error(SeatRefusal.UNKNOWN_SEAT);
    }
    lock.lock();
    try {
      Instant now = clock.instant();
      if (states[seatIndex] != SeatState.HELD || !now.isBefore(holdExpiry[seatIndex])) {
        return Result.error(SeatRefusal.NOT_HELD);
      }
      holdExpiry[seatIndex] = null;
      return Result.success(change(seatIndex, SeatState.SOLD, now));
    } finally {
      lock.unlock();
    }
  }
```

`confirm`. The condition `states[seatIndex] != SeatState.HELD || !now.isBefore(holdExpiry[seatIndex])` refuses when the seat is not held or the hold's time has come. `||` stops at the first true, so when the state is not `HELD` the second half (which would fail on a `null` expiry) is never evaluated. A confirm clears the expiry and makes the seat `SOLD`.

```java
  /**
   * Makes every hold whose time has passed available again.
   *
   * @return how many holds expired
   */
  public int expireDue() {
    lock.lock();
    try {
      Instant now = clock.instant();
      int expired = 0;
      while (!holdsByExpiry.isEmpty() && !holdsByExpiry.peek().expiresAt().isAfter(now)) {
        HoldEntry entry = holdsByExpiry.poll();
        // An entry is stale when its seat was confirmed, or expired and held again since: only the
        // seat's current hold may expire it.
        if (states[entry.seatIndex()] == SeatState.HELD
            && entry.expiresAt().equals(holdExpiry[entry.seatIndex()])) {
          holdExpiry[entry.seatIndex()] = null;
          change(entry.seatIndex(), SeatState.AVAILABLE, now);
          expired++;
        }
      }
      return expired;
    } finally {
      lock.unlock();
    }
  }
```

`expireDue` runs on every on-sale tick. It looks at the front of the queue; as long as the front entry's time is not after `now`, it takes it off. An entry can be stale: the seat may have been confirmed since, or have expired and been held again, in which case the seat's current expiry no longer equals the entry's. Only an entry that matches the seat's current hold may expire it, which is what the two-part test checks. A real expiry makes the seat `AVAILABLE` and is published like any change.

```java
  /**
   * The whole map and the feed position it matches, read in one step.
   *
   * @return the snapshot
   */
  public SeatMapSnapshot snapshot() {
    lock.lock();
    try {
      // Under the store's lock no change can be made, so the position read here is exactly the
      // last change the states include.
      StringBuilder codes = new StringBuilder(states.length);
      for (SeatState state : states) {
        codes.append(state.code());
      }
      return new SeatMapSnapshot(feed.position(), layout, codes.toString());
    } finally {
      lock.unlock();
    }
  }
```

`snapshot` takes the lock, builds one letter per seat with a `StringBuilder`, and reads the feed's position while still holding the store's lock. Because every change is published while the store's lock is held, no change can be made while this runs, so the position is exactly the last change the letters include. The lock order is store then feed, the same as everywhere.

```java
  /**
   * The seat map's shape.
   *
   * @return the layout
   */
  public SeatLayout layout() {
    return layout;
  }

  private SeatChange change(int seatIndex, SeatState state, Instant now) {
    states[seatIndex] = state;
    return feed.publish(seatIndex, state, now);
  }

  private record HoldEntry(int seatIndex, Instant expiresAt) {}
}
```

`layout` returns the shape. `change` sets the state and calls `feed.publish`, so state and publication never part ways. `HoldEntry` is a private nested record, visible only inside the store.

### `OnSaleSimulator.java`

```java
package io.plainround.seatwave.live.feed;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Plays an on-sale against the seat store: at a fixed rate it either holds a random available seat
 * or confirms one of its holds, and on every tick it lets due holds expire. The changes the feed
 * publishes are therefore real store changes (holds, confirms, expiries), not invented events.
 *
 * <p>The random choices come from a seeded generator, so two runs make the same choices in the same
 * order. Thread-safe: a tick runs under the simulator's own lock, and pause takes the same lock, so
 * when {@link #pause()} returns no tick is running and none will change a seat until {@link
 * #resume()}.
 */
public final class OnSaleSimulator implements AutoCloseable {
```

The simulator plays an on-sale against the store: at a fixed rate it either holds a random available seat or confirms one of its own holds, and it lets due holds expire. Its changes are real store changes. It implements `AutoCloseable` so it can be used in a `try` block and closed. `Random` with a seed gives the same sequence every run, which is why two runs make the same choices.

```java
  private static final int PERCENT = 100;
  // A random seat may be taken; after this many tries the tick gives up rather than scan the map.
  private static final int HOLD_ATTEMPTS = 8;

  private final SeatStore store;
  private final Random random;
  private final int confirmPercent;
  private final Duration tickInterval;
  private final ArrayDeque<Integer> heldByUs = new ArrayDeque<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> Thread.ofPlatform().name("on-sale").daemon().unstarted(runnable));
  // Held for a whole tick and by pause, so a tick already running finishes before pause returns.
  private final ReentrantLock tickLock = new ReentrantLock();
  private boolean paused;

```

`HOLD_ATTEMPTS` is 8: a random seat may already be taken; after eight tries the tick gives up instead of scanning the map. `heldByUs` remembers the seats this simulator holds. The scheduler is a `ScheduledExecutorService` with one thread. The lambda `runnable -> Thread.ofPlatform().name("on-sale").daemon().unstarted(runnable)` is a thread factory: it makes an ordinary (platform) thread named `on-sale`; `daemon()` marks it so the program may exit while it is still running; `unstarted` returns it for the executor to start. `tickLock` is the simulator's own lock: a tick holds it from start to end, and `pause` and `resume` take it to change `paused`. `paused` is a plain `boolean`, not `volatile`, because it is only ever read and written while `tickLock` is held, and a lock makes a write visible to the next thread that takes it (`java-concurrency-in-the-feed.md`).

```java
  /**
   * Creates the simulator; it does nothing until {@link #start()}.
   *
   * @param store the seat store to act on
   * @param actionsPerSecond how many holds or confirms to make per second
   * @param confirmPercent the share of actions that confirm a hold instead of making one
   * @param seed the seed of the random choices
   */
  public OnSaleSimulator(SeatStore store, int actionsPerSecond, int confirmPercent, long seed) {
    this.store = Objects.requireNonNull(store, "store");
    if (actionsPerSecond < 1) {
      throw new IllegalArgumentException("actionsPerSecond must be at least 1");
    }
    if (confirmPercent < 0 || confirmPercent > PERCENT) {
      throw new IllegalArgumentException("confirmPercent must be from 0 to 100");
    }
    this.confirmPercent = confirmPercent;
    this.random = new Random(seed);
    this.tickInterval = Duration.ofSeconds(1).dividedBy(actionsPerSecond);
  }
```

The constructor checks the rate and the percentage. `Duration.ofSeconds(1).dividedBy(actionsPerSecond)` is the time between ticks: at 20 actions per second, 50 milliseconds.

```java
  /** Starts acting at the configured rate. */
  public void start() {
    scheduler.scheduleAtFixedRate(
        this::tick, tickInterval.toNanos(), tickInterval.toNanos(), TimeUnit.NANOSECONDS);
  }

  /**
   * Stops every change, expiries included, so the seat map stands still and clients can catch up
   * and be compared with the store. It waits for a tick that is already running to finish, so a
   * snapshot taken after it returns is the last state until {@link #resume()}.
   */
  public void pause() {
    tickLock.lock();
    try {
      paused = true;
    } finally {
      tickLock.unlock();
    }
  }

  /** Starts making holds and confirms again. */
  public void resume() {
    tickLock.lock();
    try {
      paused = false;
    } finally {
      tickLock.unlock();
    }
  }

```

`start` asks the scheduler to run `tick` at a fixed rate. `this::tick` is a method reference. The delays are given in nanoseconds with `TimeUnit.NANOSECONDS`. `pause` and `resume` set the flag while they hold `tickLock`; the lab's admin routes, the slow-consumer run, the resume lab and the reconnect test call `pause` to freeze the map so a comparison with the store can be exact. Because a running tick holds `tickLock`, `pause` waits until that tick has finished before it can set the flag. When `pause` returns, no tick is running and every later tick will see `paused` and change nothing, so a snapshot taken next is final. In version 1.0.0 `pause` only set a `volatile` flag: a tick that had already checked the flag could still change a seat after `pause` returned, and the reconnect test failed about twice in 65 runs with one seat different from the snapshot.

```java
  /**
   * Runs one tick at once, on the caller's thread; used by tests and by {@link #start()}.
   *
   * @return true if the tick made a hold or a confirm
   */
  public boolean tick() {
    tickLock.lock();
    try {
      return !paused && act();
    } finally {
      tickLock.unlock();
    }
  }
```

`tick` takes `tickLock`, and `return !paused && act();` does the work only when the on-sale is not paused: `&&` evaluates its right side only when the left side is true, so a paused tick returns `false` without calling `act`. The `finally` block lets the lock go on every path, also when `act` throws.

```java
  @Override
  public void close() {
    scheduler.shutdownNow();
  }
```

`close` stops the scheduler at once with `shutdownNow`. `@Override` says the method implements `AutoCloseable.close`.

```java
  private boolean act() {
    store.expireDue();
    if (!heldByUs.isEmpty() && random.nextInt(PERCENT) < confirmPercent) {
      // A confirm of an expired hold is refused by the store; that is the rule working, not a bug.
      return !store.confirm(heldByUs.poll()).isError();
    }
    for (int attempt = 0; attempt < HOLD_ATTEMPTS; attempt++) {
      int seatIndex = random.nextInt(store.layout().seatCount());
      if (!store.hold(seatIndex).isError()) {
        heldByUs.add(seatIndex);
        return true;
      }
    }
    return false;
  }
```

`act` is the tick's work, private so that only `tick` can call it and only under the lock. It calls `store.expireDue()` first. Then, if the simulator holds anything and a random number from 0 to 99 is below `confirmPercent`, it confirms the oldest of its holds and returns whether that worked (`!...isError()`). A confirm of an expired hold is refused by the store; the comment says that is the rule working. If it did not confirm, it tries up to eight random seats to hold; the first success is remembered and ends the tick. If all eight fail it returns `false`.

```java
}
```

The closing brace ends the class.

### `ConnectionLimit.java`

```java
package io.plainround.seatwave.live.feed;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The hard limit on open feed connections, shared by the three methods, and the list of those
 * connections so they can all be dropped at once (for the reconnect test, and at shutdown).
 *
 * <p>Each open connection costs a file descriptor, kernel buffers and memory (Module 1); a node
 * that accepts every connection fails for all of them when one of those runs out. This limit fails
 * the extra connection alone, at once, with 503 and Retry-After.
 *
 * <p>Thread-safe: a semaphore and a concurrent set.
 */
public final class ConnectionLimit {
```

Each open connection costs a file descriptor (the number the operating system gives to an open socket or file), kernel buffers and memory. A node that accepts every connection fails for all of them when one of those runs out. This class makes the extra connection fail alone.

```java
  private final Semaphore slots;
  private final Set<Ticket> open = ConcurrentHashMap.newKeySet();
  private final FeedMetrics metrics;

  /**
   * Creates the limit.
   *
   * @param maxConnections the most connections open at once, across all methods
   * @param metrics where opens, closes and refusals are counted
   */
  public ConnectionLimit(int maxConnections, FeedMetrics metrics) {
    if (maxConnections < 1) {
      throw new IllegalArgumentException("maxConnections must be at least 1");
    }
    this.slots = new Semaphore(maxConnections);
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }
```

A `Semaphore` is a counter of permits. `ConcurrentHashMap.newKeySet()` is a set that many threads may use at once. `open` holds the tickets of the connections now open. The constructor starts the semaphore with `maxConnections` permits.

```java
  /**
   * Admits one connection if a slot is free.
   *
   * @param method the connection's delivery method
   * @param dropAction how to end the connection from another thread, for example by interrupting
   *     its writer thread or closing its socket
   * @return a ticket to close when the connection ends, or empty when the limit is reached
   */
  public Optional<Ticket> admit(DeliveryMethod method, Runnable dropAction) {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(dropAction, "dropAction");
    if (!slots.tryAcquire()) {
      metrics.recordRefused(method);
      return Optional.empty();
    }
    Ticket ticket = new Ticket(method, dropAction);
    open.add(ticket);
    metrics.recordOpened(method);
    return Optional.of(ticket);
  }
```

`admit` is called before anything else is done for a connection. `slots.tryAcquire()` takes a permit if one is free and returns at once, true or false, without waiting. If none is free, the refusal is counted and `Optional.empty()` is returned; the caller answers 503 with `Retry-After`. Otherwise a `Ticket` is made and remembered, the opening is counted, and the ticket is returned. `dropAction` is a `Runnable` (a piece of code with no arguments and no result) that ends the connection from another thread: interrupt its thread, or close its socket.

```java
  /**
   * Ends every open connection at once, as a crash or a careless deploy would.
   *
   * @return how many were dropped
   */
  public int dropAll() {
    int dropped = 0;
    for (Ticket ticket : Set.copyOf(open)) {
      ticket.dropAction.run();
      dropped++;
    }
    return dropped;
  }
```

`dropAll` runs every connection's `dropAction`, for the reconnect lab and for shutdown. `Set.copyOf(open)` takes a copy first, because dropping a connection makes its own thread close its ticket, which removes it from `open`.

```java
  /**
   * How many connections are open now.
   *
   * @return the count
   */
  public int openCount() {
    return open.size();
  }

```

`openCount` is the size of the set.

```java
  /** One admitted connection; closing it frees the slot exactly once. */
  public final class Ticket implements AutoCloseable {

    private final DeliveryMethod method;
    private final Runnable dropAction;

    private Ticket(DeliveryMethod method, Runnable dropAction) {
      this.method = method;
      this.dropAction = dropAction;
    }

    @Override
    public void close() {
      if (open.remove(this)) {
        slots.release();
        metrics.recordClosed(method);
      }
    }
  }
```

`Ticket` is an **inner class**: it is written inside `ConnectionLimit` without `static`, so each ticket belongs to one `ConnectionLimit` and can use its fields `open`, `slots` and `metrics`. It implements `AutoCloseable`, so the routes write `try (ConnectionLimit.Ticket _ = admitted.get())` and the ticket closes itself at the end. `close` calls `open.remove(this)`, which returns true only the first time, so the permit is released exactly once even if `close` is called twice. That is what makes the count of open connections reliable.

### `FeedMetrics.java`

```java
package io.plainround.seatwave.live.feed;

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The feed's counters and gauges, rendered in the Prometheus text format at {@code /metrics}.
 * Labels are the delivery method only, never a client or a seat, so the number of series stays
 * fixed.
 *
 * <p>Thread-safe: every value is an atomic counter or adder.
 */
public final class FeedMetrics {
```

The counters for `/metrics`, in the Prometheus text format (one line per value: a name, a space and a number). `UnixOperatingSystemMXBean` and `ManagementFactory` come from the JDK's management modules and report process numbers such as open file descriptors. `EnumMap` is a map keyed by an enum, stored as an array. The Javadoc says labels are the delivery method only, never a client or a seat, so the number of series never grows.

```java
  private final LongAdder changesPublished = new LongAdder();
  private final LongAdder overflows = new LongAdder();
  private final LongAdder stuckDisconnects = new LongAdder();
  private final LongAdder longPollReplies = new LongAdder();
  private final AtomicInteger handlerThreads = new AtomicInteger();
  private final AtomicInteger deepestQueue = new AtomicInteger();
  private final Map<DeliveryMethod, AtomicInteger> openConnections = perMethod(AtomicInteger::new);
  private final Map<DeliveryMethod, LongAdder> connectionsAccepted = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> connectionsRefused = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> changesDelivered = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> reloadsSent = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> bytesWritten = perMethod(LongAdder::new);
```

`LongAdder` is a counter built for many threads adding at once: it spreads the additions over several cells and sums them when read, so threads do not fight over one memory location. `AtomicInteger` is a single integer updated with indivisible steps; it is used where a value goes up and down (open connections). `perMethod(...)` builds one counter per delivery method; `AtomicInteger::new` and `LongAdder::new` are constructor references passed as the factory.

```java
  /** Counts one change numbered by the feed. */
  public void recordPublished() {
    changesPublished.increment();
  }

  /** Counts one subscriber whose bounded queue filled, so it was told to reload. */
  public void recordOverflow() {
    overflows.increment();
  }

  /** Counts one connection dropped because a single write stayed blocked too long. */
  public void recordStuckDisconnect() {
    stuckDisconnects.increment();
  }

  /**
   * Remembers the deepest any subscriber's queue has been, to show the bound holds.
   *
   * @param depth the queue's length after an offer
   */
  public void recordQueueDepth(int depth) {
    deepestQueue.accumulateAndGet(depth, Math::max);
  }

  /** Counts one long-poll reply sent, with or without changes. */
  public void recordLongPollReply() {
    longPollReplies.increment();
  }
```

Simple recorders. `recordQueueDepth` uses `deepestQueue.accumulateAndGet(depth, Math::max)`: one atomic step that sets the value to the larger of the old value and `depth`, even when two threads do it together. The comment on `recordQueueDepth` says it shows that the bound holds: in the slow-consumer lab the deepest queue equals the bound and never exceeds it.

```java
  /**
   * Counts changes and bytes written to one client.
   *
   * @param method how they were delivered
   * @param changes how many changes the write carried
   * @param bytes how many bytes the feed wrote for them, framing included
   */
  public void recordDelivered(DeliveryMethod method, int changes, long bytes) {
    changesDelivered.get(method).add(changes);
    bytesWritten.get(method).add(bytes);
  }

  /**
   * Counts one reload instruction sent.
   *
   * @param method how it was delivered
   */
  public void recordReloadSent(DeliveryMethod method) {
    reloadsSent.get(method).increment();
  }
```

`recordDelivered` adds the number of changes and bytes written to a client for one method. `recordReloadSent` counts reload instructions.

```java
  /**
   * Counts a connection admitted under the limit.
   *
   * @param method its delivery method
   */
  public void recordOpened(DeliveryMethod method) {
    connectionsAccepted.get(method).increment();
    openConnections.get(method).incrementAndGet();
  }

  /**
   * Counts a connection that ended.
   *
   * @param method its delivery method
   */
  public void recordClosed(DeliveryMethod method) {
    openConnections.get(method).decrementAndGet();
  }

  /**
   * Counts a connection refused because the limit was reached.
   *
   * @param method its delivery method
   */
  public void recordRefused(DeliveryMethod method) {
    connectionsRefused.get(method).increment();
  }

```

`recordOpened`, `recordClosed` and `recordRefused` count the life of a connection. `openConnections` goes up and down; `connectionsAccepted` and `connectionsRefused` only go up.

```java
  /**
   * Counts a virtual thread that serves a connection, from start to end, because the JVM's own
   * thread count does not include virtual threads.
   *
   * @param running true when the thread starts, false when it ends
   */
  public void recordHandlerThread(boolean running) {
    if (running) {
      handlerThreads.incrementAndGet();
    } else {
      handlerThreads.decrementAndGet();
    }
  }
```

`recordHandlerThread` counts the virtual threads that serve connections. The JVM's own thread count, which `process_platform_threads` shows, does not include virtual threads, so the feed counts them itself.

```java
  /**
   * The connections open now for one method.
   *
   * @param method the method
   * @return the count
   */
  public int openConnections(DeliveryMethod method) {
    return openConnections.get(method).get();
  }

```

`openConnections(method)` is a read for tests and tools.

```java
  /**
   * Renders every counter and the process's own numbers.
   *
   * @return Prometheus text, one sample per line
   */
  public String render() {
    StringBuilder text = new StringBuilder();
    line(text, "seatwave_feed_changes_published_total", changesPublished.sum());
    line(text, "seatwave_feed_queue_overflows_total", overflows.sum());
    line(text, "seatwave_feed_stuck_writer_disconnects_total", stuckDisconnects.sum());
    line(text, "seatwave_feed_long_poll_replies_total", longPollReplies.sum());
    line(text, "seatwave_feed_handler_virtual_threads", handlerThreads.get());
    line(text, "seatwave_feed_deepest_client_queue", deepestQueue.get());
    for (DeliveryMethod method : DeliveryMethod.values()) {
      String label = "{method=\"" + method.label() + "\"}";
      line(text, "seatwave_feed_open_connections" + label, openConnections.get(method).get());
      line(text, "seatwave_feed_connections_total" + label, connectionsAccepted.get(method).sum());
      line(text, "seatwave_feed_refused_total" + label, connectionsRefused.get(method).sum());
      line(
          text,
          "seatwave_feed_changes_delivered_total" + label,
          changesDelivered.get(method).sum());
      line(text, "seatwave_feed_reloads_sent_total" + label, reloadsSent.get(method).sum());
      line(text, "seatwave_feed_bytes_written_total" + label, bytesWritten.get(method).sum());
    }
    appendProcess(text);
    return text.toString();
  }
```

`render` writes the text. First the global lines. Then, for each delivery method, a `label` such as `{method="sse"}` is added to six metric names. `.sum()` reads a `LongAdder`; `.get()` reads an `AtomicInteger`.

```java
  private static void appendProcess(StringBuilder text) {
    Runtime runtime = Runtime.getRuntime();
    line(text, "process_platform_threads", ManagementFactory.getThreadMXBean().getThreadCount());
    line(text, "process_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory());
    line(text, "process_available_processors", runtime.availableProcessors());
    OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean();
    if (system instanceof com.sun.management.OperatingSystemMXBean process) {
      line(text, "process_cpu_nanoseconds_total", process.getProcessCpuTime());
    }
    if (system instanceof UnixOperatingSystemMXBean unix) {
      line(text, "process_open_fds", unix.getOpenFileDescriptorCount());
      line(text, "process_max_fds", unix.getMaxFileDescriptorCount());
    }
  }
```

`appendProcess` adds numbers about the Java process. `Runtime.getRuntime()` gives memory figures; `totalMemory() - freeMemory()` is the heap in use. The two `instanceof` tests check, at run time, that this JVM's management object has the extra interfaces; the name after the type (`process`, `unix`) is a new variable that exists only inside that `if`. `getOpenFileDescriptorCount` and `getMaxFileDescriptorCount` exist only on Unix-like systems, which is why they are guarded.

```java
  private static void line(StringBuilder text, String name, long value) {
    // The text format ends every line with a line feed on every platform, never with %n's
    // platform separator, which is a carriage return and a line feed on Windows.
    text.append(String.format(Locale.ROOT, "%s %d\n", name, value));
  }

  private static <T> Map<DeliveryMethod, T> perMethod(java.util.function.Supplier<T> maker) {
    Map<DeliveryMethod, T> values = new EnumMap<>(DeliveryMethod.class);
    for (DeliveryMethod method : DeliveryMethod.values()) {
      values.put(method, maker.get());
    }
    return values;
  }
}
```

`line` writes `name value` and a line break; `%s` is a text and `%d` a whole number. The line break is `\n`, a line feed, written as an escape inside the template. The Prometheus text format, which `/metrics` follows, separates lines with a line feed. Version 1.0.0 used `%n` instead, which is the platform's line separator: a line feed on macOS and Linux, but a carriage return and a line feed on Windows, so the same code would have written a different format there. I checked the old and new classes in a JVM started with the Windows separator (`java -Dline.separator=$'\r\n' ...`): the old text had 30 carriage returns, the new one none. `perMethod` is a **generic method**: `<T>` stands for any type, so the same code builds a map of `AtomicInteger`s and a map of `LongAdder`s. `Supplier<T>` is a function with no arguments that returns a `T`. The `EnumMap` is filled with one fresh `T` per method.

## Try it yourself

All the commands were run in a copy of the repository on the verification machine (an ARM-based Mac, Eclipse Temurin 25.0.2). Timings and the stream identifier differ on every run.

**1. Count the files and lines of the package.** From the repository folder:

```bash
ls src/main/java/io/plainround/seatwave/live/feed | wc -l
cat src/main/java/io/plainround/seatwave/live/feed/*.java | wc -l
```

The output was `16` and `1405`. Your numbers match if the code has not changed.

**2. Watch a position being read.** First compile with `./gradlew -q compileJava` (the first run downloads nothing if the libraries are cached; without a cache it needs the network). Then save this as `PositionDemo.java` in any empty folder:

```java
import io.plainround.seatwave.live.feed.FeedPosition;

public class PositionDemo {
  public static void main(String[] args) {
    System.out.println(FeedPosition.parse("4d4f4a94.231"));
    System.out.println(FeedPosition.parse("7"));
    System.out.println(FeedPosition.parse("4d4f4a94.0231"));
  }
}
```

Run it with the compiled classes and the `result4j` jar on the class path (the jar's path in the Gradle cache contains a hash, so `find` looks it up):

```bash
R=$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1)
java -cp build/classes/java/main:$R PositionDemo.java
```

The output was:

```text
Success[result=FeedPosition[streamId=4d4f4a94, sequence=231]]
Error[error=must be <8 hex digits>.<sequence>, as the feed sent it]
Error[error=must be <8 hex digits>.<sequence>, as the feed sent it]
```

The third line shows that a leading zero in the sequence is refused, as the pattern says.

**3. Start the feed and watch the counters grow.** In one terminal run `./gradlew -q serve --args='config/serve.properties'`. In another:

```bash
curl -s http://127.0.0.1:8090/metrics | grep -E 'changes_published|deepest'
sleep 2
curl -s http://127.0.0.1:8090/metrics | grep -E 'changes_published|deepest'
```

On the verification machine I used ports 18190 and 18191 in a copy of `config/serve.properties` because other work was using 8090. The numbers rise by about 40 in two seconds, since the on-sale makes 20 changes per second. Stop the feed with Ctrl+C.

## Mistakes people make

- **Letting a queue grow without a bound.** The easy way to hand changes to a slow client is an unbounded list. A client that stops reading then makes the server run out of memory. `Subscriber` has a bound, and a full queue ends in a reload.
- **Taking a catch-up and registering for live changes as two steps.** A change published between them is lost or delivered twice. `SeatFeed.subscribe` does both under one lock.
- **Numbering changes outside the store's lock.** Two threads could number in one order and change the seats in another. `SeatStore` publishes inside its lock.
- **Waiting on a condition with `if` instead of `while`.** A thread can wake without a signal, or the condition can already have changed again. Both waits here are in loops.
- **Releasing a lock without `finally`.** An exception would leave the lock held for ever. Every method here unlocks in `finally`.
- **Trusting a position from a client.** A position from an earlier process can be valid text and still wrong. The stream identifier turns that case into an explicit reload.

## Check yourself

1. **What are the only two answers `ReplayBuffer.after` can give, and why is that important?** `Catchup.Changes` or `Catchup.ReloadRequired`. With no third answer, no code path can give a client a gap.
2. **A ring has 4 slots and the newest sequence is 10. Which positions can resume, and which must reload?** The ring holds 7 to 10. A client at 6 or later can resume (it needs 7 or later). A client at 5 or earlier must reload.
3. **Why does `Subscriber.offer` clear the queue instead of dropping only the newest change?** Dropping one change would leave a hole the client could not see. Clearing and marking for reload tells the client to load the map again, so there is no silent gap and memory stays bounded.
4. **Why is the lock order "store, then feed" and never the reverse?** `SeatStore` calls `SeatFeed.publish` while holding its own lock. If any code took the feed's lock first and then the store's, two threads could each hold one and wait for the other: a deadlock.
5. **What does `ConnectionLimit.Ticket.close` return on a second call, and why does that matter?** It does nothing: `open.remove(this)` is false the second time, so the permit is released only once and the open count stays right.

## Further reading

- The Java Language Specification, Java SE 25: https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- Java SE 25 API, `java.util.concurrent.locks`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/package-summary.html
- Java SE 25 API, `java.util.concurrent.atomic`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/package-summary.html
- JEP 395, Records: https://openjdk.org/jeps/395
- JEP 409, Sealed Classes: https://openjdk.org/jeps/409
