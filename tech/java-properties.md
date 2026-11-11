# The `.properties` format and the settings files under `config/`

## What it is

A **properties file** is a plain text file of settings. Each setting is one line, `key=value`. The key is a name. The value is the text after the first `=`. A program reads the file when it starts and uses the values to decide how to run. To change a setting you edit the text file; the program's code does not change and is not rebuilt.

The format comes from Java. The JDK class `java.util.Properties` reads it. This repository has seven such files in `config/`, one for each program it can run.

## Why this repository uses it

Everything that may differ between runs lives in one file per program: the ports, how many seat changes the simulated on-sale makes per second, how many clients to connect, how long to measure. Two rules from the README make the files strict.

- **Every key is required.** There are no defaults in the code. A missing key is a problem, never a silent fallback. This stops a program from running with a value nobody chose.
- **Every problem is reported at once.** If three keys are wrong, the program prints three lines and exits with code 2. It does not stop at the first.

I checked the second rule with a copy of `config/compare.properties` that had `compare.clients=0`, no `compare.runs` line, and `compare.childProcess=maybe`. The real output:

```text
settings: compare.clients must be from 1 to 50000, but was 0
settings: compare.runs is missing
settings: compare.childProcess must be true or false, but was maybe
```

(Gradle then printed its own "Process finished with non-zero exit value 2" failure message. Exit code 2 is what these tools return for a usage or settings error.)

## Where it appears

| File | Read by | Used for |
|---|---|---|
| `config/serve.properties` | `ServeCli` | `./gradlew -q serve --args='config/serve.properties'`: the real feed node on ports 8090 and 8091. |
| `config/compare.properties` | `ComparisonCli` (via `CompareSettings`) | `./gradlew -q compare ...`: the comparison's own settings. |
| `config/compare-server.properties` | `ComparisonCli`, then the child JVM (`ServeCli`) | The node the comparison measures, on ports 8092 and 8093. |
| `config/polling-rates.properties` | `PollingRatesCli` | Lab 1 arithmetic. |
| `config/sse-resume.properties` | `SseResumeCli` | Lab 2: a node with a small replay buffer, ports 8094 and 8095. |
| `config/slow-consumer.properties` | `SlowConsumerCli` | The slow-consumer experiment, ports 8096 and 8097. |
| `config/wire-bytes.properties` | `WireBytesCli` | Bytes on the wire, ports 8098 and 8099. |

Five of the seven (`serve`, `compare-server`, `sse-resume`, `slow-consumer`, `wire-bytes`) describe a **node**: they have the same 24 keys and are read by `LiveSettings.from`. The other two, `compare` and `polling-rates`, have their own keys and are read by `CompareSettings.from` and `PollingRatesCli.report`. All of them are read with `SettingsReader.load`, which opens the file as UTF-8.

## How it works underneath

### The file format

The rules, from `java.util.Properties.load`:

- A line that starts with `#` or `!` is a **comment**. It is ignored. A comment must be on its own line.
- A key is the text before the first `=`, `:` or white space. The value is the rest of the line. Spaces around the separator are dropped, and **trailing spaces stay part of the value**.
- A value is the whole rest of the line. `h=x # not` has the value `x # not`. A trailing comment on a value line is not a comment. This repository puts every comment on its own line, above the key it explains.
- A backslash at the end of a line continues the value on the next line.
- If a key appears twice, the last one wins.

I checked these rules with a small program on Java 25.0.2:

```text
import java.io.StringReader;
import java.util.Properties;
import java.util.TreeMap;

public class Props {
  public static void main(String[] args) throws Exception {
    Properties p = new Properties();
    p.load(new StringReader("# c\nk=v\nh=x # not\nd=1\nd=2\nsp = a b \nc: z\n! bang\nurl=http://127.0.0.1:8090\nlong=a\\\n   b\n"));
    System.out.println(new TreeMap<>(p) + " sp=[" + p.getProperty("sp") + "]");
  }
}
```

```text
{c=z, d=2, h=x # not, k=v, long=ab, sp=a b , url=http://127.0.0.1:8090} sp=[a b ]
```

The value of `url` contains `:` and `=`-free text after the first separator, and is kept whole. `sp` keeps its trailing space. `d` is 2: the last one won.

### How the program turns text into checked values

`Properties` only gives text. `SettingsReader` (in the `config` package) turns each text into a number, a flag or a pattern-checked string, and **records a problem line** instead of throwing when something is wrong:

