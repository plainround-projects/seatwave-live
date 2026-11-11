# gitleaks 8.30.1 (secret scanning) and `.gitleaks.toml`

## What it is

A **secret** is a value that gives access to something: a password, an API key, a token, a private key. If it is written into a file of a repository and the repository is published, anyone can read it, and removing it later does not help, because the history keeps every old version of every file. A **secret scanner** is a program that reads the files (or the history) and looks for text that looks like a secret. **gitleaks** is a free command-line secret scanner. It is a single program, not a library: you run it from a terminal and it prints what it found and sets its **exit code** (the number a program returns when it ends; 0 means success, anything else means a problem).

A scanner works with **rules**. A rule is a pattern, written as a **regular expression** (a compact way to describe text, such as "twenty letters or digits after the word `key`"), and often a second test: **entropy**, a number that says how random a piece of text looks. The text `password` has low entropy. A real key such as a random 40-character string has high entropy. Rules that look for a generic "key" word near a random-looking value use both.

An **allow-list** is the scanner's list of exceptions: text it must not report even though a rule matches it.

## Why this repository uses it

The repository is published for learners. Before anything is published, the course checks that no secret is in the files or in the history, and `gitleaks` is the check. This project has no passwords, no tokens and no keys of its own: the feed listens on the loopback address and has no sign-in (README: "No authentication on the feed"). So the scan should find nothing. It does find one thing if left alone: the WebSocket handshake test data (see below), which looks like a key and is not one. The file `.gitleaks.toml` says so, in one narrow exception, so that a clean scan means "no secrets" and not "no secrets, apart from six known false alarms that everyone has learned to ignore".

## Where it appears

| Path | What it does with gitleaks |
| --- | --- |
| `.gitleaks.toml` | The scanner's settings for this repository: the default rules plus one allow-listed string. |
| `src/test/java/.../websocket/HandshakeTest.java` | Line 15 holds the allow-listed string as `RFC_KEY`. |
| `src/test/java/.../websocket/WebSocketServerTest.java` | Line 153 sends it in a handshake request. |
| `src/main/java/.../bench/SlowConsumer.java` | Line 214 sends it in the slow-consumer experiment's handshake. |
| `README.md` | Lines 86, 92 and 100 show it in `curl` examples of a handshake. |

gitleaks is a tool you run by hand; no build step runs it. The build does not depend on it, and there is no CI file (README: "There is no CI configuration on purpose").

## How it works underneath

1. You run `gitleaks dir <folder>` to scan the files in a folder, or `gitleaks git <repository>` to scan the history, commit by commit. This repository is checked both ways.
2. gitleaks loads its configuration. Without a file it uses its built-in **default configuration**: a long list of rules for cloud keys, tokens of many services, private-key headers and a generic rule.
3. For every file it reads the text and tries every rule. A rule reports a **finding** when its pattern matches and (if the rule asks for it) the entropy of the matched value is high enough. A finding has a rule id, a file, a line and the secret text.
4. Before reporting, gitleaks asks the allow-lists: is this finding excused? If yes, it is dropped.
5. At the end it prints a summary and returns an exit code: 0 if nothing was reported, 1 if there was a finding (the default; `--exit-code` changes it).
6. A scanner can only say "I found nothing that looks like a secret". It cannot prove that none exists: a secret that has the form of an ordinary word, or is split across two lines, is not seen.

## Walkthrough of this repository's use

### `.gitleaks.toml`, line by line

```toml
# gitleaks settings for this repository: the default rules, plus one allow-listed string.
# "dGhlIHNhbXBsZSBub25jZQ==" is the sample Sec-WebSocket-Key that RFC 6455 itself prints (base64 of
# "the sample nonce"); the tests and the slow-consumer tool use it because its accept value is
# published too. It is public test data, not a secret.
[extend]
useDefault = true

[allowlist]
description = "RFC 6455's published sample Sec-WebSocket-Key"
regexes = ['''dGhlIHNhbXBsZSBub25jZQ==''']
```

The file is TOML (`toml-version-catalog.md` explains the syntax: `#` starts a comment, `[name]` starts a table, `key = value` sets a key).

- Lines 1 to 4 are a comment. They say what the file is for and why the one exception is safe. The string `dGhlIHNhbXBsZSBub25jZQ==` is the **sample `Sec-WebSocket-Key`** that RFC 6455, the standard that defines WebSockets, prints in its own text. It is **base64** (a way to write bytes as letters and digits) of the 16 characters `the sample nonce`. I decoded it to check. A WebSocket client sends a random key in its handshake, and the server answers with an **accept value** computed from it: the key plus a fixed text, hashed with SHA-1, written in base64. For the RFC's sample key the RFC prints the accept value too, `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`. I computed it by hand with a few lines of Python and got the same text. The tests use this pair because a test can then check the server's computation against a value that someone else published.
- `[extend]` starts a table of settings about inheriting from another configuration.
- `useDefault = true` says: extend the built-in default configuration. All the default rules stay on. Without this line the file would replace the defaults, and the scan would check nothing but the rules written in this file.
- `[allowlist]` starts the global allow-list: exceptions that apply to every rule.
- `description = "RFC 6455's published sample Sec-WebSocket-Key"` is a label for people. gitleaks does not use it to decide anything, but a reader of the file learns what the exception is.
- `regexes = ['''dGhlIHNhbXBsZSBub25jZQ==''']` is a list (square brackets) with one regular expression. The triple single quotes are TOML's **literal string**: nothing inside is treated as an escape, which is why regular expressions are written this way. The pattern has no special characters, so it matches exactly that text. By default gitleaks tests an allow-list pattern against the **secret** it found (the matched value), not against the whole line. A finding whose secret matches is dropped.

