# Walkthrough of the `websocket` package

## What it is

The `websocket` package is a WebSocket server written from scratch against **RFC 6455**, the standard that defines the WebSocket protocol. It runs on plain Java sockets. It has 11 files and about 830 lines.

A **socket** is the program's end of a network connection: you read bytes from it and write bytes to it. A **WebSocket** is a connection that starts as an ordinary HTTP request, is then "upgraded", and from then on carries small messages called **frames** in both directions for as long as both sides want. The protocol facts (the handshake, the frame layout, masking, ping and pong, close codes) are taught from zero in `real-time-protocols.md`. This note walks the code.

## Why this repository uses it

The JDK has a WebSocket client (`java.net.http.WebSocket`) but no server. Its HTTP server cannot hand a connection over after it has sent `101 Switching Protocols`. A library would solve it, but the module's lesson is the protocol itself, and the bounds of each connection (a bounded queue, a write timeout, a heartbeat) must be the feed's own. `ARCHITECTURE.md` records which libraries were considered. So the package writes the handshake, the framing, and the close and heartbeat rules, and the README says plainly that production would use a maintained server.

## Where it appears

| File | What it does |
|---|---|
| `WebSocketServer.java` | Listens on its own port and starts one session per connection. |
| `WebSocketSession.java` | One connection from handshake to close, on two virtual threads. |
| `Handshake.java` | Reads and checks the opening HTTP request; writes `101` or a refusal. |
| `HandshakeRequest.java`, `HandshakeRefusal.java` | The two outcomes of reading a handshake. |
| `FrameCodec.java` | Encodes server frames and decodes masked client frames. |
| `Frame.java` | One decoded frame: opcode and payload. |
| `CloseCode.java` | The close codes the feed sends. |
| `ProtocolViolation.java` | An exception that carries the close code to send. |
| `ServiceUnavailable.java` | The 503 refusal when the feed is full. |
| `SessionSettings.java` | The four settings every session needs. |

## How it works underneath

**Two threads per connection, one writer.** The session thread is the only one that writes to the socket. It waits on the connection's `Subscriber` (from the `feed` package) for the next thing to send. A second thread, the reader, reads frames from the client. When the reader needs something sent (an answer to a ping, a close), it does not write; it stores the request in a shared variable and wakes the writer. One writer means two threads can never interleave their bytes in the middle of a frame.

**Heartbeats.** If nothing was sent for one heartbeat interval, the writer sends a **ping** frame. The client must answer with a **pong**. The reader's socket has a read timeout of three heartbeats. A client that says nothing for three heartbeats (a phone that lost its network, a link a middlebox dropped without telling anyone) is closed, which frees its slot and its memory.

**One slow reader cannot slow the others.** The feed never writes to a socket; it only offers changes to each connection's bounded queue. Each connection's own writer does the writing. A client that stops reading fills only its own queue (then gets a reload) or blocks only its own writer (then the watchdog interrupts it).

**The bytes of a frame.** Byte 0 holds the FIN bit (this is the last piece of a message), three reserved bits that must be 0, and a four-bit opcode (1 text, 8 close, 9 ping, 10 pong). Byte 1 holds the MASK bit and a 7-bit length. A length of 126 means a 16-bit length follows; 127 means a 64-bit one. Frames from a client carry a 4-byte masking key, and the payload is XORed with it, byte by byte, cycling through the key. Frames from the server are never masked. On the wire, a 5-byte text message "Hello" from the server is `81 05 48 65 6c 6c 6f`.

## Walkthrough of this repository's use

The code is copied from the source files by a script. The explanation follows each block.

### `CloseCode.java`

```java
package io.plainround.seatwave.live.websocket;

/** The close codes of RFC 6455 that the feed sends. */
public enum CloseCode {
  /** The purpose of the connection is fulfilled; here, after a reload instruction. */
  NORMAL(1000),
  /** The server is going away, or the peer stopped answering heartbeats. */
  GOING_AWAY(1001),
  /** The peer broke the protocol, for example with an unmasked frame. */
  PROTOCOL_ERROR(1002),
  /** The peer sent a kind of data the feed does not accept. */
  UNSUPPORTED_DATA(1003),
  /** The peer sent a frame larger than the feed accepts. */
  TOO_BIG(1009);
```

An enum of the five close codes the feed uses. A close frame carries a two-byte number that says why the connection ended. In RFC 6455: 1000 is a normal closure (here: after a reload instruction), 1001 means going away (the server is shutting down, or the peer stopped answering heartbeats), 1002 is a protocol error (for example an unmasked frame), 1003 means data the endpoint cannot accept, and 1009 means a message too big.

```java
  private final int code;

  CloseCode(int code) {
    this.code = code;
  }

  /**
   * The two-byte code sent in the close frame.
   *
   * @return the number
   */
  public int code() {
    return code;
  }
}
```

The usual field, constructor and getter.

### `Frame.java`

```java
package io.plainround.seatwave.live.websocket;

import java.util.Objects;

/**
 * One WebSocket frame as RFC 6455 defines it, after unmasking: its opcode and its payload. The feed
 * never sends or accepts fragments, so every frame here is a whole message.
 *
 * @param opcode what the frame carries: {@link #TEXT}, {@link #CLOSE}, {@link #PING} or {@link
 *     #PONG}, or another value the reader refuses
 * @param payload the unmasked payload bytes
 */
public record Frame(int opcode, byte[] payload) {
```

A frame after unmasking: its opcode and payload. The feed does not accept fragments (a message split over several frames), so every frame is a whole message. `byte[]` is an array of bytes.

```java
  /** A text message: UTF-8 bytes (RFC 6455, section 5.6 in the RFC's own numbering). */
  public static final int TEXT = 0x1;

  /** A binary message. */
  public static final int BINARY = 0x2;

  /** The start of the closing handshake. */
  public static final int CLOSE = 0x8;

  /** A heartbeat question; the other side must answer with a pong. */
  public static final int PING = 0x9;

  /** A heartbeat answer. */
  public static final int PONG = 0xA;
```

The opcodes as constants. `0x1` is hexadecimal, base 16: `0x8` is 8, `0x9` is 9 and `0xA` is 10. The comment on the first mentions RFC 6455 section 5.6.

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

Records compare their fields with `equals`, but an array is compared by identity, so the code is careful: the constructor clones the payload on the way in, and the `payload()` accessor is overridden to clone on the way out. Nobody can change the bytes of a frame afterwards.

### `FrameCodec.java`

```java
package io.plainround.seatwave.live.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Reads and writes WebSocket frames (RFC 6455, the base framing protocol).
 *
 * <p>Server frames are never masked; client frames must be. A frame is at most {@code maxPayload}
 * bytes, control frames at most 125, and fragments are refused, because the feed's clients send
 * only control frames. Stateless.
 */
public final class FrameCodec {
```

