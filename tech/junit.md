# JUnit Jupiter 6.1.3

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit you write tests with. The **JUnit Platform** is the part that finds and runs them. This repository uses version **6.1.3** of both. JUnit 6 needs Java 17 or newer and keeps the Jupiter style of version 5.

An **annotation** is a label written with `@` in front of a method, class or parameter, such as `@Test`. It does nothing by itself. Tools read it.

## Why this repository uses it

The project's tests double as its requirements: every test method has a long name that states one behavior in plain words, and all 93 results run with `./gradlew check`. JUnit gives the structure. AssertJ (`assertj.md`) gives the readable checks and Awaitility (`awaitility.md`) the waiting. This module is about delivery to many clients, so many tests start a real feed node on the loopback address and talk to it with real HTTP and WebSocket clients, or race 50 virtual threads for one seat; JUnit only has to start the test and notice whether it returned or threw.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `gradle/libs.versions.toml` | `junit = "6.1.3"` and the entries `junit-bom`, `junit-jupiter` and `junit-platform-launcher`. |
| `build.gradle.kts` | `platform(libs.junit.bom)`, `libs.junit.jupiter` and `libs.junit.platform.launcher`, and `useJUnitPlatform()`. |
| `gradle.lockfile` | Locks the JUnit libraries at 6.1.3 (`dependency-locking.md`). |
| `src/test/java/**` | 18 test classes with 80 `@Test` methods and 2 `@ParameterizedTest` methods (together 93 results), and 3 helper classes that hold no tests (`MutableClock`, `Raw`, `TestSettings`). |
| `src/test/resources/junit-platform.properties` | One JUnit setting: a 60-second default timeout for every test (`tests-other-packages-walkthrough.md`). |

## How it works underneath