- `intBetween(key, minimum, maximum)`: a whole number in a range. A non-number gives "must be a whole number, but was ...". Out of range gives "must be from A to B, but was N".
- `millisBetween(key, minimum, maximum)`: the same, as a duration in milliseconds.
- `flag(key)`: exactly `true` or `false`.
- `matching(key, pattern, description)`: a text that must match a pattern.
- A missing or blank key records "is missing".

After reading every key, the program checks the list of problems. If it is not empty, it prints each as `settings: <line>` and exits with code 2.

### Units

Keys that end in `Millis` are milliseconds (1,000 per second). Keys that end in `Seconds` are seconds. `compare.childHeapMegabytes` is megabytes. A rate is per second unless the key says `PerMinute`.

## Walkthrough of this repository's use

### `serve.properties`: the real node

Every counted line is quoted. The comment lines above each key are part of the file.

```properties
# The SeatWave live feed (Module 8, Section 8): ./gradlew -q serve --args='config/serve.properties'
# Every key is required; there are no defaults in code. Both servers bind to 127.0.0.1 only.
```

The first comment says which command reads the file and the second repeats the two rules: every key is required, and both servers bind to `127.0.0.1` only (the loopback address, so nothing outside the computer can connect).

```properties
http.port=8090
websocket.port=8091
```

`http.port=8090` is the port of the HTTP server, which serves long polling, server-sent events, the seat map, the browser page, health and metrics. `websocket.port=8091` is the port of the WebSocket server. A **port** is a number that tells the operating system which program on this computer should get an incoming connection. Allowed: 1,024 to 65,535 (ports below 1,024 need special rights). If you change a port, clients must use the new one; if two programs ask for the same port, the second fails to start with "address already in use". The WebSocket has its own port because the JDK's HTTP server cannot switch a connection to WebSocket.

```properties
# Connections waiting to be accepted; macOS lowers a larger value to kern.ipc.somaxconn.
http.backlog=1024
```

`http.backlog=1024` is how many connections that have arrived but not been accepted yet the operating system may queue for the HTTP server (the **listen queue**, also called the backlog). Allowed: 1 to 4,096. macOS silently lowers a bigger value to `kern.ipc.somaxconn`, which is 128 on this machine, as the comment says. A small backlog makes a burst of connections fail or wait; the comparison avoids a burst by connecting 250 clients a second.

```properties
# The one browser origin that may open the WebSocket: the page this feed serves at /.
websocket.allowedOrigin=http://127.0.0.1:8090
```

`websocket.allowedOrigin=http://127.0.0.1:8090` is the one **origin** that may open a WebSocket. An origin is the part of a web address that names a site: scheme, host and port. A browser sends it in an `Origin` header with the opening request, so that a page on another site cannot make a visitor's browser connect to this feed. The value must match `http://127.0.0.1:<port>`. A request from another origin gets `403`. The value is the page this feed serves at `/`, so the port repeats `http.port`: if you change one, change the other.

```properties
# Open feed connections, all methods together; one more gets 503 and Retry-After.
feed.maxConnections=5000
```

`feed.maxConnections=5000` is the most feed connections open at once, all three methods together. Allowed: 1 to 100,000. The next one is refused with `503` and a `Retry-After` header. A lower number protects the server's memory and file descriptors; a number above the process's file-descriptor limit would be pointless. The comparison's 1,000 clients fit easily.

```properties
# 50,000 changes: at Section 7's 3,000 changes per second, about 16 seconds of history for resumes.
feed.replayCapacity=50000
```

`feed.replayCapacity=50000` is the size of the **replay buffer**: how many of the most recent changes the server remembers for clients that reconnect. The comment gives the arithmetic: at 3,000 changes per second, 50,000 changes are about 16 seconds of history. A client that was away longer cannot be given what it missed and is told to reload the seat map. Allowed: 1 to 10,000,000. A bigger buffer allows longer absences and uses more memory; `sse-resume.properties` sets it to 200 to show the reload.

```properties
# Changes one push connection may have waiting before it is told to reload.
feed.clientQueueCapacity=1024
```

`feed.clientQueueCapacity=1024` is the **bounded queue** each push connection (SSE or WebSocket) has. The server puts every change into the queue of every push connection; a writer thread takes batches out and writes them. If a client is so slow that its queue fills, the queue is emptied and the client is told to reload, so memory per slow client stays bounded. Allowed: 1 to 100,000. Lower values cut off slow clients sooner; `slow-consumer.properties` uses 256.

```properties
feed.maxBatch=256
```

`feed.maxBatch=256` is the most changes one write (one batch of events or frames) or one long-poll reply may carry. Allowed: 1 to 10,000. A bigger batch means fewer writes under load and a bigger single reply; 1 would mean one write per change.