`FrameCodec` is stateless and cannot be instantiated; everything is `static`. `ByteBuffer` is a JDK class for building binary data with the right byte order (big-endian, the network order, is the default).

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

The magic numbers, each named. `FIN = 0x80` is the top bit of byte 0. `OPCODE_BITS = 0x0F` is the low four bits. `RESERVED_BITS = 0x70` are the three bits in between. `MASK = 0x80` is the top bit of byte 1. `LENGTH_BITS = 0x7F` is its low seven bits. The comment explains the three length forms. `LARGEST_SIXTEEN_BIT = 0xFFFF` is 65,535. `CONTROL_OPCODES = 0x8`: opcodes 8 and above are control frames. `NO_STATUS_RECEIVED = 1005` is the code RFC 6455 reports for a close frame without a code.

```java
  private FrameCodec() {}
```

The private constructor.

```java
  /**
   * Encodes one unmasked, unfragmented server frame.
   *
   * @param opcode the frame's opcode
   * @param payload the payload
   * @return the frame's bytes, header included
   */
  public static byte[] encode(int opcode, byte[] payload) {
    int headerLength =
        payload.length <= LONGEST_SHORT_LENGTH ? 2 : payload.length <= LARGEST_SIXTEEN_BIT ? 4 : 10;
    ByteBuffer frame = ByteBuffer.allocate(headerLength + payload.length);
    frame.put((byte) (FIN | opcode));
    if (payload.length <= LONGEST_SHORT_LENGTH) {
      frame.put((byte) payload.length);
    } else if (payload.length <= LARGEST_SIXTEEN_BIT) {
      frame.put((byte) SIXTEEN_BIT_LENGTH);
      frame.putShort((short) payload.length);
    } else {
      frame.put((byte) SIXTY_FOUR_BIT_LENGTH);
      frame.putLong(payload.length);
    }
    frame.put(payload);
    return frame.array();
  }
```

`encode` builds one server frame. First the header length: 2 bytes for a payload up to 125, 4 bytes (2 plus a 16-bit length) up to 65,535, otherwise 10 bytes (2 plus a 64-bit length). The nested `? :` is a chain of "if, else if, else". `ByteBuffer.allocate` makes a buffer of the exact size. `frame.put((byte) (FIN | opcode))` writes byte 0: the bit-or sets FIN to 1 and adds the opcode (text, `0x81`). A `(byte)` cast keeps the low 8 bits. A short payload writes its length as one byte. A medium payload writes 126 and then a `short` (2 bytes); a long one writes 127 and then a `long` (8 bytes). Then the payload follows. No mask bit is set: the server does not mask. For a 300-byte text payload the header is `81 7e 01 2c` (`0x012c` is 300), which I checked by running the code.

```java
  /**
   * Encodes a close frame with a code.
   *
   * @param code the close code
   * @return the frame's bytes
   */
  public static byte[] encodeClose(CloseCode code) {
    byte[] payload = {(byte) (code.code() >> BITS_PER_BYTE), (byte) (code.code() & BYTE)};
    return encode(Frame.CLOSE, payload);
  }
```

`encodeClose` builds a close frame whose payload is the two bytes of the code. `code >> 8` is the high byte; `code & 0xFF` is the low byte. For 1000 (`0x03E8`) the frame is `88 02 03 e8`.

```java
  /**
   * Reads one client frame.
   *
   * @param in the connection's input
   * @param maxPayload the largest payload accepted
   * @return the frame, unmasked
   * @throws ProtocolViolation if the frame is unmasked, fragmented, reserved, or too large
   * @throws EOFException if the peer closed the connection
   * @throws IOException if reading fails
   */
  public static Frame read(InputStream in, int maxPayload) throws IOException {
```

`read` decodes one frame from the client. It documents what it throws: `ProtocolViolation` (a subclass of `IOException`) for bad frames, `EOFException` if the peer closed.

```java
    int first = readByte(in);
    int second = readByte(in);
    int opcode = first & OPCODE_BITS;
    if ((first & RESERVED_BITS) != 0) {
      throw new ProtocolViolation(
          CloseCode.PROTOCOL_ERROR, "reserved bits set without an extension");
    }
```

It reads the first two bytes. `first & OPCODE_BITS` extracts the opcode. A nonzero reserved bit would mean an extension was negotiated; none was, so the frame is refused with 1002.

```java
    if ((first & FIN) == 0 || opcode == 0) {
      throw new ProtocolViolation(
          CloseCode.UNSUPPORTED_DATA, "fragmented messages are not accepted");
    }
```

A frame without FIN, or with opcode 0 (a continuation), is a fragment. The feed refuses fragments with 1003.

```java
    if ((second & MASK) == 0) {
      // RFC 6455: a server must close the connection on an unmasked client frame.
      throw new ProtocolViolation(CloseCode.PROTOCOL_ERROR, "client frames must be masked");
    }
```

A client frame without the mask bit is refused with 1002. RFC 6455 says a server must close the connection on an unmasked client frame.

```java
    long length = second & LENGTH_BITS;
    if (length == SIXTEEN_BIT_LENGTH) {
      length = (readByte(in) << BITS_PER_BYTE) | readByte(in);
    } else if (length == SIXTY_FOUR_BIT_LENGTH) {
      length = ByteBuffer.wrap(readExactly(in, Long.BYTES)).getLong();
    }
```

The length: the low seven bits. If they say 126, the next two bytes are the length; if 127, the next eight. `readByte(in) << 8 | readByte(in)` reads the high byte then the low byte (evaluation goes left to right). `ByteBuffer.wrap(readExactly(in, Long.BYTES)).getLong()` reads 8 bytes as a number; `Long.BYTES` is 8.

```java
    if (opcode >= CONTROL_OPCODES && length > LONGEST_SHORT_LENGTH) {
      throw new ProtocolViolation(
          CloseCode.PROTOCOL_ERROR, "control frames carry at most 125 bytes");
    }
    if (length < 0 || length > maxPayload) {
      throw new ProtocolViolation(CloseCode.TOO_BIG, "frame larger than " + maxPayload + " bytes");
    }
```

Two limits. A control frame (opcode 8 or more) may carry at most 125 bytes. Any frame longer than `maxPayload` (the session passes 125) is too big: close code 1009. A negative length can come from a 64-bit length with the top bit set, so `length < 0` is refused too, before any memory is reserved. Checking before reading is what stops a client from asking the server to allocate gigabytes.

```java
    byte[] maskKey = readExactly(in, MASK_KEY_BYTES);
    byte[] payload = readExactly(in, (int) length);
    for (int index = 0; index < payload.length; index++) {
      payload[index] ^= maskKey[index % MASK_KEY_BYTES];
    }
    return new Frame(opcode, payload);
  }
```

