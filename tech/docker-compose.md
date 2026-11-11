# Docker Compose and YAML (the optional `compose.yaml`)

## What it is

**Docker** runs programs in **containers**. A container is a sealed, lightweight box that holds one program together with the exact files it needs, so it runs the same everywhere. A container is created from an **image**, a read-only package. Images are stored in a **registry** such as Docker Hub and are named `name:tag`, where the tag picks a version.

**Docker Compose** reads a text file that describes one or more containers and runs them with one command (`docker compose up`, or `docker compose run` for a single service). The file is written in **YAML**, a format built from indentation. Two spaces under a key mean "this belongs to that key". There are three building blocks: `key: value` pairs, **lists** (lines that start with `- `, or items in square brackets on one line) and **comments** (`#` to the end of the line). Tabs are not allowed for indentation.

**This path was not run on the verification machine.** Docker was not running there (`docker info` printed `ERROR: Cannot connect to the Docker daemon`), and the course brief says not to start it. The README marks the section "[not run]". Everything below about what a container does comes from reading the file and the README, not from running a container. Two things I did check without a container: `docker compose config`, which only reads and prints the file and needs no running Docker, accepted the file and printed it back in its normal form; and Ruby's YAML loader (`ruby -ryaml`) parsed it into two services and one volume.

## Why this repository uses it

Nothing in the project needs a container: the feed, the WebSocket server and every client run in plain Java processes on the loopback address. The Compose file is an **optional** layer for readers whose machine has another JDK than the target one. It runs the same two commands as the README, `./gradlew clean check` and the comparison, inside the image `eclipse-temurin:25.0.5_8-jdk-noble`, which carries JDK 25.0.5. The verification machine had 25.0.2 (README: "The current JDK 25 update on that date was 25.0.5 (build 25.0.5+8), which was not installed on the verification machine").

## Where it appears

| Path | What it does |
| --- | --- |
| `compose.yaml` | The only Compose file: two services, `check` and `compare`, and one named volume. |
| `README.md` | The section "Docker Compose (optional)" gives the commands `docker compose run --rm check` and `docker compose run --rm compare`. |
| `.editorconfig` | Asks for two-space indentation in `*.yaml` files (`editorconfig.md`). |

## How it works underneath

