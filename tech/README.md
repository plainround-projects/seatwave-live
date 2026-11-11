# Technology notes for seatwave-live

These notes teach every technology the repository uses, from zero, through the repository's own files. They assume you have never seen a terminal, a build tool, a test or Java. Each file has the same nine parts: what it is, why this repository uses it, where it appears, how it works underneath, a walkthrough of the repository's own lines, things to try, common mistakes, five questions to check yourself, and links to the official documentation.

The project is the hands-on part of Module 8 of System Design Fundamentals: a live seat-availability feed for the SeatWave ticket-booking case, delivered three ways (long polling, server-sent events, WebSockets), with a measured comparison at 1,000 clients on the loopback address. The README and `ARCHITECTURE.md` in the folder above describe the project itself.

## If you have ten minutes

1. `real-time-protocols.md`: what the three delivery methods are and what each costs.
2. `java-client-walkthrough.md`: how a client follows the feed, reconnects with backoff and jitter, and ends with a correct seat map (read the parts "How it works underneath" and the section on `FeedClient`).
3. `measuring-a-server.md`: what p50, p99, file descriptors, CPU cores and heap mean, so that the comparison table makes sense.

## Reading order

Read the ideas first, then the language, then the code of the feed, then the clients and the measuring tools, then the settings, then the build, and last the tests.

## Every file

| File | Role |
|---|---|
| `real-time-protocols.md` | The ideas: short polling, long polling, server-sent events and WebSockets, and what each costs. |
| `java.md` | Java 25, the language and the JDK, as this repository uses them. |
| `java-concurrency-in-the-feed.md` | Threads, locks, bounded queues, timeouts and interruption in the feed. |
| `result4j.md` | The result4j library at 1.2.0: a success-or-error value, and every place the main code uses it. |
| `jackson.md` | JSON from zero and the Jackson library at 2.23.0: the one mapper of the `wire` package, its records, and every place the code and the tests write or read JSON. |
| `java-feed-walkthrough.md` | Line by line: the `feed` package (store, publisher, replay buffer, subscribers, positions, metrics). |
| `java-http-and-wire-walkthrough.md` | Line by line: the root package, `config`, `http` and `wire` (the JDK HTTP server, routes, JSON). |
| `java-websocket-walkthrough.md` | Line by line: the `websocket` package (handshake, frames, session, server on the JDK sockets). |
| `java-client-walkthrough.md` | Line by line: the `client` package (three clients, backoff with jitter, the silence deadline, rebuilding a correct seat map). |
| `java-bench-walkthrough.md` | Line by line: the `bench` package (the 1,000-client comparison, percentiles, bytes on the wire, slow consumer, labs), with real runs. |
| `measuring-a-server.md` | From zero: p50 and p99, file descriptors, CPU cores used, heap against memory outside the heap, and the tools that read them. |
| `java-properties.md` | The `.properties` format and every file under `config/`, key by key. |
| `web-page-and-logging.md` | The browser page (HTML, CSS, JavaScript), its security policy, and the logging configuration. |
| `gradle.md` | Gradle 9.9.1 and the Kotlin build file: tasks, plugins, the run tasks for the tools. |
| `gradle-wrapper.md` | The Gradle Wrapper: the pinned Gradle version and how `./gradlew` fetches it. |
| `posix-shell.md` | The `gradlew` shell script, line by line. |
| `windows-batch.md` | The `gradlew.bat` script, line by line. |
| `toml-version-catalog.md` | TOML and `gradle/libs.versions.toml`: the exact versions of every library and plugin. |
| `dependency-locking.md` | The lock files that pin every resolved jar. |
| `spotless-google-java-format.md` | The formatter that `check` enforces. |
| `jacoco.md` | Line coverage and the 80 percent floor. |
| `junit.md` | JUnit Jupiter 6.1.3, the test framework. |
| `assertj.md` | AssertJ 3.27.7, the assertion library. |
| `awaitility.md` | Awaitility 4.3.0, waiting for a condition instead of sleeping. |
| `tests-feed-walkthrough.md` | Line by line: the tests of the `feed` package. |
| `tests-transports-walkthrough.md` | Line by line: the tests of the `http`, `websocket` and `client` packages (real sockets, reconnect correctness). |
| `tests-other-packages-walkthrough.md` | Line by line: the root test helpers, the `bench` and `config` tests, and the test resources. |
| `gitleaks.md` | gitleaks 8.30.1 and `.gitleaks.toml`: scanning for secrets. |
| `docker-compose.md` | The optional `compose.yaml` (not run here). |
| `editorconfig.md` | The `.editorconfig` file. |
| `git.md` | `.gitignore` and `.gitattributes`. |

The index you are reading is `README.md`.