The 4-byte masking key, then the payload. The loop `payload[index] ^= maskKey[index % 4]` is the unmasking: XOR each byte with the key byte at that position, cycling through the four. XOR twice with the same key gives the original byte back. In RFC 6455 section 5.7, the masked bytes `37 fa 21 3d 7f 9f 4d 51 58` with key `37 fa 21 3d` decode to the text "Hello"; I ran the code on that exact frame and it decoded to `Hello`.

```java
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

`closeCodeOf` reads a close frame's payload. Fewer than two bytes means "no status", reported as 1005. Otherwise the two bytes make the number. `payload[0] & 0xFF` turns a signed byte (-128 to 127) into 0 to 255 before shifting.

```java
  private static int readByte(InputStream in) throws IOException {
    int value = in.read();
    if (value < 0) {
      throw new EOFException("the peer closed the connection");
    }
    return value;
  }
```

`readByte` reads one byte from the stream; `in.read()` returns -1 at the end, and the method turns that into an `EOFException`.

```java
  private static byte[] readExactly(InputStream in, int length) throws IOException {
    byte[] bytes = in.readNBytes(length);
    if (bytes.length != length) {
      throw new EOFException("the peer closed the connection inside a frame");
    }
    return bytes;
  }
```

`readExactly` reads exactly `length` bytes with `readNBytes` (Java 11), which keeps reading until it has them or the stream ends. A short result means the peer closed in the middle of a frame.

### `ProtocolViolation.java`

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

An **exception** is an object that is thrown to say something went wrong. This one extends `IOException` and carries the close code to send. `serialVersionUID` is a version number Java wants on serializable classes (exceptions are). The field is marked `transient`, which means "leave this out when the object is saved as bytes". I removed the word in a copy of the repository and the build still compiled with `-Xlint:all -Werror`, so the keyword states an intention here and the compiler does not require it.

### `HandshakeRequest.java`, `HandshakeRefusal.java`, `ServiceUnavailable.java`, `SessionSettings.java`

```java
package io.plainround.seatwave.live.websocket;

import java.util.Objects;

/**
 * A valid opening handshake (RFC 6455): the client asked to upgrade the feed's path to a WebSocket.
 *
 * @param rawQuery the query string, possibly empty
 * @param key the {@code Sec-WebSocket-Key} the accept value is computed from
 */
public record HandshakeRequest(String rawQuery, String key) {

  /** Checks the fields. */
  public HandshakeRequest {
    Objects.requireNonNull(rawQuery, "rawQuery");
    Objects.requireNonNull(key, "key");
  }
}
```

A valid handshake: the query string (possibly empty) and the key the accept value is computed from.

```java
package io.plainround.seatwave.live.websocket;

import java.util.Objects;

/**
 * Why an opening handshake was refused, as the HTTP reply the client gets instead of 101.
 *
 * @param status the HTTP status code
 * @param reason one sentence for the reply body and the log
 */
public record HandshakeRefusal(int status, String reason) {

  /** Checks the fields. */
  public HandshakeRefusal {
    Objects.requireNonNull(reason, "reason");
  }
}
```

A refusal: an HTTP status and one sentence, which becomes the reply body and a log message.

```java
package io.plainround.seatwave.live.websocket;

/** The refusal a WebSocket client gets when the feed is at its connection limit. */
final class ServiceUnavailable {

  static final int STATUS = 503;

  private ServiceUnavailable() {}

  static HandshakeRefusal refusal() {
    return new HandshakeRefusal(STATUS, "the feed has as many open connections as it allows");
  }
}
```

The refusal for a full feed: status 503 and a sentence. It is a class with a private constructor, because it only holds one constant and one factory method.

```java
package io.plainround.seatwave.live.websocket;

import java.time.Duration;
import java.util.Objects;

/**
 * What every WebSocket session needs to know.
 *
 * @param allowedOrigin the one browser origin that may connect
 * @param heartbeat how long the writer stays silent before it pings
 * @param handshakeTimeout how long a new socket may take to send its handshake
 * @param retryAfter the delay a refused client is asked to wait
 */
record SessionSettings(
    String allowedOrigin, Duration heartbeat, Duration handshakeTimeout, Duration retryAfter) {

  SessionSettings {
    Objects.requireNonNull(allowedOrigin, "allowedOrigin");
    Objects.requireNonNull(heartbeat, "heartbeat");
    Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
    Objects.requireNonNull(retryAfter, "retryAfter");
  }
}
```

The four settings of a session: the allowed browser origin, the heartbeat, the time a new socket gets to send its handshake, and the retry delay for a refusal. A package-private record (no `public`).

### `Handshake.java`

```java
package io.plainround.seatwave.live.websocket;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
```

Imports. `MessageDigest` computes hashes (SHA-1 here). `Base64` encodes bytes as text.

```java
/**
 * The WebSocket opening handshake (RFC 6455): reads the client's HTTP upgrade request, checks it,
 * and writes either {@code 101 Switching Protocols} with the accept value, or an HTTP refusal.
 *
 * <p>The checks, in order: the request is a {@code GET} of the feed's socket path; it asks for
 * {@code Upgrade: websocket} and {@code Connection: Upgrade}; its version is 13 (else {@code 426}
 * with the supported version); its key is 16 bytes in base64; and, when a browser sends an {@code
 * Origin}, it is the one allowed origin (else {@code 403}), because a browser lets any page open a
 * WebSocket to any server and only the server can refuse. Stateless.
 */
public final class Handshake {
```

The Javadoc lists the checks in order: a GET of the socket path; `Upgrade: websocket` and `Connection: Upgrade`; version 13 (otherwise 426 with the version supported); a key that is 16 bytes in base64; and, if a browser sends an `Origin` header, that it is the allowed one (otherwise 403). The reason for the last: a browser lets any web page open a WebSocket to any server, so only the server can refuse.

```java
  /** The path the WebSocket feed answers on. */
  public static final String PATH = "/v1/events/1/seat-changes/socket";