```properties
# The WHATWG standard suggests a comment line about every 15 seconds for proxies that drop idle links.
feed.heartbeatMillis=15000
```

`feed.heartbeatMillis=15000` is how often an idle push connection gets a keep-alive: a comment line on an event stream, a ping on a WebSocket. The comment cites the WHATWG standard's suggestion of about every 15 seconds, so proxies and phone networks do not drop an idle link. Allowed: 1 to 600,000. The comparison's client treats 45 seconds of silence (three heartbeats) as a dead link.

```properties
# One write blocked this long means the client stopped reading: drop it.
feed.writeTimeoutMillis=5000
```

`feed.writeTimeoutMillis=5000` is how long one write may stay blocked. "One write blocked this long means the client stopped reading: drop it." A background check, run four times per timeout, finds stuck writers and closes their connections. Allowed: 1 to 600,000. Shorter cuts dead clients sooner but may cut a client on a bad network; `slow-consumer.properties` uses 2,000.

```properties
# A long poll is held 25 s, under the 30 s idle timeout many proxies use.
feed.longPollHoldMillis=25000
```

`feed.longPollHoldMillis=25000` is how long a long poll with nothing to report is held open before it is answered with an empty reply. The comment explains the choice: under the 30 seconds idle timeout that many proxies use. Allowed: 1 to 600,000. The client's request timeout must be longer than this value (`compare.requestTimeoutMillis=30000`).

```properties
# The reconnect delay sent to event-stream clients, and the Retry-After of a refusal.
feed.retryMillis=1000
```

`feed.retryMillis=1000` is the reconnect delay the server tells event-stream clients to use (the `retry: 1000` line at the start of every stream) and the `Retry-After` value of a refusal. Allowed: 1 to 600,000.

```properties
# Module 1's arena: 20 sections of 1,000 seats.
store.sections=20
store.seatsPerSection=1000
```

`store.sections=20` and `store.seatsPerSection=1000` give the seat arena of Module 1: 20 sections of 1,000 seats, 20,000 seats. The most allowed is exactly that (20 and 1,000), so the seat map and the labels (`A-1` to `T-1000`) stay in one known shape.

```properties
# Module 1's 300-second hold.
store.holdSeconds=300
```

`store.holdSeconds=300` is how long a held seat stays held before it expires back to available: Module 1's 300 seconds. Allowed: 1 to 86,400. A shorter hold makes expiry changes frequent; the comparison sets 4 so that expiries happen inside a 20-second run.

```properties
onSale.actionsPerSecond=20
onSale.confirmPercent=30
onSale.seed=8
```

`onSale.actionsPerSecond=20` is how many holds or confirms the simulated on-sale makes each second (allowed: 1 to 5,000); this is the main knob for the rate of seat changes. `onSale.confirmPercent=30` is the share of actions that confirm one of the simulator's holds instead of making a new hold (0 to 100). `onSale.seed=8` seeds the random choices of seats, so two runs make the same choices in the same order (0 to 2,147,483,647).

```properties
admin.enabled=false
```

`admin.enabled=false` turns the admin routes off. When true, `POST /admin/pause`, `/admin/resume`, `/admin/drop-connections` and `/admin/settle` work, which the comparison needs. Only `true` and `false` are accepted. Keep it false on a node anyone else can reach.

```properties
serve.seconds=600
```

`serve.seconds=600` is how long `serve` runs before it stops by itself (1 to 86,400 seconds). The startup line says "for 600 s".

### `compare-server.properties`: the node the comparison measures

The comparison starts this node in a JVM of its own for every run. It differs from `serve.properties` in the ports (8092 and 8093, so it does not clash with a running real node), the hold time, the on-sale rate and `admin.enabled`.

```properties
# The node the comparison measures (Module 8, Section 8). The comparison starts it as a separate
# process for every run, so each run begins with a fresh JVM. Every key is required.
```

The comment says why a fresh process per run: each run begins with a new JVM, so one run's memory and JIT state cannot affect the next.

```properties
http.port=8092
websocket.port=8093
```

`http.port=8092` and `websocket.port=8093`: this node's ports, away from 8090 and 8091 so both can run at once. The comparison's clients take them from this file.

```properties
http.backlog=1024
```

`http.backlog=1024`: as in `serve.properties` (lowered to 128 by the system).

```properties
websocket.allowedOrigin=http://127.0.0.1:8092
```

`websocket.allowedOrigin=http://127.0.0.1:8092`: the origin of this node's page (its own HTTP port). The Java clients send no browser origin; the key is still required.

```properties
feed.maxConnections=5000
```

`feed.maxConnections=5000`: room for 1,000 clients, plus the metrics and admin requests, with a wide margin.

