# Changelog

## 1.1.0 (11 November 2026)

Added

- The `tech/` folder: beginner notes on every technology the repository uses, with line-by-line
  walkthroughs of the main code and the tests, and an index (`tech/README.md`).
- `tech/jackson.md`: JSON from zero and Jackson 2.23.0 as the `wire` package and the tests use it.
- Tests: a forced pause-during-a-tick test (`OnSaleSimulatorTest`); a client ping answered by a
  pong with the same payload, and an unknown opcode closed with 1002 (`WebSocketServerTest`); the
  close codes of unknown opcodes, stray continuations and fragmented control frames
  (`FrameCodecTest`); the page served without inline style, with its style file, the real
  WebSocket origin and the exact policy (`FeedHttpServerTest`); `data:` with and without a space and
  data lines joined with a line feed (`EventStreamConnectionTest`); a failed reload tried again
  (`FeedClientTest`); a backoff cap below its base reported as a settings problem
  (`LiveSettingsTest`); metrics lines that end with a line feed (`SeatFeedTest`).

Fixed

- `SeatFeedTest`'s join test no longer waits for a change after the publisher's last one when the
  publisher finishes first.
- Pausing the on-sale now waits for a tick that is already running, so a snapshot taken after
  `pause()` is final (the reconnect test, the slow-consumer run and the admin pause route).
- The stuck-writer test takes the moment of the check as a value, so it no longer depends on the
  machine's speed.
- The child-process test writes its node settings with free ports instead of the fixed 8092 and
  8093.
- The browser page's style is a file of its own, so the strict security policy no longer hides the
  seat map.
- Switching between the page's two feeds cancels the pending WebSocket reconnect and any older
  work, so one page keeps exactly one connection.
- The page reads the WebSocket address from the server instead of a hard-coded port 8091, and the
  policy allows exactly that origin.
- An unknown WebSocket opcode closes the connection with 1002 (protocol error), as RFC 6455
  requires, instead of 1003.
- The metrics text ends every line with a line feed on every platform, not the platform's
  separator.
- `SeatFeed.changesAfter`, used only by a test, is removed.
- A long poll whose client went away is held until its reply is written: stated as a limit of long
  polling on this server, in the code and the README.
- A backoff cap below its base is a settings problem, not a stack trace.
- The slow-consumer report proves that the server closed the slow clients' connections, by
  reading each to its end, instead of reporting `isConnected()`.
- The Java event-stream client accepts `data:` without a space and joins data lines with a line
  feed, as the standard says.
- A failed seat-map reload is tried again instead of following from the old position.
- A comparison run stops its client fleets once on every path.
- The file and line counts in `ARCHITECTURE.md`, the logging comment, and the descriptor counts in
  `compose.yaml` match the code and the measurements.
- The README's leftover "Technology notes" placeholder section is gone; the README states the
  long-poll limit and what was run in a browser.
- Two tidy-ups in the tests: no nested class just to read a close code, and imports instead of
  fully qualified class names.

## 1.0.0 (11 November 2026)

- The SeatWave live seat-availability feed for Module 8: an in-memory seat store with Module 1's
  rules (holds that expire, confirms), a publisher that numbers every change and keeps a bounded
  replay buffer, and three delivery adapters: long polling and server-sent events on the JDK's HTTP
  server, and WebSockets on a small RFC 6455 server written on the JDK's sockets.
- A seat-map snapshot with the feed position it matches, and resume-or-reload on every reconnect:
  no gap and no duplicate, or an explicit instruction to reload.
- Bounded per-connection queues with a stated slow-consumer policy (reload on overflow, disconnect
  on a write stuck past the write timeout), a connection limit with 503 and Retry-After, heartbeats.
- A Java load client for all three methods (backoff with full jitter, a silence deadline), the
  measured comparison at 1,000 clients with its CSV, the wire-bytes probe, the slow-consumer
  experiment, and the tools of Labs 1 and 2.
- A static browser page using EventSource and WebSocket, served at `/` by the feed.