1. `docker compose run --rm check` reads `compose.yaml` in the current folder and finds the service called `check`.
2. It pulls the image named in `image:` from the registry if it is not on the machine yet, and checks that the image's digest is the pinned one.
3. It creates one container, mounts the volumes, applies the security settings and starts the container's program (`command:`) in the folder `working_dir:`. `--rm` removes the container when it ends.
4. The container's program prints to your terminal and exits. Its exit code becomes the command's exit code.
5. A **bind mount** shows a folder of your machine inside the container. A **named volume** is storage that Docker manages itself, kept between runs, here for Gradle's downloaded libraries.
6. The container has its own file system and its own network. The servers that the build starts (the tests, and the comparison's feed) bind to `127.0.0.1` **inside** the container, so nothing is published to the host.

An **image digest** is a fingerprint (a SHA-256 hash) of the exact image content. A tag such as `25.0.5_8-jdk-noble` is a label that its owner could move to a different image later. A digest cannot move: a changed image has a different digest. Writing `name:tag@sha256:...` means "this tag, and I insist on exactly this content", so a pulled image that differs is refused.

A **file descriptor** is the number the operating system gives a program for each open file or network connection. Each process may hold only a limited number of them (`ulimit -n` shows the limit). 1,000 open connections need about 1,000 of them.

## Walkthrough of this repository's use

### The comment block (lines 1 to 8)

```yaml
# Optional: the build and the comparison on the target JDK (Eclipse Temurin 25.0.5) in a container,
# for readers whose machine has another JDK. Nothing in the project needs a container: the feed,
# the WebSocket server and every client run in plain JVMs on 127.0.0.1. The tag and its digest were
# checked on Docker Hub on 11 November 2026 (eclipse-temurin 25.0.5_8-jdk-noble, updated
# 1 November 2026). The project folder is mounted so the build writes build/ as usual; Gradle's
# downloads are kept in a named volume. Inside the container every server still binds to its own
# loopback address, so nothing is published to the host.
# Run them with: docker compose run --rm check   and   docker compose run --rm compare
```

These are comments. They say:
- The layer is optional, for readers whose machine has another JDK than Eclipse Temurin 25.0.5.
- Nothing in the project needs a container.
- The tag and its digest were checked on Docker Hub on 11 November 2026 (`eclipse-temurin` `25.0.5_8-jdk-noble`, updated 1 November 2026).
- The project folder is mounted so the build writes `build/` as usual, and Gradle's downloads are kept in a named volume.
- Inside the container every server binds to its own loopback address, so nothing is published to the host.
- The two commands that run the services.

### The `check` service (lines 9 to 18)

```yaml
services:
  check:
    image: eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a
    working_dir: /work
    command: ["./gradlew", "--no-daemon", "clean", "check"]
    volumes:
      - .:/work
      - gradle-home:/root/.gradle
    security_opt:
      - no-new-privileges:true
```

- `services:` starts the list of containers. Everything indented below it is a service.
- `check:` is the name of the first service. The name is yours. `docker compose run --rm check` uses it.
- `image: eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e3...1f8a` has three parts. `eclipse-temurin` is the image name on Docker Hub. `25.0.5_8-jdk-noble` is the tag: JDK 25 update 5, build 8 (`25.0.5_8`), the full JDK with the compiler (`jdk`, not just the runtime), on an Ubuntu 24.04 base (`noble` is its code name). `@sha256:...` is the digest pin explained above. Both the tag and the digest are written, so a reader sees the version, and Docker enforces the exact content.
- `working_dir: /work` is the folder inside the container in which the command starts. It is where the next section mounts the project.
- `command: ["./gradlew", "--no-daemon", "clean", "check"]` is the list of words that make the command line, in YAML's one-line list form. The program is `./gradlew`, the wrapper from the project folder (`gradle-wrapper.md`). `--no-daemon` tells Gradle not to leave its background process running: the container ends right after the build, so a daemon would be wasted. `clean` deletes `build/` first, so the run is from scratch. `check` is the gate (`gradle.md`).
- `volumes:` lists the storage shared with the container.
- `- .:/work` is a bind mount in the form `host-path:container-path`. `.` is the folder of the Compose file, the repository. `/work` is where it appears in the container. It is read-write, because the build writes `build/` and `.gradle/` into it. The container therefore works on your real files.
- `- gradle-home:/root/.gradle` is a named volume mounted at `/root/.gradle`, Gradle's cache folder for the user `root` (the user the image runs as). The first run downloads Gradle 9.9.1 and the libraries into it; later runs find them there. The name `gradle-home` is declared at the end of the file.
- `security_opt:` passes security options to Docker. `- no-new-privileges:true` is one list item. It looks like a key and a value because of the colon, but there is no space after the colon, so YAML reads it as a single piece of text. The option tells the kernel that a program in the container can never gain more rights than it started with, for example through a program that carries the set-user-id flag.

### The `compare` service (lines 19 to 34)

```yaml
  compare:
    image: eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a
    working_dir: /work
    command: ["./gradlew", "--no-daemon", "-q", "compare", "--args=config/compare.properties"]
    volumes:
      - .:/work
      - gradle-home:/root/.gradle
    # The feed runs in a process of its own: 1,000 connections need about 1,100 descriptors in it
    # (1,022 to 1,032 were open in the measured runs) and about as many in the clients' process.
    # This raises the container's own limit only, not the host's.
    ulimits:
      nofile:
        soft: 65536
        hard: 65536
    security_opt:
      - no-new-privileges:true
```

The first five keys are the same as in `check`, with these differences:
- `command: ["./gradlew", "--no-daemon", "-q", "compare", "--args=config/compare.properties"]` runs the Gradle task `compare` (`gradle.md`) instead of `check`. `-q` (quiet) hides Gradle's own messages so only the report shows. `--args=config/compare.properties` hands the settings file to the program. The `=` form keeps the whole option in one list item, so no splitting of words is involved. The path is read from the project folder, which is `/work` here.
- The three comment lines say why the next block exists. The comparison starts the feed in a process of its own, so the 1,000 server connections are in one process and the 1,000 clients in another, and each process needs about 1,100 file descriptors: one per connection and a few dozen the JVM opens for itself. The measured runs saw 1,022 to 1,032 open in the feed's process, as the comment says, and the README gives the same 1,100 per process. The limit has to be well above the 1,024 that many systems start with. The block raises the container's own limit only, not the host's.
- `ulimits:` sets resource limits for the processes in the container. `nofile:` is the limit on open files, which includes network connections. `soft: 65536` is the limit the process starts with, and `hard: 65536` is the ceiling the process may raise it to. Both are 65,536, far above what the comparison needs.
- `security_opt` with `no-new-privileges:true` is the same as in `check`.

The `compare` service runs the whole comparison in the container: Gradle starts `ComparisonCli`, which starts the feed in a JVM of its own inside the same container, then the clients. It takes about 4 minutes on the verification machine (README), so it is a command to start and wait for.

### The named volume (lines 34 and 35)

```yaml
volumes:
  gradle-home:
```

- `volumes:` at the top level (no indentation) declares the named volumes used by the services.
- `gradle-home:` declares one volume with all default options. The key has no value on its line, which YAML reads as an empty value (`null`); Compose treats that as "create it with the defaults". Docker creates it on first use, and `docker compose down -v` would delete it.
- Compose prefixes the volume with the project name, which is the name of the folder that holds the file. In my scratch copy (folder `copy`) `docker compose config` printed `name: copy_gradle-home`; in the repository folder it is `seatwave-live_gradle-home`.

### What `docker compose config` printed

I ran `docker compose config` in the scratch copy. It printed the file in its normal form: each `command` as a list with one word per line; the `.` bind mount as `type: bind`, `source:` (the absolute path of the folder), `target: /work`; the named volume as `type: volume`, `source: gradle-home`, `target: /root/.gradle`; a `networks: default:` entry that Compose adds to every service; and `ulimits.nofile` with `soft: 65536` and `hard: 65536` on the `compare` service only. That output shows how Compose reads the file and does not show how the containers behave, which was not run.

## Try it yourself

Docker was not running on the verification machine, so the container commands below were not run. The first command needs no running Docker and was run.

1. Check that the file is valid, without starting anything:

```
docker compose config
```

   Expected (run): the file printed back with the paths made absolute, as described above, and exit code 0.

2. Check the file with a second parser (this one I ran):

```
ruby -ryaml -e 'puts YAML.load_file("compose.yaml").keys.inspect'
```

   Expected: `["services", "volumes"]`.

3. Read the file with line numbers, which needs only a terminal:

```
cat -n compose.yaml
```

   You see the 35 lines quoted above.

4. Run the gate in the container (needs Docker; not run):

```
docker compose run --rm check
```

   Expected (not run): Gradle downloads itself into the volume, prints one `PASSED` line per test and ends with `BUILD SUCCESSFUL`. The first run also pulls the image, so it needs a network once.

5. Run the comparison in the container (needs Docker; not run):

```
docker compose run --rm compare
```

   Expected (not run): the table shown in the README, after about 4 minutes, and `csv: build/compare/live-feed-comparison.csv`.

## Mistakes people make

- **Tabs for indentation.** YAML forbids them. The `.editorconfig` file asks for two spaces in `*.yaml` (`editorconfig.md`).
- **Using only a tag.** A tag can move. This file adds the digest.
- **Running as root on a Linux host and being surprised by file owners.** The image runs as `root`, and the project folder is mounted read-write, so on a Linux host the files that the build writes to `build/` may belong to `root`. This file does not set a `user:`. On Docker Desktop for Mac the owner is mapped to you; I did not run it, so this is a thing to check on the first run.
- **Raising the descriptor limit in the host shell and expecting the container to see it.** A container has its own limit; that is why `compare` has its own `ulimits` block.
- **Reading `no-new-privileges:true` as a key.** It is one text item. A key needs a space after the colon.
- **Treating an optional layer as proof.** These services were not run for these notes. The plain `./gradlew clean check` on the verification machine is the proof.

## Check yourself

1. *What is a container image?* A read-only package that holds a program and the files it needs. A container is a running copy of it.
2. *What does `@sha256:...` after a tag do?* It pins the exact content, so a moved tag cannot change what runs.
3. *Why does the `compare` service have a `ulimits` block and `check` does not?* The comparison holds 1,000 client connections and 1,000 server connections, which need far more open descriptors than the default; the tests in `check` hold a few dozen.
4. *What does `- .:/work` do?* It shows the repository folder inside the container as `/work`, read-write, so the build writes `build/` into your real folder.
5. *Was this file run on the verification machine?* The containers were not: Docker was not running. Only `docker compose config` and a YAML parse were run, and the notes say so.

## Further reading

- The Compose file reference: <https://docs.docker.com/reference/compose-file/>
- `docker compose run`: <https://docs.docker.com/reference/cli/docker/compose/run/>
- `docker compose config`: <https://docs.docker.com/reference/cli/docker/compose/config/>
- Image digests: <https://docs.docker.com/reference/cli/docker/image/pull/#pull-an-image-by-digest-immutable-identifier>
- Eclipse Temurin images on Docker Hub: <https://hub.docker.com/_/eclipse-temurin>
- The YAML 1.2 specification: <https://yaml.org/spec/1.2.2/>