```properties
feed.replayCapacity=50000
```

`feed.replayCapacity=50000`: as the real node.

```properties
feed.clientQueueCapacity=1024
```

`feed.clientQueueCapacity=1024`: as the real node. At about 75 changes a second a client would need to be 13 seconds behind to fill it.

```properties
feed.maxBatch=256
```

`feed.maxBatch=256`: as the real node.

```properties
feed.heartbeatMillis=15000
```

`feed.heartbeatMillis=15000`: heartbeat every 15 seconds. `compare.silenceLimitMillis=45000` is three of these.

```properties
feed.writeTimeoutMillis=5000
```

`feed.writeTimeoutMillis=5000`: as the real node.

```properties
feed.longPollHoldMillis=25000
```

`feed.longPollHoldMillis=25000`: as the real node.

```properties
feed.retryMillis=1000
```

`feed.retryMillis=1000`: as the real node.

```properties
store.sections=20
store.seatsPerSection=1000
```

`store.sections=20` and `store.seatsPerSection=1000`: the full arena of 20,000 seats.

```properties
# Compressed from Module 1's 300 seconds so that holds expire inside a run: the feed carries
# expiries as well as holds and confirms. The feed does not care how long a hold lasts.
store.holdSeconds=4
```

`store.holdSeconds=4`: compressed from 300 so that holds expire inside a run. The feed then carries expiries as well as holds and confirms. The comment adds that the feed does not care how long a hold lasts.

```properties
# 50 holds or confirms per second, about 75 seat changes per second with the expiries; at 1,000
# clients that is about 75,000 deliveries per second.
onSale.actionsPerSecond=50
```

`onSale.actionsPerSecond=50`: 50 holds or confirms a second. With expiries that makes about 75 seat changes a second, and with 1,000 clients about 75,000 deliveries a second, as the comment calculates.

```properties
onSale.confirmPercent=30
onSale.seed=8
```

`onSale.confirmPercent=30` and `onSale.seed=8`: as the real node.

```properties
# The comparison pauses the on-sale, drops every connection and reads the heap through these.
admin.enabled=true
```

`admin.enabled=true`: the comparison needs the admin routes to pause the on-sale, drop every connection and read the heap. This is why the file is separate from `serve.properties`.

```properties
serve.seconds=600
```

`serve.seconds=600`: the child stops itself after 10 minutes if the comparison dies without stopping it.

### `compare.properties`: the comparison's own settings

This file is read by `CompareSettings.from` (see `java-bench-walkthrough.md`). Allowed ranges come from that class.

```properties
# The live-feed comparison (Module 8, Section 8 and Lab 3):
# ./gradlew -q compare --args='config/compare.properties'
```

The comment names the command: `./gradlew -q compare --args='config/compare.properties'`.

```properties
compare.serverSettings=config/compare-server.properties
```

`compare.serverSettings=config/compare-server.properties` is the path of the node's settings file. It must match `[A-Za-z0-9_./-]{1,200}`: letters, digits, `_`, `.`, `/` and `-` only.

```properties
compare.methods=long-polling,sse,websocket
```

`compare.methods=long-polling,sse,websocket` lists the delivery methods to measure, in this order. Only those three labels, comma-separated, are valid. Remove one to skip it; reorder to change the order of the report.

```properties
compare.clients=1000
```

`compare.clients=1000` is clients per method (1 to 50,000). More clients raise every cost. Above the process's file-descriptor limit, connections fail (see `measuring-a-server.md`).

```properties
compare.runs=3
```

`compare.runs=3` is runs per method (1 to 20). The report shows each run and the median.

```properties
compare.warmupSeconds=5
compare.measureSeconds=15
```

`compare.warmupSeconds=5` and `compare.measureSeconds=15`: the warm-up (changes flow, delays not recorded) and the measured phase, each 1 to 3,600 seconds. A longer measure gives more samples (at 69 changes a second and 1,000 clients, about a million delays per second) and more stable percentiles; a shorter warm-up risks measuring before the JIT has settled.

```properties
# The longest wait for every client to connect, catch up or recover; past it the run goes on and
# the report shows how many did.
compare.settleTimeoutSeconds=60
```

`compare.settleTimeoutSeconds=60` is the longest wait for every client to connect, catch up or recover (1 to 3,600). Past it the run goes on and the report shows how many clients did. Raise it for slower machines or more clients.

```properties
# 1,000 clients at 250 per second connect in 4 seconds, inside the accept backlog.
compare.connectsPerSecond=250
```

`compare.connectsPerSecond=250` is how fast the fleet starts clients (1 to 10,000): 1,000 clients take 4 seconds, "inside the accept backlog". Faster risks overflowing the listen queue (128 here).

