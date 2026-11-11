# result4j 1.2.0 (a library for results that are values)

## What it is

result4j is a small Java library. Its main type is `Result<R, E>`. A **result type** is an object that stands for "either this worked and here is the value, or it failed and here is the reason". `R` is the type of the good value and `E` the type of the reason. A `Result` is exactly one of two things: a **success** that holds an `R`, or an **error** that holds an `E`.

The Java standard library has no such type. `Optional` says "a value or nothing" but cannot say why there is nothing. An exception says why, but it jumps out of the normal flow of the program. A `Result` fills the gap: the failure is an ordinary value that a method returns, and the caller must look at it.

The library is `com.github.sviperll:result4j`, version 1.2.0, licensed Apache-2.0 (its `pom.xml` inside the jar says so; the README lists the license and the date the version was checked). The jar in the Gradle cache has 39 entries.

## Why this repository uses it

Some failures are not bugs. A person can write `http.port=80` in a settings file. A client can send `after=7` in a URL. A browser can send a WebSocket handshake from the wrong origin. A fan can try to hold a seat that someone else holds. These are **expected failures**: the program should list them, name them and answer them, not die with a stack trace. And one run should report every settings problem, not only the first.

Exceptions are kept for problems only a bug can cause: a null argument, a record built with a value its constructor forbids, a broken socket. The line this repository draws: if a person, a file or the network can cause it in normal use, it is a `Result`; if only a bug can cause it, it is an exception.

The README states the reason the JDK alone is not enough: the JDK has no success-or-typed-error type, so a refused hold or a bad setting would have to be an exception.

The dependency is declared in the version catalog, used in the build file and pinned in the lock file. The four real lines:

```text
result4j = "1.2.0"
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
    implementation(libs.result4j)
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

`implementation` means the library is needed to compile and to run the main code. The lock file shows it on all four class paths (compile and run, for main code and for tests).

## Where it appears

Fifteen files under `src/main/java` import `com.github.sviperll.result4j.Result`. Two more files use a `Result` they get from another class without importing the name (`feed/OnSaleSimulator.java` and the `bench/SseResume.java` lab, which call `.isError()` on what `SeatStore.hold` and `confirm` return).

| File | What it does with `Result` |
|---|---|
| `feed/FeedPosition.java` | `parse` returns `Result<FeedPosition, String>`: a position, or a sentence saying what is wrong. |
| `feed/SeatStore.java` | `hold` and `confirm` return `Result<SeatChange, SeatRefusal>`: the change, or the reason it was refused. |
| `feed/OnSaleSimulator.java` | Tests `isError()` on the store's results. |
| `config/LiveSettings.java` | `from` returns `Result<LiveSettings, List<String>>`: the settings, or every problem line. |
| `http/Query.java` | `parse` returns `Result<Query, String>`. |
| `http/Positions.java` | `after` turns a parameter into a position or a message; uses `mapError`. |
| `http/LongPollRoute.java` | Switches over the query result; uses `recoverError`. |
| `http/EventStreamRoute.java` | The same, plus `Last-Event-ID`; uses `mapError` and `recoverError`. |
| `websocket/Handshake.java` | `read` returns `Result<HandshakeRequest, HandshakeRefusal>`: a request to accept or a refusal to send. |
| `websocket/WebSocketSession.java` | Switches over the handshake result; uses `discardError`. |
| `ServeCli.java` | `main` switches over `LiveSettings.from`. |
| `bench/CompareSettings.java` | `from` returns `Result<CompareSettings, List<String>>`. |
| `bench/ComparisonCli.java`, `WireBytesCli.java`, `SlowConsumerCli.java`, `SseResumeCli.java` | `main` switches over the settings results. |
| `client/ClientSeatMap.java`, `client/LongPollConnection.java` | Use `orOnErrorThrow` on `FeedPosition.parse`. |

## How it works underneath

`Result<R, E>` is a **sealed interface** with two permitted types, both **records**. I confirmed this with `javap -v` on the 1.2.0 jar: the class file of `Result` has a `PermittedSubclasses` entry listing `Result$Success` and `Result$Error`, and `Result$Success` is a `final class ... extends java.lang.Record`. A **sealed** interface lists every type allowed to implement it. That lets the compiler check a `switch`: with two possible cases, a `switch` that handles both needs no `default`, and one that forgets a case does not compile.

- `Result.Success<R, E>` is a record with one component, `result`. Its `toString` prints `Success[result=...]`.
- `Result.Error<R, E>` is a record with one component, `error`. Its `toString` prints `Error[error=...]`.

Both have the same two type parameters. A `Success` never uses `E`, and an `Error` never uses `R`. That is why a pattern is written `Result.Success<LiveSettings, List<String>>(LiveSettings settings)`: both parameters are spelled out.

Everything is in memory. A `Result` is an ordinary object on the heap. No thread, no network, no file. Making one costs one small object.

**The public methods** (from `javap -public` on the jar): `success`, `error`, `fromOptional`, `fromOptionalResult`, `isError`, `map`, `flatMap`, `andThen`, `mapError`, `discardError`, `recoverError`, `throwError`, `orOnErrorThrow`, `ifSuccess`, `peekError`, `peekSuccess`, and the function builders `mapping`, `flatMapping` and `errorMapping`. This repository uses these:

| Call | What it does | Used in |
|---|---|---|
| `Result.success(value)` | Makes a success. | Everywhere a `Result` is returned. |
| `Result.error(reason)` | Makes an error. | Everywhere a `Result` is returned. |
| a `switch` with the record patterns | Takes the result apart, one branch per case. | The `*Cli` classes, `WebSocketSession`, `LongPollRoute`, `EventStreamRoute`. |
| `instanceof Result.Error<...>(String detail)` | Tests for an error and names the reason in one step. | `LongPollRoute`, `EventStreamRoute`. |
| `isError()` | True for an error. | `OnSaleSimulator`, `SseResume`. |
| `mapError(f)` | Changes the reason in an error, leaves a success alone. | `Positions`, `EventStreamRoute`. |
| `recoverError(f)` | Turns an error into a value; a success gives its own value. | `LongPollRoute`, `EventStreamRoute`. |
| `discardError()` | Success becomes `Optional.of(value)`; error becomes `Optional.empty()`. | `WebSocketSession`. |
| `orOnErrorThrow(f)` | The value of a success; for an error, throws the exception that `f` builds from the reason. | `ClientSeatMap`, `LongPollConnection`. |

I ran a small program against the jar and the compiled repository to see each call work. The program:

```java
import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.feed.FeedPosition;
import java.util.Optional;

