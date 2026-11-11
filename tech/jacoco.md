# JaCoCo 0.8.16 (code coverage)

## What it is

**Code coverage** answers one question: when the tests ran, which lines of the program were executed at least once? A line that no test ever ran is a line whose behavior nobody has checked. **JaCoCo** (Java Code Coverage) is the tool that measures it for Java. It does not tell you whether the tests are good, only which code they touched.

JaCoCo works with a **Java agent**: a small jar that the Java Virtual Machine (JVM) loads before the program starts. The agent watches classes as they are loaded and records which parts run.

## Why this repository uses it

The build has a measured floor: line coverage must be at least 0.80, and the build fails below it. That keeps the tests honest as the code grows. The README reports "Coverage was 90.3 percent of lines (2,266 of 2,509)", and a run in a scratch copy for these notes gave the same numbers.

## Where it appears

| Path | What it does with JaCoCo |
| --- | --- |
| `build.gradle.kts` | Switches the plugin on (`jacoco`), pins its version, wires the report after the tests, sets the 0.80 rule and adds it to `check`. It also switches on the XML report. |
| `gradle/libs.versions.toml` | `jacoco = "0.8.16"`. |
| `gradle.lockfile` | Locks `jacocoAgent` and `jacocoAnt` (`dependency-locking.md`). |
| `build/jacoco/test.exec` | The raw recording of one test run (a binary file). |
| `build/reports/jacoco/test/html/index.html` | The readable report. |
| `build/reports/jacoco/test/jacocoTestReport.xml` | The same numbers as XML, written because of `xml.required = true`. |

## How it works underneath

1. `./gradlew test` starts a new JVM for the tests and passes it the option `-javaagent:` with the JaCoCo agent jar (the `jacocoAgent` configuration). Gradle does this by itself once the `jacoco` plugin is on.
2. As each class loads, the agent inserts small recording instructions into its bytecode, in memory only. The files on disk do not change.
3. When the JVM ends, the agent writes everything it recorded to `build/jacoco/test.exec`.
4. The task `jacocoTestReport` (`finalizedBy` on `test`, so it always runs afterwards) reads `test.exec` plus the compiled classes in `build/classes/java/main` and writes the HTML report and, here, the XML report. It uses the `jacocoAnt` configuration.
5. The task `jacocoTestCoverageVerification` reads the same data and applies the rules. If a rule is violated, the task fails and so does `check`.

JaCoCo counts several things. The four that matter here:
- **Instructions**: single bytecode instructions. The most fine-grained number.
- **Branches**: each outcome of an `if`, a `switch` or a `&&`. A branch is covered when both outcomes were taken.
- **Lines**: a source line counts as covered if at least one of its instructions ran. This is the counter the build's rule uses.
- **Methods** and **classes**: counted as covered if any part of them ran.

Only code under `src/main/java` is measured. The test classes themselves are not. Code that runs in another process is not measured either: the real comparison starts the feed in a JVM of its own (`ChildProcessServer`), and that child is a different process without the agent, so what it runs does not count.

## Walkthrough of this repository's use

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`toolVersion = libs.versions.jacoco.get()` fixes JaCoCo at 0.8.16. Without it, Gradle would use its own default.

```kotlin
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}
```

`finalizedBy(tasks.jacocoTestReport)` is what writes the report after every test run. The rest of the test task is explained in `gradle.md`.

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

The task `jacocoTestReport` writes the HTML report by default. `xml.required = true` adds an XML file, `build/reports/jacoco/test/jacocoTestReport.xml`. The comment above the block in the build file says why: the XML gives the exact covered and missed line counts that the README quotes. Near its end the XML holds one element per counter: `<counter type="LINE" missed="243" covered="2266"/>` (the last such element is the total of the whole project; the ones before it belong to single packages, classes and methods).