```properties
compare.connectTimeoutMillis=2000
```

`compare.connectTimeoutMillis=2000` is how long a client waits for a TCP connection to open (1 to 600,000 ms).

```properties
# Longer than the server's 25-second long-poll hold, so a held poll is never cut by the client.
compare.requestTimeoutMillis=30000
```

`compare.requestTimeoutMillis=30000` is the longest a client waits for a reply or for a stream's headers. It is "longer than the server's 25-second long-poll hold, so a held poll is never cut by the client". If you set it below the hold, every quiet long poll would fail with a timeout.

```properties
# Three of the server's 15-second heartbeats: a push link silent this long is dropped and reopened.
compare.silenceLimitMillis=45000
```

`compare.silenceLimitMillis=45000`: "three of the server's 15-second heartbeats". A push link silent this long is dropped and reopened by the client's watchdog.

```properties
# Reconnects back off from 100 ms, doubling, to 5 s at most, with full jitter.
compare.backoffBaseMillis=100
compare.backoffCapMillis=5000
```

`compare.backoffBaseMillis=100` and `compare.backoffCapMillis=5000`: reconnect waits start with a ceiling of 100 ms, double, and stop at 5,000 ms, with full jitter. **The cap must be at least the base.** The settings reader does not check this: with `backoffCapMillis=50` the settings are accepted and the program then stops with `IllegalArgumentException: base must be positive and cap at least base` from `Backoff` (I ran it).

```properties
compare.childProcess=true
```

`compare.childProcess=true` runs the node in a JVM of its own (`true` for real numbers). `false` runs it in this JVM and the process numbers then include the clients.

```properties
compare.childHeapMegabytes=1024
```

`compare.childHeapMegabytes=1024` is the child's fixed heap (1 to 32,768): both `-Xms` and `-Xmx`, with the heap pre-touched.

```properties
compare.output=build/compare
```

`compare.output=build/compare` is the folder for the CSV and the node's logs. It must match the same path pattern as `compare.serverSettings`.

### `polling-rates.properties`: Lab 1

```properties
# Lab 1 (easy): ./gradlew -q pollingRates --args='config/polling-rates.properties'
# Module 2's 200,000 fans, SIMPLE's 5-second refresh, Module 5's 80 ms distant fan, a 25 s hold.
```

The comment ties the numbers to earlier modules: Module 2's 200,000 fans, the 5-second refresh of the simple design, Module 5's 80 ms distant fan, and a 25-second hold.

```properties
rates.fans=200000
```

`rates.fans=200000` is how many people watch (1 to 100,000,000). Every rate in the table scales with it.

```properties
rates.pollIntervalMillis=5000
```

`rates.pollIntervalMillis=5000` is how often a short-polling fan asks (1 to 3,600,000 ms). Halving it doubles the short-polling requests.

```properties
rates.roundTripMillis=80
```

`rates.roundTripMillis=80` is the network round trip of one request, from Module 5. A long poll's next request leaves one round trip after the last reply, so a bigger round trip lowers the long-polling request rate.

```properties
rates.longPollHoldMillis=25000
```

`rates.longPollHoldMillis=25000` is how long the server holds an empty long poll. A longer hold cuts the rate on a quiet evening.

```properties
# Section 7's 3,000 seat changes per second during the on-sale (180,000 per minute), against one
# change a minute on a quiet evening.
rates.busyChangesPerMinute=180000
```

`rates.busyChangesPerMinute=180000` is 3,000 changes a second (180,000 / 60) during the on-sale (1 to 10,000,000).

```properties
rates.quietChangesPerMinute=1
```

`rates.quietChangesPerMinute=1` is one change a minute on a quiet evening.

### `sse-resume.properties`, `slow-consumer.properties`, `wire-bytes.properties`: node settings for the labs

These three have the same shape as `serve.properties` (the same 24 keys, in the same lines). Only the values below differ. All three bind to ports of their own so that they run beside a real node.

| Key | serve | sse-resume | slow-consumer | wire-bytes | Why it differs |
|---|---|---|---|---|---|
| `http.port` / `websocket.port` | 8090 / 8091 | 8094 / 8095 | 8096 / 8097 | 8098 / 8099 | Each program has its own pair. |
| `websocket.allowedOrigin` | `...:8090` | `...:8094` | `...:8096` | `...:8098` | Must repeat that file's `http.port`. |
| `feed.replayCapacity` | 50000 | 200 | 50000 | 50000 | Lab 2: a small buffer so 500 missed changes overflow it. |
| `feed.clientQueueCapacity` | 1024 | 1024 | 256 | 1024 | The slow client must fill its queue within seconds. |
| `feed.writeTimeoutMillis` | 5000 | 5000 | 2000 | 5000 | A stuck write is cut sooner. |
| `store.holdSeconds` | 300 | 300 | 2 | 300 | Holds expire quickly, so the store keeps changing. |
| `onSale.actionsPerSecond` | 20 | 50 | 500 | 20 | The slow client needs fast changes to fall behind. |

