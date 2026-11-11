# Java 25 (the language and the JDK)

## What it is

Java is a programming language. You write **classes** in text files that end in `.java`. A program called the **compiler** (`javac`) turns them into **bytecode**, a compact instruction format stored in `.class` files. A second program, the **Java Virtual Machine** (JVM), reads the bytecode and runs it. The same bytecode runs on a Mac, a Windows PC and a Linux server.

Java is **statically typed**. Every variable, parameter and return value has a type, such as `int` (a whole number) or `String` (text). The compiler checks the types before the program runs. Passing text where a number is expected stops the build; it does not crash later.

The **JDK** (Java Development Kit) is the download that holds the compiler, the JVM and the **standard library**: ready-made classes such as `String`, `List`, `Duration`, `ByteBuffer`, an HTTP server and a WebSocket client. This repository uses **JDK 25**, a long-term-support release. The machine used for these notes had Eclipse Temurin 25.0.2 (`openjdk version "25.0.2" 2026-01-20 LTS`).

## Why this repository uses it

`seatwave-live` is the hands-on project of Module 8 of a system design course. It builds a live seat-availability feed three ways and measures them. Java 25 gives, with no framework:

- **A web server and an HTTP client in the JDK** (`com.sun.net.httpserver`, `java.net.http`), and plain sockets (`java.net`) for the WebSocket server that the JDK does not have.
- **Virtual threads**: cheap threads, so one thread per connection is affordable even at thousands of connections (see `java-concurrency-in-the-feed.md`).
- **Records, sealed interfaces and pattern matching**: small immutable data types and `switch` statements that the compiler proves complete. The feed's two central answers, "here are the changes" or "reload", are written as a sealed type, so no code path can forget a case.
- **A concurrency library** (`java.util.concurrent`): locks, conditions, atomics, executors and schedulers.
- **Time, regular expressions, hashing and base64** in the standard library.

## Where it appears

| Path | What it does with Java |
|---|---|
| `src/main/java/io/plainround/seatwave/live/` (2 files) | `LiveFeedNode`, `ServeCli`: wire one node and run it. |
| `.../feed/` (16 files) | The domain: store, feed, ring, queues. See `java-feed-walkthrough.md`. |
| `.../config/`, `.../http/`, `.../wire/` | Settings, the HTTP routes, JSON. See `java-http-and-wire-walkthrough.md`. |
| `.../websocket/` (11 files) | The WebSocket server. See `java-websocket-walkthrough.md`. |
| `.../client/`, `.../bench/` | The load clients and measuring tools. See `java-client-walkthrough.md` and `java-bench-walkthrough.md`. |
| `src/test/java/` | The tests, also Java. |
| `build.gradle.kts` | Tells Gradle to compile for Java 25 (`options.release = 25`) and to treat every warning as an error (`-Xlint:all -Werror`). |

In a copy of the repository, `./gradlew -q compileJava` compiled 82 source files under `src/main/java` into 112 class files. There are more class files than source files because each record, enum and nested type gets a class file of its own. The `feed` folder has 16 source files and 24 class files.

## How it works underneath

**From text to a running program.** You write `SeatChange.java`. The compiler reads it and writes `SeatChange.class`. Gradle does this for you. To start a program, the `java` command starts a JVM process, loads the class you name, and calls its `main` method. In this repository `./gradlew -q serve` runs `ServeCli.main`. A class file starts with a **major version** number that says which Java it was built for: the compiled `SeatChange.class` here reports `major version: 69`, which is Java 25. A JVM older than that refuses to load it.

**A process** is one running copy of a program, with its own memory. When `main` returns and no other non-daemon thread is running, the process ends with **exit status** 0, meaning success. `System.exit(n)` ends it at once with another number. A non-zero status means failure; `ServeCli` uses 2 for "the command was used wrongly".

**Memory.** The **stack** holds the method calls in progress with their local variables; every thread has its own. The **heap** holds objects: `new SeatChange(...)` makes an object on the heap. A variable holds a **reference**, like an address that points to the object. `null` is the reference that points to nothing. The JVM frees objects that nothing can reach any more; this is **garbage collection**.