  // RFC 6455 fixes this GUID: the accept value is SHA-1 of the key followed by it, in base64.
  private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private static final int MAX_HEAD_BYTES = 8_192;
  private static final int KEY_BYTES = 16;
  private static final String VERSION = "13";
  private static final int BAD_REQUEST = 400;
  private static final int FORBIDDEN = 403;
  private static final int NOT_FOUND = 404;
  private static final int UPGRADE_REQUIRED = 426;
  private static final int HEADER_END = 4;
```

`PATH` is the one path served. `GUID` is a fixed string that RFC 6455 says must be added to the key before hashing. The numbers are the status codes used. `MAX_HEAD_BYTES = 8_192` caps the size of the request head. `KEY_BYTES = 16` is the decoded key size.

```java
  private Handshake() {}
```

The private constructor: the class is only static methods.

```java
  /**
   * Reads and checks the opening handshake.
   *
   * @param in the connection's input, positioned at the start of the request
   * @param allowedOrigin the one browser origin that may connect
   * @return the request, or the refusal to send
   * @throws IOException if reading fails or the peer closes early
   */
  public static Result<HandshakeRequest, HandshakeRefusal> read(
      InputStream in, String allowedOrigin) throws IOException {
```

`read` returns a `Result`: the request, or the refusal to send. It throws `IOException` if reading fails or the peer closes early.

```java
    Objects.requireNonNull(allowedOrigin, "allowedOrigin");
    String head = readHead(in);
    if (head.isEmpty()) {
      return refuse(BAD_REQUEST, "the request head is larger than " + MAX_HEAD_BYTES + " bytes");
    }
```

`readHead` (below) reads the request up to the blank line. An empty string means the head was bigger than the cap: 400.

```java
    String[] lines = head.split("\r\n");
    String[] requestLine = lines[0].split(" ");
    if (requestLine.length != 3 || !"GET".equals(requestLine[0])) {
      return refuse(BAD_REQUEST, "a WebSocket handshake is a GET request");
    }
```

`head.split("\r\n")` cuts the head into lines (HTTP uses carriage return plus line feed). `lines[0]` is the request line: three parts separated by spaces, such as `GET /v1/events/1/seat-changes/socket?after=4d4f4a94.230 HTTP/1.1`. Anything else, or a method other than GET, is a 400.

```java
    String target = requestLine[1];
    int question = target.indexOf('?');
    String path = question < 0 ? target : target.substring(0, question);
    if (!PATH.equals(path)) {
      return refuse(NOT_FOUND, "no WebSocket at " + path);
    }
```

The target has a path and an optional query after `?`. The path must equal `PATH`; otherwise 404.

```java
    Map<String, String> headers = headers(lines);
    if (!"websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""))
        || !headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade")) {
      return refuse(BAD_REQUEST, "Upgrade: websocket and Connection: Upgrade are required");
    }
```

`headers(lines)` turns the header lines into a map with lower-case names. The request must say `Upgrade: websocket` (the value compared without regard to case) and its `Connection` value must contain `upgrade` (a browser sends `keep-alive, Upgrade`). Otherwise 400.

```java
    if (!VERSION.equals(headers.get("sec-websocket-version"))) {
      return refuse(UPGRADE_REQUIRED, "only WebSocket version 13 is supported");
    }
```

The version must be exactly `13`. Otherwise 426 "Upgrade Required", and the reply tells the client which version is supported.

```java
    String key = headers.getOrDefault("sec-websocket-key", "");
    if (!isValidKey(key)) {
      return refuse(BAD_REQUEST, "Sec-WebSocket-Key must be 16 bytes in base64");
    }
```

The key is checked by `isValidKey`: it must decode from base64 to 16 bytes.

```java
    String origin = headers.get("origin");
    if (origin != null && !origin.equals(allowedOrigin)) {
      return refuse(FORBIDDEN, "origin " + origin + " may not open this feed");
    }
```

The origin check. A request with no `Origin` header (a command-line client, not a browser) passes. A request with an `Origin` that is not the allowed one gets 403. The check is an exact string match.

```java
    return Result.success(
        new HandshakeRequest(question < 0 ? "" : target.substring(question + 1), key));
  }
```

On success the result holds the query string without the `?` (or empty) and the key.

```java
  /**
   * The {@code Sec-WebSocket-Accept} value for a key.
   *
   * @param key the client's {@code Sec-WebSocket-Key}
   * @return base64 of SHA-1 of the key and the RFC's GUID
   */
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

`acceptFor` computes the `Sec-WebSocket-Accept` value. It appends the fixed GUID to the client's key, takes the SHA-1 hash of those characters, and writes the 20 bytes of the hash as base64. The client does the same sum and compares, which proves the server understood the WebSocket handshake and is not a plain HTTP server echoing something. For the RFC's sample key `dGhlIHNhbXBsZSBub25jZQ==` the answer is `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`; the code and the `openssl` command both give that. `NoSuchAlgorithmException` is a checked exception that cannot happen for SHA-1, which every JDK must provide, so it becomes an `IllegalStateException`. SHA-1 is used here only because the standard says so; it is not used for security.

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

`accepted` builds the `101 Switching Protocols` reply: a status line, `Upgrade: websocket`, `Connection: Upgrade`, the accept header and a blank line. Each line ends with `\r\n`, and the header ends with `\r\n\r\n`.

```java
  /**
   * The HTTP reply that refuses the handshake.
   *
   * @param refusal the status and reason
   * @param retryAfterSeconds the Retry-After value for a 503, ignored otherwise
   * @return the reply's bytes
   */
  public static byte[] refused(HandshakeRefusal refusal, long retryAfterSeconds) {
    byte[] body = (refusal.reason() + "\n").getBytes(StandardCharsets.UTF_8);
    StringBuilder reply =
        new StringBuilder("HTTP/1.1 ").append(refusal.status()).append(" Refused\r\n");
    if (refusal.status() == UPGRADE_REQUIRED) {
      reply.append("Sec-WebSocket-Version: ").append(VERSION).append("\r\n");
    }
    if (refusal.status() == ServiceUnavailable.STATUS) {
      reply.append("Retry-After: ").append(retryAfterSeconds).append("\r\n");
    }
    reply.append("Content-Type: text/plain; charset=utf-8\r\n");
    reply.append("Content-Length: ").append(body.length).append("\r\n");
    reply.append("Connection: close\r\n\r\n");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(reply.toString().getBytes(StandardCharsets.US_ASCII));
    bytes.writeBytes(body);
    return bytes.toByteArray();
  }
```

`refused` builds the HTTP reply for a refusal: a body (the reason and a line break), the status line (the reason phrase is always the word `Refused`), then extra headers where the status needs them: `Sec-WebSocket-Version: 13` for a 426, `Retry-After` for a 503. Then the content type, the length in bytes, `Connection: close`, and the blank line. The head is written as ASCII and the body as UTF-8.

```java
  private static Result<HandshakeRequest, HandshakeRefusal> refuse(int status, String reason) {
    return Result.error(new HandshakeRefusal(status, reason));
  }
```

A small helper that builds an error `Result`.

```java
  // Reads up to the blank line that ends the head; an empty string means the head was too large.
  private static String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int matched = 0;
    byte[] end = {'\r', '\n', '\r', '\n'};
    while (matched < HEADER_END) {
      int value = in.read();
      if (value < 0) {
        throw new IOException("the peer closed the connection during the handshake");
      }
      head.write(value);
      matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
      if (head.size() > MAX_HEAD_BYTES) {
        return "";
      }
    }
    return head.toString(StandardCharsets.US_ASCII);
  }
```

`readHead` reads one byte at a time until it has seen the four bytes `\r\n\r\n`. `matched` counts how many of the four have been seen in a row. `matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0)` reads: if this byte is the next expected one, advance; otherwise start again at 1 if it is a carriage return, else at 0. Reading byte by byte (from a `BufferedInputStream`) matters: the code must not read past the blank line, because the bytes after it belong to the frames that follow. If the peer closes, it throws. If the head passes 8,192 bytes it returns the empty string.

```java
  private static Map<String, String> headers(String[] lines) {
    Map<String, String> headers = new HashMap<>();
    for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
      int colon = lines[lineIndex].indexOf(':');
      if (colon > 0) {
        headers.put(
            lines[lineIndex].substring(0, colon).strip().toLowerCase(Locale.ROOT),
            lines[lineIndex].substring(colon + 1).strip());
      }
    }
    return headers;
  }
```

`headers` splits each line at the first colon, lower-cases and strips the name, strips the value, and stores them. A line without a colon is skipped. If a header repeats, the last one wins.

```java
  private static boolean isValidKey(String key) {
    try {
      return Base64.getDecoder().decode(key).length == KEY_BYTES;
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
  }
```

`isValidKey` decodes the base64 and checks for 16 bytes. A bad base64 string makes the decoder throw `IllegalArgumentException`, which becomes `false`.

### `WebSocketServer.java`

```java
package io.plainround.seatwave.live.websocket;

import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.http.Parts;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Imports. `ServerSocket` is a socket that listens for connections; `Socket` is one accepted connection.

```java
/**
 * The feed's WebSocket server: the server side of RFC 6455 written on the JDK's own sockets, bound
 * to 127.0.0.1, with one virtual thread per connection and a second one for its reader.
 *
 * <p>Why not the JDK's HTTP server: it cannot hand a connection over after {@code 101 Switching
 * Protocols}, so the WebSocket feed listens on its own port. The JDK has a WebSocket client ({@code
 * java.net.http.WebSocket}) but no server; this class and its few helpers are that server.
 *
 * <p>Thread-safe: the accept loop runs on one thread; each session is confined to its own threads.
 */
public final class WebSocketServer implements AutoCloseable {
```

The Javadoc says it all: the server side of RFC 6455 on plain sockets, bound to 127.0.0.1, one virtual thread per connection and a second for its reader, on its own port because the JDK's HTTP server cannot upgrade.

```java
  private static final Logger LOG = LoggerFactory.getLogger(WebSocketServer.class);
  // A client gets this long to send its handshake once its TCP connection is open.
  private static final java.time.Duration HANDSHAKE_TIMEOUT = java.time.Duration.ofSeconds(5);
```

The logger and `HANDSHAKE_TIMEOUT` of five seconds: a client gets that long to send its handshake once its TCP connection is open.

```java
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

The fields: the listening socket, an executor that starts a virtual thread per task, the shared parts and the session settings.

```java
  /**
   * Starts listening on 127.0.0.1.
   *
   * @param settings the port, backlog, allowed origin and timings
   * @param parts the feed and its limit
   * @return the running server
   * @throws IOException if the port cannot be bound
   */
  public static WebSocketServer start(LiveSettings settings, Parts parts) throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(parts, "parts");
    ServerSocket listener = new ServerSocket();
    listener.bind(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.network().websocketPort()),
        settings.network().backlog());
    WebSocketServer server =
        new WebSocketServer(
            listener,
            parts,
            new SessionSettings(
                settings.network().allowedOrigin(),
                settings.timing().heartbeat(),
                HANDSHAKE_TIMEOUT,
                settings.timing().retry()));
    Thread.ofVirtual().name("ws-accept").start(server::acceptLoop);
    return server;
  }
```

`start` makes a `ServerSocket` and binds it to the loopback address, the port from the settings (0 lets the system choose) and the backlog. It builds the `SessionSettings` from the node's settings. `Thread.ofVirtual().name("ws-accept").start(server::acceptLoop)` starts the accept loop on a virtual thread.

```java
  /**
   * The port the server listens on.
   *
   * @return the port
   */
  public int port() {
    return listener.getLocalPort();
  }
```

`port()` returns the port that is really bound.

```java
  @Override
  public void close() throws IOException {
    listener.close();
    sessions.shutdownNow();
  }
```

`close` closes the listener, which makes the blocked `accept()` throw, and shuts the executor down.

```java
  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket socket = listener.accept();
        socket.setTcpNoDelay(true);
        sessions.submit(() -> new WebSocketSession(socket, parts, settings).run());
      } catch (IOException acceptFailed) {
        if (!listener.isClosed()) {
          LOG.warn("accept failed: {}", acceptFailed.toString());
        }
      }
    }
  }
```

The accept loop. `listener.accept()` waits for the next connection. `setTcpNoDelay(true)` turns off Nagle's algorithm, the operating system's habit of waiting briefly to combine small writes; a feed wants each small frame to go at once. The session is submitted to the executor as a task. If accept fails and the listener is not closed, a warning is logged and the loop goes on; when the listener is closed the loop ends.

### `WebSocketSession.java`

```java
package io.plainround.seatwave.live.websocket;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.Delivery;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatChange;
import io.plainround.seatwave.live.feed.Subscriber;
import io.plainround.seatwave.live.http.Parts;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Imports. `AtomicReference` holds one value that can be read and replaced by several threads safely. `SocketTimeoutException` is what a read throws when its timeout passes.

```java
/**
 * One WebSocket connection, from the handshake to the close, on two virtual threads: this one is
 * the only writer (changes, pings, pongs, close); a reader thread reads the client's control frames
 * and hands anything that needs an answer to the writer by waking it.
 *
 * <p><b>Heartbeats.</b> When nothing is sent for a heartbeat interval the writer sends a ping. The
 * reader's socket timeout is three heartbeats: a client that answers nothing for that long (a phone
 * that lost its network, a link a middlebox dropped without telling anyone) is closed, which frees
 * its slot and its memory.
 *
 * <p>Thread-safe: only the writer writes to the socket; the reader shares two atomic references.
 */
final class WebSocketSession {
```

The Javadoc: one connection on two virtual threads. The session's thread is the only writer. The reader thread reads the client's control frames and hands anything that needs an answer to the writer by waking it. Heartbeats and the three-heartbeat read timeout are described there too.

```java
  private static final Logger LOG = LoggerFactory.getLogger(WebSocketSession.class);
  // Clients send only control frames, which RFC 6455 limits to 125 bytes.
  private static final int MAX_CLIENT_PAYLOAD = 125;
  private static final int MISSED_HEARTBEATS_BEFORE_CLOSE = 3;
  private static final byte[] PING_PAYLOAD = "hb".getBytes(StandardCharsets.US_ASCII);
  private static final String AFTER_PREFIX = "after=";
  private static final int BAD_REQUEST = 400;
```

Constants. `MAX_CLIENT_PAYLOAD = 125`: the feed accepts only control frames from clients, which RFC 6455 limits to 125 bytes. `MISSED_HEARTBEATS_BEFORE_CLOSE = 3`. `PING_PAYLOAD` is the two bytes `hb`. `AFTER_PREFIX` is the only query the socket accepts.

```java
  private final Socket socket;
  private final Parts parts;
  private final String allowedOrigin;
  private final Duration heartbeat;
  private final Duration handshakeTimeout;
  private final long retryAfterSeconds;
  private final AtomicReference<byte[]> pongDue = new AtomicReference<>();
  private final AtomicReference<CloseCode> closeDue = new AtomicReference<>();

