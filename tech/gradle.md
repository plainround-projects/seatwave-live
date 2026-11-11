# Gradle 9.9.1 (build tool) and the Kotlin DSL

## What it is

Source code is text. A computer cannot run text until other programs have turned it into something runnable. A **build tool** is the program that does all those steps in the right order, from one command: it fetches the libraries the code needs, compiles the Java source files into **class files** (the form the Java Virtual Machine, the JVM, can run), runs the tests, checks the code style and measures the test coverage. **Gradle** is a build tool for Java. You tell it what you want (for example `check`) and it works out which smaller steps are needed.

A Gradle build is described in files written in a language. This repository uses the **Kotlin DSL**: the build file is a Kotlin script. "DSL" means domain-specific language, a small language made for one job. A file that ends in `.kts` is a Kotlin script, a Kotlin program that is run directly instead of being compiled first.

A **terminal** is a window where you type commands. The word after the program name, such as `check` in `./gradlew check`, is the name of a **task**. A task is one unit of work Gradle knows how to do.

## Why this repository uses it

The hands-on project of Module 8 must build and test with one command and the same result on every machine. Gradle gives that: `./gradlew check` checks the formatting of the code, compiles it with every warning turned into an error, runs 103 tests and fails if the line coverage is below 80 percent. The tests start real servers on the loopback address and real clients, so the build proves that long polling, server-sent events and WebSockets all deliver the same seat map. The build file also gives each of the six programs (the feed node, the comparison at 1,000 clients, the slow-consumer experiment, the wire-bytes probe and the two lab tools) its own task, so a learner types one short command instead of assembling a long Java command line. It keeps the long measuring runs out of `check`, so the gate takes about half a minute.

## Where it appears

| Path | What it does with Gradle |
| --- | --- |
| `settings.gradle.kts` | Names the project and says where libraries may be downloaded from. |
| `build.gradle.kts` | The build itself: plugins, Java version, libraries, compiler flags, formatter, coverage, the test task, the six program tasks. |
| `gradle/libs.versions.toml` | The version catalog: one place for every version (`toml-version-catalog.md`). |
| `gradle.lockfile`, `settings-gradle.lockfile` | Pinned versions of every library, including the ones libraries bring along (`dependency-locking.md`). |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | The wrapper that downloads and starts the right Gradle (`gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`). |
| `build/` and `.gradle/` | Folders Gradle creates while it works. Git ignores them (`git.md`). |

## How it works underneath

1. You run `./gradlew check`. The wrapper starts a **Gradle process**, a Java program that stays alive in the background for a while (the **daemon**) so the next command starts quickly.
2. Gradle reads `settings.gradle.kts` first. That file decides which projects are part of the build. Here there is one project.
3. Gradle then reads `build.gradle.kts`. Running the file does not build anything. It only **configures**: it creates tasks and tells each one what it depends on.
4. Gradle looks at the task you asked for and at everything it depends on, and builds a **task graph**: a list of tasks in a legal order, each after the tasks it needs.
5. It runs the tasks. For each task it checks whether the inputs (source files, settings) and outputs (class files, reports) are the same as last time. If yes, the task is marked `UP-TO-DATE` and skipped.
6. Everything it makes goes into the `build/` folder. Downloaded libraries are kept in a cache folder in your home directory (`~/.gradle`), shared by all projects.

A **plugin** is a package of tasks and settings that you switch on in one line. The `java` plugin adds the tasks that compile and test Java. A **dependency** is a library your code needs. A **configuration** is a named list of dependencies for one purpose, such as "what the tests need at run time". A **repository** is a server that stores libraries; Maven Central is the big public one.

## Walkthrough of this repository's use

### `settings.gradle.kts`

```kotlin
// One place decides where dependencies come from; a project that adds its own repository fails the build.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "seatwave-live"
```

- Line 1 is a comment: `//` starts a comment in Kotlin. It says that one place decides where libraries come from.
- `dependencyResolutionManagement { ... }` is a block. A block is a name followed by braces; everything inside configures that one topic. This block configures how Gradle **resolves** dependencies, which means finding the exact file for each library.
- `repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS` makes the build fail if `build.gradle.kts` ever adds a repository of its own. All repositories must be declared here. This stops a project from quietly pulling libraries from an unreviewed server.
- `repositories { mavenCentral() }` lists the only allowed repository: Maven Central. The build file therefore also cannot add a snapshot server or a company mirror without editing this file in plain sight.
- `rootProject.name = "seatwave-live"` names the project. The name appears in reports, for example "Rule violated for bundle seatwave-live".

