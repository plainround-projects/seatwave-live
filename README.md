# seatwave-live

The SeatWave live seat-availability feed, built three ways (long polling, server-sent events and WebSockets), with a measured comparison of delivery delay and server load at 1,000 clients. Every seat change of an on-sale is numbered once and reaches every watching client exactly once, or the client is told to reload the seat map: never a silent gap. Everything listens on 127.0.0.1 only.

## Which module this belongs to

This is the hands-on project of Module 8, "Talking back: real-time delivery", of the course System Design Fundamentals. The running case is SeatWave, a ticket-booking service. Module 7 gave it a versioned API whose seat states are `available`, `held` and `sold`; this repository adds the feed that tells fans when a seat changes:

- A seat store with Module 1's rules (holds that expire after a hold time, confirms), driven by a simulated on-sale, and a publisher that numbers every change and keeps a bounded replay buffer.
- `GET /v1/events/1/seat-changes?after=<position>`: long polling on the JDK's HTTP server.
- `GET /v1/events/1/seat-changes/stream`: server-sent events with `Last-Event-ID` resume.
- `ws://127.0.0.1:8091/v1/events/1/seat-changes/socket?after=<position>`: WebSockets, on the server side of RFC 6455 written on the JDK's sockets.
- `GET /v1/events/1/seat-map`: the whole map with the position it matches.
- A Java load client for all three methods, the comparison at 1,000 clients and its CSV, a wire-bytes probe, a slow-consumer experiment, and the tools of the module's labs.
- A browser page at `http://127.0.0.1:8090/` that follows the feed with `EventSource` or `WebSocket`.

## Prerequisites