public class R4j {
  static String describe(Result<FeedPosition, String> r) {
    return switch (r) {
      case Result.Success<FeedPosition, String>(FeedPosition p) -> "ok " + p.written();
      case Result.Error<FeedPosition, String>(String why) -> "refused: " + why;
    };
  }

  public static void main(String[] args) {
    Result<FeedPosition, String> good = FeedPosition.parse("41550af9.368");
    Result<FeedPosition, String> bad = FeedPosition.parse("7");
    System.out.println(good);
    System.out.println(bad);
    System.out.println(describe(good));
    System.out.println(describe(bad));
    System.out.println(good.isError() + " " + bad.isError());
    System.out.println(bad.mapError(why -> "after " + why));
    System.out.println(good.discardError().isPresent() + " " + bad.discardError().isPresent());
    System.out.println(bad.recoverError(why -> new FeedPosition("00000000", 0)).written());
    System.out.println(good.map(FeedPosition::sequence));
    try {
      bad.orOnErrorThrow(IllegalArgumentException::new);
    } catch (IllegalArgumentException e) {
      System.out.println("threw: " + e.getMessage());
    }
  }
}
```

and its real output:

```text
Success[result=FeedPosition[streamId=41550af9, sequence=368]]
Error[error=must be <8 hex digits>.<sequence>, as the feed sent it]
ok 41550af9.368
refused: must be <8 hex digits>.<sequence>, as the feed sent it
false true
Error[error=after must be <8 hex digits>.<sequence>, as the feed sent it]
true false
00000000.0
Success[result=368]
threw: must be <8 hex digits>.<sequence>, as the feed sent it
```

## Walkthrough of this repository's use

Each place is quoted and explained. The files belong to the packages named; this file explains only the `Result` lines.

### `feed/FeedPosition.java`: a position, or a sentence

```java
  public static Result<FeedPosition, String> parse(String written) {
    Objects.requireNonNull(written, "written");
    Matcher matcher = WRITTEN.matcher(written);
    if (!matcher.matches()) {
      return Result.error("must be <8 hex digits>.<sequence>, as the feed sent it");
    }
    return Result.success(new FeedPosition(matcher.group(1), Long.parseLong(matcher.group(2))));
  }
