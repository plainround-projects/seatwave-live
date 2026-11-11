# AssertJ 3.27.7

## What it is

An **assertion** is a check inside a test: "this value must be that value". AssertJ is a library of assertions with a **fluent** style: you write `assertThat(actual)` and then chain method calls that read like a sentence, each returning the object so the next call can follow. When a check fails, AssertJ prints the expected and the actual value in a clear message.

## Why this repository uses it

Tests are the requirements here, so they should read like requirements. `assertThat(changes.changes()).extracting(change -> change.position().sequence()).containsExactly(8L, 9L, 10L)` says "the replay buffer returns changes 8, 9 and 10, in that order, and nothing else" in one statement. The README lists AssertJ with the reason "The tests, their assertions, and waiting for a condition instead of sleeping." In a test of a feed the message matters, because a failure often shows a string of seat states, an HTTP reply or a list of positions, and that value is the clue.

## Where it appears

| Path | What it does with AssertJ |
| --- | --- |
| `gradle/libs.versions.toml` | `assertj = "3.27.7"` and `assertj-core`. |
| `build.gradle.kts` | `testImplementation(libs.assertj.core)`. |
| `gradle.lockfile` | Locks `assertj-core` and its dependency `byte-buddy` 1.18.3 (`dependency-locking.md`). |
| `src/test/java/**` | `assertThat` is used in all 18 test classes; `assertThatThrownBy` in 6 classes (`FrameCodecTest`, `FeedPositionTest`, `SeatLayoutTest`, `ClientSeatMapTest`, `ReplayBufferTest`, `BackoffTest`). There are 207 `assertThat(` calls and 21 `assertThatThrownBy(` calls in all. |

## How it works underneath

1. `assertThat(x)` looks at the type of `x` and returns the matching **assertion object**: one for a `String`, one for a `List`, one for a `Map`, one for an `int`, one for a `boolean`, one for an `Optional`, one for a `byte[]`, one for a `Path`, one for any other object. Each has the methods that make sense for that type.
2. Each method (`isEqualTo`, `contains`, `hasSize`) checks the value. If the check holds, it returns the same assertion object, so another call can follow. If not, it throws an `AssertionError` with a message, and JUnit marks the test as failed (`junit.md`).
3. For exceptions, `assertThatThrownBy(() -> code)` runs the code in a try block, catches whatever is thrown and lets you check its type. The `() -> code` part is a **lambda**: a small unnamed function that is handed over and run later, inside AssertJ, so AssertJ can catch what it throws.
4. Because the checks are chained on one `assertThat`, several conditions on the same value come in one statement.
5. An assertion runs once, on the thread that calls it, at that moment. It does not wait. A test of a network program therefore waits in a different way: the call to the server blocks until the answer comes or its timeout ends, and only then does the assertion look at it. When a test must wait for something that happens later in another thread, it uses Awaitility (`awaitility.md`).

## Walkthrough of this repository's use

The numbers in this section come from `grep -rhoE` over `src/test` and count raw text matches of each method call, so a call of the same name on another type (for example `contains` on a `Set` inside production-like helper code) would be counted too. The most used method names in the suite are `isEqualTo` (89), `contains` (39), `isInstanceOf` (30), `startsWith` (20), `isEmpty` (12), `hasSize` (11) and `anySatisfy` (7).

### `isEqualTo`: the most used check

```java
  @Test
  void appliesTheNextChange() {
    assertThat(map.apply(seat("0000abcd.11", "A-2", "held"))).isEqualTo(Applied.APPLIED);
    assertThat(map.states()).isEqualTo("ahaaa");
    assertThat(map.position().written()).isEqualTo("0000abcd.11");
  }
```

`isEqualTo` compares with `equals`. In this test:
- `map.apply(...)` returns a value of the enum `Applied`, and `isEqualTo(Applied.APPLIED)` expects exactly that constant.
- `map.states()` is a `String` of one letter per seat, and `isEqualTo("ahaaa")` expects seat 2 (`A-2`) to be `h` for held and the other four seats `a` for available.
- `map.position().written()` is the text form of the client's position, expected to be `0000abcd.11`, one past the snapshot's `0000abcd.10`.