```kotlin
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

`counter = "LINE"` picks the line counter. `minimum = "0.80".toBigDecimal()` is the lowest accepted ratio of covered lines to all lines. The comment above it names the untested remainder: the command-line entry points. `tasks.check { dependsOn(...) }` makes the rule part of `check`. When I raised the minimum to 0.95 in a scratch copy, the build failed with `Rule violated for bundle seatwave-live: lines covered ratio is 0.90, but expected minimum is 0.95`.

### The real numbers (Temurin 25.0.2, 93 tests)

I ran `./gradlew clean check` in a scratch copy and read `build/reports/jacoco/test/jacocoTestReport.xml` and the HTML report. The last row of the HTML table, "Total", reads:

| Counter | Missed | Total | Covered |
| --- | --- | --- | --- |
| Instructions | 1,119 | 12,557 | 91 percent (11,438 covered) |
| Branches | 193 | 727 | 73 percent (534 covered) |
| Lines | 243 | 2,509 | 2,266 lines, 90.3 percent |
| Methods | 25 | 473 | 448 |
| Classes | 4 | 102 | 98 |
| Complexity | 193 | 845 | 652 |

The rule needs 0.80 of 2,509 lines, which is 2,007.2, so at least 2,008 covered lines. The build has 2,266. **The margin is 258 lines.** The README's figures (93 tests, 90.3 percent, 2,266 of 2,509) match this run.

Per package, the missed lines (from the XML report, class by class):

| Package | Missed lines | Total lines | Where |
| --- | --- | --- | --- |
| `live` (the root package) | 22 | 63 | All 22 lines of `ServeCli` (the node that runs until its serve time ends). |
| `config` | 10 | 114 | Three in the `Network` record of `LiveSettings`, two in `SettingsReader`, two in the `OnSale` record, and one each in the `Limits` record, the `Timing` record and `LiveSettings` itself. |
| `feed` | 9 | 444 | Two each in `SeatChange`, `SeatMapSnapshot` and `OnSaleSimulator`, and one each in `SeatStore`, `Subscriber` and `ConnectionLimit`. |
| `wire` | 3 | 33 | Three in `FeedJson`. |
| `http` | 9 | 282 | Four in `FeedHttpServer`, two each in `LongPollRoute` and `EventStreamRoute`, one in `Query`. |
| `websocket` | 16 | 302 | Ten in `WebSocketSession`, three in `Handshake`, two in `FrameCodec`, one in `WebSocketServer`. |
| `client` | 59 | 398 | `WebSocketConnection` 14 (and 5 more in its inner `Listener`), `EventStreamConnection` 11, `FeedClient` 10 (and 3 in its inner `Sink`), `LongPollConnection` 6, `ClientStats` 5, `SeatMapReader` 3, and one each in `FleetPlan` and `FeedEndpoints`. |
| `bench` | 115 | 873 | `SlowConsumerCli` 19, `ComparisonCli` 18, `WireBytesCli` 17, `SseResumeCli` 13, `ChildProcessServer` 11, `PollingRatesCli` 11, `Comparison` 9, `FeedAdmin` 6, `ProcessMemory` 3, two each in `CompareSettings` and `PollingRates`, one each in `InProcessServer`, `LatencySummary`, `WireBytesProbe` and `SlowConsumer`. |

The command-line entry points explain 100 of the 243 missed lines: `ServeCli` 22, `SlowConsumerCli` 19, `ComparisonCli` 18, `WireBytesCli` 17, `SseResumeCli` 13 and `PollingRatesCli` 11. `Classes` shows 4 missed of 102, and they are `ServeCli`, `SlowConsumerCli`, `WireBytesCli` and `SseResumeCli`, the four whose `main` method is all they have. `ComparisonCli` and `PollingRatesCli` are only partly missed because the tests call their `run` and `report` methods directly. `ServeCli` is a good example of what the report cannot see: one test (`startsTheFeedInAProcessOfItsOwnAndReadsItsMemory`) starts it, but in a child JVM that has no JaCoCo agent, so it shows 0 of 22 lines covered although it did run. The other 143 missed lines are spread over the classes in the table, mostly one to fourteen lines each; I did not read each one. The comparison test runs with `childProcess` set to `false`, so the lines of `Comparison`, `ChildProcessServer` and `ProcessMemory` that handle the separate process are among the missed ones.

The heart of the project is covered almost completely: the `feed` package misses 9 of 444 lines, the `websocket` package 16 of 302 and the `http` package 9 of 282.

### Reading the report

Open `index.html`. Each row is a package; click it for its classes, click a class for its methods, click a method for the source with colors: green lines ran, red lines never ran, yellow lines ran only partly (a branch outcome was never taken).

## Try it yourself

All of these ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run the gate and the report:

```
./gradlew clean check
```

   Expected: 93 `PASSED` lines, `> Task :jacocoTestCoverageVerification`, `BUILD SUCCESSFUL in 25s` and `10 actionable tasks: 10 executed`.

2. Print the totals from the HTML report:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  1,119 of 12,557  91%  193 of 727  73%  193  845  243  2,509  25  473  4  102`.