### Why the exception is public test data and not a secret

Three facts make this one string safe.
1. It is **public**: it is printed in the RFC, which anyone can read. A value that is already published cannot leak.
2. It **opens nothing**. A WebSocket key is a one-time random value, not a credential. It is sent in every handshake in the clear, and it proves nothing about who sent it. It cannot sign in anywhere. The server here has no sign-in at all.
3. The exception is **narrow**. It names the exact string, not a file or a folder. A real key pasted into `HandshakeTest.java` would still be found, because only this one text is excused.

### The real result

I ran the scan in a scratch copy of the repository, before these notes were written, with the file as it is:

```
gitleaks dir . --no-banner --no-color
```

```
3:11AM INF scanned ~377249 bytes (377.25 KB) in 27.8ms
3:11AM INF no leaks found
```

The exit code was 0. (The clock, the size and the time differ on every run; the size grows when the notes exist, because the notes are files too.) Then I replaced the file by a copy that has only the first two settings, `[extend]` and `useDefault = true`, and ran the same command:

```
3:11AM INF scanned ~377249 bytes (377.25 KB) in 29.4ms
3:11AM WRN leaks found: 6
```

The exit code was 1. The six findings, all of the rule `generic-api-key` (the rule for "a key-like word next to a random-looking value"), were at `HandshakeTest.java` line 15, `WebSocketServerTest.java` line 153, `SlowConsumer.java` line 214 and `README.md` lines 86, 92 and 100. Adding `-v` (verbose) to a scan of one file shows what a finding looks like:

```
Finding:     RFC_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
Secret:      dGhlIHNhbXBsZSBub25jZQ==
RuleID:      generic-api-key
Entropy:     4.168296
File:        src/test/java/io/plainround/seatwave/live/websocket/HandshakeTest.java
Line:        15
Fingerprint: src/test/java/io/plainround/seatwave/live/websocket/HandshakeTest.java:generic-api-key:15
```

The rule fired because the variable is named `RFC_KEY` (a key-like word) and the value has an entropy of 4.17, which is high. That is the false alarm the allow-list removes. With the original file restored the scan was clean again. The history of the repository was scanned too, with `gitleaks git .` in a local clone: `1 commits scanned.` and `no leaks found`.

## Try it yourself

gitleaks is installed on the verification machine (`/opt/homebrew/bin/gitleaks`, version 8.30.1). All commands ran in a scratch copy on 11 November 2026.

1. Check the version:

```
gitleaks version
```

   Expected: `8.30.1`.

2. Scan the files with the repository's settings:

```
gitleaks dir . --no-banner --no-color
```

   Expected: a line `INF scanned ~<bytes> bytes ... in <time>` and a line `INF no leaks found`. Check the exit code with `echo $?`: `0`.

3. Scan without the exception. In a scratch copy, replace the contents of `.gitleaks.toml` by the two lines `[extend]` and `useDefault = true`, then run the command of step 2:

   Expected: `WRN leaks found: 6` and exit code `1`.

4. See one finding in full:

```
gitleaks dir src/test/java/io/plainround/seatwave/live/websocket/HandshakeTest.java --no-banner --no-color -v
```

   Expected (with the exception removed): the block shown above, with `RuleID: generic-api-key` and `Line: 15`. With the exception in place: `no leaks found`.

5. Scan the history of a clone:

```
gitleaks git . --no-banner --no-color
```

   Expected: `1 commits scanned.` and `no leaks found`.

## Mistakes people make

- **Allow-listing a whole file or folder.** Everything in it, including a real key added next month, would then be excused. This repository excuses one exact string.
- **Removing a secret from the latest version and thinking it is gone.** The history still has it. A leaked secret must be revoked at its source; the repository is then cleaned.
- **Forgetting `useDefault = true`.** The file would replace the default rules instead of adding to them, and the scan would look for almost nothing.
- **Treating a clean scan as proof.** A scanner finds what its rules describe. It is one check among others, not an audit.
- **Putting a real credential in a test and allow-listing it.** The exception here is allowed only because the value is published and opens nothing.
- **Scanning only the files.** A secret that was committed once and deleted is only found by `gitleaks git`.

## Check yourself

1. *What is a secret scanner?* A program that reads files or history and reports text that looks like a password, key or token.
2. *What does `useDefault = true` do?* It keeps gitleaks's built-in rules and adds this file's settings on top.
3. *What string does the allow-list excuse, and why is it safe?* `dGhlIHNhbXBsZSBub25jZQ==`, the sample WebSocket key that RFC 6455 prints. It is public and opens nothing.
4. *Why does the scan find six false alarms without the exception?* The rule `generic-api-key` matches a key-like name or word next to a random-looking value, and the sample key appears in six places.
5. *What exit code does gitleaks return when it finds a leak?* 1, by default. It returns 0 when it finds nothing.

## Further reading

- gitleaks project and configuration reference (8.x): <https://github.com/gitleaks/gitleaks>
- RFC 6455, The WebSocket Protocol, section 1.3 (the sample key and its accept value): <https://www.rfc-editor.org/rfc/rfc6455#section-1.3>
- TOML 1.0.0 specification: <https://toml.io/en/v1.0.0>