### The plugins block

```kotlin
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}
```

- `plugins { ... }` lists the plugins to switch on.
- `java` adds the Java tasks: `compileJava`, `test`, `jar` and the `check` task.
- `jacoco` adds the coverage tool (`jacoco.md`).
- `alias(libs.plugins.spotless)` switches on the Spotless formatter plugin. `alias` means "use the plugin described in the version catalog". `libs.plugins.spotless` points at the `spotless` line in the `[plugins]` section of `gradle/libs.versions.toml`, which holds the plugin id and its version (`toml-version-catalog.md`).

### Project coordinates

```kotlin
group = "io.plainround"
version = "1.1.0"
```

`group` and `version` are the project's own identity in the Maven style: a group, a name (from `settings.gradle.kts`) and a version. They would matter if the project were published as a library. Here they only label the build. The version `1.1.0` is the second release, a fix release after the first, `1.0.0` (`CHANGELOG.md`).

### The Java toolchain

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```

A **toolchain** is the exact JDK (Java Development Kit) that builds the code. `languageVersion = JavaLanguageVersion.of(25)` says "use Java 25". Gradle looks for an installed JDK 25 on the machine and uses it for compiling and for running tests, even if the JDK that started Gradle is different. This build does not configure an automatic download of a JDK, so a JDK 25 must be installed (the README names Eclipse Temurin 25.0.2). The `java { toolchain { ... } }` nesting is a block inside a block: `java` is the extension that the `java` plugin added, and `toolchain` is one of its parts.

### Dependencies and configurations

```kotlin
dependencies {
    implementation(libs.result4j)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

A name like `implementation`, `runtimeOnly`, `testImplementation` or `testRuntimeOnly` is a **configuration**. The `test` prefix means "only for the test code". `Implementation` means "needed to compile and run". `RuntimeOnly` means "needed only when running, not when compiling". A **classpath** is the list of jar files and folders the JVM searches for classes. Each line below puts one library on one classpath. `libs.something` is a catalog entry (`toml-version-catalog.md`).

- `implementation(libs.result4j)` puts the result4j library on the classpath of the main code. It expands to `com.github.sviperll:result4j:1.2.0`. The settings reader, the seat store and the position parser use it so that a bad setting or a refused hold is a value, not an exception (`result4j.md`).
- `implementation(platform(libs.jackson.bom))` brings in a **BOM**, a bill of materials. A BOM is a small published file that lists the matching versions of many libraries of one family. `platform(...)` tells Gradle "use this list to decide versions". The Jackson family has several jars (`jackson-databind`, `jackson-core`, `jackson-annotations`), and they must be the same release. In the dependency tree the BOM shows the three versions it fixes: `jackson-databind:2.23.0`, `jackson-annotations:2.23` and `jackson-core:2.23.0`, each marked `(c)`, which means "a constraint, not a dependency".
- `implementation(libs.jackson.databind)` is the JSON library itself, used by the `wire` package. The catalog line has no version; the BOM supplies it. The tree line reads `com.fasterxml.jackson.core:jackson-databind -> 2.23.0`: the arrow means "no version was asked for, 2.23.0 was chosen".
- `implementation(libs.slf4j.api)` is the logging interface. `runtimeOnly(libs.logback.classic)` is the logging engine behind it. The code is compiled only against the interface, so the engine is `runtimeOnly`. Logback itself asks for `slf4j-api:2.0.19`; the tree shows `org.slf4j:slf4j-api:2.0.19 -> 2.0.20` because the build asks for 2.0.20 directly and the higher version wins.
- `testImplementation(platform(libs.junit.bom))` brings in the JUnit BOM, so every JUnit library is 6.1.3 without a version of its own.
- `testImplementation(libs.junit.jupiter)` adds the JUnit Jupiter bundle: the annotations, the parameterized-test support and the engine that finds and runs the tests (`junit.md`).
- `testImplementation(libs.assertj.core)` adds AssertJ, the readable assertions (`assertj.md`).
- `testImplementation(libs.awaitility)` adds Awaitility, which waits for a condition instead of sleeping (`awaitility.md`). Two test classes use it.
- There is no `testRuntimeOnly(libs.logback.classic)`: the test classpath inherits the main code's `runtimeOnly` libraries, so Logback is there for the tests already, and `src/test/resources/logback-test.xml` configures it (`tests-other-packages-walkthrough.md`).
- `testRuntimeOnly(libs.junit.platform.launcher)` adds the JUnit Platform launcher, the piece Gradle calls to start the tests. Gradle 9 expects the project to declare it. It is needed only when tests run. Its version comes from the BOM.

There is no JSON-schema library, no web framework and no WebSocket library in the list. The feed uses the JDK's own HTTP server and sockets (`ARCHITECTURE.md` explains why).

### Dependency locking

```kotlin
// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}
```

Libraries bring other libraries (their **transitive dependencies**). `lockAllConfigurations()` makes Gradle write the exact version of every library of every configuration into `gradle.lockfile`, and read that file on every later build. If the resolved versions would differ from the lock, the build fails. The whole story, with every line of both files, is in `dependency-locking.md`.

### The Java compiler flags

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
```

- `tasks.withType<JavaCompile>().configureEach { ... }` means: for every task of the type `JavaCompile` (there is one for main code and one for test code), run this block. `<JavaCompile>` is a Kotlin type argument, the type to select. `configureEach` applies the block to each one lazily, only when needed.
- `options.encoding = "UTF-8"` makes the compiler read source files as UTF-8 text, so the result does not depend on the machine's default.
- `options.release = 25` makes the compiler accept only Java 25 language features and use only Java 25 library classes.
- The comment on line 38 gives the reason for the next line: "Every warning is an error: a warning that is allowed to stay is a defect nobody owns."
- `options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))` passes two flags to `javac`, the Java compiler. `-Xlint:all` turns on every optional warning (raw types, unchecked casts, an auto-closeable resource that is never closed, missing serial ids and more). `-Werror` turns each warning into an error, so the build stops. The flags apply to the test code too.
- I ran an experiment in a scratch copy: a new class with the raw types `java.util.List` and `java.util.ArrayList` made `compileJava` fail. The output had `warning: [rawtypes] found raw type: List`, then `error: warnings found and -Werror specified`, and the task was marked `FAILED`.

### Spotless and the formatter

```kotlin
spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}
```

Spotless is a Gradle plugin that checks and rewrites source files to one layout (`spotless-google-java-format.md`).

- `spotless { java { ... } }` configures Spotless for Java files.
- `googleJavaFormat(libs.versions.googleJavaFormat.get())` chooses the formatter. `libs.versions.googleJavaFormat` is the version entry `googleJavaFormat = "2.0.0"` from the catalog, and `.get()` turns it into the text `2.0.0`.
- `removeUnusedImports()` deletes `import` lines the file never uses.
- `endWithNewline()` makes every file end with one line break.
- Spotless adds the tasks `spotlessCheck` (fails if a file would change) and `spotlessApply` (changes the files). The `check` task depends on `spotlessCheck`, which is how formatting becomes part of the gate.

### JaCoCo, the coverage tool

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`jacoco { toolVersion = ... }` fixes the JaCoCo version to the catalog entry `jacoco = "0.8.16"`. Without it, Gradle would pick a default version. `jacoco.md` explains what the tool measures.

### The test task

```kotlin
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}
```

- `tasks.test { ... }` configures the existing task named `test`.
- `useJUnitPlatform()` tells it to run tests through the JUnit Platform. Without it Gradle would look for older JUnit 4 tests and find none.
- `testLogging { events("passed", "failed", "skipped") }` prints one line per test result while the task runs, for example `BackoffTest > repeatsItsWaitsForTheSameSeed() PASSED`. Without it, only failures show.
- `finalizedBy(tasks.jacocoTestReport)` means: after `test` finishes, always run `jacocoTestReport`, even if tests failed. That creates the coverage report in `build/reports/jacoco/test/html`.

There is no `maxParallelForks`, no `forkEvery` and no `systemProperty`, so Gradle runs all test classes in one test JVM, one test class after another, and the tests read the settings files from the project folder, which is the test task's working directory. Each test that needs a server starts its own on port 0, which lets the system pick a free port, so no two tests share a socket. A timeout of 60 seconds per test comes from `src/test/resources/junit-platform.properties` (`tests-other-packages-walkthrough.md`).

### The XML coverage report

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

`tasks.jacocoTestReport { ... }` configures the task that writes the coverage report. `reports { xml.required = true }` switches on the **XML report**. By default the task writes only the HTML report, which people read in a browser. XML is a text format made of tags such as `<counter type="LINE" missed="243" covered="2266"/>`; a program can read it, so the exact numbers can be copied into the README without counting by eye. The file lands in `build/reports/jacoco/test/jacocoTestReport.xml`.

### The coverage rule wired into `check`

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

- `tasks.jacocoTestCoverageVerification` is the task that checks coverage against a rule. `dependsOn(tasks.test)` makes sure the tests have run, so there is coverage data to read.
- `violationRules { rule { limit { ... } } }` is the rule. A **limit** names a counter, here `counter = "LINE"`, and a bound. `minimum = "0.80".toBigDecimal()` means the covered share of lines must be at least 0.80. `.toBigDecimal()` turns the text into an exact decimal number, which the plugin requires. The comment explains that the untested remainder is the command-line entry points.
- `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }` adds the coverage check to `check`. By default `check` does not run it, so this line is what makes the floor a real gate. I proved it in a scratch copy: with the minimum raised to 0.95 the build failed with `Rule violated for bundle seatwave-live: lines covered ratio is 0.90, but expected minimum is 0.95`.
- `check` is the standard "is this project healthy" task. It runs the tests, `spotlessCheck` and the coverage verification. None of the `JavaExec` tasks of the next section is a dependency of it.

### The six program tasks

```kotlin
// One command per example: each entry point gets its own task, so a reader never assembles a classpath.
// The measuring runs (compare, slowConsumer, wireBytes) are tasks of their own and never part of check.
val entryPoints = mapOf(
    "serve" to "io.plainround.seatwave.live.ServeCli",
    "compare" to "io.plainround.seatwave.live.bench.ComparisonCli",
    "slowConsumer" to "io.plainround.seatwave.live.bench.SlowConsumerCli",
    "wireBytes" to "io.plainround.seatwave.live.bench.WireBytesCli",
    "pollingRates" to "io.plainround.seatwave.live.bench.PollingRatesCli",
    "sseResume" to "io.plainround.seatwave.live.bench.SseResumeCli",
)
```

```kotlin
entryPoints.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Runs $entryPoint."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = entryPoint
        // Paths in --args are read from the project folder, wherever Gradle was started.
        workingDir = rootDir
    }
}
```

- The two comment lines at the top say what the block is for: "One command per example: each entry point gets its own task, so a reader never assembles a classpath." and "The measuring runs (compare, slowConsumer, wireBytes) are tasks of their own and never part of check."
- `val entryPoints = mapOf(...)` creates a read-only map in Kotlin: each pair is a task name and the full name of a class that has a `main` method (an **entry point**, the place where a Java program starts). `"a" to "b"` builds one pair. The trailing comma after the last pair is allowed in Kotlin. The six pairs are:
  - `serve` runs `ServeCli`, the feed node itself: the seat store, the simulated on-sale, the HTTP server on port 8090 and the WebSocket server on port 8091, read from `config/serve.properties`.
  - `compare` runs `ComparisonCli`, the measured comparison at 1,000 clients per method: the feed in a JVM of its own, then the clients, three runs per method, a CSV at the end. The README reports about 4 minutes on the verification machine.
  - `slowConsumer` runs `SlowConsumerCli`, the experiment with clients that stop reading, which shows the per-client queue stopping at its bound.
  - `wireBytes` runs `WireBytesCli`, which counts the bytes that cross the wire for one change with each method.
  - `pollingRates` runs `PollingRatesCli`, lab one of the module: the request rates of short polling, long polling and push for 200,000 fans, by arithmetic only.
  - `sseResume` runs `SseResumeCli`, lab two: a client whose stream is cut resumes from `Last-Event-ID`, and a client away longer than the replay buffer is told to reload.
- `entryPoints.forEach { (taskName, entryPoint) -> ... }` runs the block once per pair. The round brackets `(taskName, entryPoint)` take the pair apart into its two parts.
- `tasks.register<JavaExec>(taskName) { ... }` creates a new task of the type `JavaExec`, which runs a Java program. `register` creates it lazily.
- `group = "application"` files the task in the `application` group in the `./gradlew tasks` list. `description = "Runs $entryPoint."` is the text shown next to it; `$entryPoint` inserts the variable into the text.
- `classpath = sourceSets.main.get().runtimeClasspath` gives the program the compiled main classes plus the libraries they need (result4j, Jackson, SLF4J, Logback). A **source set** is a named group of source folders; `main` is `src/main/java` together with `src/main/resources`. Because `JavaExec` depends on the classpath, Gradle compiles the code first.
- `mainClass = entryPoint` names the class to start.
- The comment "Paths in --args are read from the project folder, wherever Gradle was started." and `workingDir = rootDir`: a **working directory** is the folder a program treats as "here": a relative path such as `config/serve.properties` is looked for from it. `rootDir` is the folder that holds `settings.gradle.kts`, the project folder.

None of the six `JavaExec` tasks depends on `check`, and `check` depends on none of them. They run only when you ask.

**Why the measuring runs are their own tasks and not part of `check`.** Four reasons. First, time: the comparison runs about 4 minutes, the slow-consumer run ten seconds of load, and a gate that takes minutes is a gate people stop running. Second, resources: 1,000 clients and 1,000 server connections need about 2,100 file descriptors (open-connection slots the operating system hands out) across the two processes, and a machine with a small `ulimit -n` cannot run it; the README says how to check. Third, the result is a table of numbers that a person reads, not a pass-or-fail answer, and the numbers depend on the machine and the minute. Fourth, `serve` never ends by itself until its serve time (600 seconds in `config/serve.properties`) has passed. What `check` does run is the same machinery on a small scale: `MeasuringToolsTest` runs the comparison with 10 clients per method, the slow consumer with 3, the resume lab and the wire-bytes probe, and checks the shape of what they print (`tests-other-packages-walkthrough.md`). The cost: the `main` methods of the `*Cli` classes are not covered by tests, which is why the coverage floor is 0.80 and not higher (`jacoco.md`).

### The commands you type

- `./gradlew check` runs the full gate. `./` means "the file `gradlew` in this folder".
- `./gradlew spotlessApply` rewrites source files into the formatter's layout. Run it before `check` if `spotlessCheck` complains.
- `-q` (`--quiet`) hides Gradle's own messages, so only the program's output shows.
- `--args='...'` passes the text after it to the program's `main` method as arguments. Gradle splits the text at spaces into separate arguments. `./gradlew -q slowConsumer --args='config/slow-consumer.properties 50 10'` therefore gives `SlowConsumerCli` three arguments: the settings file, 50 and 10.
- `--offline` makes Gradle use only what is in its cache and touch no network. I used it for every command in these notes.
- When a program calls `System.exit` with a non-zero value, Gradle reports `Execution failed for task ':name'` and exits with 1 itself.

## Try it yourself

All commands below ran in a scratch copy of this repository on 11 November 2026 (Temurin 25.0.2, Gradle 9.9.1). Timings differ on every run.

1. List the tasks that run the programs:

```
./gradlew --offline tasks --group application
```

   The list starts with `Application tasks` and has these six lines, in alphabetical order: `compare - Runs io.plainround.seatwave.live.bench.ComparisonCli.`, `pollingRates - Runs io.plainround.seatwave.live.bench.PollingRatesCli.`, `serve - Runs io.plainround.seatwave.live.ServeCli.`, `slowConsumer - Runs io.plainround.seatwave.live.bench.SlowConsumerCli.`, `sseResume - Runs io.plainround.seatwave.live.bench.SseResumeCli.` and `wireBytes - Runs io.plainround.seatwave.live.bench.WireBytesCli.`

2. Run the whole gate:

```
./gradlew --offline clean check
```

   It prints one `PASSED` line per test (103 in all), then `> Task :jacocoTestCoverageVerification`, `> Task :spotlessJavaCheck` and ends with `BUILD SUCCESSFUL in 29s` and `10 actionable tasks: 10 executed`.

3. Run the quickest lab, which needs no server:

```
./gradlew --offline -q pollingRates --args='config/polling-rates.properties'
```

   Expected: nine lines. The first reads `200,000 fans; short poll every 5000 ms; long poll held up to 25000 ms; round trip 80 ms` and the last reads `push, connections held                    200,000        200,000`.

4. Break the coverage floor on purpose, in a scratch copy only. Change `"0.80"` to `"0.95"` in `build.gradle.kts` and run `./gradlew --offline check -q`. The output contains `Rule violated for bundle seatwave-live: lines covered ratio is 0.90, but expected minimum is 0.95`, then `Execution failed for task ':jacocoTestCoverageVerification'` and `BUILD FAILED`.

5. Show how the Jackson BOM and the lock shape the main classpath:

```
./gradlew --offline -q dependencies --configuration runtimeClasspath
```

   Expected: a tree that starts with `com.github.sviperll:result4j:1.2.0` and `com.fasterxml.jackson:jackson-bom:2.23.0`, with the line `org.slf4j:slf4j-api:2.0.19 -> 2.0.20` under Logback, and `{strictly ...}` constraint lines at the end that come from the lock.

## Mistakes people make

- **Running `gradle` instead of `./gradlew`.** The installed Gradle may be a different version. The wrapper always starts 9.9.1 (`gradle-wrapper.md`).
- **Editing `gradle.lockfile` by hand.** The file says manual edits can break the build. Regenerate it (`dependency-locking.md`).
- **Adding a repository in `build.gradle.kts`.** `FAIL_ON_PROJECT_REPOS` in the settings file stops it on purpose.
- **Ignoring a compiler warning.** `-Werror` makes that impossible here.
- **Thinking `test` is enough.** `./gradlew test` skips the formatter check and the coverage floor. Only `check` runs all three.
- **Running a program with a path that is relative to the wrong folder.** The six tasks set `workingDir = rootDir`, so `config/serve.properties` works from any folder. A `java` command typed by hand does not.
- **Running `compare` on a shell with a small descriptor limit.** `ulimit -n` shows the limit; the README explains how to lower `compare.clients` or raise the limit for one shell.
- **Looking for the XML report in the wrong place.** It is `build/reports/jacoco/test/jacocoTestReport.xml`, written only because of the `xml.required = true` line.
- **Committing anything from `build/`.** The folder is ignored on purpose (`git.md`).
- **Forgetting that the build file runs.** It is a program. Typing mistakes show up as Kotlin errors, not as friendly messages.

## Check yourself

1. *What is a task?* One named unit of work, such as `compileJava`, `test` or `check`. Gradle runs a task only after the tasks it depends on.
2. *What does `implementation(platform(libs.jackson.bom))` do?* It loads a list of matching versions for the Jackson family, so `jackson-databind`, `jackson-core` and `jackson-annotations` are one release, 2.23.0.
3. *Why does `-Werror` matter?* It turns every compiler warning into an error, so no warning can stay unnoticed.
4. *Which line makes the coverage floor part of `check`?* `dependsOn(tasks.jacocoTestCoverageVerification)` inside `tasks.check`.
5. *Why is `compare` not part of `check`?* It takes about 4 minutes, needs about 2,100 open descriptors across its two processes, prints numbers for a person to read, and the small-scale version of the same code already runs in `MeasuringToolsTest`.

## Further reading

- Gradle 9.9.1 user guide: <https://docs.gradle.org/9.9.1/userguide/userguide.html>
- Build file basics: <https://docs.gradle.org/9.9.1/userguide/part1_gradle_init.html>
- Java toolchains: <https://docs.gradle.org/9.9.1/userguide/toolchains.html>
- Kotlin DSL primer: <https://docs.gradle.org/9.9.1/userguide/kotlin_dsl.html>
- Platforms and BOMs: <https://docs.gradle.org/9.9.1/userguide/platforms.html>
- Custom task classes and up-to-date checks: <https://docs.gradle.org/9.9.1/userguide/custom_tasks.html>
- The Java plugin and its `check` task: <https://docs.gradle.org/9.9.1/userguide/java_plugin.html>
- The JaCoCo plugin and its reports: <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
