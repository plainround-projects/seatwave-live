# Real-time protocols: polling, long polling, server-sent events and WebSockets

## What it is

A **protocol** is an agreement about what bytes two programs send each other, and in what order. This note is about one problem: how does a server tell a client "something just changed" when the client is a browser or an app that can only start conversations?

Ordinary web traffic is **request and reply**. The client sends a request (an HTTP message, with a method such as `GET`, a path such as `/v1/events/1/seat-map`, and header lines), and the server sends one reply (a status code such as 200, headers and a body). Nothing more happens until the client asks again. **HTTP** is the protocol of the web; it travels over **TCP**, which gives two programs a reliable, ordered stream of bytes between them. A TCP connection stays open until one side closes it, and one connection can carry many requests one after another.

For a live seat map the server knows the news first. There are four answers, from the oldest to the newest:

1. **Polling.** The client asks again and again on a timer. Simple, wasteful.
2. **Long polling.** The client asks, and the server holds the request open until it has news or a time limit passes. Then the client asks again at once.
3. **Server-sent events** (SSE). The client makes one request and the server never finishes the reply: it keeps writing little messages into it. One direction only, server to client. The browser object is `EventSource`.
4. **WebSockets.** The client starts with an HTTP request that asks to switch protocols. After the server agrees, the same TCP connection carries small messages in both directions, called frames. The browser object is `WebSocket`.

This repository implements the last three, and it contains a calculator for the first (the polling-rates lab, covered in another note).

## Why this repository uses it

Module 8 of the course teaches that the delivery method is a choice with a price, not a fashion. The repository delivers the same stream of numbered seat changes by all three methods, from one feed, so that the three can be measured against each other at 1,000 clients. The measuring is done by other files; this note explains what each method puts on the wire and which standard defines each fact.

## Where it appears

| Method | Server code | Standard |
|---|---|---|
| Long polling | `http/LongPollRoute.java`, `feed/SeatFeed.java` (`awaitChangesAfter`), `wire/FeedJson.java` (`longPoll`) | No standard defines it. It is ordinary HTTP (RFC 9110). RFC 6202 describes its known problems. |
| Server-sent events | `http/EventStreamRoute.java`, `feed/Subscriber.java` | WHATWG HTML Living Standard, the "Server-sent events" chapter. Chunked transfer: RFC 9112. |
| WebSockets | `websocket/Handshake.java`, `websocket/FrameCodec.java`, `websocket/WebSocketSession.java`, `websocket/CloseCode.java` | RFC 6455 for the protocol; the WHATWG HTML Living Standard for the browser `WebSocket` object. |
| Resuming | `feed/FeedPosition.java`, `feed/ReplayBuffer.java`, `feed/SeatFeed.java` (`subscribe`) | The repository's own rule; `Last-Event-ID` is from the WHATWG standard. |
| The browser side | `src/main/resources/web/live-seat-map.js` | WHATWG HTML Living Standard. |

## How it works underneath

**What lives where.** Each client has one TCP connection to the server, and a TCP connection is a file descriptor (a number the operating system gives to an open socket) in the server process. HTTP messages are lines of text: a request line, header lines, a blank line, a body. WebSocket frames are binary: a few header bytes and a payload. On the server, each connection is served by one thread that blocks until there is something to send; this repository uses a virtual thread for each (see `java-concurrency-in-the-feed.md`). The feed itself is one object in memory that numbers every change and hands it to every waiting connection.

**One stream, three wrappers.** All three methods carry the same JSON message for a change: `{"type":"seat","position":"4d4f4a94.231","seat":"E-741","state":"held","changedAt":"..."}`. What differs is the wrapper around it and who starts each exchange.

## Walkthrough of this repository's use

### Polling (not built here; the baseline)

The client sends `GET /seat-changes?after=P` every few seconds. Most answers are "nothing new". The cost is the request rate: with 200,000 fans polling every 5 seconds the server must answer 40,000 requests per second, and on a quiet evening 92 percent of them find nothing. Those two numbers come from the repository's `README.md` (the polling-rates lab); I did not run that lab. The delay is also up to a whole polling interval. Long polling and push fix both.

### Long polling