Every counted line of the three files is quoted below, key group by key group, with the value and the reason. The comment lines are part of each file.

#### `sse-resume.properties`

```properties
# Lab 2 (medium): ./gradlew -q sseResume --args='config/sse-resume.properties 500'
# Every key is required; there are no defaults in code. Both servers bind to 127.0.0.1 only.
```

The header comment names Lab 2 (`sseResume`) and repeats the two rules.

```properties
http.port=8094
websocket.port=8095
```

`http.port=8094` and `websocket.port=8095`: this lab's pair of ports.

```properties
# Connections waiting to be accepted; macOS lowers a larger value to kern.ipc.somaxconn.
http.backlog=1024
```

`http.backlog=1024`: as `serve.properties`.

```properties
# The one browser origin that may open the WebSocket: the page this feed serves at /.
websocket.allowedOrigin=http://127.0.0.1:8094
```

`websocket.allowedOrigin=http://127.0.0.1:8094`: repeats this file's HTTP port.

```properties
# Open feed connections, all methods together; one more gets 503 and Retry-After.
feed.maxConnections=5000
```

`feed.maxConnections=5000`: as `serve.properties`; the lab opens one connection.

```properties
# Lab 2: a small buffer of 200 changes, so a client away for 500 changes must reload.
feed.replayCapacity=200
```

`feed.replayCapacity=200`: the point of the lab. The comment: "a small buffer of 200 changes, so a client away for 500 changes must reload". It must be below the number of changes made while away (the second argument, 500).

```properties
# Changes one push connection may have waiting before it is told to reload.
feed.clientQueueCapacity=1024
```

`feed.clientQueueCapacity=1024`: as `serve.properties`.

```properties
feed.maxBatch=256
```

`feed.maxBatch=256`: as `serve.properties`.

```properties
# The WHATWG standard suggests a comment line about every 15 seconds for proxies that drop idle links.
feed.heartbeatMillis=15000
```

`feed.heartbeatMillis=15000`: as `serve.properties`.

```properties
# One write blocked this long means the client stopped reading: drop it.
feed.writeTimeoutMillis=5000
```

`feed.writeTimeoutMillis=5000`: as `serve.properties`.

```properties
# A long poll is held 25 s, under the 30 s idle timeout many proxies use.
feed.longPollHoldMillis=25000
```

`feed.longPollHoldMillis=25000`: as `serve.properties` (the lab uses event streams only).

```properties
# The reconnect delay sent to event-stream clients, and the Retry-After of a refusal.
feed.retryMillis=1000
```

`feed.retryMillis=1000`: as `serve.properties`.

```properties
# Module 1's arena: 20 sections of 1,000 seats.
store.sections=20
store.seatsPerSection=1000
```

`store.sections=20` and `store.seatsPerSection=1000`: the full arena.

```properties
# Module 1's 300-second hold.
store.holdSeconds=300
```

`store.holdSeconds=300`: as `serve.properties`; no hold expires during the lab, so every change is a hold or a confirm.

```properties
onSale.actionsPerSecond=50
onSale.confirmPercent=30
onSale.seed=8
```

`onSale.actionsPerSecond=50`: 50 actions a second, so the two 1-second gaps between drops produce about 50 changes each.

```properties
admin.enabled=false
```

`admin.enabled=false`: the lab drops connections through the node's object, not through an admin route.

```properties
serve.seconds=600
```

`serve.seconds=600`: unused by the lab, required by the reader.

#### `slow-consumer.properties`

```properties
# The slow-consumer experiment: ./gradlew -q slowConsumer --args='config/slow-consumer.properties 50 10'
# Every key is required; there are no defaults in code. Both servers bind to 127.0.0.1 only.
```

The header comment names the experiment and its command with the default arguments `50 10` (50 normal clients, 10 seconds).

```properties
http.port=8096
websocket.port=8097
```

`http.port=8096` and `websocket.port=8097`.

```properties
# Connections waiting to be accepted; macOS lowers a larger value to kern.ipc.somaxconn.
http.backlog=1024
```

`http.backlog=1024`: as `serve.properties`.