- JDK 25. The build was verified on Eclipse Temurin 25.0.2 on macOS (Apple silicon) on 11 November 2026. The current JDK 25 update on that date was 25.0.5 (build 25.0.5+8), which was not installed on the verification machine.
- `curl` for the session below (8.7.1 on the verification machine).
- Nothing else: the Gradle wrapper (9.9.1, whose jar's SHA-256 matches the published value) downloads Gradle and the libraries below on the first build.
- For 1,000 clients, each of the two processes needs about 1,100 file descriptors. Check with `ulimit -n` (Section "How many clients fit").
- Optional: Docker with Compose, to run the build on JDK 25.0.5 (see "Docker Compose (optional)").

## Dependencies and build plugins

Every version is exact. Each was checked on Maven Central or the Gradle Plugin Portal on 11 November 2026, and every one of the 30 jars in `gradle.lockfile` was checked against the OSV database on the same day, which listed no vulnerability for any of them.

| Coordinates | Version | License | Why the JDK alone is not enough |
|---|---|---|---|
| `com.github.sviperll:result4j` | 1.2.0 | Apache-2.0 | The JDK has no success-or-typed-error type, so a refused hold or a bad setting would have to be an exception. |
| `com.fasterxml.jackson.core:jackson-databind` (BOM `com.fasterxml.jackson:jackson-bom`) | 2.23.0 | Apache-2.0 | The JDK has no JSON parser; the 2.x line, as in Module 7. |
| `org.slf4j:slf4j-api` and `ch.qos.logback:logback-classic` | 2.0.20 and 1.6.6 | MIT; EPL-2.0 or LGPL-2.1 | Logging with a correlation id, as the course's guidelines require. |
| `org.junit:junit-bom`, `org.assertj:assertj-core`, `org.awaitility:awaitility` (test) | 6.1.3, 3.27.7, 4.3.0 | EPL-2.0, Apache-2.0, Apache-2.0 | The tests, their assertions, and waiting for a condition instead of sleeping. |
| Gradle plugin `com.diffplug.spotless` with google-java-format | 8.11.0 with 2.0.0 | Apache-2.0 | The formatter, enforced by `check`. |
| JaCoCo | 0.8.16 | EPL-2.0 | Line coverage, with a floor of 80 percent wired into `check`. |

No WebSocket library is used. The JDK has a WebSocket client (`java.net.http.WebSocket`), which the load client uses, but no server, and its HTTP server cannot hand a connection over after `101 Switching Protocols`; the server side of RFC 6455 is written in the `websocket` package. `ARCHITECTURE.md` says which libraries were considered and why they were not chosen.

## Build and test

```bash
./gradlew clean check
```

Formats are checked, the code compiles with every warning as an error, 103 tests run and the coverage floor is checked. The last lines of a run on the verification machine:

```text

BUILD SUCCESSFUL in 30s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
```

Coverage was 90.8 percent of lines (2,315 of 2,550).

## The feed and a session

```bash
./gradlew -q serve --args='config/serve.properties'
```

```text
SeatWave live feed on http://127.0.0.1:8090 and ws://127.0.0.1:8091 for 600 s; on-sale at 20 actions per second; at most 5000 connections
```

In another terminal (the session's real output, from the verification machine):

```text
$ curl -s http://127.0.0.1:8090/health
ok
$ curl -s http://127.0.0.1:8090/v1/events/1/seat-map | cut -c1-120
{"eventId":1,"position":"41550af9.366","sections":20,"seatsPerSection":1000,"states":"aaaaaaaaaaaaaaaaaaaaaaaaaasaaaaaaa
$ curl -s 'http://127.0.0.1:8090/v1/events/1/seat-changes?after=41550af9.367' | cut -c1-400
{"changes":[{"type":"seat","position":"41550af9.368","seat":"D-731","state":"held","changedAt":"2026-11-11T02:39:51.904878Z"},{"type":"seat","position":"41550af9.369","seat":"P-968","state":"held","changedAt":"2026-11-11T02:39:51.952003Z"},{"type":"seat","position":"41550af9.370","seat":"D-342","state":"held","changedAt":"2026-11-11T02:39:52.004371Z"},{"type":"seat","position":"41550af9.371","seat
$ curl -s -N --max-time 2 'http://127.0.0.1:8090/v1/events/1/seat-changes/stream?after=41550af9.367' | head -9
retry: 1000

id: 41550af9.368
event: seat
data: {"type":"seat","position":"41550af9.368","seat":"D-731","state":"held","changedAt":"2026-11-11T02:39:51.904878Z"}

id: 41550af9.369
event: seat
data: {"type":"seat","position":"41550af9.369","seat":"P-968","state":"held","changedAt":"2026-11-11T02:39:51.952003Z"}
$ curl -s -i --max-time 2 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' 'http://127.0.0.1:8091/v1/events/1/seat-changes/socket?after=41550af9.367' | head -4
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=

$ curl -s -i --max-time 2 -H 'Origin: https://evil.example' -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' 'http://127.0.0.1:8091/v1/events/1/seat-changes/socket?after=41550af9.367'
HTTP/1.1 403 Refused
Content-Type: text/plain; charset=utf-8
Content-Length: 51
Connection: close

origin https://evil.example may not open this feed

$ curl -s -i --max-time 2 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 8' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' 'http://127.0.0.1:8091/v1/events/1/seat-changes/socket?after=41550af9.367'
HTTP/1.1 426 Refused
Sec-WebSocket-Version: 13
Content-Type: text/plain; charset=utf-8
Content-Length: 39
Connection: close

only WebSocket version 13 is supported
$ curl -s -i 'http://127.0.0.1:8090/v1/events/1/seat-changes?after=7' | grep -v '^Date'
HTTP/1.1 400 Bad Request
Content-type: application/problem+json
Content-length: 261
Cache-control: no-store

{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the feed's contract","status":400,"detail":"after must be <8 hex digits>.<sequence>, as the feed sent it","instance":"urn:uuid:3d865934-1f3a-443c-96e1-21627a669d93"}

$ curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8090/v1/events/2/seat-map'
404

$ curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8090/admin/pause
404
$ curl -s http://127.0.0.1:8090/metrics | grep -E '^seatwave_feed_(changes_published|open_connections|connections_total|reloads)'
seatwave_feed_changes_published_total 430
seatwave_feed_open_connections{method="long-polling"} 0
seatwave_feed_connections_total{method="long-polling"} 1
seatwave_feed_reloads_sent_total{method="long-polling"} 0
seatwave_feed_open_connections{method="sse"} 0
seatwave_feed_connections_total{method="sse"} 1
seatwave_feed_reloads_sent_total{method="sse"} 0
seatwave_feed_open_connections{method="websocket"} 0
seatwave_feed_connections_total{method="websocket"} 1
seatwave_feed_reloads_sent_total{method="websocket"} 0
```

The browser page is at `http://127.0.0.1:8090/` while `serve` runs: it draws section A and follows the feed with server-sent events or a WebSocket, and a select switches between them. It was run on the verification machine in a browser pane after the version 1.1.0 fixes. On the default ports it drew all 1,000 seats in color with no error in the browser's console, followed the feed with both methods, and after six switches made as fast as a script can, `/metrics` showed exactly one open connection. On a copy of `config/serve.properties` with ports 8190 and 8191 and the admin routes on (to drop every connection) the page connected its WebSocket to 8191, the port the server named in the page, and after a drop of every connection and a switch to server-sent events, no second WebSocket was opened.

## The comparison at 1,000 clients

```bash
./gradlew -q compare --args='config/compare.properties'
```

Each run starts the feed in a JVM of its own, pauses its on-sale, connects a tenth of the clients and reads the server's memory, connects the rest and reads it again, runs a warm-up and a measured phase, drops every connection at once, then pauses and compares every client's map with the store's. The run on the verification machine took about 4 minutes:

```text
Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process may open 1048576 files
feed in its own JVM (fixed, pre-touched heap of 1024 MB); 1000 clients per method, 3 run(s) each; warm-up 5 s, measured 15 s; on-sale 50 actions per second over 20000 seats
method       run  conn changes/s  deliver/s     req/s   p50 ms   p99 ms   max ms  cores  us/del   heap/c    rss/c pthr vthr  fds recov ms reconn reload gaps correct
long-polling   1  1000     69.7    69712.3   22121.5    44.56   129.09   248.46  1.710    24.5    70159    10522   26 1001 1022     161    406     0    0    1000
long-polling   2  1000     69.4    69384.6   23543.1    39.62   114.32   218.03  1.731    24.9    70256     6990   21 1001 1022     214    301     0    0    1000
long-polling   3  1000     69.4    69356.8   24721.9    37.39   109.20   211.58  1.768    25.5    70305    12815   21 1001 1022     162    245     0    0    1000
sse            1  1000     69.4    69443.9       0.0     7.98    24.42    57.46  0.700    10.1    80202     8119   22 1001 1022     155   1000     0    0    1000
sse            2  1000     69.3    69347.9       0.0     6.88    13.78    29.38  0.749    10.8    80412     9411   23 1001 1022      56   1000     0    0    1000
sse            3  1000     69.4    69386.0       0.0     6.93    15.87    70.48  0.725    10.5    80190    12797   21 1001 1022     157   1000     0    0    1000
websocket      1  1000     69.4    69415.4       0.0     6.92    13.26    17.87  0.749    10.8    21528     6571   26 1001 1032     178   1000     0    0    1000
websocket      2  1000     69.4    69370.4       0.0     7.02    13.63    35.73  0.722    10.4    21420     6207   26 1001 1031     132   1000     0    0    1000
websocket      3  1000     69.4    69361.7       0.0     6.98    13.86    33.06  0.743    10.7    21591     6462   26 1001 1031      72   1000     0    0    1000
median of the runs:
long-polling med  1000     69.4    69384.6   23543.1    39.62   114.32   218.03  1.731    24.9    70256    10522   21 1001 1022     162    301     0    0    1000
sse          med  1000     69.4    69386.0       0.0     6.93    15.87    57.46  0.725    10.5    80202     9411   22 1001 1022     155   1000     0    0    1000
websocket    med  1000     69.4    69370.4       0.0     6.98    13.63    33.06  0.743    10.7    21528     6462   26 1001 1031     132   1000     0    0    1000
csv: build/compare/live-feed-comparison.csv
```

The CSV, one line per run, is written to `build/compare/live-feed-comparison.csv`.

## Bytes on the wire, the slow consumer, and the labs

```bash
./gradlew -q wireBytes --args='config/wire-bytes.properties 200'
./gradlew -q slowConsumer --args='config/slow-consumer.properties 50 10'
./gradlew -q pollingRates --args='config/polling-rates.properties'
./gradlew -q sseResume --args='config/sse-resume.properties 500'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process may open 1048576 files
method          client->server B   server->client B   total B/change   (200 changes, one at a time, one client)
long-polling               120.5              296.4            416.8
sse                          0.0              154.4            154.4
websocket                    0.0              113.9            113.9
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process may open 1048576 files
settings: client queue 256 changes, write timeout 2000 ms, on-sale 500 actions per second for 10 s
changes published: 6300
deepest client queue: 256 (bound 256)
queue overflows (client told to reload): 2
stuck writes cut after the write timeout: 2
open connections at the end: sse 50, websocket 0 (the 50 normal clients are sse)
slow clients' connections ended by the server (each read to its end): yes
heap after a collection: 56907 KB before, 59476 KB after
normal clients: delay p50 0.65 ms, p99 7.78 ms, max 36.39 ms (315000 samples); 50 of 50 match the store
```

```text
200,000 fans; short poll every 5000 ms; long poll held up to 25000 ms; round trip 80 ms
                                     busy on-sale  quiet evening
seat changes per second                 3,000.000          0.017
short polling, requests per second         40,000         40,000
short polls that find nothing, %              0.0           92.0
long polling, requests per second       2,489,627          9,744
long polling, connections held            200,000        200,000
push, messages per second             600,000,000          3,333
push, connections held                    200,000        200,000
```

```text
replay buffer: 200 changes; on-sale 50 actions per second
drop 1: the server closed 1 stream(s) at sequence 49
drop 2: the server closed 1 stream(s) at sequence 100
scenario 1, resumed inside the buffer: applied 150, duplicates 0, gaps 0, reloads 0, reconnects 2; client at c11ef2dd.150, store at c11ef2dd.150; map equals store: yes
while the client was away: 500 holds, feed now at sequence 650
scenario 2, away longer than the buffer: applied 150, duplicates 0, gaps 0, reloads 1, reconnects 3; client at c11ef2dd.650, store at c11ef2dd.650; map equals store: yes
```

## How many clients fit

Each open connection uses one file descriptor in the server process and one in the client process. On the verification machine the shell's limit (`ulimit -n`) was 1,048,576, `launchctl limit maxfiles` reported a soft limit of 256 for programs started by the system, `kern.maxfilesperproc` was 122,880, and the server process reported a limit of 1,048,576 descriptors, so 1,000 clients fitted with room to spare; nothing was changed. On a machine where `ulimit -n` prints a small number such as 256, either lower `compare.clients` in `config/compare.properties`, or raise the limit for one shell only before running the comparison, for example `ulimit -n 4096` (this changes nothing outside that shell). The accept backlog is capped by `kern.ipc.somaxconn` (128 here), which is why clients connect at 250 per second instead of all at once.

## The settings

Every node setting is in a file under `config/`, with a comment on where each value comes from, and none has a default in code: a missing key stops the program with one line per problem.

## Project structure

```text
seatwave-live/
  config/                     the node, comparison and lab settings
  compose.yaml                optional: the build on JDK 25.0.5 in a container, not run here
  src/main/java/io/plainround/seatwave/live/
    LiveFeedNode, ServeCli
    config/ feed/ wire/ http/ websocket/ client/ bench/
  src/main/resources/web/     the browser page, its style and its script
  src/test/java/io/plainround/seatwave/live/   the tests
  build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml, gradle.lockfile
```

## Technology notes

The `tech/` folder explains every technology this repository uses, from zero: Java, concurrency in the feed, the three real-time protocols, measuring a server, JSON and Jackson, Gradle and its wrapper scripts, the version catalog, dependency locking, the formatter, the coverage tool, the secret scanner, the test libraries, the web page and logging, properties files, Docker Compose, EditorConfig and Git. Eight more files walk through the main code and the tests line by line. Start at `tech/README.md`, which gives a reading order.

## What is simplified for teaching

- The seat store is in memory, under one lock, with Module 1's rules; production keeps the rules in the database and feeds the publisher from it (Module 27).
- One node and one event; spreading connections over nodes is Module 10.
- No authentication on the feed: seat availability is public in Module 7's API too. Authenticating long-lived connections is discussed in the module and built in Module 38.
- The WebSocket server is the course's own, for teaching; production would use a maintained server.
- The load clients run on the same machine as the server, so their CPU competes with the server's and the delays include no network distance.
- A long poll whose client has gone away is noticed only when its reply is written, up to one hold later (25 seconds with `config/serve.properties`): the JDK's HTTP server gives a handler no signal that the connection closed. Until then the request keeps its virtual thread and its place under the connection limit. The hold is bounded, so the place is always freed; a production server would watch held connections for a close.

## Continuous integration

There is no CI configuration on purpose: the course builds and checks each project locally, with the commands above.

## Docker Compose (optional)

`compose.yaml` runs `./gradlew clean check` and the comparison in `eclipse-temurin:25.0.5_8-jdk-noble` (pinned by digest, checked on Docker Hub on 11 November 2026), for readers who want the target JDK. **[not run]**: Docker was not running on the verification machine, and the course brief says not to start it.

```bash
docker compose run --rm check
docker compose run --rm compare
```

## License

MIT, see `LICENSE`. Author: Sumit Srivastava.