I changed the second expectation to `"aaaaa"` in a scratch copy and ran the test. Gradle printed `ClientSeatMapTest > appliesTheNextChange() FAILED` and `org.opentest4j.AssertionFailedError at ClientSeatMapTest.java:23`; the message is in the result file, as `expected: "aaaaa"` and ` but was: "ahaaa"`.

`isEqualTo` also compares whole values. In `SeatStoreTest`, `result4j` results are compared as values:

```java
    assertThat(store.hold(3)).isEqualTo(Result.error(SeatRefusal.SEAT_TAKEN));
    assertThat(store.hold(10)).isEqualTo(Result.error(SeatRefusal.UNKNOWN_SEAT));
    assertThat(store.hold(-1)).isEqualTo(Result.error(SeatRefusal.UNKNOWN_SEAT));
```

`Result.error(SeatRefusal.SEAT_TAKEN)` builds a result that holds that refusal, and `isEqualTo` compares it with what `hold` returned (`result4j.md`).

### Text: `contains` with several pieces, `startsWith`, `endsWith`

```java
      assertThat(head)
          .contains("HTTP/1.1 200", "Content-type: text/event-stream", "Cache-control: no-store");
      assertThat(event)
          .contains(
              "id: " + start.next().written() + "\n",
              "event: seat\n",
              "data: {\"type\":\"seat\"",
              "\"seat\":\"A-1\"");
```

`contains` on a `String` checks that the text includes every given piece (the method takes any number of them). A failing `contains` prints the whole text and the missing piece. Here the first `assertThat` reads the reply headers the server sent for an event stream and expects the status line `HTTP/1.1 200`, the content type `text/event-stream` and `no-store` caching. The second reads one event and expects its `id:` line to be the next position, its `event:` line to say `seat`, and its `data:` line to be the JSON of a seat change for seat `A-1`. `startsWith` (20 calls), `endsWith` (4), `doesNotContain` (1) and `doesNotEndWith` (1) are the neighbors.

### Exceptions: `assertThatThrownBy`, `isInstanceOf`, `hasMessageContaining`

```java
  @Test
  void refusesANegativeSequenceAndABadStream() {
    assertThatThrownBy(() -> new FeedPosition("3f9a2c1e", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FeedPosition("xyz", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

`assertThatThrownBy(() -> new FeedPosition("3f9a2c1e", -1))` runs the constructor and catches what it throws. `.isInstanceOf(IllegalArgumentException.class)` checks the type. If nothing was thrown, the check fails with "Expecting code to raise a throwable". The check on a type is exact enough to matter: `isInstanceOf(Exception.class)` would pass for almost any failure.

```java
  @Test
  void refusesUnmaskedFragmentedReservedAndOversizedFrames() {
    assertThatThrownBy(() -> FrameCodec.read(input(new byte[] {(byte) 0x89, 0}), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("masked");
    assertThatThrownBy(() -> FrameCodec.read(input(clientFrame(Frame.TEXT, "a")), 125))
        .isInstanceOf(ProtocolViolation.class)
        .hasMessageContaining("fragmented");
```

`hasMessageContaining("masked")` (5 calls in all) adds a check on the exception's message. Two different refusals throw the same type, `ProtocolViolation`, and the message tells them apart: this test proves that an unmasked frame is refused for being unmasked and a fragmented frame for being fragmented, not for some other reason.

### Collections: `hasSize`, `extracting`, `containsExactly`, `containsExactlyInAnyOrder`, `anySatisfy`

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

- `assertThat(catchup).isInstanceOf(Catchup.Changes.class)` checks the kind of answer before the test casts it.
- `assertThat(changes.changes()).extracting(change -> change.position().sequence())` turns a list of changes into a list of their sequence numbers. `extracting` takes a function that is applied to each element.
- `.containsExactly(8L, 9L, 10L)` demands those elements, in that order, with nothing else. The `L` marks a `long`, because the sequence numbers are `long`.

```java
    assertThat(problemsOf(settings))
        .containsExactlyInAnyOrder(
            "http.port must be from 1024 to 65535, but was 80",
            "websocket.allowedOrigin must be http://127.0.0.1:<port>, but was https://evil.example",
            "feed.replayCapacity is missing",
            "feed.maxBatch must be a whole number, but was many",
            "admin.enabled must be true or false, but was yes");
```

`containsExactlyInAnyOrder(...)` (1 call) demands exactly these five problem lines in any order. The settings reader collects every problem in one run, and the order of the lines is not part of the promise.

```java
    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("long-polling med    10"));
    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("sse          med    10"));
    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("websocket    med    10"));
    assertThat(
            lines.stream()
                .filter(line -> line.contains(" med "))
                .map(line -> line.substring(line.length() - 7).strip()))
        .containsOnly("10");
