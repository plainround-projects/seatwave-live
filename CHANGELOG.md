# Changelog

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