**The JIT compiler.** The JVM first interprets bytecode. It counts how often a method runs and turns the busy ones into machine code while the program runs. The first seconds are therefore slower than the rest, which is why the measuring tools run a warm-up before they measure.

**Threads.** A **thread** is one line of execution. Several run at once on different cores. A **blocking** call, such as reading from a socket, makes its thread wait. A **virtual thread** is a thread that the JDK manages itself; when it blocks, the JDK parks it and lets another one use the core.

**The class path and modules.** The **class path** is the list of places where the JVM looks for compiled classes and libraries (**jar files**, which are zip files of classes). Gradle builds the right class path for each task. The JDK itself is split into **modules**: the web server used here lives in the module `jdk.httpserver`, and `com.sun.management` (open file descriptors, process CPU time) in `jdk.management`. Both are shipped with the JDK and exported for programs to use.

**Versions and preview features.** A new Java comes out every six months; some releases are marked LTS (long-term support) and are maintained for years; 25 is one. A language feature is either **final** (usable by everyone) or **preview** (usable only with a flag, and may change). The build passes no preview flag (`--enable-preview` does not occur in `build.gradle.kts`), so every feature in this repository is a final feature of Java 25, and each has a lowest version, listed at the end of this note. A program that uses a feature from Java 21 cannot be compiled by JDK 17.

## Walkthrough of this repository's use

This is the tour of every language feature the main code (`src/main/java`) uses, in the order a beginner meets them. Each has a quoted example from this repository. The tour found no `var`, no text block (`"""`), no anonymous class, no `assert`, no `synchronized` and no preview feature anywhere in `src/main/java`.

### Files, packages and imports

A **package** is a named group of classes. The name matches a folder path under `src/main/java`. The first line of a file is `package ...;`. Each `import` lets the file write a short class name.

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

### Classes, fields, methods and constructors

A **class** is a blueprint for objects. It has **fields** (data) and **methods** (code). A **constructor** is a method with the class's name that builds an object; `new ServeCli()` would call it. A **private empty constructor** stops anyone from making an object of a class that only has static methods.

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

**Visibility.** `public`: any class may use it. `private`: only this class. No word: only classes of the same package (this is called package-private; `ReplayBuffer` has no `public` on its class line, so only the `feed` package can use it). **`static`**: belongs to the class, not to an object. **`final`**: assigned once (a field or variable) or may not be extended (a class).

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

### Constants, numbers and operators

A **constant** is a `static final` field. The compiler allows underscores inside numbers (`1_024`, Java 7). The numeric types are `byte` (8 bits, -128 to 127), `short`, `int` (32 bits), `long` (64 bits), `char` (a character, stored as a 16-bit number), `double` and `boolean`. A value is converted to a wider type automatically and to a narrower one only with a **cast**, such as `(byte) x`. `0x80` is hexadecimal, base 16: 128.

```java
  private static final int FIN = 0x80;
  private static final int OPCODE_BITS = 0x0F;
  private static final int RESERVED_BITS = 0x70;
  private static final int MASK = 0x80;
  private static final int LENGTH_BITS = 0x7F;
  // Lengths up to 125 fit in the second byte; 126 means a 16-bit length follows, 127 a 64-bit one.
  private static final int LONGEST_SHORT_LENGTH = 125;
  private static final int SIXTEEN_BIT_LENGTH = 126;
  private static final int SIXTY_FOUR_BIT_LENGTH = 127;
  private static final int LARGEST_SIXTEEN_BIT = 0xFFFF;
  private static final int MASK_KEY_BYTES = 4;
  private static final int CONTROL_OPCODES = 0x8;
  private static final int BYTE = 0xFF;
  private static final int BITS_PER_BYTE = 8;
  private static final int CLOSE_CODE_BYTES = 2;
  // RFC 6455: the code a close frame without a code is reported as.
  private static final int NO_STATUS_RECEIVED = 1005;
```

Operators: `+ - * /` (integer division drops the fraction), `%` (remainder), `== != < > <= >=`, `&& ||` (and, or; they stop as soon as the answer is known), `!`, and the **bit** operators `&` (and), `|` (or), `^` (xor), `<<` and `>>` (shift). The frame codec uses them to read and write single bits of a byte:

```java
    byte[] maskKey = readExactly(in, MASK_KEY_BYTES);
    byte[] payload = readExactly(in, (int) length);
    for (int index = 0; index < payload.length; index++) {
      payload[index] ^= maskKey[index % MASK_KEY_BYTES];
    }
    return new Frame(opcode, payload);
  }

  /**
   * Reads the close code of a close frame's payload.
   *
   * @param payload the payload
   * @return the code, or 1005 ("no status received", RFC 6455) when the payload has none
   */
  public static int closeCodeOf(byte[] payload) {
    if (payload.length < CLOSE_CODE_BYTES) {
      return NO_STATUS_RECEIVED;
    }
    return ((payload[0] & BYTE) << BITS_PER_BYTE) | (payload[1] & BYTE);
  }
```

`payload[index] ^= maskKey[index % 4]` is shorthand for `payload[index] = payload[index] ^ maskKey[index % 4]`. A `byte` is signed in Java, so `payload[0] & 0xFF` turns it into 0 to 255 before it is shifted.

`char` is a number, so letters can be computed: `(char) (FIRST_SECTION + seatIndex / seatsPerSection)` adds a whole number to `'A'` and casts back.

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

`Math.addExact(sequence, 1)` throws instead of wrapping around on overflow:

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

### Strings and text

A `String` is an immutable piece of text. `+` joins strings. `.equals(...)` compares contents (`==` would compare whether two variables point at the same object). `String.format(Locale.ROOT, "%c-%d", ...)` fills a template; `%d` is a whole number, `%s` text, `%c` a character, `%n` a line break. `"\r\n"` is carriage return plus line feed; `\\` is one backslash. The repository always passes `Locale.ROOT` ("no local customs") so numbers print the same everywhere.

```java
  /**
   * The {@code 101} reply that completes the handshake.
   *
   * @param request the accepted request
   * @return the reply's bytes
   */
  public static byte[] accepted(HandshakeRequest request) {
    String reply =
        "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + acceptFor(request.key())
            + "\r\n\r\n";
    return reply.getBytes(StandardCharsets.US_ASCII);
  }
```

### Arrays

An **array** is a fixed-size row of items of one type. `new SeatChange[capacity]` makes one; `ring[i]` reads slot `i`, counted from 0; `ring.length` is its size.

```java
  private int slot(long sequence) {
    return (int) (sequence % ring.length);
  }
}
```

### Control flow

`if` / `else`, `for`, `while`, the enhanced `for` that visits each item of a collection, and the conditional expression `a ? b : c`.

```java
  /**
   * Runs one tick at once, on the caller's thread; used by tests and by {@link #start()}.
   *
   * @return true if the tick made a hold or a confirm
   */
  public boolean tick() {
    if (paused) {
      return false;
    }
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

```java
      StringBuilder codes = new StringBuilder(states.length);
      for (SeatState state : states) {
        codes.append(state.code());
      }
      return new SeatMapSnapshot(feed.position(), layout, codes.toString());