3. Print the total line counter from the XML report:

```
grep -o '<counter type="LINE"[^>]*>' build/reports/jacoco/test/jacocoTestReport.xml | tail -1
```

   Expected: `<counter type="LINE" missed="243" covered="2266"/>`.

4. Print the totals of the `bench` package:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/io.plainround.seatwave.live.bench/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  606 of 4,913  87%  87 of 196  55%  80  269  115  873  12  171  3  30`. The `bench` package has 873 lines, 115 of them missed, and three missed classes (`SlowConsumerCli`, `WireBytesCli` and `SseResumeCli`; the fourth missed class of the whole project, `ServeCli`, is in the root package).

5. See the recording and the report folders:

```
ls build/jacoco build/reports/jacoco/test build/reports/jacoco/test/html
```

   Expected: `test.exec`; `html` and `jacocoTestReport.xml`; and `index.html`, `jacoco-resources`, `jacoco-sessions.html` and one folder per package (`io.plainround.seatwave.live` and its sub-packages `.bench`, `.client`, `.config`, `.feed`, `.http`, `.websocket` and `.wire`).

6. Open the report in your browser (on a Mac): `open build/reports/jacoco/test/html/index.html`.

## Mistakes people make

- **Treating 80 percent as a goal.** Coverage shows which lines never ran. A line that ran can still be unchecked. The tests here also assert results.
- **Chasing 100 percent for `main` methods.** The build documents them as the untested remainder instead (here `ServeCli` and the five `*Cli` classes of the measuring tools).
- **Running only `./gradlew test` and expecting the rule to be checked.** The rule runs in `jacocoTestCoverageVerification`, which `check` runs.
- **Reading an old report.** The report is rewritten after each `test` run; run the tests first.
- **Forgetting the margin.** There are 258 lines of headroom now, but every untested line uses some of it, so new code still needs tests in the same change.
- **Expecting a child process to be measured.** The agent is in the test JVM only. The feed that `compare` starts in its own JVM is not counted when the test starts it.
- **Deleting a test to make a number look right.** The check then fails for the right reason.

## Check yourself

1. *What does the line counter measure?* The share of source lines in which at least one instruction ran during the tests.
2. *Which task checks the rule and which writes the reports?* `jacocoTestCoverageVerification` checks; `jacocoTestReport` writes the HTML and the XML.
3. *Why is coverage 90.3 percent and not 100?* The command-line classes are never fully run by a test (100 of the 243 missed lines), and the rest are lines in other classes that no test reaches, mostly in the clients and the measuring tools.
4. *What makes the rule part of `check`?* `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }`.
5. *How many lines of headroom are there?* 258: 2,266 covered against 2,008 required.

## Further reading

- JaCoCo 0.8.16 documentation: <https://www.jacoco.org/jacoco/trunk/doc/>
- JaCoCo counters explained: <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- Gradle JaCoCo plugin (9.9.1): <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