```properties
# The one browser origin that may open the WebSocket: the page this feed serves at /.
websocket.allowedOrigin=http://127.0.0.1:8096
```

`websocket.allowedOrigin=http://127.0.0.1:8096`: repeats the HTTP port. The slow WebSocket client sends no `Origin` header, which the server accepts.

```properties
# Open feed connections, all methods together; one more gets 503 and Retry-After.
feed.maxConnections=5000
```

`feed.maxConnections=5000`: as `serve.properties`.

```properties
# 50,000 changes: at Section 7's 3,000 changes per second, about 16 seconds of history for resumes.
feed.replayCapacity=50000
```

`feed.replayCapacity=50000`: as `serve.properties`.

```properties
# Changes one push connection may have waiting before it is told to reload.
feed.clientQueueCapacity=256
```

`feed.clientQueueCapacity=256`: a smaller queue (256 instead of 1,024) so that a client that never reads falls 256 changes behind within a second at 500 actions a second. The printed bound is 256.

```properties
feed.maxBatch=256
```

`feed.maxBatch=256`: as `serve.properties`.

```properties
# The WHATWG standard suggests a comment line about every 15 seconds for proxies that drop idle links.
feed.heartbeatMillis=15000
```

`feed.heartbeatMillis=15000`: as `serve.properties`.

```properties
# One write blocked this long means the client stopped reading: drop it.
feed.writeTimeoutMillis=2000
```

`feed.writeTimeoutMillis=2000`: a stuck write is cut after 2 seconds instead of 5, so the experiment shows the cut within a 10-second run.

```properties
# A long poll is held 25 s, under the 30 s idle timeout many proxies use.
feed.longPollHoldMillis=25000
```

`feed.longPollHoldMillis=25000`: as `serve.properties`.

```properties
# The reconnect delay sent to event-stream clients, and the Retry-After of a refusal.
feed.retryMillis=1000
```

`feed.retryMillis=1000`: as `serve.properties`.

```properties
# Module 1's arena: 20 sections of 1,000 seats.
store.sections=20
store.seatsPerSection=1000
```

`store.sections=20` and `store.seatsPerSection=1000`: the full arena.

```properties
# Module 1's 300-second hold.
store.holdSeconds=2
```

`store.holdSeconds=2`: holds expire after 2 seconds, so the store keeps changing (holds, then expiries) even when the simulator's random seats run into held ones.

```properties
onSale.actionsPerSecond=500
onSale.confirmPercent=30
onSale.seed=8
```

`onSale.actionsPerSecond=500`: ten times the real rate, to fill the slow clients' queues quickly. (Real output: 6,302 changes published in 10 seconds.)

```properties
admin.enabled=false
```

`admin.enabled=false`: not needed; the experiment reads the node's metrics directly.

```properties
serve.seconds=600
```

`serve.seconds=600`: unused by the experiment.

#### `wire-bytes.properties`

```properties
# Bytes on the wire per change: ./gradlew -q wireBytes --args='config/wire-bytes.properties 200'
# Every key is required; there are no defaults in code. Both servers bind to 127.0.0.1 only.
```

The header comment names the wire-bytes probe and its command with 200 changes.

```properties
http.port=8098
websocket.port=8099
```

`http.port=8098` and `websocket.port=8099`: the probe puts a relay in front of each of these; its clients use the relays' ports.

```properties
# Connections waiting to be accepted; macOS lowers a larger value to kern.ipc.somaxconn.
http.backlog=1024
```

`http.backlog=1024`: as `serve.properties`.

```properties
# The one browser origin that may open the WebSocket: the page this feed serves at /.
websocket.allowedOrigin=http://127.0.0.1:8098
```

`websocket.allowedOrigin=http://127.0.0.1:8098`: repeats the HTTP port.

```properties
# Open feed connections, all methods together; one more gets 503 and Retry-After.
feed.maxConnections=5000
```

`feed.maxConnections=5000`: as `serve.properties`; the probe uses one connection.

```properties
# 50,000 changes: at Section 7's 3,000 changes per second, about 16 seconds of history for resumes.
feed.replayCapacity=50000
```

`feed.replayCapacity=50000`: as `serve.properties`.

```properties
# Changes one push connection may have waiting before it is told to reload.
feed.clientQueueCapacity=1024
```

`feed.clientQueueCapacity=1024`: as `serve.properties`.

```properties
feed.maxBatch=256
```

`feed.maxBatch=256`: as `serve.properties`. The probe makes one change at a time, so batches hold one change.

```properties
# The WHATWG standard suggests a comment line about every 15 seconds for proxies that drop idle links.
feed.heartbeatMillis=15000
```