The client sends `GET /v1/events/1/seat-changes?after=<position>`. The server asks the feed for changes after that position. If there are none, the request waits inside the feed until a change is published or the hold time passes (`feed.longPollHoldMillis`, 25,000 ms in `config/serve.properties`, chosen to stay under the 30-second idle timeout that many proxies use). Then it replies with JSON and the client asks again, from the `next` position the reply names.

```java
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

The wait is `SeatFeed.awaitChangesAfter`: it loops while the answer is an empty list and time remains, waiting on a condition that `publish` signals.

```java
  public Catchup awaitChangesAfter(FeedPosition known, Duration wait) throws InterruptedException {
    Objects.requireNonNull(known, "known");
    long remainingNanos = wait.toNanos();
    lock.lockInterruptibly();
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

A reply with news, from a real run (the long-poll request was sent at the newest position on a node making one change per second, and it came back after 0.53 seconds, which is the wait for the next change):

```text
{"changes":[{"type":"seat","position":"e0cf50b3.8","seat":"O-858","state":"held","changedAt":"2026-11-11T03:14:39.242216Z"}],"next":"e0cf50b3.8","reload":false}
```

Costs: every cycle is a full HTTP request with its headers (the README measures 120.5 bytes per change from client to server and 296.4 back, one client, one change at a time), the server holds one open connection per client, and between a reply and the next request there is a short blind spot that the feed covers by the `after` position, so no change is lost. The server learns that a client went away only when it tries to write the reply.

### Server-sent events

**The request.** An ordinary `GET`. The browser adds `Accept: text/event-stream`. **The reply.** Status 200 and `Content-Type: text/event-stream`. The reply has no length, so HTTP/1.1 uses **chunked transfer coding** (RFC 9112): the body is sent in pieces, and the reply never ends until the server closes the connection. A real run:

```text
HTTP/1.1 200 OK
Date: Wed, 11 Nov 2026 03:14:56 GMT
Transfer-encoding: chunked
Content-type: text/event-stream
Cache-control: no-store

retry: 1000

```

**The format.** The WHATWG standard defines the **event stream** as UTF-8 text made of lines. A blank line ends an event. Each non-blank line is `field: value`. The fields are:

- `data:` the payload. Several `data:` lines in one event are joined with a line break.
- `event:` the name of the event. Without it the event is called `message`. The page listens by name: `addEventListener("seat", ...)`.
- `id:` the event's id. The browser remembers the last one it saw.
- `retry:` how many milliseconds the browser should wait before it reconnects.
- A line that starts with `:` is a **comment**. The browser ignores it. It is used as a heartbeat.

The code that builds one event and the first and heartbeat bytes:

```java
  private static byte[] event(String type, String id, byte[] data) {
    StringBuilder event = new StringBuilder();
    if (id != null) {
      event.append("id: ").append(id).append('\n');
    }
    event.append("event: ").append(type).append('\n');
    event.append("data: ").append(new String(data, StandardCharsets.UTF_8)).append("\n\n");
    return event.toString().getBytes(StandardCharsets.UTF_8);
  }
```

```java
  private static final Logger LOG = LoggerFactory.getLogger(EventStreamRoute.class);
  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
  // Streaming responses have no length; the JDK server then uses chunked transfer coding.
  private static final long STREAMING = 0;
```

```java
  private void stream(HttpExchange exchange, Subscriber subscriber)
      throws IOException, InterruptedException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(HttpStatus.OK, STREAMING);
    OutputStream out = exchange.getResponseBody();
    write(
        subscriber,
        out,
        ("retry: " + retry.toMillis() + "\n\n").getBytes(StandardCharsets.UTF_8),
        0);
```

The standard advises authors to send a comment line about every 15 seconds so that old proxies do not drop an idle connection; `config/serve.properties` takes the same figure (`feed.heartbeatMillis=15000`). On a node I set to one change per second and a heartbeat of 400 ms, 2.5 seconds of stream looked like this (the wait for a heartbeat restarts after each write):

```text
retry: 1000

: heartbeat

: heartbeat

id: e0cf50b3.9
event: seat
data: {"type":"seat","position":"e0cf50b3.9","seat":"O-931","state":"sold","changedAt":"2026-11-11T03:14:40.244730Z"}

: heartbeat

: heartbeat

id: e0cf50b3.10
event: seat
data: {"type":"seat","position":"e0cf50b3.10","seat":"T-159","state":"sold","changedAt":"2026-11-11T03:14:41.244256Z"}

: heartbeat
```

**Reconnection, from the standard.** If the connection drops, the browser waits the `retry` time (default set by the browser if the server never sent one) and sends the same request again, with one extra header: `Last-Event-ID: <the last id it saw>`. A server that answers HTTP 204 tells the browser to stop reconnecting. This is why every event the feed sends carries its position as its `id`: the id is the resume point. When the browser reconnects, it sends the **old** `after=` in the URL (the URL has not changed) and the **new** position in the header, so the route reads the header first:

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
}
```

A `reload` event carries no `id`, because the client must load the seat map instead of resuming. The route then closes the stream and the page starts over.

### WebSockets

**What it adds.** After an upgrade the connection is no longer HTTP. Both sides may send at any time, in small messages called frames, and the connection stays open until a close.

**The opening handshake** (RFC 6455, Section 4). The client sends a normal-looking `GET` with these headers: `Connection: Upgrade`, `Upgrade: websocket`, `Sec-WebSocket-Version: 13`, `Sec-WebSocket-Key: <16 random bytes in base64>` and, from a browser, `Origin: <the page's origin>`. The server checks them and answers `101 Switching Protocols` with `Sec-WebSocket-Accept`. After the blank line that ends the 101 reply, the bytes are frames. The repository's own checks, in order, are in `Handshake.read`; its constants name the path, the GUID and the limits:

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