1. `./gradlew test` starts a new JVM and hands it the test classes, with the JUnit Platform launcher (`junit-platform-launcher`) as the entry point.
2. The Platform asks the **Jupiter engine** (`junit-jupiter-engine`) to **discover** tests: it scans the classes under `build/classes/java/test` for methods marked `@Test` or `@ParameterizedTest`.
3. For **each** test method Jupiter creates a **new instance** of the test class, runs the methods marked `@BeforeEach`, then the test method, then the methods marked `@AfterEach`, and throws the instance away. State cannot leak from one test to the next through an ordinary field.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError`, which is what a wrong check throws. It is an **error** if it throws anything else.
5. Results go to `build/test-results/test/*.xml` (one file per class, 18 here), and Gradle prints one line per result because of `testLogging { events("passed", "failed", "skipped") }`.
6. The test classes and methods are **package-private** (no `public`). JUnit 5 and 6 allow that, which keeps tests out of the public surface. The three helper classes are `public` on purpose, because tests in several packages use them.

JUnit does not promise an order between tests, and no test here depends on one. By default JUnit runs the tests one after the other on one thread, and Gradle uses one test JVM, so two tests never overlap in time. Each test that needs a node starts its own on port 0, which lets the system pick a free port, so the tests would not share a socket even if they did overlap.

## Walkthrough of this repository's use

### `@Test`: one behavior, one sentence

```java
  @Test
  void repeatsItsWaitsForTheSameSeed() {
    assertThat(new Backoff(BASE, CAP, 7).delay(4)).isEqualTo(new Backoff(BASE, CAP, 7).delay(4));
  }
```

`@Test` marks the method as a test. The name is a sentence about behavior, so a failure reads like a broken requirement: "repeats its waits for the same seed". The body has three steps that most tests here follow: **arrange** (build the input), **act** (call the code), **assert** (check the outcome). Here the single `assertThat` line builds two `Backoff` objects with the same seed, asks both for the wait before attempt 4 and checks that the answers are equal.

Tests that open a socket or load a file declare `throws Exception`. That is allowed: JUnit calls the method and treats any exception it throws as an error. It saves the test from catching checked exceptions such as `IOException` or `InterruptedException` that would be noise in a test.

### A new instance for every test

```java
class ConnectionLimitTest {

  private final FeedMetrics metrics = new FeedMetrics();
  private final ConnectionLimit limit = new ConnectionLimit(2, metrics);
```

`metrics` and `limit` are fields with an initial value, and they are not `static`. Because Jupiter creates a new `ConnectionLimitTest` object for every test method, each of the two tests in the class gets its own `FeedMetrics` and its own limit of two connections. The first test fills the limit; the second would see a full limit if the object were shared.

### `@BeforeEach` and `@AfterEach`: a fresh node for every test

```java
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

- `private LiveFeedNode node;` is a field of the object, not of the class (there is no `static`). Because JUnit creates a new instance for every test method, each test gets its own field.
- `@BeforeEach` marks a method that runs **before every test method**. `start()` starts one feed node (the seat store, the HTTP server and the WebSocket server, all on free ports) with the test settings.
- `@AfterEach` marks a method that runs **after every test method**, also when the test failed. `stop()` closes the node and the HTTP client so no port is left held. A test that publishes changes cannot disturb the next test, because the next test has a whole new node.
- `ClientSeatMapTest` uses `@BeforeEach` on its own, to load the same five-seat snapshot before each test:

```java
  private final ClientSeatMap map = new ClientSeatMap();

  @BeforeEach
  void load() {
    map.load(new SnapshotView(1, "0000abcd.10", 1, 5, "aaaaa"));
  }
```

### `@TempDir`: a folder for one test

```java
class MeasuringToolsTest {

  @TempDir Path folder;
```

`@TempDir` on a field asks JUnit to create a new empty temporary folder, store it in `folder` before each test, and delete it, with everything inside, after the test. `MeasuringToolsTest` and `BenchReportsTest` use it: the comparison writes its CSV file into the folder, and the report tests write a small CSV there and read it back. (`@TempDir` may also be put on a method parameter; this repository uses the field form in both places.)

### `@ParameterizedTest`: one test, many inputs

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

A **parameterized test** is one method that JUnit runs once per input. `@ParameterizedTest` replaces `@Test`. `@ValueSource(strings = {...})` lists the inputs, ten bad position strings here, and each one is passed to the method as `written`. The ten runs count as ten results (this is why `FeedPositionTest` has 13 results from 4 methods). The list includes the empty string, a missing dot, a dot with no sequence after it, an upper-case stream id, a stream id of seven characters, a negative sequence, a leading zero, a number too long for a `long`, a trailing space, and `../etc.1`, an attempt to smuggle a path into a position.

```java
  @ParameterizedTest
  @EnumSource(DeliveryMethod.class)
  void endsWithTheStoresSeatMapAfterEveryConnectionIsDroppedTwice(DeliveryMethod method)
      throws Exception {
```

`@EnumSource(DeliveryMethod.class)` takes every value of the enum, here `LONG_POLLING`, `SERVER_SENT_EVENTS` and `WEBSOCKET`, and runs the method once for each. One method therefore proves the same property of all three delivery methods (3 results).

### Closing what a test opens

```java
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
```

Here there is no `@AfterEach`. Instead the test opens the node and the raw socket in the round brackets of a `try`. A resource opened there is closed automatically when the block ends, even when an assertion throws, and in the reverse order of opening. `LiveFeedNode` and `Raw` implement `AutoCloseable` for that purpose. That is why no server is left holding its port after a failing test (`java.md` explains the construct).

### A helper class that is not a test

```java
/** A clock the test moves by hand, so expiries happen exactly when the test says. */
public final class MutableClock extends Clock {
```

`MutableClock` is not a test. It is a helper for several: a clock whose time the test moves by hand. JUnit ignores it because it has no `@Test`. The class is `public` and `final`, and `tests-other-packages-walkthrough.md` explains its lines.

### The one JUnit setting

```properties
# No test may hang the build: each one fails after 60 seconds.
junit.jupiter.execution.timeout.default = 60 s
```

A file with exactly this name on the test classpath is read by the JUnit Platform at start-up. `junit.jupiter.execution.timeout.default = 60 s` gives every test (and every `@BeforeEach` and `@AfterEach`) a time limit of 60 seconds; a test that runs longer fails with a timeout error instead of hanging the build. The longest test in the suite takes about 6.6 seconds, so the limit never fires in a healthy run. It exists to turn a deadlock or a missed wake-up in a concurrent program into a failure with a name.

### What this repository does not use

There is no `@BeforeAll`, `@AfterAll`, `@Nested`, `@DisplayName`, `@Timeout`, `@Disabled` or `@ExtendWith`. The timeout is the global one above, not per test. No test is skipped: every run prints 93 `PASSED` lines and no `SKIPPED`.

### Static imports and the assertion style

All tests start with lines such as `import static org.assertj.core.api.Assertions.assertThat;`. A **static import** lets you write `assertThat(...)` instead of `Assertions.assertThat(...)`. The checks come from AssertJ, not from JUnit's own `Assertions` class (`assertj.md`).

### Where tests run from

Gradle runs the tests with the project folder as the working directory. That is why tests such as `LiveSettingsTest` and `MeasuringToolsTest` can read `config/serve.properties` and `config/compare-server.properties` by those relative paths.

### The build wiring

`testImplementation(libs.junit.jupiter)` makes the annotations and the engine available. `testRuntimeOnly(libs.junit.platform.launcher)` adds the launcher. `useJUnitPlatform()` tells the `test` task to use the Platform. The BOM `platform(libs.junit.bom)` fixes every JUnit library at 6.1.3 (`gradle.md`).

## Try it yourself

All ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run all tests and count the results:

```
./gradlew --offline cleanTest test | grep -c PASSED
```

   Output: `93`. (`cleanTest` forces the tests to run again instead of being skipped as up to date.)

2. Run one class:

```
./gradlew --offline cleanTest test --tests '*BackoffTest'
```

   Output: four lines ending in `PASSED`, then `BUILD SUCCESSFUL in 1s` and `7 actionable tasks: 3 executed, 4 up-to-date`.

3. Run one method:

```
./gradlew --offline cleanTest test --tests '*SeatStoreTest.letsExactlyOneOfManyRacingFansHoldASeat'
```

   Output: one `PASSED` line, `SeatStoreTest > letsExactlyOneOfManyRacingFansHoldASeat() PASSED`.

4. Count the annotations at the start of a line:

```
grep -rhoE "^ *@[A-Za-z]+" src/test | sed 's/^ *//' | sort | uniq -c
```

   Output: `1 @AfterEach`, `2 @BeforeEach`, `1 @EnumSource`, `7 @Override`, `2 @ParameterizedTest`, `2 @TempDir`, `80 @Test` and `1 @ValueSource`. (Together: 80 plain tests, 10 results from `@ValueSource` and 3 from `@EnumSource` make 93.)

5. Read the totals from the result files:

```
grep -o 'tests="[0-9]*"' build/test-results/test/*.xml
```

   Each of the 18 files has a `tests="..."` count after a full run: 3 (`BenchReportsTest`), 5 (`MeasuringToolsTest`), 4 (`BackoffTest`), 4 (`ClientSeatMapTest`), 3 (`ReconnectCorrectnessTest`), 3 (`LiveSettingsTest`), 2 (`ConnectionLimitTest`), 13 (`FeedPositionTest`), 3 (`OnSaleSimulatorTest`), 6 (`ReplayBufferTest`), 6 (`SeatFeedTest`), 4 (`SeatLayoutTest`), 7 (`SeatStoreTest`), 5 (`EventStreamRouteTest`), 11 (`FeedHttpServerTest`), 4 (`FrameCodecTest`), 4 (`HandshakeTest`) and 6 (`WebSocketServerTest`). They add up to 93.

## Mistakes people make

- **Vague names** such as `test1`. A good name is a sentence about behavior, as here.
- **Tests that depend on each other's order.** JUnit gives no order promise. Each test builds its own node, on a port the operating system picks.
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Forgetting the launcher with Gradle 9.** Without `junit-platform-launcher` on the test runtime classpath the tests can not start. The build declares it.
- **A filter that matches too much.** `--tests '*feed*'` would also match the package name `feed`, so it runs every test in that package. Use the full class name when you want one class.
- **Running a test from a different folder.** The settings files are found by relative paths, so the test must start in the project folder, as Gradle does.
- **Starting a server and never stopping it.** A server left running after a test holds its port. The tests close every node in `@AfterEach` or in a `try` block.
- **Sharing a field between tests and expecting it to reset.** A `static` field is shared by all tests of a class. These tests use object fields on purpose.

## Check yourself

1. *What does `@Test` do?* Marks a method that JUnit runs as a test.
2. *Why does JUnit create a new instance of the class for every test?* So no test can see state left by another. It is why each test here gets a new limit, a new clock or a new node.
3. *What does `@TempDir` do?* JUnit makes a new empty folder for the test, stores it in the field or parameter, and deletes it afterwards.
4. *When does an `@AfterEach` method run?* After every test method, also after a failed one.
5. *How do 80 plain tests and 2 parameterized methods give 93 results?* The 2 parameterized methods run 10 times (`@ValueSource`) and 3 times (`@EnumSource`): 80 + 10 + 3 = 93.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- Writing tests and annotations: <https://docs.junit.org/6.1.3/writing-tests/annotations.html>
- Running JUnit on Gradle: <https://docs.gradle.org/9.9.1/userguide/java_testing.html>
