# The browser page and the logging configuration

## What it is

This note covers two things that are not Java code.

**The page.** A **web page** is a text file that a **browser** (a program that shows web pages) reads and draws. It is written in three languages that work together. **HTML** says what is on the page: a heading, a drop-down list, a block for the seat map. **CSS** says how it looks: colors, sizes, spacing. **JavaScript** says what it does: fetch data, change the page when a message arrives. The browser keeps a tree of objects that stands for the page, called the **DOM** (Document Object Model), and JavaScript changes the page by changing that tree.

**Logging.** A **log** is a stream of short lines a program writes about what it is doing. The repository's Java code writes lines through **SLF4J** (a front that hides which logging library is used) and a library called **Logback** decides where they go and what they look like. The file `logback.xml` is its configuration.

Every file under `src/main/resources/` is covered here: `logback.xml`, `web/live-seat-map.html` and `web/live-seat-map.js`.

## Why this repository uses it

The page is a small demonstration that the feed works from a real browser. Browsers have two built-in ways to follow a feed: the `EventSource` object (server-sent events) and the `WebSocket` object. The page lets a reader switch between them and watch seat state change. It also shows the client side of the module's rule: apply a change only if it is the next one, and load the whole seat map again if there is a gap or a reload message.

Logging matters for a different reason. A server is a program nobody watches. When something fails, the log is the only record. The repository's rule (from the course's guidelines) is that every request carries a correlation id, so the lines of one request can be found together.

## Where it appears

| File | What it is |
|---|---|
| `src/main/resources/web/live-seat-map.html` | The page: structure and styling. |
| `src/main/resources/web/live-seat-map.js` | The page's script: load the map, follow the feed, reconnect. |
| `src/main/resources/logback.xml` | The logging configuration, found on the class path by Logback. |
| `http/FeedHttpServer.java` (`page` method) | Serves the two web files from the class path. |
| `http/FeedHttpServer.java`, `http/EventStreamRoute.java`, `websocket/WebSocketSession.java`, `websocket/WebSocketServer.java` | The logging calls in the packages these notes cover. |

## How it works underneath

**Loading the page.** The browser sends `GET /`. `FeedHttpServer.page` reads `web/live-seat-map.html` from inside the program's own files (the **class path**, the list of places Java looks for files and compiled code) and returns it with a `Content-Security-Policy` header. The browser parses the HTML into the DOM, finds `<script src="/live-seat-map.js">`, sends `GET /live-seat-map.js`, and runs the script.

**The Content-Security-Policy.** This header tells the browser what the page may do. The feed sends `default-src 'self'; connect-src 'self' ws://127.0.0.1:<websocket port>`. `default-src 'self'` means: load and run only things that come from this page's own origin (scheme, host and port). `connect-src` lists where the page may open network connections: its own origin, and the WebSocket port. A browser enforces this; a command-line client such as `curl` ignores it. The header is a defense against injected script, and it also has a side effect for this page, described below.

**The event loop.** A page's JavaScript runs on one thread. It never waits. When it asks for something slow (a download, a timer, a message), it gives the browser a function to call later, and the browser calls that function when the thing is ready. Writing `await` inside an `async` function is a way to say "continue here when it is ready" without freezing the page.

