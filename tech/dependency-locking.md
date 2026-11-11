# Dependency locking

## What it is

Your project depends on libraries. Those libraries depend on other libraries. A library you did not ask for but that comes along is a **transitive dependency**. For example this project asks for Jackson's `jackson-databind`, and `jackson-databind` needs `jackson-core` and `jackson-annotations`; those two are transitive dependencies.

If a build resolved "the newest compatible version" every time, two builds a week apart could use different jars and give different results. **Dependency locking** stops that. Gradle writes the exact version it chose for every library into a **lockfile**, commits it with the code, and from then on insists on those exact versions.

A **version** like `3.27.7` has a major part, a minor part and a patch part. A **coordinate** is the full address of a library: `group:name:version`, for example `org.assertj:assertj-core:3.27.7`.

## Why this repository uses it

The README says that every one of the 30 jars in `gradle.lockfile` was checked against the OSV database (the open source vulnerability database) on 11 November 2026, and that it listed no vulnerability for any of them. That sentence only means something because the file is locked: the list that was checked is the list that is built, today and next year. The version catalog (`toml-version-catalog.md`) pins the libraries this project names; the lockfile pins everything else.

The file is short because the feed is built on the JDK. Of the 30 locked coordinates, 8 are on the main code's classpath (result4j, SLF4J, Logback with its core, and Jackson with its three jars and its BOM), 15 more exist only for the tests (JUnit, AssertJ, Awaitility and what they bring), and 7 belong to JaCoCo's own tools.

## Where it appears

| Path | What it does |
| --- | --- |
| `build.gradle.kts` | `dependencyLocking { lockAllConfigurations() }` turns it on (`gradle.md`). |
| `gradle.lockfile` | The locked versions of every configuration of the project: 4 comment lines, 30 library lines and one `empty=` line. |
| `settings-gradle.lockfile` | The same for the settings phase: 3 comment lines and one data line. |

## How it works underneath

1. A **configuration** is a named list of dependencies for one purpose (`compileClasspath`, `testRuntimeClasspath`, ...). Each has its own lock entries.
2. When a configuration is resolved, Gradle reads `gradle.lockfile`. For every library it finds there, it treats the locked version as a strict requirement. If the build's own requirements cannot be met with that version, resolution fails.
3. A library that is on a classpath but is **not** in the lockfile also fails the build, so nothing slips in unlocked.
4. To change the lock, you ask Gradle to recompute and rewrite the file: `./gradlew :dependencies --write-locks`. You commit the new file, so the change is visible in review.
5. Lines in the file are sorted, and each line says which configurations use that library. The names after `=` mean "these configurations use this exact version".
6. When two libraries ask for different versions of the same library, Gradle picks one (normally the newest) and the lock records that one. The dependency tree shows it as `a -> b`: "asked for a, got b". The lock has only the winner. In this repository there is one such case: Logback 1.6.6 asks for `slf4j-api:2.0.19`, the build asks for 2.0.20, and the lock records 2.0.20.

I tested step 2 in a scratch copy: after changing `assertj-core:3.27.7` to `assertj-core:3.27.6` in the lockfile only, `compileTestJava` failed with `Could not resolve org.assertj:assertj-core:3.27.7.` and `Cannot find a version of 'org.assertj:assertj-core' that satisfies the version constraints`, and the constraint line read `'org.assertj:assertj-core:{strictly 3.27.6}' because of the following reason: Dependency version enforced by Dependency Locking`. The real lockfile was not touched.

## Walkthrough of this repository's use

### `gradle.lockfile`, line by line

```text
# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
# To regenerate this file, run: ./gradlew :dependencies --write-locks
```

Lines 1 to 4 are comments that Gradle writes. They say the file is generated, hand edits are risky, it belongs in source control, and the command that regenerates it. The command is `./gradlew :dependencies --write-locks`; `:dependencies` names the root project's `dependencies` task.

