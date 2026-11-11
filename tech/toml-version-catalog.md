# TOML and the Gradle version catalog

## What it is

**TOML** (Tom's Obvious Minimal Language) is a plain-text file format for settings. It is built to be read by people first. A TOML file is a list of `key = value` pairs, grouped under headings written in square brackets. Gradle uses one TOML file, `gradle/libs.versions.toml`, as a **version catalog**: a single list of every library, plugin and version the build uses, each with a short name. The build file refers to entries by name, so the version numbers live in exactly one place.

## Why this repository uses it

The README says "Every version is exact", and the first line of the catalog says the same. One file means one place to read, one place to change and one place to check against the vulnerability database. The build file then contains no version number at all, except the Java language level 25 and the project's own `1.1.0`. This repository has a short catalog because the feed is built on the JDK: no web framework, no database and no WebSocket library, only a result type, a JSON library, a logging pair and the test and build tools.

## Where it appears

| Path | What it does with TOML |
| --- | --- |
| `gradle/libs.versions.toml` | The catalog: 10 versions, 10 libraries, 1 plugin. |
| `build.gradle.kts` | Reads it through `libs.xxx` names, 13 of them (`gradle.md`). |
| `gradle.lockfile`, `settings-gradle.lockfile` | The locked, resolved result of what the catalog asks for (`dependency-locking.md`). |
| `.editorconfig` | Gives `*.toml` files a two-space indent (`editorconfig.md`). |

## How it works underneath

1. Gradle looks for `gradle/libs.versions.toml` automatically. The file name decides the catalog name: `libs`.
2. It parses the three tables and, for each entry, creates a typed accessor that the Kotlin build file can call.
3. Nothing is downloaded when the catalog is read. It is only a list of names. Downloads start when a task needs a classpath.
4. `settings-gradle.lockfile` has a line for the internal configuration that Gradle uses to read the catalog (`dependency-locking.md`).

**TOML syntax in six rules.**
- `#` starts a comment that runs to the end of the line.
- `[name]` on its own line starts a **table** (a named group). Everything below it belongs to it, until the next table heading.
- `key = "text"`: a key, an equals sign and a value. Text is always in double quotes.
- `{ a = "x", b = "y" }` is an **inline table**: a small group written on one line.
- `version.ref = "junit"` is a **dotted key**: the key `version` holds a table whose key `ref` is `"junit"`. It is the same as `version = { ref = "junit" }`.
- Keys are case sensitive. Blank lines do not matter.

## Walkthrough of this repository's use

### The comment and the `[versions]` table

```toml
# Every version is exact. Each was checked on Maven Central or the Gradle Plugin Portal on 11 November 2026.
[versions]
result4j = "1.2.0"
jackson = "2.23.0"
slf4j = "2.0.20"
logback = "1.6.6"
junit = "6.1.3"
assertj = "3.27.7"
awaitility = "4.3.0"
spotless = "8.11.0"
googleJavaFormat = "2.0.0"
jacoco = "0.8.16"
```

- Line 1 says every version is exact and was checked on 11 November 2026. Exact means there are no ranges such as `3.+`: a range would let a new release appear without any change to the repository.
- `[versions]` starts a table of named versions. The name is yours to choose; the value is a string.
- `result4j = "1.2.0"` the result library (`result4j.md`).
- `jackson = "2.23.0"` the version of the whole Jackson family. It is used by the BOM entry below.
- `slf4j = "2.0.20"` and `logback = "1.6.6"` the logging interface and engine.
- `junit = "6.1.3"` the version of the whole JUnit family, used by the BOM entry below (`junit.md`).
- `assertj = "3.27.7"` the assertion library (`assertj.md`).
- `awaitility = "4.3.0"` the waiting library (`awaitility.md`).
- `spotless = "8.11.0"` the version of the Spotless Gradle plugin.
- `googleJavaFormat = "2.0.0"` the version of the formatter that Spotless runs. It is read directly by the build file.
- `jacoco = "0.8.16"` the coverage tool version. Also read directly by the build file.

### The `[libraries]` table

```toml
[libraries]
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
jackson-bom = { module = "com.fasterxml.jackson:jackson-bom", version.ref = "jackson" }
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
awaitility = { module = "org.awaitility:awaitility", version.ref = "awaitility" }
```

Each line is an alias, an equals sign and an inline table.
- `module = "group:name"` is the library's address on Maven Central: the group, a colon and the artifact name. For example `com.github.sviperll:result4j`.
- `version.ref = "result4j"` says "use the entry called `result4j` from `[versions]`". The reference is by name, not by number.
- `jackson-bom` is the Jackson bill of materials and carries the version. `jackson-databind` has **no version**. That is intentional: the build file brings in `jackson-bom` first (`platform(libs.jackson.bom)`), and the BOM's list supplies the version for the module.
- `junit-bom` carries `version.ref = "junit"`; `junit-jupiter` and `junit-platform-launcher` have no version for the same reason as `jackson-databind`.
- `slf4j-api` and `logback-classic` carry their own versions, because no BOM covers them here.
- `assertj-core` and `awaitility` carry theirs for the same reason.

### The `[plugins]` table

```toml
[plugins]
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

`spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }` names a Gradle plugin. `id` is the plugin's identifier and `version.ref` points at the version `8.11.0`. In the build file it is switched on with `alias(libs.plugins.spotless)`.

### How a catalog name becomes `libs.something`

The part before the first dot is the catalog name (`libs`). After it comes the table. Dashes, underscores and dots in an alias all become dots in the accessor.

| Catalog line | In `build.gradle.kts` | Meaning |
| --- | --- | --- |
| `result4j = { module = ... }` in `[libraries]` | `libs.result4j` | The library `com.github.sviperll:result4j:1.2.0`. |
| `jackson-bom = { ... }` | `libs.jackson.bom` | The Jackson bill of materials 2.23.0. |
| `jackson-databind = { ... }` | `libs.jackson.databind` | Jackson databind, version from the BOM. |
| `logback-classic = { ... }` | `libs.logback.classic` | Logback 1.6.6. |
| `junit-platform-launcher = { ... }` | `libs.junit.platform.launcher` | The launcher, version from the BOM. |
| `spotless = { id = ... }` in `[plugins]` | `libs.plugins.spotless` | The Spotless plugin 8.11.0. |
| `googleJavaFormat = "2.0.0"` in `[versions]` | `libs.versions.googleJavaFormat.get()` | The text `2.0.0`. |
| `jacoco = "0.8.16"` in `[versions]` | `libs.versions.jacoco.get()` | The text `0.8.16`. |

A library entry used as a dependency is turned into a full address with a version. A version entry used as a value has to be read with `.get()`, which returns its text.

## Try it yourself

All commands ran in a scratch copy of this repository on 11 November 2026.

1. See which catalog names the build uses:

```
grep -n "libs\." build.gradle.kts
```

   Expected: 13 lines. The first is `4:    alias(libs.plugins.spotless)` and the last two are `44:        googleJavaFormat(libs.versions.googleJavaFormat.get())` and `51:    toolVersion = libs.versions.jacoco.get()`.

2. Ask Gradle which version AssertJ resolved to and why:

```
./gradlew -q dependencyInsight --dependency assertj-core --configuration testRuntimeClasspath
```

   The output starts with `org.assertj:assertj-core:3.27.7` and includes `Selection reasons:` followed by `By constraint: Dependency version enforced by Dependency Locking`.

3. See the BOM at work:

```
./gradlew -q dependencies --configuration testRuntimeClasspath | grep "junit-jupiter "
```

   Expected: a line `+--- org.junit.jupiter:junit-jupiter -> 6.1.3`. The version came from the BOM, not from the catalog entry.

4. Count the entries:

```
grep -c " = " gradle/libs.versions.toml
```

   Expected: `21` (10 versions, 10 libraries, 1 plugin).

## Mistakes people make

- **Single quotes or no quotes around text.** TOML text uses double quotes (single quotes are literal strings, rarely needed).
- **Writing a version where `version.ref` is meant.** `version = "1.2.0"` inside a library entry works but defeats the one-place rule.
- **Using a range** such as `"3.+"`. This repository pins exact versions on purpose.
- **Mixing up the dash and the dot.** The alias `junit-jupiter` is spelled `libs.junit.jupiter` in Kotlin.
- **Forgetting `.get()`** on a `libs.versions...` value.
- **Giving the BOM members their own versions.** That would fight the BOM. They are left without one here.
- **Changing a version and not regenerating the lock.** The catalog says what you ask for; the lock says what was found. Change both together (`dependency-locking.md`).

## Check yourself

1. *What are the three tables in this file?* `[versions]`, `[libraries]` and `[plugins]`.
2. *What does `version.ref = "assertj"` mean?* "Use the version named `assertj` from the `[versions]` table."
3. *Why does `jackson-databind` have no version?* The BOM (`jackson-bom`) provides it.
4. *How is `junit-platform-launcher` written in the build file?* `libs.junit.platform.launcher`.
5. *Why is `googleJavaFormat` read with `.get()`?* It is a version entry used as a plain value, not a dependency, so Gradle gives a provider and `.get()` returns its text, `2.0.0`.

## Further reading

- Version catalogs in Gradle 9.9.1: <https://docs.gradle.org/9.9.1/userguide/version_catalogs.html>
- The TOML 1.0.0 specification: <https://toml.io/en/v1.0.0>
- Platforms and BOMs in Gradle: <https://docs.gradle.org/9.9.1/userguide/platforms.html>