**The accept value.** The server proves it understood the handshake by computing: append the fixed string `258EAFA5-E914-47DA-95CA-C5AB0DC85B11` to the key, take the SHA-1 hash of that text (20 bytes), and write the hash as base64. With the RFC's sample key `dGhlIHNhbXBsZSBub25jZQ==` the answer is `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=` (I got that from the repository's code and from the `openssl` command, below).

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

**The origin check.** A browser lets a script on any website open a WebSocket to any server, and it sends the page's origin in `Origin`. The browser will not stop it; only the server can refuse. `Handshake.read` compares the header to the one allowed origin and answers 403 for any other. A client that sends no `Origin` (not a browser) is let through: the check protects browser users from other sites, and is not a login.

**Frames** (RFC 6455, Section 5.2). Every frame starts with two bytes:

- **Byte 0:** bit 7 is `FIN` (this is the last frame of a message); bits 6 to 4 are reserved and must be 0; bits 3 to 0 are the opcode.
- **Byte 1:** bit 7 is `MASK`; bits 6 to 0 are the payload length, 0 to 125, or 126 (a 16-bit length follows), or 127 (a 64-bit length follows).

After the length come, only if `MASK` is 1, four bytes of masking key, and then the payload. The opcodes this repository uses: `0x1` text, `0x8` close, `0x9` ping, `0xA` pong. The server writes frames with `FrameCodec.encode`:

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

A real first message from the node, the bytes after the 101 reply: `81 71 7b 22 74 79 70 65 ...`. `81` is FIN and text. `71` is 0x71, which is 113: the length of the JSON message, and the mask bit is 0 because server frames are never masked. `7b 22 74 79 70 65` is `{"type`. So the frame is 2 bytes of header and 113 of JSON, 115 bytes; the same change as an SSE event was 150 bytes.

**Masking** (Section 5.3). Every frame a client sends must be masked, and the server must close the connection if one is not. The client picks a random 4-byte key for each frame and XORs each payload byte with the key byte at position `index mod 4`. The purpose is to protect old proxies that might mistake the bytes of a frame for an HTTP message, so a malicious page cannot craft them. XOR twice with the same key gives back the original, so the server unmasks with the same loop:

```java
    byte[] maskKey = readExactly(in, MASK_KEY_BYTES);
    byte[] payload = readExactly(in, (int) length);
    for (int index = 0; index < payload.length; index++) {
      payload[index] ^= maskKey[index % MASK_KEY_BYTES];
    }
    return new Frame(opcode, payload);
  }
```