Each line below has the shape `group:name:version=configuration,configuration,...`. The meaning of each configuration name is in the table after the walkthrough.

#### Logging: the engine

```text
ch.qos.logback:logback-classic:1.6.6=runtimeClasspath,testRuntimeClasspath
ch.qos.logback:logback-core:1.6.6=runtimeClasspath,testRuntimeClasspath
```

- `ch.qos.logback:logback-classic:1.6.6` is the logging engine from the catalog. It is only on the two runtime classpaths: `runtimeClasspath` and `testRuntimeClasspath`. It is not on a compile classpath, because the code is compiled against the SLF4J interface and the engine is added with `runtimeOnly`.
- `ch.qos.logback:logback-core:1.6.6` is the part of Logback that `logback-classic` is built on. It came along as a transitive dependency.

#### Jackson: the one family with one version

```text
com.fasterxml.jackson.core:jackson-annotations:2.23=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.core:jackson-core:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.core:jackson-databind:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson:jackson-bom:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

- Four lines, all at 2.23.0, except `jackson-annotations`, whose Jackson-published version has no patch number: `2.23`. That is Jackson's own naming, not a typo.
- `jackson-annotations`, `jackson-core` and `jackson-databind` are the three core jars of Jackson. They are on all four classpaths because the main code uses `jackson-databind` directly (the `wire` package) and the other two come with it.
- `com.fasterxml.jackson:jackson-bom:2.23.0` is the bill of materials from `platform(libs.jackson.bom)`. It fixes the three versions above (`gradle.md`).

#### The result type

```text
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

`com.github.sviperll:result4j:1.2.0` is the result type used by the settings reader, the seat store and the position parser (`result4j.md`). All four classpaths use it. It brings no library of its own.

#### The test libraries and what they bring

```text
net.bytebuddy:byte-buddy:1.18.3=testCompileClasspath,testRuntimeClasspath
org.apiguardian:apiguardian-api:1.1.2=testCompileClasspath
org.assertj:assertj-core:3.27.7=testCompileClasspath,testRuntimeClasspath
org.awaitility:awaitility:4.3.0=testCompileClasspath,testRuntimeClasspath
org.hamcrest:hamcrest:2.1=testCompileClasspath,testRuntimeClasspath
```

- `net.bytebuddy:byte-buddy:1.18.3` is a library that creates classes while a program runs. AssertJ asks for it; it is on the two test classpaths only.
- `org.apiguardian:apiguardian-api:1.1.2` holds the `@API` annotation that marks which parts of JUnit are stable. JUnit's API jar asks for it. It is only needed to compile, so only `testCompileClasspath` lists it.
- `org.assertj:assertj-core:3.27.7` is the assertion library from the catalog (`assertj.md`).
- `org.awaitility:awaitility:4.3.0` is the waiting library from the catalog (`awaitility.md`).
- `org.hamcrest:hamcrest:2.1` is an older assertion-matcher library. Awaitility asks for it.

#### JaCoCo's own tools

```text
org.jacoco:org.jacoco.agent:0.8.16=jacocoAgent,jacocoAnt
org.jacoco:org.jacoco.ant:0.8.16=jacocoAnt
org.jacoco:org.jacoco.core:0.8.16=jacocoAnt
org.jacoco:org.jacoco.report:0.8.16=jacocoAnt
```

- `org.jacoco:org.jacoco.agent:0.8.16` is the **agent**, a small jar the test JVM loads so that JaCoCo can watch which lines run. It is on `jacocoAgent` (used by the test task) and on `jacocoAnt`.
- `org.jacoco:org.jacoco.ant`, `org.jacoco.core` and `org.jacoco.report`, all 0.8.16, are the tools that read the recorded data, write the HTML and XML reports and check the coverage floor. Gradle runs them through Ant, an older build tool, so the configuration is named `jacocoAnt` (`jacoco.md`).
- All four are the version from the catalog entry `jacoco = "0.8.16"`.

#### A null-safety annotation library