**Reconnection.** A connection to a server can break. Two designs exist. `EventSource` reconnects by itself after a delay (the server's `retry:` line, 1,000 ms here) and sends the id of the last event it received in a `Last-Event-ID` header; the server resumes from there. `WebSocket` does not reconnect; the page must do it. The page waits a random time that is below a ceiling that doubles after every failure up to a cap. This is **exponential backoff with full jitter**. Without the random part, a thousand clients that lose a server at the same moment would all come back at the same moment and knock it over again.

## Walkthrough of this repository's use

All blocks are copied from the files by a script.

### `logback.xml`

**XML** is a text format where data sits between tags: `<name>content</name>`. Tags nest. An attribute is written inside the opening tag: `name="console"`.

```xml
<configuration>
  <!-- One line per request with its correlation id, which is also the instance of any problem body. -->
  <!-- Log lines go to standard error, so a tool's report on standard output stays clean. -->
```

`<configuration>` is the outer tag of every Logback file. The two lines between `<!--` and `-->` are comments. They say that log lines go to standard error, so a tool's report on standard output stays clean. The first comment also says "One line per request with its correlation id". That is not what the code does today: at the default level nothing is logged per request (see "Try it yourself" and the notes at the end of this file).

```xml
  <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-none}] %logger{0} - %msg%n</pattern>
    </encoder>
  </appender>
```

An **appender** is a destination. This one is called `console` and its class `ConsoleAppender` writes to the terminal. `<target>System.err</target>` selects standard error (the stream for messages about the program; `System.out`, standard output, is for the program's results). The **encoder** turns an event into text using a **pattern**:

- `%d{HH:mm:ss.SSS}` the time: hours, minutes, seconds and milliseconds.
- `%-5level` the level, padded to 5 characters and left-aligned, so `INFO ` and `DEBUG` line up.
- `[%X{correlationId:-none}]` a value from the **MDC** (Mapped Diagnostic Context): a small map attached to the current thread. `%X{correlationId:-none}` prints its `correlationId` entry, or `none` if the thread has none.
- `%logger{0}` the logger's name shortened to the last part, such as `EventStreamRoute`.
- `-` a literal dash. `%msg` the message. `%n` a line break.

```xml
  <root level="INFO">
    <appender-ref ref="console"/>
  </root>
</configuration>
```

The **root** logger is the parent of all loggers. Its `level` is `INFO`. The levels, from most detailed to most serious, are TRACE, DEBUG, INFO, WARN and ERROR. A logger shows events at its level or above, so at `INFO` the DEBUG lines are hidden. `<appender-ref ref="console"/>` connects the root logger to the appender above.

### The logging calls in these packages

A **logger** is an object obtained once per class: `LoggerFactory.getLogger(EventStreamRoute.class)`. The calls are `LOG.debug(...)`, `LOG.warn(...)` and `LOG.error(...)`. A `{}` in the message is a placeholder that is filled with the next argument only if the line is actually written, so a hidden debug line costs almost nothing. All six calls in these packages are below.

```java
  private void serve(HttpExchange exchange) {
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);
    parts.metrics().recordHandlerThread(true);
    try (exchange) {
      route(exchange, correlationId);
    } catch (IOException clientGone) {
      LOG.debug("request ended early: {}", clientGone.toString());
    } catch (RuntimeException bug) {
      LOG.error("unexpected failure", bug);
    } finally {
      parts.metrics().recordHandlerThread(false);
      MDC.remove("correlationId");
    }
  }
```

In `serve`, which runs for every HTTP request: the line `MDC.put("correlationId", correlationId)` stores a fresh random id on the current thread, so every line logged while this thread serves the request shows it. It is removed in `finally`. `LOG.debug("request ended early: {}", clientGone.toString())` records that the client went away. `LOG.error("unexpected failure", bug)` is for a bug: when the last argument is an exception, Logback prints its stack trace. The same id is the `instance` field of any problem reply (`urn:uuid:<id>`), so a person who has an error body can find the log lines.

```java
    } catch (InterruptedException dropped) {
      Thread.currentThread().interrupt();
    } catch (IOException clientGone) {
      // A client that closes its stream, or a write the watchdog interrupted, ends here.
      LOG.debug("event stream ended: {}", clientGone.toString());
    }
```

A closed event stream is normal: the client went away, or the watchdog interrupted a stuck write. It is logged at DEBUG.

```java
    } catch (IOException ended) {
      LOG.debug("websocket ended: {}", ended.toString());
    } finally {
      parts.metrics().recordHandlerThread(false);
    }
```

The end of a WebSocket connection, at DEBUG.

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

`closeQuietly` logs the rare failure to close a socket, at DEBUG.

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

The accept loop logs `LOG.warn("accept failed: {}", ...)` at WARN level if the listening socket fails while it is still open. This is one of only two calls at WARN or above in these packages; the other is the ERROR in `serve`.

The WebSocket threads do not set a correlation id, so their lines show `[none]`.

### `live-seat-map.html`

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>SeatWave live seat map</title>
```

`<!doctype html>` tells the browser this is modern HTML. `<html lang="en">` marks the language. `<head>` holds information about the page. `<meta charset="utf-8">` says the file is UTF-8 text. The `viewport` meta tag makes a phone use its real screen width instead of pretending to be a wide desktop. `<title>` is the text on the browser tab.

```html
  <style>
    body { font-family: system-ui, sans-serif; margin: 16px; background: #fff; color: #111; }
    #map { display: grid; grid-template-columns: repeat(50, 8px); gap: 1px; }
    #map span { width: 8px; height: 8px; background: #2e7d32; }
    #map span.h { background: #f9a825; }
    #map span.s { background: #9e9e9e; }
    .status { margin: 8px 0; }
  </style>
```

The `<style>` element holds CSS. A rule has a **selector** (what it applies to) and **declarations** in braces. `body { ... }` styles the page body: a system font, a 16-pixel margin, white background and near-black text. `#map` selects the element whose `id` is `map`: `display: grid` makes it a grid with `grid-template-columns: repeat(50, 8px)`, which is 50 columns of 8 pixels, and a 1-pixel gap. `#map span` styles every `span` inside it: 8 by 8 pixels and green (`#2e7d32`). `#map span.h` styles a span that also has the class `h` (held): amber. `span.s` (sold): gray. `.status` is a class: a margin above and below.

```html
</head>
<body>
  <h1>SeatWave live seat map</h1>
```

`</head>` ends the head, `<body>` begins what is drawn, and `<h1>` is the main heading.

```html
  <p class="status">Method:
    <select id="method" aria-label="Delivery method">
      <option value="sse">Server-sent events (EventSource)</option>
      <option value="websocket">WebSocket</option>
    </select>
    <span id="state" role="status">connecting</span>
  </p>
```

A paragraph with the class `status`. The `<select>` is a drop-down list; its `id` is `method`, which the script uses to find it, and `aria-label` gives a screen reader a name for it. Each `<option>` has a `value` (what the script reads) and a label (what the person sees). The `<span id="state" role="status">` shows the connection state; `role="status"` tells assistive technology to read changes aloud politely.

```html
  <p class="status">Section A, seats 1 to 1,000. Green is available, amber held, grey sold.</p>
  <div id="map" aria-label="Seats of section A"></div>
  <script src="/live-seat-map.js"></script>
</body>
</html>
```

A line of explanation. The empty `<div id="map">` is the container the script fills with 1,000 spans, one per seat of section A. `<script src="/live-seat-map.js">` loads the script from the same server. It is the last thing in the body, so the elements above it exist when the script runs.

### `live-seat-map.js`

```javascript
// The browser side of the SeatWave live feed, for a reader to open by hand at
// http://127.0.0.1:8090/ while ./gradlew -q serve runs. It loads the seat map once, then follows
// the feed with EventSource or WebSocket from the map's position, and applies each change only if
// it is the next one; a reload message, or a change that is not the next one, loads the map again.
"use strict";
```

Comments start with `//`. `"use strict"` switches on strict mode: some silent mistakes (such as using a variable that was never declared) become errors.

```javascript
const WEBSOCKET_PORT = 8091;
const SHOWN_SEATS = 1000;
const BACKOFF_BASE_MS = 500;
const BACKOFF_CAP_MS = 30000;
```

`const` declares a name that is assigned once. `WEBSOCKET_PORT` is the WebSocket server's port; it is written into the script, and equals the default in `config/serve.properties`. `SHOWN_SEATS` is how many seats the page draws (section A, 1,000 seats). The two `BACKOFF_` values are the first ceiling (500 ms) and the cap (30,000 ms) of the reconnection wait.

```javascript
const mapElement = document.getElementById("map");
const stateElement = document.getElementById("state");
const methodElement = document.getElementById("method");
const seats = [];
let streamId = "";
let sequence = 0;
let connection = null;
let failures = 0;
```

`document.getElementById("map")` returns the DOM element with that id. `seats` is an array (a list) that will hold the 1,000 span elements. `let` declares a variable that may be assigned again: `streamId` and `sequence` are the position of the last change the page applied, `connection` is the `EventSource` or `WebSocket` in use, and `failures` counts consecutive failed reconnections.

```javascript
for (let seatIndex = 0; seatIndex < SHOWN_SEATS; seatIndex++) {
  const seat = document.createElement("span");
  seat.title = "A-" + (seatIndex + 1);
  mapElement.appendChild(seat);
  seats.push(seat);
}
```

A `for` loop that runs 1,000 times. Each time it creates a `<span>` with `document.createElement`, sets its tooltip (`title`) to `A-1`, `A-2`, `A-3` up to `A-1000`, adds it to the map block with `appendChild` and remembers it in `seats`.

```javascript
function show(seatIndex, code) {
  if (seatIndex < SHOWN_SEATS) {
    seats[seatIndex].className = code === "a" ? "" : code;
  }
}
```

`show` paints one seat. A function is declared with `function name(parameters) { ... }`. It sets the element's `className`: for the code `a` (available) the class is empty, so the default green applies; for `h` or `s` the class is that letter, which matches the CSS rules `span.h` and `span.s`. `===` compares without converting types; `? :` is "if, then, else".

```javascript
async function loadMap() {
  const reply = await fetch("/v1/events/1/seat-map", { cache: "no-store" });
  const map = await reply.json();
  [streamId, sequence] = splitPosition(map.position);
  for (let seatIndex = 0; seatIndex < SHOWN_SEATS; seatIndex++) {
    show(seatIndex, map.states[seatIndex]);
  }
  return map.position;
}
```

`loadMap` is an `async` function: it may use `await`, which pauses this function (not the page) until a **Promise** (a value that will arrive later) is ready. `fetch(url, options)` makes an HTTP request and returns a Promise of the reply. `{ cache: "no-store" }` tells the browser not to reuse an earlier answer. `await reply.json()` reads the body and parses the JSON into an object. `[streamId, sequence] = splitPosition(map.position)` is **destructuring**: it assigns the two items of the returned array to the two variables. The loop paints the first 1,000 letters of `map.states`: indexing a string with `[i]` gives one character. The function returns the position so the caller can start following from it.

```javascript
function splitPosition(position) {
  const dot = position.indexOf(".");
  return [position.slice(0, dot), Number(position.slice(dot + 1))];
}
```

`splitPosition` cuts `4d4f4a94.231` at the dot: the text before is the stream identifier, and the text after becomes a number with `Number(...)`. `slice(a, b)` returns the part between two indexes.

```javascript
// Returns false when the page must reload the map: a reload message, or a gap.
function apply(message) {
  if (message.type === "reload") {
    return false;
  }
  const [changeStream, changeSequence] = splitPosition(message.position);
  if (changeStream !== streamId || changeSequence > sequence + 1) {
    return false;
  }
  if (changeSequence <= sequence) {
    return true;
  }
  const [section, number] = message.seat.split("-");
  if (section === "A") {
    show(Number(number) - 1, message.state[0]);
  }
  sequence = changeSequence;
  return true;
}
```

`apply` takes one message (an object) and decides what to do. It returns `false` when the page must load the map again. A `reload` message returns `false`. Otherwise it splits the message's position. If the change belongs to another stream, or its sequence is more than one ahead of the page's (a gap), it returns `false`: the page never paints past a gap. If the sequence is at or below the page's, the change is a duplicate and is ignored (`true`). Otherwise it is exactly the next change: the seat label such as `A-802` is split at the hyphen, and if the section is `A` the seat is painted with the first letter of the state (`"held"[0]` is `h`), then the page's `sequence` moves on. These are the same three outcomes as `ClientSeatMap.apply` in the Java client.

```javascript
async function follow() {
  const position = await loadMap();
  if (methodElement.value === "sse") {
    // EventSource reconnects by itself after the server's retry delay, to the same URL, and sends
    // the last id it saw in the Last-Event-ID header; the server resumes from there.
    const source = new EventSource("/v1/events/1/seat-changes/stream?after=" + position);
    connection = source;
    source.onopen = () => { stateElement.textContent = "open (server-sent events)"; };
    source.onerror = () => { stateElement.textContent = "reconnecting"; };
    source.addEventListener("seat", (event) => {
      if (!apply(JSON.parse(event.data))) { restart(); }
    });
    source.addEventListener("reload", restart);
    return;
  }
  // A WebSocket does not reconnect by itself: the page backs off with full jitter and resumes from
  // the last change it applied.
  openSocket();
}
```

`follow` starts following. First it loads the map and gets the position. If the select says `sse`: `new EventSource(url)` opens the event stream with `after=<position>`. The comment explains the browser's behavior: after a break it reconnects to the same URL after the server's `retry` delay and sends the last id it saw in `Last-Event-ID`; the server prefers the header. The handlers: `onopen` shows "open"; `onerror` shows "reconnecting" (the browser is already retrying). `addEventListener("seat", ...)` runs for events named `seat`: parse the data text as JSON with `JSON.parse` and apply it; if `apply` says false, call `restart`. A `reload` event also calls `restart`. The arrow function `(event) => { ... }` is a short way to write an unnamed function. For any other method the function goes on to `openSocket`.

```javascript
function openSocket() {
  const socket = new WebSocket("ws://127.0.0.1:" + WEBSOCKET_PORT
      + "/v1/events/1/seat-changes/socket?after=" + streamId + "." + sequence);
  connection = socket;
  socket.onopen = () => { failures = 0; stateElement.textContent = "open (WebSocket)"; };
  socket.onmessage = (event) => {
    if (!apply(JSON.parse(event.data))) { restart(); }
  };
  socket.onclose = () => {
    stateElement.textContent = "reconnecting";
    const ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** failures++);
    setTimeout(openSocket, Math.random() * ceiling);
  };
}
```

`openSocket` creates `new WebSocket(url)` with `after=<stream>.<sequence>`, the last change the page applied. `onopen` resets the failure counter. `onmessage` parses and applies each message. `onclose` runs whenever the connection ends, for any reason: it shows "reconnecting", computes the ceiling `Math.min(30000, 500 * 2 ** failures++)` (`**` is the power operator and `failures++` adds one after reading), and schedules `openSocket` with `setTimeout` after a random time from 0 up to the ceiling (`Math.random()` is between 0 and 1). That is the full-jitter backoff.

```javascript
function restart() {
  if (connection) {
    connection.onclose = null;
    connection.close();
  }
  follow();
}
```

`restart` closes the current connection and follows again. Setting `connection.onclose = null` first stops the close handler from scheduling a reconnect of its own: the page is about to start a fresh one.

```javascript
methodElement.addEventListener("change", restart);
follow();
```

Switching the method calls `restart`. The last line starts everything when the script loads.

## Try it yourself

**1. Check the script's syntax without a browser.** With Node.js installed (v24.14.1 on the machine used):

```bash
node --check src/main/resources/web/live-seat-map.js && echo syntax-ok
```

```text
syntax-ok
```

**2. Read the page's response headers.** With the feed running (I used a copy on ports 18190 and 18191; the default ports are 8090 and 8091):

```bash
curl -s -i http://127.0.0.1:18190/ | head -6
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://127.0.0.1:18190/live-seat-map.js
```

```text
HTTP/1.1 200 OK
Date: Wed, 11 Nov 2026 03:00:39 GMT
Content-security-policy: default-src 'self'; connect-src 'self' ws://127.0.0.1:18191
Content-type: text/html; charset=utf-8
Content-length: 1081
Cache-control: no-store
200 text/javascript; charset=utf-8
```

**3. See a DEBUG log line.** Copy the repository folder, change `level="INFO"` to `level="DEBUG"` in the copy's `src/main/resources/logback.xml`, run `serve` from the copy, open an event stream and drop it after a second:

```bash
P=$(curl -s http://127.0.0.1:18290/v1/events/1/seat-map | sed 's/.*"position":"\([^"]*\)".*/\1/')
curl -s -N --max-time 1 "http://127.0.0.1:18290/v1/events/1/seat-changes/stream?after=$P" > /dev/null
```

The terminal that runs `serve` printed (standard error) this line (the id and time differ on every run):

```text
03:01:30.070 DEBUG [cf878c86-68e7-4b1c-8d9b-e673949f3500] EventStreamRoute - event stream ended: java.io.IOException: Broken pipe
```

With the unchanged `INFO` setting the same requests printed nothing at all.

**4. Open the page in a browser.** This is the part the README says was **not run in a browser on the verification machine**. I opened it with the browser pane of my own session, in two runs of the feed: one on ports 18190 and 18191, one on the default ports 8090 and 8091. What I saw:

- The page loaded, the state text changed to `open (server-sent events)`, and the script created 1,000 `span` elements.
- Seat changes arrived: the number of spans with a class (held or sold) rose from 19 to 22 in three seconds (first run).
- On the first run, switching the select to WebSocket did nothing useful: the script is written for port 8091 (`WEBSOCKET_PORT`), and the page's own policy allows only the real WebSocket port (18191 there), so the connection could not open. On the default ports (second run) the same switch changed the state to `open (WebSocket)`, `/metrics` showed one open WebSocket connection, and the count of colored spans went on rising (12, 15, 19).
- **The seat map was not visible, on either run.** The page showed the heading, the select and the sentence, in the browser's default serif font, with no colored squares. The browser's console said: `Applying inline style violates the following Content Security Policy directive 'default-src 'self''`. The feed's own header (`default-src 'self'`) forbids the inline `<style>` element. The computed style of the map block was `display: block` and its spans had width `auto` and no background. The script works; the styling does not apply.

The fix is small (move the CSS into a file the feed serves, or add a style source to the policy). These notes describe the repository as it is and do not change it.

## Mistakes people make

- **Putting a long-lived connection's reconnect logic in the wrong place.** `EventSource` reconnects by itself; a `WebSocket` does not. The page backs off with jitter only for the WebSocket.
- **Reconnecting with no random wait.** Many clients that retry on the same schedule hit the server together.
- **Painting a change that is not the next one.** `apply` refuses a gap and a foreign stream, which is what keeps the page's map equal to the store's.
- **Leaving a reconnect timer running when the connection is replaced.** `restart` clears the close handler of the connection it closes, but a timer that `onclose` already scheduled is not cancelled. In my test, closing a WebSocket and switching to server-sent events within a few milliseconds sometimes ended with a WebSocket open as well as the event stream; across six rounds the server counted three open WebSocket connections and four open event streams for one page. The remedy is to keep the timer's id and clear it in `restart`.
- **Logging a secret, or logging at too high a level.** The feed logs no request body and no client address; the correlation id is the only identifier.
- **A policy that blocks your own page.** A strict Content-Security-Policy is good, but it must be tested with the real page.

## Check yourself

1. **What does `EventSource` do that `WebSocket` does not?** It reconnects by itself, after the server's `retry` delay, and sends the last event id in `Last-Event-ID`.
2. **What does `apply` return for a change whose sequence is two ahead of the page's?** `false`: that is a gap, so the page loads the whole map again.
3. **Why does the backoff pick a random time below the ceiling?** So that many clients do not retry at the same moment.
4. **What does `%X{correlationId:-none}` print for a WebSocket session's log line?** `none`, because only `FeedHttpServer.serve` puts a correlation id in the MDC.
5. **At the `INFO` level, which calls in these packages can print?** Only `LOG.warn("accept failed: ...")` in `WebSocketServer` and `LOG.error("unexpected failure", ...)` in `FeedHttpServer`. The four DEBUG calls are hidden.

## Further reading

- WHATWG HTML Living Standard (the document, `script`, server-sent events and the `WebSocket` interface): https://html.spec.whatwg.org/multipage/
- WHATWG Fetch Standard: https://fetch.spec.whatwg.org/
- WHATWG DOM Standard: https://dom.spec.whatwg.org/
- ECMAScript language specification: https://tc39.es/ecma262/
- Content Security Policy Level 3 (W3C): https://www.w3.org/TR/CSP3/
- SLF4J 2.0 manual: https://www.slf4j.org/manual.html
- Logback 1.6 manual (configuration and layouts): https://logback.qos.ch/manual/index.html
