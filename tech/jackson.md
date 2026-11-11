# JSON and Jackson 2.23.0

## What it is

**JSON** (JavaScript Object Notation) is a plain-text format for structured data, defined in RFC 8259. A JSON text is one **value**. A value is one of: an **object** (named fields in curly braces, `{"name": value, ...}`), an **array** (an ordered list in square brackets, `[value, ...]`), a **string** (text in double quotes), a **number**, `true` or `false`, or `null` (no value). Spaces and line breaks between the parts do not matter, but a line break may not appear inside a string. This is one seat change as the feed sends it:

```json
{"type":"seat","position":"4d4f4a94.231","seat":"A-7","state":"held","changedAt":"2026-11-11T10:00:00Z"}
```

It is an object with five fields, all strings.

**Jackson** is a Java library that converts between JSON text and Java objects. Writing an object as JSON is **serializing**; reading JSON into an object of a given type is **deserializing**, or **binding**. The JDK has no JSON parser, so a library is needed. This repository pins Jackson **2.23.0**. Three Jackson jars take part: `jackson-core` (the low-level reader and writer of JSON tokens, 2.23.0), `jackson-databind` (the part that maps tokens to and from Java objects, 2.23.0) and `jackson-annotations` (the tags such as `@JsonInclude`, version `2.23`, which Jackson numbers with two parts). A fourth artifact, `jackson-bom`, is a **bill of materials**: a published list of matching versions, so that every Jackson jar on the class path is the same release.

## Why this repository uses it

All three delivery methods send the same messages: a seat change, or an instruction to reload. The README says so: one JSON shape for all three methods. Long polling sends a list of them in one reply, server-sent events put each in a `data:` line, and a WebSocket sends each as a text frame. The seat map and the error bodies are JSON too. The Java clients of the comparison read the same JSON back. Writing and parsing JSON by hand would be a few hundred lines of code that the course would then have to test; the course's approved list names Jackson for JSON, and Module 7 used the same 2.x line.