```

- `assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("long-polling med    10"))` (7 calls of `anySatisfy` in all) expects at least one element to satisfy the inner assertion. The report has many lines; the test names the one it cares about.
- The long statement builds a stream of the lines that contain ` med `, cuts the last seven characters of each, trims them, and `containsOnly("10")` (1 call) demands that the result holds nothing but `"10"`: in every method's median line, the last column, the number of clients whose map equals the store's, is 10.
- `hasSize(...)` (11 calls) is the usual check on a size, for example `hasSize(4)` on the CSV file's lines.

### Optionals: `contains`, `isEmpty`, `isPresent`, `hasValueSatisfying`

```java
    assertThat(held.discardError())
        .hasValueSatisfying(change -> {
          assertThat(change.state()).isEqualTo(SeatState.HELD);
          assertThat(change.position().sequence()).isEqualTo(1);
          assertThat(change.changedAt()).isEqualTo(clock.instant());
        });
```

`held.discardError()` turns a `Result` into an `Optional` of the success value. `hasValueSatisfying(change -> { ... })` (4 calls) checks that the `Optional` holds a value and then runs the inner assertions on it. In the same file the three inner lines check the new state, the position's sequence number and the timestamp, which must equal the fake clock's time. `SeatLayoutTest` uses the short form: `assertThat(arena.indexOf("B-7")).contains(1_006)` on an `Optional<Integer>` checks the content, `isEmpty()` (12 calls) checks that nothing is there, and `isPresent()` (4 calls) checks that something is.

### Numbers and flags: `isCloseTo`, `isBetween`, `isZero`, `isPositive`, `isGreaterThan`

```java
    assertThat(rates.shortPollingRequestsPerSecond()).isEqualTo(40_000.0);
    assertThat(rates.emptyShare(1 / 60.0)).isCloseTo(0.920, within(0.001));
    assertThat(rates.longPollingRequestsPerSecond(3_000)).isCloseTo(2_489_627, within(1.0));
    assertThat(rates.pushMessagesPerSecond(3_000)).isEqualTo(600_000_000.0);
```

- `isEqualTo(40_000.0)` compares a `double` exactly; this works because 40,000 is a whole number, which a `double` stores without any rounding.
- `isCloseTo(0.920, within(0.001))` (2 calls) accepts any value from 0.919 to 0.921. A computed share cannot be compared with `isEqualTo` after rounding; `within` states the tolerance in the open.
- `isBetween(300.0, 500.0)` in `BackoffTest` (2 calls) does the same with a range. `isZero()` (3), `isPositive()` (1), `isGreaterThan(...)` (2) and `isGreaterThanOrEqualTo(...)` (1) are the plain comparisons, and `isTrue()` (2) and `isFalse()` (2) check flags.

### Arrays, files and navigation

```java
    assertThat(FrameCodec.encode(Frame.TEXT, new byte[125]))
        .hasSize(127)
        .startsWith((byte) 0x81, (byte) 125);
```

An assertion on a `byte[]` has `hasSize` and `startsWith`. The cast `(byte) 0x81` is needed because a Java `byte` is signed and 0x81 does not fit; it says: the frame has 127 bytes, which is 2 bytes of header plus the 125 bytes of payload; the first byte of a final text frame is 0x81 and the second is the payload length, 125. The next line of that test, `encodeClose`, uses `containsExactly(0x88, 2, 0x03, 0xE8)`.

```java
    assertThat(Files.readAllLines(folder.resolve("out.csv"), StandardCharsets.UTF_8)).hasSize(4);
    assertThat(median.delay().p99Millis()).isEqualTo(2.0);
    assertThat(table).hasSize(6).last().asString().startsWith("sse          med");
    assertThat(ComparisonReport.median(runs.subList(0, 2)).delay().p99Millis()).isEqualTo(2.0);
