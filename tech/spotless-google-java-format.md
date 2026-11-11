# Spotless 8.11.0 and google-java-format 2.0.0

## What it is

A **formatter** rewrites source code into one fixed layout: where the spaces go, where lines break, how imports are ordered. It changes no behavior. **google-java-format** is a formatter for Java with one fixed style and almost no options. **Spotless** is a Gradle plugin that runs formatters over the files of a project. It can only check ("does any file differ from the formatted version?") or apply ("rewrite them").

Two tools, two jobs: Spotless is the Gradle plugin in version 8.11.0. google-java-format is the formatter it runs, in version 2.0.0, downloaded as a library when needed.

## Why this repository uses it

With one enforced layout nobody argues about style, reviews show only real changes, and nobody formats by hand. The README says that "Formats are checked" as the first step of `./gradlew clean check`, and the dependency table lists the plugin with the words "The formatter, enforced by `check`." Because `spotlessCheck` is part of `check`, an unformatted file stops the gate.

## Where it appears

| Path | What it does with Spotless |
| --- | --- |
| `build.gradle.kts` | Switches the plugin on (`alias(libs.plugins.spotless)`) and configures it (`spotless { java { ... } }`). |
| `gradle/libs.versions.toml` | Holds the two versions: `spotless = "8.11.0"` and `googleJavaFormat = "2.0.0"`. |
| `src/main/java/**`, `src/test/java/**` | The files it checks (every Java source file of the project, 82 main files and 21 test files). |
| `.editorconfig` | Declares two-space indentation for Java, the same as the formatter (`editorconfig.md`). |

## How it works underneath

1. `./gradlew spotlessCheck` runs the task `spotlessJava`, which finds the Java source files and feeds each one through the formatter steps in the order they are listed: google-java-format, then removal of unused imports, then the end-of-file rule.
2. The result is compared with the file on disk. If they are identical, the task passes. If not, it fails and prints a **diff** (a list of the lines that would change; in the diff a middle dot stands for a space) and the sentence `Run './gradlew spotlessApply' to fix all violations.`
3. `./gradlew spotlessApply` writes the formatted result back into the files.
4. Spotless remembers what it checked, so unchanged files are skipped. A second run prints `UP-TO-DATE`.

google-java-format's rules, as they show in this repository's code: two spaces of indentation, four spaces for a continued line, a hard limit of 100 columns, one statement per line, braces always, a blank line between methods, and imports sorted in ASCII order with all `import static` lines first. Six lines of this repository are longer than 100 characters (the longest has 125), and each is a piece of a single string literal (a piece of text in double quotes) that the formatter cannot break without changing the text. Two of them are in the tests: line 91 of `HandshakeTest.java` (101 characters, the raw HTTP request text) and line 153 of `WebSocketServerTest.java` (111 characters, the request headers that contain the RFC key). The other four are in `SseResume.java` (line 115), `SlowConsumer.java` (lines 119 and 166) and `ComparisonReport.java` (line 171).

## Walkthrough of this repository's use

```kotlin
spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}
```

- `spotless { ... }` is the extension block of the plugin.
- `java { ... }` configures the Java formatting target.
- `googleJavaFormat(libs.versions.googleJavaFormat.get())` adds the google-java-format step. The version comes from the catalog (`toml-version-catalog.md`), so changing the formatter means editing one line. The step also pins the style, because the formatter has only one.
- `removeUnusedImports()` deletes `import` lines whose names are never used. Spotless does this with its own step; the formatter alone does not.
- `endWithNewline()` makes sure the last character of each file is a line break.

The plugin itself is switched on in line 4 of the build file with `alias(libs.plugins.spotless)` (`gradle.md`).

### How the layout shows in the tests

Open any test and you see the formatter's choices. In `ReplayBufferTest.java`, lines 67 and 68 are one statement: `assertThatThrownBy(() -> new ReplayBuffer(STREAM, 0))` and, on the next line, `.isInstanceOf(IllegalArgumentException.class);`. The second line starts four spaces deeper than the first, which is the formatter's continuation indent. Nobody typed that by hand: `spotlessApply` produced it.

### An experiment in a scratch copy

I copied the repository (without `build/` and `.gradle/`) to a scratch folder, so the real files stayed untouched. I added a file `Messy.java` with two badly laid out lines, `package io.plainround.seatwave.live;` and `final class Messy {   int   a=1 ;}`, and ran `./gradlew spotlessCheck`. It failed:

```
> Task :spotlessJavaCheck FAILED
> The following files had format violations:
      src/main/java/io/plainround/seatwave/live/Messy.java
          @@ -1,2 +1,5 @@
           package·io.plainround.seatwave.live;
          -final·class·Messy·{···int···a=1·;}
          +
          +final·class·Messy·{
          +··int·a·=·1;
          +}
  Run './gradlew spotlessApply' to fix all violations.
```

Lines that begin with a space in the diff are unchanged context, a line with `-` is what is on disk, and a line with `+` is what the formatter wants. A middle dot stands for a space. The formatter added the blank line after the `package` line, split the class onto three lines and fixed the spaces. Then I ran `./gradlew spotlessApply`, which rewrote the file to exactly the layout in the `+` lines, and `./gradlew spotlessCheck` passed (`BUILD SUCCESSFUL`). I deleted the scratch file afterwards.

## Try it yourself

1. Check the real repository (it must already be formatted):

```
./gradlew --offline spotlessCheck
```

   Expected: `> Task :spotlessJavaCheck` and `BUILD SUCCESSFUL`. On a repeat run each task says `UP-TO-DATE`.

2. List the Spotless tasks:

```
./gradlew --offline tasks --all | grep -i spotless
```

   You see `spotlessApply`, `spotlessCheck`, `spotlessDiagnose`, `spotlessJava`, `spotlessJavaApply`, `spotlessJavaCheck`, `spotlessJavaDiagnose` and `spotlessInstallGitPrePushHook`.

3. Look for lines longer than 100 characters in the Java files (these are the six string literals named above):

```
python3 -c "import glob,sys; [print(f,i) for f in glob.glob('src/**/*.java',recursive=True) for i,l in enumerate(open(f,encoding='utf-8').read().split(chr(10)),1) if len(l)>100]"
```

   Expected: six lines, in the order the file system lists them: `src/test/java/io/plainround/seatwave/live/websocket/HandshakeTest.java 91`, `src/test/java/io/plainround/seatwave/live/websocket/WebSocketServerTest.java 153`, `src/main/java/io/plainround/seatwave/live/bench/SseResume.java 115`, `SlowConsumer.java 119`, `SlowConsumer.java 166` and `ComparisonReport.java 171`. Every other Java line is at most 100 characters. (The tool `awk` counts bytes on some systems and then also lists `PollingRates.java`, whose line 59 holds a few multi-byte characters; the Python count is by characters.)

4. Try the failure yourself, in a copy so the repository stays clean:

```
mkdir /tmp/spotless-demo && rsync -a --exclude build --exclude .gradle --exclude .git --exclude tech ./ /tmp/spotless-demo/ && cd /tmp/spotless-demo
```

   Create `src/main/java/io/plainround/seatwave/live/Messy.java` there with the two lines from the experiment above, then run `./gradlew spotlessCheck` (expect `FAILED` and the diff) and `./gradlew spotlessApply` (expect `BUILD SUCCESSFUL` and the file in the formatter's layout). Delete the folder afterwards.

## Mistakes people make

- **Fixing style by hand.** Run `./gradlew spotlessApply`; it is faster and always right.
- **Committing without running `spotlessApply`.** The next `check` fails.
- **Expecting the formatter to fix everything.** It never renames a variable or deletes dead code. It only changes layout.
- **Formatting the Kotlin build files with it.** This build configures Spotless for Java only; `build.gradle.kts` is not formatted by it.
- **Using different formatter versions on different machines.** The version is pinned in the catalog, so everyone gets 2.0.0.
- **Hand-wrapping long strings.** The formatter never splits a string literal, so a long literal stays a long line; the tests join pieces with `+` where a break is wanted (see the request text in `HandshakeTest.java`).

## Check yourself

1. *What is the difference between `spotlessCheck` and `spotlessApply`?* The first fails if any file would change. The second changes the files.
2. *Which version of the formatter does the build use, and where is it set?* 2.0.0, in `gradle/libs.versions.toml` (`googleJavaFormat`).
3. *Why can an unformatted file stop the build?* `check` depends on `spotlessCheck`.
4. *What does `removeUnusedImports()` do?* Deletes `import` lines that nothing uses.
5. *What indentation does the style use?* Two spaces, with four for a continued line.

## Further reading

- Spotless Gradle plugin documentation (8.x): <https://github.com/diffplug/spotless/tree/main/plugin-gradle>
- google-java-format project (2.0.0): <https://github.com/google/google-java-format>