The RFC's own example is the masked text "Hello": `81 85 37 fa 21 3d 7f 9f 4d 51 58`. Decoding it with the repository's code gives `Hello` (checked, below).

**Ping and pong** (Section 5.5.2 and 5.5.3). A ping is a control frame with an optional payload of at most 125 bytes. The other side must answer with a pong carrying the same payload. A pong that nobody asked for is allowed and is ignored. This is how a connection is shown to be alive. A browser's `WebSocket` object answers pings by itself, and a page cannot send one; so the server is the side that pings.

**Close** (Section 5.5.1 and 7.4.1). A close frame carries a two-byte status code and may carry a reason. The side that receives one answers with a close and then the TCP connection ends. The codes this repository sends:

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

1000 is a normal end (the feed sends it after a reload message), 1001 means going away (the server stops, or the peer stopped answering), 1002 a protocol error (an unmasked frame, an unknown opcode, a fragmented control frame), 1003 data the feed cannot accept (it accepts no messages from clients) and 1009 a message too big. A close frame without a code is reported as 1005 (RFC 6455 reserves 1005 and 1006 for local reporting; they are never sent). The close for 1000 is the four bytes `88 02 03 e8`.

**Heartbeats in this repository.** The writer pings when nothing was sent for one heartbeat interval. The reader's socket timeout is three intervals. Any frame from the client, a pong included, resets the timeout. I tested it with a raw socket that completes the handshake and then never answers anything, on a node whose heartbeat I set to 400 ms:

```text
 0.00s: HTTP/1.1 101 Switching Protocols
 0.19s: frame text b'{"type":"seat","position":"e0cf50b3.20",'
 0.60s: frame ping b'hb'
 1.00s: frame ping b'hb'
 1.19s: frame text b'{"type":"seat","position":"e0cf50b3.21",'
 1.22s: frame close (1001,)
 1.22s: server closed the TCP connection
```