```text
org.jspecify:jspecify:1.0.0=testCompileClasspath
```

`org.jspecify:jspecify:1.0.0` holds annotations that say whether a value may be null. JUnit 6 asks for it. Compile only: `testCompileClasspath`.

#### JUnit

```text
org.junit.jupiter:junit-jupiter-api:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.jupiter:junit-jupiter-engine:6.1.3=testRuntimeClasspath
org.junit.jupiter:junit-jupiter-params:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.jupiter:junit-jupiter:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.platform:junit-platform-commons:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.platform:junit-platform-engine:6.1.3=testRuntimeClasspath
org.junit.platform:junit-platform-launcher:6.1.3=testRuntimeClasspath
org.junit:junit-bom:6.1.3=testCompileClasspath,testRuntimeClasspath
```

- Four `org.junit.jupiter` lines: `junit-jupiter-api` (the annotations such as `@Test`), `junit-jupiter-engine` (finds and runs tests; only at run time, so only `testRuntimeClasspath`), `junit-jupiter-params` (parameterized tests, which `FeedPositionTest` and `ReconnectCorrectnessTest` use), and `junit-jupiter` (the bundle that brings them together). All at 6.1.3.
- Three `org.junit.platform` lines: `junit-platform-commons`, `junit-platform-engine` (run time only) and `junit-platform-launcher` (run time only, the one the build file adds with `testRuntimeOnly`).
- `org.junit:junit-bom:6.1.3` is the bill of materials from `platform(...)`.

#### The test-failure exception

```text
org.opentest4j:opentest4j:1.3.0=testCompileClasspath,testRuntimeClasspath
```

`org.opentest4j:opentest4j:1.3.0` defines the `AssertionFailedError` that tests throw when a check fails. JUnit and AssertJ both use it.

#### ASM and SLF4J