```

```java
  public static byte[] encode(int opcode, byte[] payload) {
    int headerLength =
        payload.length <= LONGEST_SHORT_LENGTH ? 2 : payload.length <= LARGEST_SIXTEEN_BIT ? 4 : 10;
    ByteBuffer frame = ByteBuffer.allocate(headerLength + payload.length);
```

The **switch** statement chooses a branch. The arrow form (Java 14) does not fall through to the next branch:

```java
      switch (event.group(2)) {
        case "seat-map" ->
            replies.json(
                exchange, HttpStatus.OK, parts.json().snapshot(parts.store().snapshot(), EVENT_ID));
        case "seat-changes" -> longPoll.serve(exchange, correlationId);
        default -> eventStream.serve(exchange, correlationId);
      }
      return;
```

### `null`, `Optional` and `Objects.requireNonNull`

`null` means "no object". Using a `null` as if it were an object throws `NullPointerException`. The code avoids `null` in two ways. `Objects.requireNonNull(x, "name")` stops a `null` early with a clear message (it is in every constructor). `Optional<T>` is a box that holds a value or nothing, so the type tells the caller that there may be no answer:

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

### Exceptions

An **exception** is an object that is thrown when something goes wrong; it jumps out of the running methods to the nearest `catch` that handles it. **Checked** exceptions (those that extend `Exception` but not `RuntimeException`, such as `IOException`) must be named in the method's `throws` clause or caught. **Unchecked** ones (`IllegalArgumentException`, `NullPointerException`, `IllegalStateException`) need not. `finally` always runs. A custom exception:

```java
package io.plainround.seatwave.live.websocket;

import java.io.IOException;

/** A peer broke the WebSocket protocol; the connection is closed with {@link #closeCode()}. */
public final class ProtocolViolation extends IOException {

  private static final long serialVersionUID = 1L;

  /** The close code to send. */
  private final transient CloseCode closeCode;

  /**
   * Creates the exception.
   *
   * @param closeCode the close code to send
   * @param message what the peer did
   */
  public ProtocolViolation(CloseCode closeCode, String message) {
    super(message);
    this.closeCode = closeCode;
  }

  /**
   * The close code to send.
   *
   * @return the code
   */
  public CloseCode closeCode() {
    return closeCode;
  }
}
```

`extends IOException` makes `ProtocolViolation` a kind of `IOException`. `super(message)` calls the parent's constructor. `serialVersionUID` is a version number Java wants on serializable classes. The field is marked `transient` ("leave it out when the object is saved as bytes"). `-Xlint:all -Werror` still compiled when I removed the word in a copy, so it states an intention.

A checked exception that cannot happen is turned into an unchecked one, with the original attached as the cause:

```java
  public static String acceptFor(String key) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
      return Base64.getEncoder().encodeToString(digest);
    } catch (NoSuchAlgorithmException missing) {
      // Every JDK must provide SHA-1 (the MessageDigest documentation lists it as required).
      throw new IllegalStateException("SHA-1 is not available", missing);
    }
  }
```

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

The routes catch `InterruptedException` and restore the thread's interrupt flag, as `java-concurrency-in-the-feed.md` explains:

```java
    } catch (InterruptedException dropped) {
      // The connection is being dropped (the lab's drop, or shutdown): stop and send nothing.
      Thread.currentThread().interrupt();
    }
  }
```

### try-with-resources

A **resource** is something that must be closed: a file, a socket, a lock-like ticket. `try (...) { ... }` closes each resource when the block ends, whether it ends normally or by an exception. The resource must implement `AutoCloseable`. Since Java 9 the resource may be an existing variable:

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

```java
  void run() {
    parts.metrics().recordHandlerThread(true);
    try (socket) {
      // A client that opens a socket and sends nothing must not hold a thread for ever.
      socket.setSoTimeout((int) handshakeTimeout.toMillis());
      InputStream in = new BufferedInputStream(socket.getInputStream());
      OutputStream out = socket.getOutputStream();
      Result<HandshakeRequest, HandshakeRefusal> handshake = Handshake.read(in, allowedOrigin);
      switch (handshake) {
        case Result.Error<HandshakeRequest, HandshakeRefusal>(HandshakeRefusal refusal) ->
            out.write(Handshake.refused(refusal, retryAfterSeconds));
        case Result.Success<HandshakeRequest, HandshakeRefusal>(HandshakeRequest request) ->
            open(request, in, out);
      }
    } catch (IOException ended) {
      LOG.debug("websocket ended: {}", ended.toString());
    } finally {
      parts.metrics().recordHandlerThread(false);
    }
  }
```

### Generics

A **generic** type has a type in angle brackets: `List<SeatChange>` is a list of `SeatChange`s, and the compiler rejects adding anything else. A **generic method** declares its own type variable: `<T> T read(byte[] json, Class<T> type)` returns whatever type the caller names. The empty `<>` (the diamond, Java 7) lets the compiler infer the type on the right-hand side.

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

```java
  private static <T> Map<DeliveryMethod, T> perMethod(java.util.function.Supplier<T> maker) {
    Map<DeliveryMethod, T> values = new EnumMap<>(DeliveryMethod.class);
    for (DeliveryMethod method : DeliveryMethod.values()) {
      values.put(method, maker.get());
    }
    return values;
  }
}
```

### Collections

A **collection** holds many items: `List` (ordered), `Set` (no duplicates), `Map` (key to value), `ArrayDeque` (a queue you add to at the back and remove from the front), `EnumMap` (a map keyed by an enum). `List.of`, `Set.of` and `Map.of` (Java 9) build unmodifiable collections and `List.copyOf`, `Set.copyOf`, `Map.copyOf` (Java 10) make unmodifiable copies, which refuse `null`. The records copy their lists on the way in, so a value can be shared between threads without a lock:

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

### Interfaces

An **interface** is a promise of methods with no code (a **contract**); a class `implements` it. `AutoCloseable` is the JDK's interface for "has a `close()`". The client package declares two of its own:

```java
package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.wire.MessageView;