The pings came 400 ms apart, and the close with code 1001 came 1.2 seconds (three heartbeats) after the handshake, which freed the connection's slot. With the default of 15,000 ms the same silent client would be cut off after 45 seconds.

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
      byte[] pong = pongDue.getAndSet(null);
      if (pong != null) {
        write(subscriber, out, FrameCodec.encode(Frame.PONG, pong), 0);
      }
      switch (delivery) {
        case Delivery.Batch batch -> write(subscriber, out, frames(batch), batch.changes().size());
        case Delivery.Idle idle ->
            write(subscriber, out, FrameCodec.encode(Frame.PING, PING_PAYLOAD), 0);
        case Delivery.Woken woken -> {
          // The pong or close above was the reason.
        }
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

### Resuming after a reconnect

Both push methods have the same resume contract, written in `ARCHITECTURE.md`: a client that names the position of the last change it applied receives every later change exactly once, or is told to reload the whole seat map. Never a silent gap.

- **Where the client says it.** Long polling and WebSockets: the query parameter `after=<position>`. Server-sent events: the `Last-Event-ID` header when present, else `after`.
- **What a position is.** `<8 hex digits>.<sequence>`: the stream identifier, which changes whenever the feed process starts, and the number of the last change. Example: `4d4f4a94.231`.
- **The four cases**, decided by `ReplayBuffer.after`:

| The client's position | The answer |
|---|---|
| The newest one | An empty list: nothing missed. |
| Older, still inside the ring | The missed changes, in order, up to one batch. |
| Older than the oldest the ring still holds | Reload. |
| Another stream identifier, or a sequence this stream never reached | Reload. |

```java
  Catchup after(FeedPosition known, int maxChanges) {
    if (!known.streamId().equals(streamId) || known.sequence() > lastSequence) {
      // Another stream, or a sequence this stream never reached: the client's map came from an
      // earlier process, and replaying from it would apply the wrong history.
      return new Catchup.ReloadRequired(position());
    }
    long oldestKept = Math.max(1, lastSequence - ring.length + 1);
    if (known.sequence() + 1 < oldestKept) {
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

- **No gap, no duplicate.** For a push connection the catch-up is read and the connection is registered for live changes in one step, under one lock, in `SeatFeed.subscribe`:

```java
  public Subscriber subscribe(FeedPosition known) {
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

- **Reload.** For long polling the reply is `{"changes":[],"next":"<current position>","reload":true}`. For SSE it is the event `reload`. For WebSocket it is a text frame with `{"type":"reload",...}` followed by a normal close. The client then fetches `GET /v1/events/1/seat-map`, which returns the whole map and the position it matches, and starts again from that position. A real run with a position from another stream:

```text
{"changes":[],"next":"4d4f4a94.470","reload":true}
```

- **How the clients back off.** A browser's `EventSource` waits the `retry` time. The page's WebSocket code, and the Java clients, wait a random time under a ceiling that doubles after each failure and is capped. That is described in `web-page-and-logging.md`.

### Choosing

| | Long polling | Server-sent events | WebSockets |
|---|---|---|---|
| Direction | Server answers each request | Server to client | Both |
| Connection | One request in flight per client, again and again | One long reply | One upgraded connection |
| Browser object | `fetch` | `EventSource` | `WebSocket` |
| Reconnects by itself | The client loop does | Yes, with `Last-Event-ID` | No, the page must |
| Where the standard is | HTTP only | WHATWG HTML | RFC 6455 |
| Bytes per change in the README's run | 416.8 | 154.4 | 113.9 |

The bytes row is the README's measurement (200 changes, one at a time, one client); I did not re-run it. The README's table at 1,000 clients on one machine shows a median delivery delay of 39.62 ms for long polling and about 7 ms for the two push methods; that run took about four minutes and I did not repeat it either.

## Try it yourself

Ports: I ran the first four commands on a copy of the repository on ports 18390 and 18391, with `onSale.actionsPerSecond=1` and `feed.heartbeatMillis=400` in the copy's settings so that the effects show in a few seconds. The settings file was made with:

```bash
sed -e 's/8090/18390/g; s/8091/18391/g; s/^onSale.actionsPerSecond=20/onSale.actionsPerSecond=1/; s/^feed.heartbeatMillis=15000/feed.heartbeatMillis=400/; s/^serve.seconds=600/serve.seconds=200/' config/serve.properties > /tmp/slow.properties
./gradlew -q serve --args='/tmp/slow.properties'
```

Timings and ids differ on every run.

**1. A held long poll.** Read the newest position, then ask for changes after it and time the reply:

```bash
H=http://127.0.0.1:18390
P=$(curl -s $H/v1/events/1/seat-map | sed 's/.*"position":"\([^"]*\)".*/\1/')
curl -s -w '\n%{time_total}s\n' "$H/v1/events/1/seat-changes?after=$P"
```

```text
{"changes":[{"type":"seat","position":"e0cf50b3.8","seat":"O-858","state":"held","changedAt":"2026-11-11T03:14:39.242216Z"}],"next":"e0cf50b3.8","reload":false}
0.527163s
```

**2. An event stream with heartbeats.**

```bash
curl -s -N --max-time 2.5 "$H/v1/events/1/seat-changes/stream?after=$P"
```

`curl` ends after 2.5 seconds with exit status 28 ("operation timed out"), which is expected here. The output looked like the stream shown above: `retry: 1000`, then `: heartbeat` lines and `id`, `event`, `data` blocks.

**3. The chunked reply headers.**

```bash
curl -s -i -N --max-time 1 "$H/v1/events/1/seat-changes/stream?after=$P" | head -8
```

```text
HTTP/1.1 200 OK
Date: Wed, 11 Nov 2026 03:14:56 GMT
Transfer-encoding: chunked
Content-type: text/event-stream
Cache-control: no-store

retry: 1000

```

**4. A WebSocket client that never answers.** Save this as `silent.py` and run it with `python3 -I silent.py`. It performs the handshake with the RFC's sample key, then only listens, and prints each frame it receives with the time:

```python
import socket, time, sys, urllib.request, re
port_ws, port_http = 18391, 18390
pos = re.search(r'"position":"([^"]+)"', urllib.request.urlopen(f"http://127.0.0.1:{port_http}/v1/events/1/seat-map").read().decode()).group(1)
# ask from the newest position; 1 change per second, so mostly idle
s = socket.create_connection(("127.0.0.1", port_ws))
s.sendall((f"GET /v1/events/1/seat-changes/socket?after={pos} HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
           "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
t0 = time.time(); buf = b""; s.settimeout(5)
head_done = False
while True:
    try:
        d = s.recv(4096)
    except socket.timeout:
        print("timeout waiting"); break
    if not d:
        print(f"{time.time()-t0:5.2f}s: server closed the TCP connection"); break
    buf += d
    if not head_done and b"\r\n\r\n" in buf:
        head, buf = buf.split(b"\r\n\r\n", 1); head_done = True
        print(f"{time.time()-t0:5.2f}s: " + head.split(b"\r\n")[0].decode())
    while head_done and len(buf) >= 2:
        op, ln = buf[0] & 0x0F, buf[1] & 0x7F
        if len(buf) < 2 + ln: break
        pl, buf = buf[2:2+ln], buf[2+ln:]
        name = {1: "text", 8: "close", 9: "ping"}.get(op, str(op))
        shown = pl[:40] if op != 8 else (int.from_bytes(pl[:2], "big"),)
        print(f"{time.time()-t0:5.2f}s: frame {name} {shown}")
```

The output is the one shown in the heartbeat section above. Stop the node with Ctrl+C.

**5. The accept value, by hand.**

```bash
printf 'dGhlIHNhbXBsZSBub25jZQ==258EAFA5-E914-47DA-95CA-C5AB0DC85B11' | openssl sha1 -binary | base64
```

```text
s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

**6. Masked and unmasked frames with the repository's code.** See `java-websocket-walkthrough.md`, "Try it yourself", command 3: it decodes the RFC's masked `Hello` to `Hello` and shows that an unmasked client frame raises `ProtocolViolation: client frames must be masked`.

## Mistakes people make

- **Polling on a short timer and calling it real time.** The delay is the interval and the cost is the request rate.
- **Holding a request for longer than the proxies in between allow.** Many proxies cut an idle connection at 30 seconds, so the hold is 25.
- **Resuming without a name for "where I was".** Without a position the client cannot say what it missed, and the server cannot say no. Every event here has one.
- **Reusing sequence numbers across restarts.** An in-memory buffer that restarts at 1 would let an old client apply the wrong history. The stream identifier prevents it.
- **Trusting a browser's `WebSocket` to be safe because it is a browser.** Any page can open one; check the `Origin`.
- **Unmasked frames, unbounded lengths, no heartbeat.** Each of these is a way for one bad client to hurt the server; the codec refuses the first two and the session handles the third.
- **Believing a WebSocket reconnects itself.** It does not; an `EventSource` does.

## Check yourself

1. **What makes server-sent events resume at the right place after a break?** Each event has an `id:` (the feed's position). The browser sends the last one in `Last-Event-ID` when it reconnects.
2. **How does the server compute `Sec-WebSocket-Accept`?** Key plus the fixed GUID, SHA-1, base64.
3. **Why does a client frame carry a masking key and a server frame not?** RFC 6455 requires clients to mask, to keep a script from crafting bytes that a proxy might read as HTTP. Servers do not need to.
4. **What does the server do with a client that is silent for three heartbeats?** It sends close code 1001 and ends the connection, freeing its slot.
5. **A client reconnects with a position from before the feed restarted. What happens?** The stream identifier differs, so `ReplayBuffer.after` answers reload; the client loads the seat map and starts again from its position.

## Further reading

- WHATWG HTML Living Standard, "Server-sent events": https://html.spec.whatwg.org/multipage/server-sent-events.html
- WHATWG HTML Living Standard, "Web sockets" (the browser `WebSocket` interface): https://html.spec.whatwg.org/multipage/web-sockets.html
- RFC 6455, The WebSocket Protocol: https://www.rfc-editor.org/rfc/rfc6455
- RFC 6202, Known Issues and Best Practices for the Use of Long Polling and Streaming in Bidirectional HTTP: https://www.rfc-editor.org/rfc/rfc6202
- RFC 9110, HTTP Semantics: https://www.rfc-editor.org/rfc/rfc9110
- RFC 9112, HTTP/1.1 (chunked transfer coding): https://www.rfc-editor.org/rfc/rfc9112