```

`parse` reads a position the way a client wrote it, for example `41550af9.368`. The return type says what it can give: a `FeedPosition` or a `String`. If the text does not match the pattern (eight hex digits, a dot, a sequence number), it returns `Result.error` with the message that the HTTP server will later put in a `400` reply. If it matches, it builds the position and returns `Result.success`. Nothing is thrown, because a bad query parameter is normal input from outside. The `new FeedPosition(...)` inside can still throw for a negative sequence, but the pattern already ruled that out.

### `feed/SeatStore.java`: the change, or the reason it was refused

```java
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

```java
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

`hold` returns `Result.error(SeatRefusal.UNKNOWN_SEAT)` for an index outside the arena and `Result.error(SeatRefusal.SEAT_TAKEN)` when the seat is not available. Otherwise it returns `Result.success(change)` with the change that was published. `confirm` refuses with `NOT_HELD` when the seat is not held or its hold has expired. `SeatRefusal` is an enum, so the reason is one of a fixed list. Two clients racing for one seat is the ordinary case, so the loser gets a value, not an exception. The `lock` and `try ... finally` are the store's own locking, explained in `java-concurrency-in-the-feed.md`.

### `feed/OnSaleSimulator.java`: only the success matters

```java
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
```

The simulator does not import `Result`; it only calls `isError()` on what the store returns. `!store.confirm(heldByUs.poll()).isError()` is true when the confirm worked. The comment says that a confirm of an expired hold is refused by the store: "that is the rule working, not a bug". The same style appears for `hold`: it tries up to eight random seats and counts one that is not an error.

### `config/LiveSettings.java`: the settings, or every problem

```java
  public static Result<LiveSettings, List<String>> from(SettingsReader reader) {
```

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

`from` reads every key first (each read records a problem instead of throwing), and only at the end checks `reader.problems()`. If the list is not empty, it returns `Result.error(list)`: the error type is `List<String>`, one line per problem. Otherwise it builds the settings and returns `Result.success(...)`. This is how a settings file with three mistakes produces three lines in one run (see `java-properties.md`).

### `ServeCli.java` and the `*Cli` classes in `bench/`: the switch

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

`switch (LiveSettings.from(...))` with two cases. `case Result.Error<LiveSettings, List<String>>(List<String> problems) -> { ... }` takes the error apart and calls the list `problems`; it prints each line and exits with code 2. `case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> serve(settings);` takes the success apart and starts the server. If one of the cases were missing, the file would not compile. The four tools in `bench/` (`ComparisonCli`, `WireBytesCli`, `SlowConsumerCli`, `SseResumeCli`) have the same switch, and `ComparisonCli` nests a second one for the node's settings after the comparison's own settings. `bench/CompareSettings.from` returns `Result.error(reader.problems())` or `Result.success(new CompareSettings(...))` in the same way as `LiveSettings`.

### `http/Query.java`: a query string, or a message

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

`parse` accepts the raw text after the `?` in a URL, and a set of parameter names that are allowed. It returns three kinds of error, each a sentence: the query is longer than 256 characters, a parameter is not part of this request, a parameter is repeated. Everything else is `Result.success(new Query(values))`. An empty query is a success with no values. Refusing unknown and repeated parameters is a safety rule: the server does exactly what the contract says and nothing else.

### `http/Positions.java`: `mapError` and a default

```java
  static Result<FeedPosition, String> after(Query query) {
    return query
        .get("after")
        .map(written -> FeedPosition.parse(written).mapError(detail -> "after " + detail))
        .orElse(Result.error("after is required: the position of the last change you have"));
  }
```

`query.get("after")` returns an `Optional<String>`. `.map(written -> FeedPosition.parse(written).mapError(detail -> "after " + detail))` turns the text into a `Result` whose error message is prefixed with the parameter name. `mapError` changes only the error side: `must be <8 hex digits>...` becomes `after must be <8 hex digits>...`, which is what the real server replies (see the README, the `400` example). If there is no `after` parameter, `.orElse(Result.error("after is required: ..."))` gives the missing-parameter error.

### `http/LongPollRoute.java` and `http/EventStreamRoute.java`: `instanceof`, `recoverError`, and a nested switch

```java
  private static Result<FeedPosition, String> positionFrom(String rawQuery) {
    return switch (Query.parse(rawQuery, Set.of("after"))) {
      case Result.Error<Query, String>(String detail) -> Result.error(detail);
      case Result.Success<Query, String>(Query query) -> Positions.after(query);
    };
  }
```

```java
    Result<FeedPosition, String> asked = positionFrom(exchange.getRequestURI().getRawQuery());
    if (asked instanceof Result.Error<FeedPosition, String>(String detail)) {
      replies.problem(exchange, Problem.INVALID_REQUEST, detail, correlationId);
      return;
    }
    FeedPosition position = asked.recoverError(detail -> feed.position());
```

`positionFrom` switches over the query result: an error is passed on unchanged (`Result.error(detail)` makes a new error of the wanted type); a success goes on to `Positions.after`. In `serve`, `asked instanceof Result.Error<FeedPosition, String>(String detail)` tests for an error and names its text in one step; the route replies `400` with that text as a problem detail and returns. After that, `asked.recoverError(detail -> feed.position())` gets the position: for a success it is the value itself; the lambda for an error cannot run here, because the error case has already returned, and it is written to give the method a total shape.

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
```

```java
    Result<FeedPosition, String> asked = positionFrom(exchange);
    if (asked instanceof Result.Error<FeedPosition, String>(String detail)) {
      replies.problem(exchange, Problem.INVALID_REQUEST, detail, correlationId);
      return;
    }
    FeedPosition position = asked.recoverError(detail -> feed.position());
```

The event-stream route is the same, with one addition: a browser's `Last-Event-ID` header wins over the `after` parameter. `FeedPosition.parse(lastEventId).mapError(detail -> "Last-Event-ID " + detail)` names the header in the message if it is malformed. The `yield` keyword hands the value of a block inside a switch expression.

### `websocket/Handshake.java` and `websocket/WebSocketSession.java`: accept, or a refusal to send

```java
  public static Result<HandshakeRequest, HandshakeRefusal> read(
      InputStream in, String allowedOrigin) throws IOException {
    Objects.requireNonNull(allowedOrigin, "allowedOrigin");
    String head = readHead(in);
    if (head.isEmpty()) {
      return refuse(BAD_REQUEST, "the request head is larger than " + MAX_HEAD_BYTES + " bytes");
    }
    String[] lines = head.split("\r\n");
    String[] requestLine = lines[0].split(" ");
    if (requestLine.length != 3 || !"GET".equals(requestLine[0])) {
      return refuse(BAD_REQUEST, "a WebSocket handshake is a GET request");
    }
    String target = requestLine[1];
    int question = target.indexOf('?');
    String path = question < 0 ? target : target.substring(0, question);
    if (!PATH.equals(path)) {
      return refuse(NOT_FOUND, "no WebSocket at " + path);
    }
    Map<String, String> headers = headers(lines);
    if (!"websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""))
        || !headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade")) {
      return refuse(BAD_REQUEST, "Upgrade: websocket and Connection: Upgrade are required");
    }
    if (!VERSION.equals(headers.get("sec-websocket-version"))) {
      return refuse(UPGRADE_REQUIRED, "only WebSocket version 13 is supported");
    }
    String key = headers.getOrDefault("sec-websocket-key", "");
    if (!isValidKey(key)) {
      return refuse(BAD_REQUEST, "Sec-WebSocket-Key must be 16 bytes in base64");
    }
    String origin = headers.get("origin");
    if (origin != null && !origin.equals(allowedOrigin)) {
      return refuse(FORBIDDEN, "origin " + origin + " may not open this feed");
    }
    return Result.success(
        new HandshakeRequest(question < 0 ? "" : target.substring(question + 1), key));
  }
```

```java
  private static Result<HandshakeRequest, HandshakeRefusal> refuse(int status, String reason) {
    return Result.error(new HandshakeRefusal(status, reason));
  }
```

```java
      Result<HandshakeRequest, HandshakeRefusal> handshake = Handshake.read(in, allowedOrigin);
      switch (handshake) {
        case Result.Error<HandshakeRequest, HandshakeRefusal>(HandshakeRefusal refusal) ->
            out.write(Handshake.refused(refusal, retryAfterSeconds));
        case Result.Success<HandshakeRequest, HandshakeRefusal>(HandshakeRequest request) ->
            open(request, in, out);
      }
```

`read` parses the opening request of a WebSocket. Every way it can be wrong returns `refuse(status, reason)`, which builds `Result.error(new HandshakeRefusal(status, reason))`: the error side is a record that holds the HTTP status (400, 403, 404, 426) and a sentence. The README shows two real refusals: `403 Refused` for a wrong origin and `426 Refused` for version 8. A good request returns `Result.success(new HandshakeRequest(...))`. The session switches over it: an error writes the refusal bytes (`Handshake.refused(refusal, ...)`) and ends; a success goes on to `open`.

```java
  private static Optional<FeedPosition> positionOf(String rawQuery) {
    if (!rawQuery.startsWith(AFTER_PREFIX) || rawQuery.contains("&")) {
      return Optional.empty();
    }
    return FeedPosition.parse(rawQuery.substring(AFTER_PREFIX.length())).discardError();
  }
```

`positionOf` uses `discardError()`: it does not care why a position was bad, only whether there is one. A success becomes `Optional.of(position)`, an error becomes `Optional.empty()`, and the caller then falls back to the feed's current position or refuses.

### `client/ClientSeatMap.java` and `client/LongPollConnection.java`: `orOnErrorThrow`

```java
    FeedPosition position =
        FeedPosition.parse(String.valueOf(snapshot.position()))
            .orOnErrorThrow(IllegalArgumentException::new);
```

```java
      position =
          FeedPosition.parse(String.valueOf(reply.next()))
              .orOnErrorThrow(IllegalArgumentException::new);
```

Here the position comes from the server itself (the seat map's `position`, a long-poll reply's `next`, a change's `position`), not from a person. A malformed one means the server broke its own contract: that is a bug, not an expected failure, so the client converts the error back into an exception. `orOnErrorThrow(IllegalArgumentException::new)` passes the error text (a `String`) to the constructor `new IllegalArgumentException(String)` through a method reference and throws it. I ran it above: `threw: must be <8 hex digits>.<sequence>, as the feed sent it`. In `FeedClient` and the fleet this exception would end the client's thread, so a protocol violation is visible instead of quietly ignored.

## Try it yourself

1. List the library's public methods. Find the jar with `find ~/.gradle -name 'result4j-1.2.0.jar'`, then:

```text
javap -public -cp <the jar> com.github.sviperll.result4j.Result
```

You will see the 19 methods listed above (the output is the interface with `success`, `error`, `map` and the others).

2. Prove that `Result` is sealed:

```text
javap -v -cp <the jar> com.github.sviperll.result4j.Result | grep -A3 PermittedSubclasses
```

Output:

```text
PermittedSubclasses:
  com/github/sviperll/result4j/Result$Success
  com/github/sviperll/result4j/Result$Error
```

3. Run the demo program. Compile the repository once (`./gradlew -q compileJava`), save the program above as `R4j.java` outside the repository and run:

```text
java -cp "build/classes/java/main:<the result4j jar>" R4j.java
```

You get the ten lines shown above.

4. Find every use in the repository:

```text
grep -rn "Result\.\|isError\|recoverError\|mapError\|discardError\|orOnErrorThrow" src/main/java
```

5. See a `Result` at work in a real tool. Break a settings file (remove a key) and run any tool; the "settings: ... is missing" line is the `Error` side printed. Output from `config/compare.properties` with three mistakes is in `java-properties.md`.

## Mistakes people make

- **Using a `Result` for a bug.** A null argument or an impossible state should throw. A `Result` for everything makes callers check things that cannot happen.
- **Using an exception for ordinary input.** A mistyped setting is a normal event; the program should say which setting.
- **Ignoring the error side.** The point of the type is that a caller must look. A `switch` without both cases does not compile, which is why this repository uses `switch`.
- **Forgetting the second type parameter.** The patterns need `Result.Success<R, E>(...)` with both.
- **Wrapping an `Optional` in a `Result` without a reason.** Use an `Optional` when there is no reason to give.
- **Converting an error to an exception in the middle of a flow.** `orOnErrorThrow` is right only where an error means a bug, as in the client.

## Check yourself

1. What are the two kinds of `Result`, and what does each hold? (`Success` holds the value, `Error` holds the reason.)
2. Why can a `switch` over a `Result` have no `default`? (The interface is sealed with exactly two permitted types, so the compiler knows the cases are complete.)
3. What does `mapError` do to a success? (Nothing; it returns the success unchanged.)
4. Why does `LiveSettings.from` return a `List<String>` as its error type rather than a `String`? (So that one run reports every problem.)
5. Why do `ClientSeatMap` and `LongPollConnection` throw on a bad position instead of returning a `Result`? (The position comes from the server, not from a person: a bad one is a protocol bug, not an expected failure.)

## Further reading

- result4j, source and README: https://github.com/sviperll/result4j
- Java Language Specification, Java SE 25: sealed classes and interfaces (chapter 8.1.6 and 9.1.4), record patterns (14.30.1): https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- `java.lang.Record`, JDK 25 API: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Record.html
- JEP 440 (record patterns) and JEP 441 (pattern matching for switch): https://openjdk.org/jeps/440 and https://openjdk.org/jeps/441