```text
org.ow2.asm:asm-commons:9.11.1=jacocoAnt
org.ow2.asm:asm-tree:9.11.1=jacocoAnt
org.ow2.asm:asm:9.11.1=jacocoAnt
org.slf4j:slf4j-api:2.0.20=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

- Three `org.ow2.asm` lines at 9.11.1 (`asm-commons`, `asm-tree` and `asm`): a library that reads and changes Java bytecode. JaCoCo's tools use it to find the lines of each class, so the configuration is `jacocoAnt` only.
- `org.slf4j:slf4j-api:2.0.20` is the logging interface from the catalog. Logback asks for 2.0.19; the lock records 2.0.20 for all four classpaths.

#### The line that lists nothing

```text
empty=annotationProcessor,testAnnotationProcessor
```

`empty=` lists configurations that resolved to nothing: `annotationProcessor` and `testAnnotationProcessor`. An **annotation processor** is a compiler plug-in; this project has none, so both are empty. Recording them proves they were locked too, so adding one later is noticed.

The formatter is not in this file. Spotless fetches `google-java-format` on its own, outside the project's configurations, so its version is pinned by `googleJavaFormat = "2.0.0"` in the catalog and not by the lock.

### The meaning of each configuration name after `=`

| Name | What it holds |
| --- | --- |
| `compileClasspath` | Everything needed to compile the main code. |
| `runtimeClasspath` | Everything needed to run the main code. |
| `testCompileClasspath` | Everything needed to compile the tests. |
| `testRuntimeClasspath` | Everything needed to run the tests. |
| `jacocoAgent` | The JaCoCo agent that measures the tests. |
| `jacocoAnt` | The JaCoCo tools that write the report and check the floor. |
| `annotationProcessor`, `testAnnotationProcessor` | Compiler plug-ins for main and test code (empty here). |

### `settings-gradle.lockfile`

```text
# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
```

The three comment lines are the same as in the other file, without the regeneration line. The one data line is:

```text
empty=incomingCatalogForLibs0
```

`empty=incomingCatalogForLibs0` is the lock of an internal configuration Gradle creates for the catalog named `libs`; it holds no dependencies, so it is listed as empty. It exists because dependency locking also covers the settings phase, where catalogs are read.

### Regenerating the files

`./gradlew :dependencies --write-locks` recomputes both `gradle.lockfile` and `settings-gradle.lockfile`. In a scratch clone I deleted both files and ran the command: it ended with exit status 0 and `git status --short` showed no modified file, so the written files were identical to the committed ones, which proves the committed files are exactly what Gradle resolves. When you change a version in the catalog, run it, then commit both files.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. Look at the locked versions:

```
cat gradle.lockfile
```

   Expected: the 4 comment lines, 30 library lines and the `empty=` line quoted above (35 lines).

2. Count the libraries:

```
grep -v '^#' gradle.lockfile | grep -vc '^empty'
```

   Expected: `30`.

3. Prove the committed lock is current:

```
./gradlew dependencies --write-locks -q > /dev/null; git status --short
```

   Expected: no line for `gradle.lockfile` or `settings-gradle.lockfile` (untracked files, such as a new `tech/` folder, may be listed).

4. See a version being enforced:

```
./gradlew -q dependencyInsight --dependency assertj-core --configuration testRuntimeClasspath
```

   Expected: `org.assertj:assertj-core:3.27.7`, then `Selection reasons:` and a line `By constraint: Dependency version enforced by Dependency Locking`.

5. See a request that lost:

```
./gradlew -q dependencyInsight --dependency slf4j-api --configuration runtimeClasspath
```

   Expected: `org.slf4j:slf4j-api:2.0.20`, the reasons `By constraint: Dependency version enforced by Dependency Locking` and `By ancestor`, and further down `org.slf4j:slf4j-api:2.0.19 -> 2.0.20` under `ch.qos.logback:logback-classic:1.6.6`: Logback asked for 2.0.19 and got 2.0.20.

6. See that the main classpath is small:

```
./gradlew -q dependencies --configuration runtimeClasspath | grep -c -e '^+---' -e '^\\---'
```

   Expected: `13` (the top-level lines of the tree, including the `{strictly}` constraint lines).

## Mistakes people make

- **Editing the lockfile by hand.** The header warns about it. Use `--write-locks`.
- **Changing a version in the catalog and forgetting to regenerate.** The build then fails with "Dependency version enforced by Dependency Locking".
- **Not committing the lockfile.** The lock is only useful if everyone gets the same file.
- **Thinking the lock replaces the catalog.** The catalog says what you ask for; the lock records what Gradle found for it, including everything transitive.
- **Locking only some configurations.** `lockAllConfigurations()` locks them all, including the JaCoCo ones.
- **Ignoring a lockfile diff.** Review it: it shows exactly which library versions moved.
- **Trusting a vulnerability scan of the direct dependencies only.** Scan the lockfile, which lists everything. A direct dependency can bring a vulnerable one.

## Check yourself

1. *What is a transitive dependency?* A library your dependencies need, which you did not ask for directly.
2. *Which command rewrites the lock?* `./gradlew :dependencies --write-locks`.
3. *What does the text after `=` on a lockfile line mean?* The configurations that use that exact version.
4. *Why is `junit-jupiter-engine` only on `testRuntimeClasspath`?* Tests need the engine only to run, not to compile.
5. *Logback asks for SLF4J 2.0.19. Why does the lock say 2.0.20?* The build asks for 2.0.20 directly, Gradle picks the higher version, and the lock records the winner.

## Further reading

- Locking dependency versions in Gradle 9.9.1: <https://docs.gradle.org/9.9.1/userguide/dependency_locking.html>
- Declaring dependencies and configurations: <https://docs.gradle.org/9.9.1/userguide/declaring_dependencies.html>
- Viewing the dependency tree: <https://docs.gradle.org/9.9.1/userguide/viewing_debugging_dependencies.html>
- Dependency constraints: <https://docs.gradle.org/9.9.1/userguide/dependency_constraints.html>