  WebSocketSession(Socket socket, Parts parts, SessionSettings settings) {
    this.socket = socket;
    this.parts = parts;
    this.allowedOrigin = settings.allowedOrigin();
    this.heartbeat = settings.heartbeat();
    this.handshakeTimeout = settings.handshakeTimeout();
    this.retryAfterSeconds = Math.max(1, settings.retryAfter().toSeconds());
  }
```

Fields. The two `AtomicReference`s are the mailbox between the reader and the writer: `pongDue` holds the payload of a ping that must be answered, and `closeDue` holds a close code that must be sent. The constructor copies the settings and makes sure the retry time is at least one second.

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

`run` is the whole life of a connection. It counts the handler thread. `try (socket)` closes the socket at the end whatever happens. The first timeout is the handshake timeout, so a client that connects and says nothing frees the thread. It wraps the input in a `BufferedInputStream` (reading one byte at a time from a raw socket would be slow) and reads the handshake. The `switch` takes the result apart: a refusal writes the HTTP reply and the session ends; a request goes to `open`. An `IOException` anywhere is a normal end, logged at debug level.

```java
  private void open(HandshakeRequest request, InputStream in, OutputStream out) throws IOException {
    Optional<FeedPosition> position = positionOf(request.rawQuery());
    if (position.isEmpty()) {
      out.write(
          Handshake.refused(new HandshakeRefusal(BAD_REQUEST, "after=<position> is required"), 0));
      return;
    }
```

`open` first reads the position from the query. `positionOf` returns empty if the query is not exactly `after=<position>`. Without it, the reply is 400 "after=<position> is required".

```java
    Optional<ConnectionLimit.Ticket> admitted =
        parts.limit().admit(DeliveryMethod.WEBSOCKET, this::closeQuietly);
    if (admitted.isEmpty()) {
      out.write(Handshake.refused(ServiceUnavailable.refusal(), retryAfterSeconds));
      return;
    }
```

Next the connection limit: `parts.limit().admit(DeliveryMethod.WEBSOCKET, this::closeQuietly)`. The drop action closes the socket. If the feed is full the reply is 503 with `Retry-After`.

```java
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      out.write(Handshake.accepted(request));
      out.flush();
      socket.setSoTimeout((int) heartbeat.multipliedBy(MISSED_HEARTBEATS_BEFORE_CLOSE).toMillis());
      Subscriber subscriber = parts.feed().subscribe(position.get());
      Thread reader = Thread.ofVirtual().name("ws-reader").start(() -> readFrames(in, subscriber));
      try {
        writeFrames(out, subscriber);
      } catch (InterruptedException dropped) {
        Thread.currentThread().interrupt();
      } finally {
        parts.feed().unsubscribe(subscriber);
        closeQuietly();
        reader.interrupt();
      }
    }
  }
```

With a ticket, the code writes `101` and flushes, and changes the socket timeout to three heartbeats. Then it subscribes at the client's position, starts the reader on a new virtual thread, and runs the writer on this one. The `finally` runs on every exit: unsubscribe from the feed, close the socket (which makes the reader's blocked read fail), and interrupt the reader. The ticket closes at the end of the `try`, which frees the slot.

```java
  private void writeFrames(OutputStream out, Subscriber subscriber)
      throws IOException, InterruptedException {
    while (true) {
      Delivery delivery = subscriber.next(heartbeat);
      CloseCode close = closeDue.get();
      if (close != null) {
        write(subscriber, out, FrameCodec.encodeClose(close), 0);
        return;
      }
```

`writeFrames` is the writer loop. `subscriber.next(heartbeat)` waits for a delivery. Before using it, the writer checks the mailbox: if a close code is waiting, it sends a close frame and ends.

```java
      byte[] pong = pongDue.getAndSet(null);
      if (pong != null) {
        write(subscriber, out, FrameCodec.encode(Frame.PONG, pong), 0);
      }
```

`pongDue.getAndSet(null)` takes the waiting ping payload and empties the box in one step. A pong goes out with the same payload (RFC 6455 requires that).

```java
      switch (delivery) {
        case Delivery.Batch batch -> write(subscriber, out, frames(batch), batch.changes().size());
        case Delivery.Idle idle ->
            write(subscriber, out, FrameCodec.encode(Frame.PING, PING_PAYLOAD), 0);
        case Delivery.Woken woken -> {
          // The pong or close above was the reason.
        }
```

The `switch` on the delivery. A batch is written as text frames. `Idle` means a heartbeat interval passed with nothing sent: a ping frame goes out. `Woken` needs nothing more: the pong or close above was the reason.

```java
        case Delivery.Reload reload -> {
          byte[] message = parts.json().bytes(parts.json().reload(parts.feed().position()));
          ByteArrayOutputStream frames = new ByteArrayOutputStream();
          frames.writeBytes(FrameCodec.encode(Frame.TEXT, message));
          frames.writeBytes(FrameCodec.encodeClose(CloseCode.NORMAL));
          write(subscriber, out, frames.toByteArray(), 0);
          parts.metrics().recordReloadSent(DeliveryMethod.WEBSOCKET);
          return;
        }
      }
    }
  }
```

A reload: the writer builds the `reload` message, wraps it in a text frame, adds a normal close frame (1000), and writes both in one go. Then it counts the reload and returns. The client's page sees the reload message, loads the seat map and reconnects.

```java
  private void readFrames(InputStream in, Subscriber subscriber) {
    try {
      while (true) {
        Frame frame = FrameCodec.read(in, MAX_CLIENT_PAYLOAD);
```

`readFrames` runs on the reader thread. Each pass reads one frame; the maximum payload is 125 bytes.

```java
        switch (frame.opcode()) {
          case Frame.PING -> {
            pongDue.set(frame.payload());
            subscriber.wake();
          }
```

A ping from the client: store its payload for the writer and wake the writer.

```java
          case Frame.PONG -> {
            // Any frame resets the socket timeout; a pong needs nothing more.
          }
```

A pong needs nothing: any frame arriving resets the socket's read timeout, which is the point of the heartbeat.

```java
          case Frame.CLOSE -> {
            closeDue.set(CloseCode.NORMAL);
            subscriber.wake();
            return;
          }
```

A close from the client: store a normal close for the writer, wake it, and stop reading.

```java
          default ->
              throw new ProtocolViolation(
                  CloseCode.UNSUPPORTED_DATA, "the feed accepts no messages");
        }
      }
```

Anything else (a text or binary message, or an unknown opcode) is not accepted: close code 1003.

```java
    } catch (ProtocolViolation violation) {
      closeDue.set(violation.closeCode());
      subscriber.wake();
    } catch (SocketTimeoutException silent) {
      // Three heartbeats without a word: the peer is gone, or a middlebox dropped the link.
      closeDue.set(CloseCode.GOING_AWAY);
      subscriber.wake();
```

The three ways the reader ends. A protocol violation from the codec: send the close code it carries. A `SocketTimeoutException`: three heartbeats without a word, so the client is gone or a middlebox dropped the link: close code 1001. Both store a code and wake the writer, which sends the close frame.

```java
    } catch (IOException ended) {
      // The peer closed, or the socket was closed to drop the connection: end the writer too.
      closeQuietly();
      closeDue.set(CloseCode.GOING_AWAY);
      subscriber.wake();
    }
  }
```

Any other `IOException` means the peer closed, or the socket was closed to drop the connection. The reader closes the socket, stores 1001 and wakes the writer so it ends too.

```java
  private byte[] frames(Delivery.Batch batch) {
    ByteArrayOutputStream frames = new ByteArrayOutputStream();
    for (SeatChange change : batch.changes()) {
      byte[] message = parts.json().bytes(parts.json().message(change, parts.layout()));
      frames.writeBytes(FrameCodec.encode(Frame.TEXT, message));
    }
    return frames.toByteArray();
  }
```

`frames` turns a batch into bytes: each change becomes a JSON message, wrapped in a text frame, and all are joined in one array, so one batch is one write.

```java
  private void write(Subscriber subscriber, OutputStream out, byte[] bytes, int changes)
      throws IOException {
    subscriber.beginWrite(Thread.currentThread());
    try {
      out.write(bytes);
      out.flush();
    } finally {
      subscriber.endWrite();
    }
    parts.metrics().recordDelivered(DeliveryMethod.WEBSOCKET, changes, bytes.length);
  }
```

`write` is the only place bytes leave. It brackets the write with `beginWrite` and `endWrite` so the watchdog can find a writer stuck on a client that stopped reading, writes, flushes, and counts the bytes in the metrics.

```java
  private static Optional<FeedPosition> positionOf(String rawQuery) {
    if (!rawQuery.startsWith(AFTER_PREFIX) || rawQuery.contains("&")) {
      return Optional.empty();
    }
    return FeedPosition.parse(rawQuery.substring(AFTER_PREFIX.length())).discardError();
  }
```

`positionOf` accepts only a query that starts with `after=` and has no `&`, and parses the rest. `discardError()` turns the `Result` into an `Optional`: the position, or empty.

```java
  private void closeQuietly() {
    try {
      socket.close();
    } catch (IOException alreadyBroken) {
      // Closing a broken socket can fail; there is nothing left to free.
      LOG.debug("closing a websocket failed: {}", alreadyBroken.toString());
    }
  }
```

`closeQuietly` closes the socket and logs the (rare) failure at debug level. It is also what `ConnectionLimit.dropAll` calls to drop this connection.

## Try it yourself

I ran these in a copy of the repository, with the node on ports 18190 and 18191 (other work was using 8090 and 8091). Start it in one terminal with `./gradlew -q serve --args='config/serve.properties'` (on the default ports) and use the port you chose in the commands.

**1. See the handshake and the first frame bytes.** The sample key is the one from RFC 6455:

```bash
P=$(curl -s http://127.0.0.1:18190/v1/events/1/seat-map | sed 's/.*"position":"\([^"]*\)".*/\1/')
curl -s -i --max-time 2 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "http://127.0.0.1:18191/v1/events/1/seat-changes/socket?after=$P" | head -c 400 | xxd | head -12
```

Output (the first part is the 101 reply; after the blank line, `81 71` is the frame header: FIN and text, then a length of 0x71 = 113 with no mask bit):

```text
00000000: 4854 5450 2f31 2e31 2031 3031 2053 7769  HTTP/1.1 101 Swi
00000010: 7463 6869 6e67 2050 726f 746f 636f 6c73  tching Protocols
00000020: 0d0a 5570 6772 6164 653a 2077 6562 736f  ..Upgrade: webso
00000030: 636b 6574 0d0a 436f 6e6e 6563 7469 6f6e  cket..Connection
00000040: 3a20 5570 6772 6164 650d 0a53 6563 2d57  : Upgrade..Sec-W
00000050: 6562 536f 636b 6574 2d41 6363 6570 743a  ebSocket-Accept:
00000060: 2073 3370 504c 4d42 6954 7861 5139 6b59   s3pPLMBiTxaQ9kY
00000070: 477a 7a68 5a52 624b 2b78 4f6f 3d0d 0a0d  GzzhZRbK+xOo=...
00000080: 0a81 717b 2274 7970 6522 3a22 7365 6174  ..q{"type":"seat
00000090: 222c 2270 6f73 6974 696f 6e22 3a22 3464  ","position":"4d
000000a0: 3466 3461 3934 2e32 3331 222c 2273 6561  4f4a94.231","sea
000000b0: 7422 3a22 452d 3734 3122 2c22 7374 6174  t":"E-741","stat
```

**2. Compute the accept value yourself.**

```bash
printf 'dGhlIHNhbXBsZSBub25jZQ==258EAFA5-E914-47DA-95CA-C5AB0DC85B11' | openssl sha1 -binary | base64
```

```text
s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

**3. Run the codec on the RFC's own example.** Compile with `./gradlew -q compileJava`, save the following as `WireDemo.java` in an empty folder, and run it with the classes and the `result4j` jar on the class path:

```java
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.websocket.CloseCode;
import io.plainround.seatwave.live.websocket.Frame;
import io.plainround.seatwave.live.websocket.FrameCodec;
import io.plainround.seatwave.live.websocket.Handshake;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class WireDemo {
  public static void main(String[] args) throws Exception {
    System.out.println("accept = " + Handshake.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
    byte[] hello = FrameCodec.encode(Frame.TEXT, "Hello".getBytes(StandardCharsets.UTF_8));
    System.out.println("server text frame  = " + HexFormat.of().formatHex(hello));
    System.out.println("server close 1000  = " + HexFormat.of().formatHex(FrameCodec.encodeClose(CloseCode.NORMAL)));
    byte[] masked = HexFormat.of().parseHex("818537fa213d7f9f4d5158");
    Frame frame = FrameCodec.read(new ByteArrayInputStream(masked), 125);
    System.out.println("client frame read  = opcode " + frame.opcode() + ", payload " + new String(frame.payload(), StandardCharsets.UTF_8));
    byte[] unmasked = HexFormat.of().parseHex("810548656c6c6f");
    try {
      FrameCodec.read(new ByteArrayInputStream(unmasked), 125);
    } catch (Exception refused) {
      System.out.println("unmasked client frame -> " + refused.getClass().getSimpleName() + ": " + refused.getMessage());
    }
    System.out.println("long text header   = " + HexFormat.of().formatHex(FrameCodec.encode(Frame.TEXT, new byte[300]), 0, 4));
  }
}
```

```bash
R=$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1)
java -cp build/classes/java/main:$R WireDemo.java
```

```text
accept = s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
server text frame  = 810548656c6c6f
server close 1000  = 880203e8
client frame read  = opcode 1, payload Hello
unmasked client frame -> ProtocolViolation: client frames must be masked
long text header   = 817e012c
```

**4. The refusals.** An unsupported version, a wrong origin and a missing position:

```bash
curl -s -i --max-time 2 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 8' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "http://127.0.0.1:18191/v1/events/1/seat-changes/socket?after=4d4f4a94.5"
curl -s -i --max-time 2 -H 'Origin: https://evil.example' -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "http://127.0.0.1:18191/v1/events/1/seat-changes/socket?after=4d4f4a94.5" | head -3
curl -s -i --max-time 2 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "http://127.0.0.1:18191/v1/events/1/seat-changes/socket"
```

```text
HTTP/1.1 426 Refused
Sec-WebSocket-Version: 13
Content-Type: text/plain; charset=utf-8
Content-Length: 39
Connection: close

only WebSocket version 13 is supported

HTTP/1.1 403 Refused
Content-Type: text/plain; charset=utf-8
Content-Length: 51
HTTP/1.1 400 Refused
Content-Type: text/plain; charset=utf-8
Content-Length: 29
Connection: close

after=<position> is required
```

**5. A real client, with a proper close.** The JDK's own WebSocket client takes three messages, then sends a normal close. Save this as `WsTry.java` in an empty folder:

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

public class WsTry {
  public static void main(String[] a) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    int[] seen = {0};
    WebSocket.Listener listener = new WebSocket.Listener() {
      public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        if (seen[0]++ < 3) System.out.println("text: " + data);
        if (seen[0] == 3) { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); }
        ws.request(1); return null; }
      public CompletionStage<?> onPong(WebSocket ws, ByteBuffer m) { System.out.println("pong received"); ws.request(1); return null; }
      public CompletionStage<?> onClose(WebSocket ws, int code, String why) { System.out.println("closed " + code); done.countDown(); return null; }
      public void onError(WebSocket ws, Throwable t) { System.out.println("error " + t); done.countDown(); }
    };
    WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
        .buildAsync(URI.create("ws://127.0.0.1:18191/v1/events/1/seat-changes/socket?after=" + a[0]), listener).join();
    ws.sendPing(ByteBuffer.wrap("hi".getBytes()));
    done.await(10, TimeUnit.SECONDS);
  }
}
```

Run it with the position from the first command:

```bash
java WsTry.java $P
```

```text
text: {"type":"seat","position":"4d4f4a94.941","seat":"E-324","state":"sold","changedAt":"2026-11-11T03:01:02.984791Z"}
text: {"type":"seat","position":"4d4f4a94.942","seat":"A-653","state":"sold","changedAt":"2026-11-11T03:01:03.042471Z"}
text: {"type":"seat","position":"4d4f4a94.943","seat":"D-422","state":"held","changedAt":"2026-11-11T03:01:03.093714Z"}
closed 1000
```

No `pong received` line appeared in this run, because the program closes as soon as the third message arrives. The next command waits for it. After the run, `/metrics` showed `seatwave_feed_open_connections{method="websocket"} 0`, which shows the session ended and freed its slot.

**6. Ping and pong.** Save this as `PongTry.java`. It sends a ping with the payload `hi` and waits up to five seconds for the pong:

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

public class PongTry {
  public static void main(String[] a) throws Exception {
    CountDownLatch pong = new CountDownLatch(1);
    WebSocket.Listener listener = new WebSocket.Listener() {
      public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) { ws.request(1); return null; }
      public CompletionStage<?> onPong(WebSocket ws, ByteBuffer m) { byte[] b = new byte[m.remaining()]; m.get(b); System.out.println("pong payload: " + new String(b)); pong.countDown(); ws.request(1); return null; }
    };
    WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
        .buildAsync(URI.create("ws://127.0.0.1:18191/v1/events/1/seat-changes/socket?after=" + a[0]), listener).join();
    ws.sendPing(ByteBuffer.wrap("hi".getBytes()));
    System.out.println("pong arrived: " + pong.await(5, TimeUnit.SECONDS));
    ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    System.exit(0);
  }
}
```

```bash
java PongTry.java $P
```

```text
pong payload: hi
pong arrived: true
```

The server's reader stored the payload, woke the writer, and the writer sent a pong with the same two bytes, as RFC 6455 requires. Stop the node with Ctrl+C.

## Mistakes people make

- **Trusting a client's length field.** A client can claim a frame of gigabytes. `FrameCodec.read` checks the length before it reads or allocates.
- **Accepting unmasked client frames.** The standard forbids them; masking exists so that a client cannot craft bytes that a proxy mistakes for HTTP. The codec refuses them with 1002.
- **Letting two threads write to one socket.** Their bytes can interleave inside a frame. Here only the session thread writes.
- **Forgetting the origin check.** A browser sends the page's `Origin`, and only the server can refuse. The handshake compares it with the one allowed origin.
- **Reading too far in the handshake.** The bytes after the blank line are frames. `readHead` stops exactly at the blank line.
- **Having no heartbeat.** A dead peer then holds a slot for ever. Ping, pong and the three-heartbeat read timeout free it.

## Check yourself

1. **How is the `Sec-WebSocket-Accept` value computed?** Append the fixed GUID `258EAFA5-E914-47DA-95CA-C5AB0DC85B11` to the client's key, take the SHA-1 hash, and write the hash as base64.
2. **Why are client frames masked and server frames not?** RFC 6455 requires clients to mask so that a script cannot make a proxy mistake frame bytes for an HTTP message; servers do not need to.
3. **What does a length byte of 126 mean?** The next two bytes hold the real length (a 16-bit number). 127 means the next eight bytes.
4. **Which thread writes to the socket, and how does the reader get a pong sent?** The session's own thread is the only writer. The reader stores the payload in `pongDue` and wakes the subscriber, and the writer sends the pong.
5. **When does the server close a connection for silence?** When the reader's read timeout, three heartbeats, passes without any frame from the client.

## Further reading

- RFC 6455, The WebSocket Protocol: https://www.rfc-editor.org/rfc/rfc6455
- WHATWG HTML Living Standard, the `WebSocket` interface: https://html.spec.whatwg.org/multipage/web-sockets.html
- Java SE 25 API, `java.net.ServerSocket`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/ServerSocket.html
- Java SE 25 API, `java.security.MessageDigest`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/MessageDigest.html
- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