`feed.heartbeatMillis=15000`: as `serve.properties`. A heartbeat inside the counted part would add a few bytes per 15 seconds.

```properties
# One write blocked this long means the client stopped reading: drop it.
feed.writeTimeoutMillis=5000
```

`feed.writeTimeoutMillis=5000`: as `serve.properties`.

```properties
# A long poll is held 25 s, under the 30 s idle timeout many proxies use.
feed.longPollHoldMillis=25000
```

`feed.longPollHoldMillis=25000`: as `serve.properties`.

```properties
# The reconnect delay sent to event-stream clients, and the Retry-After of a refusal.
feed.retryMillis=1000
```

`feed.retryMillis=1000`: as `serve.properties`.

```properties
# Module 1's arena: 20 sections of 1,000 seats.
store.sections=20
store.seatsPerSection=1000
```

`store.sections=20` and `store.seatsPerSection=1000`: the full arena.

```properties
# Module 1's 300-second hold.
store.holdSeconds=300
```

`store.holdSeconds=300`: as `serve.properties`.

```properties
onSale.actionsPerSecond=20
onSale.confirmPercent=30
onSale.seed=8
```

`onSale.actionsPerSecond=20`: not used by the probe, which never starts the on-sale; it makes its own changes with `store.hold`. The key is still required.

```properties
admin.enabled=false
```

`admin.enabled=false`: not needed.

```properties
serve.seconds=600
```

`serve.seconds=600`: unused by the probe.

## Try it yourself

1. Read the format rules from a real run. Save the `Props` program above as `Props.java` and run `java Props.java`. The output is the line shown above.

2. See every problem at once. Copy a settings file, break three keys and run the tool. I broke `config/compare.properties` as described in "Why this repository uses it" and got the three-line output shown there.

3. See how one value (the run time, an argument) changes the result. Run `./gradlew -q slowConsumer --args='config/slow-consumer.properties 10 5'` and then the same with `10 10`. With 5 seconds the queue had not yet filled (`deepest client queue: 11 (bound 256)`, `queue overflows: 0`, `stuck writes cut: 0`); with 10 seconds it had (`deepest client queue: 256 (bound 256)`, `queue overflows: 2`, `stuck writes cut: 2`). The settings in the file decide how fast: with `feed.clientQueueCapacity=256` and 500 actions a second the queue fills only when the slow client's kernel buffers are also full.

4. Check the cap rule. In a copy of `config/compare.properties`, set `compare.clients=10`, `compare.runs=1`, `compare.methods=sse` and `compare.backoffCapMillis=50`. The comparison starts a child node and then stops with the `IllegalArgumentException` shown above. (It stops the child node as it goes; I confirmed none was left running.)

Numbers and timings differ on every run.

## Mistakes people make

- **A comment at the end of a value line.** `feed.maxBatch=256 # most` would make the value `256 # most`, which is not a number. Put comments on their own line.
- **Quoting values.** `http.port="8090"` keeps the quotes in the value.
- **Changing one of a pair.** `websocket.allowedOrigin` repeats `http.port`; change one and the browser page gets a 403.
- **Two programs on one port.** Each file here has its own pair of ports; if you copy a file, change them.
- **A request timeout under the long-poll hold.** Every quiet long poll would then time out.
- **Setting the backoff cap below the base.** Accepted by the reader, fatal later.
- **Trailing spaces.** They stay in the value (the reader strips them for its own keys, but the plain `Properties` class does not).

## Check yourself

1. What is the value of `h` in `h=x # not`? (`x # not`: the comment sign does not start a comment on a value line.)
2. Why are there no defaults in the code? (A missing key must be a visible problem, not a silent value nobody chose.)
3. What does `feed.clientQueueCapacity` bound, and what happens when it is reached? (The changes waiting for one push connection; the queue is emptied and the client is told to reload.)
4. Why does `compare-server.properties` set `admin.enabled=true` while `serve.properties` sets it to false? (The comparison needs the pause, drop and settle routes; a real node should not expose them.)
5. Why is `compare.requestTimeoutMillis` 30,000 when the hold is 25,000? (A held poll must end by the server's hold before the client's timeout, so the client never cuts a healthy poll.)

## Further reading

- `java.util.Properties`, JDK 25 API (the exact format, in the `load(Reader)` method): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- `java.time.Duration`, JDK 25 API: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Duration.html
- WHATWG HTML Living Standard, "Server-sent events" (reconnection time, the `retry` field, keep-alive comments): https://html.spec.whatwg.org/multipage/server-sent-events.html
- RFC 6455, section 10.2 on the `Origin` header: https://www.rfc-editor.org/rfc/rfc6455#section-10.2