```

`hasSize(6).last().asString().startsWith("sse          med")` **navigates**: after a check on the list, `last()` moves the assertion to the last element, and `asString()` treats it as text, so the next call is a `String` check. `assertThat(path).exists()` in `MeasuringToolsTest` checks that a file is there. `assertThat(dropped).hasValue(2)` in `ConnectionLimitTest` reads an `AtomicInteger` the same way.

### A description of your own: `as`

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

`.as(file)` (2 calls in all, the other is in `FeedHttpServerTest`) puts a name in front of the failure message. In this loop over five settings files a failure would otherwise say that an `Optional` was empty; with `.as(file)` it names the file that was wrong. It must come **before** the check that may fail, because a message set afterwards is too late.

### The build wiring

`testImplementation(libs.assertj.core)` makes AssertJ available to the test code only. Every test imports `assertThat` with `import static`, as `junit.md` explains. `Assertions.within` is imported in `BenchReportsTest`.

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Count the `assertThat(` calls:

```
grep -rhoE "assertThat\(" src/test | wc -l
```

   Output: `207`.

2. Count the `assertThatThrownBy(` calls:

```
grep -rhoE "assertThatThrownBy\(" src/test | wc -l
```

   Output: `21`.

3. See which assertion methods are used and how often:

```
grep -rhoE "\.(isEqualTo|contains|isInstanceOf|startsWith|isEmpty|hasSize|anySatisfy)\(" src/test | sort | uniq -c | sort -rn
```

   Output: `89 .isEqualTo(`, `39 .contains(`, `30 .isInstanceOf(`, `20 .startsWith(`, `12 .isEmpty(`, `11 .hasSize(` and `7 .anySatisfy(`.

4. Watch a failure message. In a scratch copy, change `isEqualTo("ahaaa")` in `ClientSeatMapTest.appliesTheNextChange` to `isEqualTo("aaaaa")` and run:

```
./gradlew --offline test --tests '*ClientSeatMapTest.appliesTheNextChange'
```

   Output: `ClientSeatMapTest > appliesTheNextChange() FAILED` and `org.opentest4j.AssertionFailedError at ClientSeatMapTest.java:23`, then `BUILD FAILED`. Gradle does not print the message itself. It is in the result file, `build/test-results/test/TEST-io.plainround.seatwave.live.client.ClientSeatMapTest.xml`, as `expected: "aaaaa"` and ` but was: "ahaaa"` (AssertJ's own wording). Put the line back afterwards.

5. List the files that use `assertThatThrownBy`:

```
grep -rl "assertThatThrownBy" src/test
```

   Output: six files: `FrameCodecTest.java`, `FeedPositionTest.java`, `SeatLayoutTest.java`, `ClientSeatMapTest.java`, `ReplayBufferTest.java` and `BackoffTest.java`.

## Mistakes people make

- **Swapping the two sides.** `assertThat(actual).isEqualTo(expected)`: the value you test goes inside `assertThat`. Swapped, the message calls the wrong one "expected".
- **A check that is built but not finished.** `assertThat(x);` with no check after it does nothing. Always end with a method such as `isEqualTo`.
- **Putting `.as(...)` after the check.** The description is read when the check fails, so it must come first.
- **Testing a type too loosely.** `isInstanceOf(Exception.class)` would pass for almost anything. The tests here name the exact type.
- **Comparing floating-point results with `isEqualTo`.** Use `isCloseTo(value, within(tolerance))`, as `BenchReportsTest` does for the share of empty polls.
- **Checking a list with `get(0)`.** `containsExactly`, `hasSize` and `anySatisfy` say what you mean and print the whole list when they fail.

## Check yourself

1. *What does `assertThat(actual)` return?* An assertion object for the type of `actual`, with the methods that suit that type.
2. *Which method checks that a number is exactly zero?* `isZero()`.
3. *What does `anySatisfy` do?* It runs an inner assertion on the elements of a collection and passes when at least one satisfies it.
4. *How do you check that code throws an `IllegalArgumentException`?* `assertThatThrownBy(() -> code).isInstanceOf(IllegalArgumentException.class)`.
5. *Why put `.as("...")` before the check?* So the sentence is part of the message when the check fails.

## Further reading

- AssertJ Core 3.27.7 documentation: <https://assertj.github.io/doc/>
- AssertJ Core API (Javadoc, 3.27.7): <https://www.javadoc.io/doc/org.assertj/assertj-core/3.27.7/index.html>