The second reason is safety. The clients read what a server sends. The repository binds that text only to a few known records, and an unknown field is an error, so nothing a server sends can make the client build an object of a type the code did not expect (the course's guideline 59).

## Where it appears

| Path | How it uses Jackson |
|---|---|
| `src/main/java/.../wire/FeedJson.java` | The one class that holds a configured `ObjectMapper`. `bytes` serializes, `read` binds. |
| `src/main/java/.../wire/MessageView.java` | The only Jackson annotation in the repository: `@JsonInclude`. |
| `src/main/java/.../wire/LongPollView.java`, `SnapshotView.java`, `http/ProblemView.java` | Records whose components are the fields of the JSON. They have no Jackson code at all. |
| `src/main/java/.../http/Replies.java`, `LongPollRoute.java`, `EventStreamRoute.java`, `FeedHttpServer.java`, `websocket/WebSocketSession.java` | The server: call `FeedJson.bytes` for every reply, event and frame. |
| `src/main/java/.../client/LongPollConnection.java`, `EventStreamConnection.java`, `WebSocketConnection.java`, `SeatMapReader.java`, `bench/FeedAdmin.java` | The clients: call `FeedJson.read` for every reply, event and frame. |
| `src/test/java/.../client/EventStreamConnectionTest.java`, `FeedClientTest.java` | Give a real `FeedJson` to the code under test. |
| `gradle/libs.versions.toml`, `build.gradle.kts`, `gradle.lockfile` | Pin the version, import the bill of materials, and lock the four coordinates (`gradle.md`, `toml-version-catalog.md`, `dependency-locking.md`). |

The main code imports Jackson in two files only, `FeedJson.java` and `MessageView.java`. Everything else goes through `FeedJson`, so a change of library would touch one package.

## How it works underneath

**An `ObjectMapper`** is Jackson's main object. You configure it once, then call `writeValueAsBytes(object)` or `readValue(bytes, SomeType.class)` as often as you like. Jackson documents a configured mapper as safe to share between threads, as long as its configuration is not changed after first use. The feed shares one mapper per `FeedJson` across all its connection threads.

**Writing a record.** A **record** is a Java class that is only its data, such as `record MessageView(String type, String position, ...)`. Jackson finds the record's **components** (the names in parentheses), calls their accessor methods (`type()`, `position()` and the others) and writes one JSON field per component, in the order the record declares them. A `String` becomes a JSON string, an `int` a number, a `boolean` `true` or `false`, a `List` an array, another record a nested object, and a `null` the JSON `null`, unless an annotation says to leave it out.

**Reading a record.** A record has one **canonical constructor**, with one parameter per component. Jackson reads the JSON object's fields, matches them to the components by name, and calls that constructor. A field missing from the JSON arrives as `null` (or 0 or `false` for a number or a boolean). The record needs no setters, no empty constructor and no annotations. If the record has a **compact constructor** (code that runs inside the canonical one), that code runs too, so a record can repair or refuse what it is given.

**Tokens and features.** Underneath, `jackson-core` reads the text as a stream of tokens: start of object, field name, value, end of object. A text that is not valid JSON fails there, with a `JsonParseException`. `jackson-databind` then binds the tokens to the type. A **feature** is a named switch on the mapper; `DeserializationFeature`s change how binding behaves. A field that matches no component fails with an `UnrecognizedPropertyException` when the feature `FAIL_ON_UNKNOWN_PROPERTIES` is on. Both exceptions are subclasses of `java.io.IOException`, so code that reads JSON handles them like any other failed read.

## Walkthrough of this repository's use

All blocks are copied from the files by a script.

### The build

```toml
jackson = "2.23.0"
```

```toml
jackson-bom = { module = "com.fasterxml.jackson:jackson-bom", version.ref = "jackson" }
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind" }
```

The version catalog names the version once, `2.23.0`. The `jackson-bom` entry uses it (`version.ref = "jackson"`); the `jackson-databind` entry has no version of its own (`toml-version-catalog.md`).

```kotlin
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
```

`platform(...)` imports the bill of materials, which fixes the versions of the whole Jackson family; `jackson-databind` then gets 2.23.0 from it, and brings `jackson-core` and `jackson-annotations` with it. `implementation` means the main code compiles against it and the tests and programs run with it (`gradle.md`).

```text
com.fasterxml.jackson.core:jackson-annotations:2.23=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.core:jackson-core:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.core:jackson-databind:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson:jackson-bom:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

The lock file records the four coordinates that were resolved, on all four class paths, so no build can quietly pick another version (`dependency-locking.md`). Gradle's dependency report shows the same:

```text
+--- com.fasterxml.jackson:jackson-bom:2.23.0
|    +--- com.fasterxml.jackson.core:jackson-databind:2.23.0 (c)
|    +--- com.fasterxml.jackson.core:jackson-annotations:2.23 (c)
|    \--- com.fasterxml.jackson.core:jackson-core:2.23.0 (c)
+--- com.fasterxml.jackson.core:jackson-databind -> 2.23.0
|    +--- com.fasterxml.jackson.core:jackson-annotations:2.23
|    +--- com.fasterxml.jackson.core:jackson-core:2.23.0
```

`(c)` marks a constraint from the bill of materials, and `-> 2.23.0` says that no version was asked for and 2.23.0 was chosen.

### `FeedJson.java`: the one mapper

```java
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
```

The two Jackson types the class uses: the feature switch and the mapper.

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

- The class comment states the safety rule: incoming JSON binds only to this package's records, and an unknown field is an error, so a server cannot make the client build an arbitrary type.
- It also states the thread-safety: one configured `ObjectMapper` is safe to share.
- `new ObjectMapper()` makes a mapper with Jackson's defaults. `.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` turns on the one switch this repository names: a JSON field with no matching record component is an error. `enable` returns the same mapper, so the result is the configured mapper, stored in a `final` field and never changed again. (Jackson 2.23 has this feature on by default already; turning it on explicitly makes the rule visible in the code and keeps it if a default ever changes.)

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

`reload` builds a `MessageView` with only a type and a position; the seat, the state and the time are `null`. How Jackson writes that is decided by the annotation on `MessageView`, below.

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

`bytes` serializes. `mapper.writeValueAsBytes(...)` returns the JSON as UTF-8 bytes, the form every caller sends on the wire. `Objects.requireNonNull(value, "value")` refuses a missing value with a message that names it. `writeValueAsBytes` declares a checked `JsonProcessingException`, a kind of `IOException`. Only this package's records are written, which Jackson always can serialize, so a failure would be a bug: the comment says so, and the method rethrows it as an unchecked `UncheckedIOException` that names the class, with the original as its cause.

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

`read` binds. `<T> T read(byte[] json, Class<T> type)` is a **generic method**: `T` stands for the type the caller names, and `MessageView.class` is the object that names the type `MessageView`, so `read(bytes, MessageView.class)` returns a `MessageView`. `mapper.readValue(bytes, type)` parses and binds. Unlike `bytes`, this method passes the `IOException` on: malformed JSON and an unknown field are expected failures of reading what another program sent, and each caller decides what they mean (below).

### The records

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageView(
    String type, String position, String seat, String state, String changedAt) {
```

`@JsonInclude(JsonInclude.Include.NON_NULL)` is the repository's only Jackson annotation. It tells Jackson to leave out a field whose value is `null` instead of writing `"seat":null`. A seat change has all five fields; a reload has only two, and goes on the wire as `{"type":"reload","position":"4d4f4a94.231"}`. When a client reads that back, the three missing fields arrive as `null`, so the round trip gives the same record. The components are written in the declared order, which is why every message starts with `type`.

```java
public record LongPollView(List<MessageView> changes, String next, boolean reload) {

  /** Copies the list; a missing list is read as empty. */
  public LongPollView {
    changes = changes == null ? List.of() : List.copyOf(changes);
  }
```

The reply to a long poll: a `List<MessageView>` becomes a JSON array of message objects, `next` a string, `reload` `true` or `false`. The compact constructor runs when Jackson binds a reply too: a reply with no `changes` field would give `null`, and the constructor turns it into an empty list, so no code ever meets a `null` list. `List.copyOf` also makes the list unchangeable.

```java
public record SnapshotView(
    int eventId, String position, int sections, int seatsPerSection, String states) {}
```

The seat map. Three components are `int` (`eventId`, `sections` and `seatsPerSection`), so they are written as JSON numbers, not strings.

```java
public record ProblemView(String type, String title, int status, String detail, String instance) {}
```

The error body in the problem details format of RFC 9457. It has no annotation, so all five fields are always written; `status` is a number.

### The server writes

```java
    send(exchange, problem.status(), PROBLEM_JSON, json.bytes(view));
```

```java
      byte[] body = json.longPoll(catchup, layout, position);
```

```java
                exchange, HttpStatus.OK, parts.json().snapshot(parts.store().snapshot(), EVENT_ID));
```

```java
          byte[] event = event("reload", null, json.bytes(json.reload(feed.position())));
```

```java
          event("seat", change.position().written(), json.bytes(json.message(change, layout))));
```

```java
          byte[] message = parts.json().bytes(parts.json().reload(parts.feed().position()));
```

```java
      byte[] message = parts.json().bytes(parts.json().message(change, parts.layout()));
```

Every reply, event and frame of the server is made the same way: a `FeedJson` method builds the record (`message`, `reload`, `longPoll`, `snapshot`, or a `ProblemView` built by hand) and `bytes` turns it into UTF-8 JSON. The HTTP routes write the bytes as a body; the event stream puts them after `data: `; the WebSocket session puts them in a text frame. The node makes one `FeedJson` and shares it through `Parts`, and every connection thread uses it at once, which the shared mapper allows. The architecture notes that the JSON is encoded once per write, not once per change; at 1,000 clients that cost did not need saving.

### The clients read

```java
        reply = json.read(response.body(), LongPollView.class);
```

```java
      return Optional.of(json.read(response.body(), SnapshotView.class));
```

```java
        MessageView message = json.read(text.getBytes(StandardCharsets.UTF_8), MessageView.class);
```

```java
          MessageView message =
              json.read(data.toString().getBytes(StandardCharsets.UTF_8), MessageView.class);
```

```java
    return json.read(
        get("/v1/events/1/seat-map").getBytes(java.nio.charset.StandardCharsets.UTF_8),
        SnapshotView.class);
```

Each client reads with the record it expects, and nothing else. `getBytes(StandardCharsets.UTF_8)` turns a text that the client already holds as a `String` (a WebSocket text frame, an event's data lines) back into the UTF-8 bytes `read` takes. What a failed read means is up to each caller, and each one treats it as a broken connection, never as a crash: `SeatMapReader` returns an empty answer and the client backs off and tries again; the three connection classes end the connection with `Ending.DISCONNECTED`, and the client reconnects from its last position. `EventStreamConnectionTest` relies on exactly that: an event whose data lines join into invalid JSON is refused, and no message reaches the client's map.

### The tests

```java
      new EventStreamConnection(
              http, new FeedEndpoints(server.getAddress().getPort(), 1), new FeedJson(), WAIT)
          .follow(START, recorder);
```

```java
              new SeatMapReader(http, endpoints, new FeedJson(), WAIT),
```

The tests give the code a real `FeedJson`, not a stand-in: the JSON on the wire is part of what they check. The seat maps and events in those tests are written as JSON text by hand, so a change in how Jackson reads them would show up as a failing test. `ClientSeatMapTest` builds `MessageView` and `SnapshotView` records directly with their constructors and needs no JSON at all.

## Try it yourself

All of this ran on 11 November 2026 on the verification machine (Eclipse Temurin 25.0.2), from the repository after `./gradlew -q compileJava`.

1. See what the feed writes and how it reads. Save this as `JsonDemo.java` in an empty folder outside the repository:

```java
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.LongPollView;
import io.plainround.seatwave.live.wire.MessageView;
import java.nio.charset.StandardCharsets;

public class JsonDemo {
  public static void main(String[] args) throws Exception {
    FeedJson json = new FeedJson();
    MessageView seat = new MessageView("seat", "4d4f4a94.231", "A-7", "held", "2026-11-11T10:00:00Z");
    System.out.println(new String(json.bytes(seat), StandardCharsets.UTF_8));
    System.out.println(new String(json.bytes(json.reload(new FeedPosition("4d4f4a94", 231))), StandardCharsets.UTF_8));
    System.out.println(json.read("{\"type\":\"reload\",\"position\":\"4d4f4a94.231\"}".getBytes(StandardCharsets.UTF_8), MessageView.class));
    System.out.println(json.read("{\"next\":\"4d4f4a94.231\",\"reload\":true}".getBytes(StandardCharsets.UTF_8), LongPollView.class));
    try {
      json.read("{\"type\":\"seat\",\"price\":10}".getBytes(StandardCharsets.UTF_8), MessageView.class);
    } catch (java.io.IOException refused) {
      System.out.println(refused.getClass().getSimpleName() + ": " + refused.getMessage().lines().findFirst().orElse(""));
    }
    try {
      json.read("{\"type\":\"seat\",".getBytes(StandardCharsets.UTF_8), MessageView.class);
    } catch (java.io.IOException refused) {
      System.out.println(refused.getClass().getSimpleName() + ": " + refused.getMessage().lines().findFirst().orElse(""));
    }
  }
}
```

   Run it with the compiled classes and the four library jars on the class path. The jars' paths in the Gradle cache contain a hash, so `find` looks them up:

```bash
J=$(for jar in result4j-1.2.0 jackson-databind-2.23.0 jackson-core-2.23.0 jackson-annotations-2.23; do find ~/.gradle -name "$jar.jar" | head -1; done | paste -sd: -)
java -cp <repository>/build/classes/java/main:$J JsonDemo.java
```

   It printed:

```text
{"type":"seat","position":"4d4f4a94.231","seat":"A-7","state":"held","changedAt":"2026-11-11T10:00:00Z"}
{"type":"reload","position":"4d4f4a94.231"}
MessageView[type=reload, position=4d4f4a94.231, seat=null, state=null, changedAt=null]
LongPollView[changes=[], next=4d4f4a94.231, reload=true]
UnrecognizedPropertyException: Unrecognized field "price" (class io.plainround.seatwave.live.wire.MessageView), not marked as ignorable (5 known properties: "position", "type", "seat", "changedAt", "state")
JsonParseException: Unexpected end-of-input within/between Object entries
```

   Line 1: the components in their declared order. Line 2: `NON_NULL` left out the three empty fields. Line 3: reading it back gives `null` for them. Line 4: a long-poll reply with no `changes` field gets an empty list from the compact constructor. Line 5: an unknown field is refused, and the message lists the five fields the record has. Line 6: text that is not complete JSON fails in the token reader.

2. Show the Jackson jars of the main class path:

```bash
./gradlew -q --offline dependencies --configuration runtimeClasspath | grep -i jackson
```

   The first lines are the tree quoted under "The build" above; the last four are the lock file's `{strictly ...}` constraints.

3. Find every use: `grep -rn "FeedJson\|jackson" src/main/java src/test/java` lists the files of the "Where it appears" table.

## Mistakes people make

- **Making a new `ObjectMapper` for every message.** Building and configuring a mapper is slow, and it is safe to share. Make one and keep it, as `FeedJson` does.
- **Letting unknown fields pass in silence.** A typo in a field name then gives a `null` that surfaces much later. Turn `FAIL_ON_UNKNOWN_PROPERTIES` on and bind to known records.
- **Binding untrusted JSON to a general type** (`Object`, a `Map`, or a type the JSON itself names). The reader then decides what kind of objects you build. Bind to the exact record you expect.
- **Treating a parse failure as a bug.** JSON from another program can be malformed. Here it ends a connection, which the client already knows how to recover from.
- **Mixing two JSON libraries** in one program, or two Jackson versions. The bill of materials and the lock file keep one version of one library.
- **Writing `null` fields nobody needs.** A `reload` with three `null` fields would be valid JSON, but longer on every connection; `@JsonInclude(NON_NULL)` keeps the message short.

## Check yourself

1. *Which method serializes, and which binds?* `FeedJson.bytes` serializes with `writeValueAsBytes`; `FeedJson.read` binds with `readValue`.
2. *What does a reload message look like on the wire, and why?* `{"type":"reload","position":"..."}`: `@JsonInclude(NON_NULL)` leaves out the three `null` fields.
3. *What happens when a server sends a message with a field `price`?* `read` throws an `UnrecognizedPropertyException`; the client ends that connection and reconnects.
4. *Why does a long-poll reply without `changes` not give a `null` list?* Jackson calls the record's canonical constructor, and the compact constructor turns `null` into an empty list.
5. *Why can all connection threads share one `FeedJson`?* A configured `ObjectMapper` that is not reconfigured is safe to use from many threads.

## Further reading

- RFC 8259, The JavaScript Object Notation (JSON) Data Interchange Format: <https://www.rfc-editor.org/rfc/rfc8259>
- Jackson databind 2.23 API, `ObjectMapper`: <https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/2.23.0/com/fasterxml/jackson/databind/ObjectMapper.html>
- Jackson databind 2.23 API, `DeserializationFeature`: <https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/2.23.0/com/fasterxml/jackson/databind/DeserializationFeature.html>
- Jackson annotations 2.23 API, `JsonInclude`: <https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-annotations/2.23/com/fasterxml/jackson/annotation/JsonInclude.html>
- The Java Language Specification, Java SE 25, records: <https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.10>