/** Receives what a {@link FeedConnection} reads; implemented by {@link FeedClient}. */
public interface MessageSink {

  /** Called once per connection, when it is established and before its first message. */
  void connected();

  /**
   * Handles one message.
   *
   * @param message a seat change or a reload instruction
   * @return whether to keep following
   */
  Verdict accept(MessageView message);

  /** Called for a heartbeat (an event-stream comment or a WebSocket ping): the link is alive. */
  void heartbeat();
}
```

An interface with exactly one abstract method is a **functional interface**; a lambda (below) can stand in for it. `@FunctionalInterface` is an annotation that makes the compiler check it:

```java
  @FunctionalInterface
  private interface Check {
    boolean holds() throws IOException, InterruptedException;
  }
```

### Nested and inner classes

A class may be declared inside another. A **static nested** class is just a class placed inside another for tidiness:

```java
  private static final class Listener implements WebSocket.Listener {
```

An **inner** class (no `static`) belongs to an object of the outer class and can use its fields. `ConnectionLimit.Ticket` uses the limit's `slots`, `open` and `metrics`, and the outer class reads the ticket's private field. The compiler turns this into a hidden field `this$0` that points at the outer object (`javap` shows it):

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

### Enums

An **enum** is a type with a fixed set of values, written out in its declaration. Each value is an object and may carry data. Every enum has `values()`, which returns an array of all of them.

```java
public enum SeatState {
  /** Nobody holds or owns the seat. */
  AVAILABLE("available", 'a'),
  /** A fan holds the seat until the hold expires or is confirmed. */
  HELD("held", 'h'),
  /** The seat is booked. */
  SOLD("sold", 's');

  private final String wireName;
  private final char code;

  SeatState(String wireName, char code) {
    this.wireName = wireName;
    this.code = code;
  }
```

### Records

A **record** (Java 16) is a short way to write a class that only carries data. `record SeatChange(FeedPosition position, int seatIndex, SeatState state, Instant changedAt)` gives, with no more code: private final fields, a constructor, accessor methods named like the components (`position()`), and `equals`, `hashCode` and `toString`. A **compact constructor** has no parameter list and runs before the fields are set; it is the place for checks. `javap` shows what the compiler made: `public final class ... SeatChange extends java.lang.Record`.

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

A record is **immutable**: no field changes after construction. Nested records are implicitly `static`.

### Sealed interfaces

A **sealed** interface (Java 17) lists, or implies, the only types that may implement it. When the permitted types are nested in the same file, `permits` can be left out. `javap -v` on the compiled `Catchup` shows `PermittedSubclasses` with `Catchup$Changes` and `Catchup$ReloadRequired`. The point is exhaustiveness: a `switch` over a sealed type that handles every case needs no `default`, and adding a case later makes the compiler flag every switch that missed it.

```java
/**
 * What the feed can give a client that names the position it has: the changes after it, or an
 * instruction to reload the whole seat map. There is no third answer, so no code path can hand a
 * client a gap.
 */
public sealed interface Catchup {
```

```java
package io.plainround.seatwave.live.feed;

import java.util.List;

/** What a connection's writer should send next, as {@link Subscriber#next} decides it. */
public sealed interface Delivery {

  /**
   * Changes to send, in order.
   *
   * @param changes one or more changes
   */
  record Batch(List<SeatChange> changes) implements Delivery {

    /** Copies the list. */
    public Batch {
      changes = List.copyOf(changes);
    }
  }

  /** The client fell too far behind: tell it to reload the seat map, then end the connection. */
  record Reload() implements Delivery {}

  /** Nothing happened for a heartbeat interval: send a heartbeat so idle links stay open. */
  record Idle() implements Delivery {}

  /** Another part of the connection asked the writer to wake, for example to answer a ping. */
  record Woken() implements Delivery {}
}
```

### Pattern matching for `instanceof`

`x instanceof Type name` (Java 16) tests the type and, if it matches, declares `name` as `x` seen as that type, in one step. No cast is needed.

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

The names are in scope only where the test is known to be true: here, inside the `while` condition's remaining parts and its body.

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

### Pattern matching for `switch`, and record patterns

A `switch` (Java 21) can match on **types** and take records apart. A **record pattern** such as `Result.Error<LiveSettings, List<String>>(List<String> problems)` matches a record of that type and names its parts. A **switch expression** (Java 14) produces a value; `->` separates a label from its result and `yield` gives a value from a block.

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

`Result.Error` and `Result.Success` are the two cases of a sealed type in the library `result4j` (see `result4j.md`). Because the type is sealed, the compiler checks that both are handled.

### Unnamed variables

When a variable must be declared but its value is never used, the name `_` says so (Java 22). The ticket is only wanted for its `close()` at the end of the block:

```java
    FeedPosition position = asked.recoverError(detail -> feed.position());
    Thread handler = Thread.currentThread();
    Optional<ConnectionLimit.Ticket> admitted =
        limit.admit(DeliveryMethod.LONG_POLLING, handler::interrupt);
    if (admitted.isEmpty()) {
      replies.problem(exchange, Problem.FEED_FULL, "too many open feed connections", correlationId);
      return;
    }
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

### Lambdas and method references

A **lambda** is an unnamed function written `(parameters) -> result` or `(parameters) -> { statements }` (Java 8). It can be used wherever a functional interface is expected. A **method reference** `Type::method` or `object::method` is a short way to write a lambda that only calls that method; `AtomicInteger::new` refers to a constructor.

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

```java
  void addAll(List<SeatChange> changes) {
    changes.forEach(this::offer);
  }
```

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

`handler::interrupt` in `LongPollRoute` is the lambda `() -> handler.interrupt()`.

### Streams

A **stream** (Java 8) is a pipeline over a sequence of items: `.filter(...)` keeps some, `.map(...)` transforms each, `.allMatch(...)` asks whether all pass, `.findFirst()` takes the first (as an `Optional`), `.toList()` (Java 16) collects into an unmodifiable list.

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

  /**
   * Finds the state with a one-letter code.
   *
   * @param code the code
   * @return the state, or empty for any other character
   */
  public static Optional<SeatState> fromCode(char code) {
    return Arrays.stream(values()).filter(state -> state.code == code).findFirst();
  }
```

```java
public record SeatMapSnapshot(FeedPosition position, SeatLayout layout, String states) {

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

### Annotations

An **annotation** is a marker written with `@`. Some are for the compiler (`@Override` says "this method replaces one of the parent's; fail if it does not", and `@FunctionalInterface` above), some are read by libraries at run time. `@JsonInclude(NON_NULL)` is read by Jackson:

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

```java
  /** Copies the payload, so the frame cannot change later. */
  public Frame {
    payload = Objects.requireNonNull(payload, "payload").clone();
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
```

Here `@Override` is on the accessor `payload()`, replacing the one the record would have made, so it returns a copy.

### `volatile`, `transient` and threads

`volatile` makes a field's reads and writes visible to all threads at once. `transient` was shown above. Threads are made with the builders added in Java 21: `Thread.ofVirtual()` and `Thread.ofPlatform()`, and with `Executors.newVirtualThreadPerTaskExecutor()`.

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

```java
  private static final Logger LOG = LoggerFactory.getLogger(WebSocketServer.class);
  // A client gets this long to send its handshake once its TCP connection is open.
  private static final java.time.Duration HANDSHAKE_TIMEOUT = java.time.Duration.ofSeconds(5);

  private final ServerSocket listener;
  private final ExecutorService sessions = Executors.newVirtualThreadPerTaskExecutor();
  private final Parts parts;
  private final SessionSettings settings;

  private WebSocketServer(ServerSocket listener, Parts parts, SessionSettings settings) {
    this.listener = listener;
    this.parts = parts;
    this.settings = settings;
  }
```

### Javadoc

A comment between `/**` and `*/` is **Javadoc**: documentation that tools turn into pages. `{@code x}` marks code, `{@link Type#member}` makes a link, `@param`, `@return` and `@throws` document the parts of a method. The repository's rule is that every public class and method has one.

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

### The lowest Java version each feature needs

| Feature | First final in | Example in this repository |
|---|---|---|
| Underscores in numbers, diamond `<>`, try-with-resources, `switch` on strings | 7 | `1_024`, `new ArrayDeque<>(capacity)` |
| Generics, enums, annotations, `@Override` | 5 | `Map<DeliveryMethod, T>`, `SeatState` |
| Lambdas, method references, streams, `Optional` | 8 | `Subscriber.addAll`, `SeatState.fromCode` |
| `Math.addExact`, `LongAdder`, `ConcurrentHashMap.newKeySet`, `Base64`, `Duration` | 8 | `FeedPosition.next`, `FeedMetrics`, `Handshake` |
| A variable as a try-with-resources resource (`try (exchange)`), `List.of`, `Map.of`, `Set.of`, `InputStream.readAllBytes`, `Duration.toSeconds` | 9 | `FeedHttpServer.serve`, `FeedHttpServer.PAGES` |
| `List.copyOf`, `Set.copyOf`, `Map.copyOf`, `URLDecoder.decode(String, Charset)` | 10 | `Catchup.Changes`, `Query` |
| `String.isBlank`, `String.strip`, `Optional.isEmpty`, `InputStream.readNBytes(int)`, `ByteArrayOutputStream.writeBytes`, running a single source file with `java File.java` | 11 | `SettingsReader.text`, `FrameCodec.readExactly` |
| `String.transform` | 12 | `LiveSettings.from` |
| Arrow `switch` and switch expressions with `yield` | 14 | `SettingsReader.flag`, `FeedHttpServer.admin` |
| Records, pattern matching for `instanceof`, `Stream.toList()` | 16 | `SeatChange`, `SeatFeed.awaitChangesAfter`, `FeedJson.longPoll` |
| Sealed interfaces, `HexFormat` | 17 | `Catchup`, `Delivery`, `SeatFeed` constructor |
| `Thread.sleep(Duration)` | 19 | `ServeCli.serve` |
| Virtual threads, `Thread.ofVirtual()`, `Thread.ofPlatform()`, `Executors.newVirtualThreadPerTaskExecutor()` | 21 | `FeedHttpServer`, `WebSocketServer`, `OnSaleSimulator` |
| Pattern matching for `switch`, record patterns | 21 | `ServeCli.main`, `LongPollRoute.positionFrom` |
| Unnamed variables (`_`) | 22 | `LongPollRoute.serve`, `EventStreamRoute.serve`, `WebSocketSession.open` |

The version of each library method comes from my knowledge of the API history and was not looked up in the documentation during writing; the compile with `options.release = 25` proves only that all of them exist in Java 25.

## Try it yourself

All run in a copy of the repository on the verification machine (an ARM-based Mac, Eclipse Temurin 25.0.2). Timings differ on every run.

**1. Which Java is it?**

```bash
java -version
```

```text
openjdk version "25.0.2" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-25.0.2+10 (build 25.0.2+10-LTS)
OpenJDK 64-Bit Server VM Temurin-25.0.2+10 (build 25.0.2+10-LTS, mixed mode, sharing)
```

**2. Compile, then count.**

```bash
./gradlew -q compileJava
find src/main/java -name '*.java' | wc -l
find build/classes -name '*.class' | wc -l
```

```text
      82
     112
```

**3. See what the compiler made of a record.**

```bash
javap -cp build/classes/java/main io.plainround.seatwave.live.feed.SeatChange
```

```text
Compiled from "SeatChange.java"
public final class io.plainround.seatwave.live.feed.SeatChange extends java.lang.Record {
  public io.plainround.seatwave.live.feed.SeatChange(io.plainround.seatwave.live.feed.FeedPosition, int, io.plainround.seatwave.live.feed.SeatState, java.time.Instant);
  public final java.lang.String toString();
  public final int hashCode();
  public final boolean equals(java.lang.Object);
  public io.plainround.seatwave.live.feed.FeedPosition position();
  public int seatIndex();
  public io.plainround.seatwave.live.feed.SeatState state();
  public java.time.Instant changedAt();
}
```

**4. See that a sealed interface lists its subtypes, and the class file's version.**

```bash
javap -v -cp build/classes/java/main io.plainround.seatwave.live.feed.Catchup | grep -A3 '^PermittedSubclasses'
javap -v -cp build/classes/java/main io.plainround.seatwave.live.feed.SeatChange | grep 'major version'
```

```text
PermittedSubclasses:
  io/plainround/seatwave/live/feed/Catchup$Changes
  io/plainround/seatwave/live/feed/Catchup$ReloadRequired
InnerClasses:
  major version: 69
```

**5. See the hidden pointer of an inner class.**

```bash
javap -cp build/classes/java/main 'io.plainround.seatwave.live.feed.ConnectionLimit$Ticket'
```

```text
Compiled from "ConnectionLimit.java"
public final class io.plainround.seatwave.live.feed.ConnectionLimit$Ticket implements java.lang.AutoCloseable {
  final io.plainround.seatwave.live.feed.ConnectionLimit this$0;
  public void close();
}
```

**6. Run a one-file program.** Since Java 11 the `java` command can compile and run a single source file. The position demo of `java-feed-walkthrough.md` ("Try it yourself", command 2) does this with the repository's compiled classes on the class path.

## Mistakes people make

- **Comparing strings with `==`.** It asks whether two variables point at the same object. Use `.equals(...)`.
- **Forgetting that a `byte` is signed.** `payload[0] & 0xFF` gives 0 to 255; without it a byte above 127 becomes negative.
- **Using `null` for "no answer".** The type system cannot warn about it. This code uses `Optional` and `Result`.
- **Letting a mutable collection escape.** A caller could change it behind your back. The records copy with `List.copyOf` and `Map.copyOf`.
- **A `switch` with a `default` that hides a missing case.** With a sealed type and no `default`, the compiler finds the missing case. `Catchup` and `Delivery` are switched without a `default`.
- **Catching `InterruptedException` and ignoring it.** Restore the flag with `Thread.currentThread().interrupt()` or stop.
- **Using a feature the target Java does not have.** Every feature here needs at most Java 22; the build pins 25 (`options.release = 25`) so the compiler also checks the library calls.
- **Warnings that nobody owns.** `-Xlint:all -Werror` makes every compiler warning fail the build.

## Check yourself

1. **What does the compiler produce, and what runs it?** `javac` turns `.java` files into `.class` bytecode files; the JVM loads and runs them.
2. **What do you get from `record SeatChange(...)` without writing it?** Private final fields, a constructor, accessors named like the components, `equals`, `hashCode` and `toString`.
3. **Why does the code switch over `Catchup` with no `default`?** `Catchup` is sealed with two cases, so the compiler checks that both are handled; a third case added later makes the compiler flag every switch that missed it.
4. **What does `_` mean in `try (ConnectionLimit.Ticket _ = admitted.get())`?** An unnamed variable (Java 22): the ticket is only needed to be closed at the end of the block.
5. **Which Java release first allowed `record` and which `switch` with record patterns?** Records: Java 16. Pattern matching for `switch` and record patterns: Java 21.

## Further reading

- The Java Language Specification, Java SE 25: https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- Java SE 25 API: https://docs.oracle.com/en/java/javase/25/docs/api/index.html
- JEP 395, Records: https://openjdk.org/jeps/395
- JEP 409, Sealed Classes: https://openjdk.org/jeps/409
- JEP 394, Pattern Matching for `instanceof`: https://openjdk.org/jeps/394
- JEP 441, Pattern Matching for `switch`: https://openjdk.org/jeps/441
- JEP 440, Record Patterns: https://openjdk.org/jeps/440
- JEP 456, Unnamed Variables and Patterns: https://openjdk.org/jeps/456
- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- JEP 330, Launch Single-File Source-Code Programs: https://openjdk.org/jeps/330
