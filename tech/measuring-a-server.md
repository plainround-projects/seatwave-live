# Measuring a server: percentiles, file descriptors, CPU, heap and memory

## What it is

This file teaches, from nothing, the five things the README and the comparison report about a running server, and the commands that read them on a Mac.

- **Latency percentiles** (p50, p99): how long things took, said honestly.
- **File descriptors**: the small numbers an operating system hands out for every open file and connection.
- **CPU "cores used"**: how much of the processor a program kept busy.
- **Heap and memory outside the heap**: where a Java program keeps its data, and what the rest of its memory is.
- **Threads**: the lines of execution inside a program, and how many the server needs.

It is a reading and measuring guide, not a program. Nothing here changes a limit or a setting. Every command below only reads, and each output shown is real: I ran it on this machine (an ARM-based Mac with 12 logical processors and 32 GB of memory, running JDK 25.0.2) against a feed node I started in a copy of the repository.

## Why this repository uses it

Module 8 asks the learner to choose a delivery method from numbers. A number means nothing if you do not know what it counts. "p99 is 114 ms" and "1,022 file descriptors" and "1.73 cores" are the columns of the comparison table. You can only trust or question them if you know exactly what each one is, and how to read the same figure with a tool you did not write.

## Where it appears

| Where | What is measured | How |
|---|---|---|
| `bench/LatencySummary.java` | p50, p99, max of delays | Sort, nearest rank. |
| `bench/MachineLine.java` | The file-descriptor limit and processor count of a tool's own process | `getMaxFileDescriptorCount()`, `availableProcessors()`. |
| `feed/FeedMetrics.java` (`GET /metrics`) | Open file descriptors, limit, CPU time, platform threads, heap in use | JVM management beans. |
| `bench/ProcessMemory.java` | Resident memory | `ps -o rss= -p <pid>`. |
| `bench/Comparison.java` | CPU cores, heap and RSS per connection, threads, fds | Differences of readings. |
| README, "How many clients fit" | The shell limit and the system limits | `ulimit -n`, `launchctl limit maxfiles`, `sysctl`. |

The code is explained in `java-bench-walkthrough.md`; this file explains what the numbers mean.

## How it works underneath

### 1. Percentiles: p50, p99, and why the tail matters

Suppose a server answered 10 requests in these times, in milliseconds: 5, 5, 6, 6, 7, 7, 8, 8, 9, 400. The **average** is 46 ms. That describes no request: nine took under 10 ms, one took 400 ms. The average hides the one that hurt.

A **percentile** is a position in the sorted list. Sort the times from smallest to largest.

- **p50** (the median) is the time that half of all requests stayed under. Here it is about 7 ms.
- **p99** is the time that 99 of every 100 requests stayed under. For 1,000 requests it is the 990th time in the sorted list, so ten requests are slower than it.
- **max** is the slowest.

The slowest 1 percent is called the **tail**. Three reasons it matters here.

1. **A tail hit is a real person.** A fan whose seat map is 400 ms stale is looking at a seat that is already gone.
2. **Fan-out multiplies the tail.** One seat change is delivered to 1,000 clients. Even if each delivery lands in the tail with probability 1 in 100, about 10 of the 1,000 deliveries of that one change are slow. At 69 changes per second the system makes about 69,000 deliveries per second, so about 690 tail deliveries every second.
3. **Many steps make a tail likely.** If a page does 20 things and each has a 1 percent chance of being slow, the chance that at least one is slow is `1 - 0.99^20`, about 18 percent.

The comparison reads p50, p99 and max by **nearest rank**: sort, then take the value at position `ceil(p / 100 * n)`. I ran the repository's `LatencySummary` on 1 ms to 100 ms with one 250 ms outlier:

```text
LatencySummary[count=100, p50Millis=50.0, p99Millis=99.0, maxMillis=250.0]
```

The outlier changes the max and not p99, which is why a report prints all three.

### 2. File descriptors: why 1,000 connections need 1,000 of them

A **file descriptor** (fd) is a small whole number that the operating system gives a running program for each thing it has open: a file, a pipe, a network connection. The program says "write to fd 7" instead of naming the thing. Every program starts with three: 0 is **standard input**, 1 is **standard output**, 2 is **standard error**.

Real output (`lsof` lists a process's open files; `-d 0,1,2` selects the first three; I cut the other lines):

```text
COMMAND   PID            USER   FD   TYPE DEVICE SIZE/OFF      NODE NAME
zsh     23844 sumitsrivastava    0r   CHR    3,2      0t0       336 /dev/null
```

Every TCP connection is a **socket**, and every socket is one file descriptor in each program that holds an end of it. A server with 1,000 open client connections therefore holds 1,000 fds, plus the few it needs for itself (its listening socket, its log files, the Java runtime's files). The client program holds 1,000 more on its side. This is why the README says each of the two processes needs about 1,100.

The operating system sets a **limit** on how many a process may hold, so that a buggy program cannot use them all up. When the limit is reached, the next `accept` or `open` fails with the error "Too many open files". Two limits matter:

- The **soft limit** is the current ceiling, which the program may raise itself up to the hard limit.
- The **hard limit** is the highest the soft limit can go.

Real output from this machine:

```text
ulimit -n: 1048576
ulimit -Hn: unlimited
```

`ulimit` is a built-in command of the shell (the program that reads your typed commands). `ulimit -n` prints the shell's soft limit on open files and `ulimit -Hn` the hard one. Programs started from that shell inherit them. Here the soft limit is 1,048,576, far above the 1,100 needed. On a machine where `ulimit -n` prints 256 (the old macOS default), a 1,000-client run would fail; the README then says to lower `compare.clients` or to raise the limit for one shell with `ulimit -n 4096`. I did not run that, because these notes change no limit.

The system has limits of its own too. Real output:

```text
launchctl limit maxfiles
	maxfiles    256            unlimited

kern.maxfilesperproc: 122880
kern.maxfiles: 245760
kern.ipc.somaxconn: 128
```

`launchctl limit maxfiles` shows the defaults for programs that the system's launcher starts: a soft limit of 256 and no hard limit. `kern.maxfilesperproc` is the most fds one process can ever hold (122,880), `kern.maxfiles` the most in the whole system. `kern.ipc.somaxconn` is the longest **listen queue** the system allows: the line of connections that have arrived and wait for the program to `accept` them. 128 means a burst of more than 128 connections at once can overflow it. That is why the fleet connects 250 clients a second and not 1,000 at once. `sysctl <name>` reads such a system value, and I only read them.

How to count a running server's fds three ways. The feed reports its own at `GET /metrics`; on a node with no clients:

```text
process_open_fds 22
process_max_fds 1048576
```

With 20 event-stream clients connected, the same lines read `process_open_fds 43` and, after the clients left, `process_open_fds 23`. The jump is 21: one per connection, plus the one used by the `curl` request that read the metrics. `lsof -p <pid> | wc -l` gave 41 and then 62: the same difference, but `lsof` also lists the working folder, the program file and memory-mapped files, so its absolute count is higher than the fd count. Use the difference, or the metric.

### 3. CPU and "cores used"

The **CPU** is the part of the computer that runs instructions. A modern chip has several **cores**, each able to run a thread at the same moment. This machine reports 12 logical processors (8 performance cores and 4 efficiency cores).

A program's **CPU time** is the total time its threads spent running on cores, added up across all threads. The operating system counts it. The JVM exposes it as `process_cpu_nanoseconds_total` (a **counter**: it only goes up).

**Cores used** is CPU time per second of real (wall-clock) time:

```text
cores = (cpu_time_end - cpu_time_start) / elapsed_seconds
```

If a program burned 1.73 seconds of CPU time during 1 second of real time, it kept 1.73 cores busy on average. The most any program can use here is 12. A real reading from the idle node (20 on-sale actions a second, no clients): the counter went from 3,260,635,000 to 3,281,167,000 nanoseconds over 5.017 seconds:

```text
cores = (3281167000 - 3260635000) / 1e9 / 5.017 = 0.0041
```

That is four thousandths of one core. The comparison does the same subtraction with the counter read before and after the measured phase.

Dividing cores by the deliveries per second gives **CPU microseconds per delivery** (`us/del`): how much CPU each change delivered to one client costs. It is a better comparison across methods than total CPU, because it does not depend on how many changes happened.

### 4. Heap and memory outside the heap

A Java program keeps its objects in the **heap**: one big region the JVM manages. New objects are made there; the **garbage collector** frees those nothing uses any more. The heap has a **maximum size**, set by `-Xmx`; without it the JVM picks one quarter of the machine's memory. `jcmd` (a JDK tool that sends commands to a running JVM) shows it. Real output for the node with no open connections:

```text
garbage-first heap   total reserved 8388608K, committed 528384K, used 7242K [0x000000787f800000, 0x0000007a7f800000)
 region size 4096K, 3 young (12288K), 2 survivors (8192K)
```

Read it: the JVM may grow the heap to 8,388,608 KB (8 GB, a quarter of this machine's 32 GB), it has asked the system for 528,384 KB (**committed**), and **7,242 KB** hold live and not-yet-collected objects (**used**). "Garbage-first" is the collector's name (G1). The numbers `-XX:MaxHeapSize=8589934592` and `-XX:InitialHeapSize=536870912` from `jcmd <pid> VM.flags` agree with it.

Heap is not all of a program's memory. A JVM process also holds, outside the heap:

- the **stacks** of its threads,
- **socket buffers** and other buffers that the operating system keeps for open connections,
- the **code cache** (machine code the JIT made),
- the loaded classes and the JVM's own working memory,
- native libraries.

The operating system knows the total. It calls the part that is in RAM right now the **resident set size** (RSS). `ps` shows it:

```text
ps -o pid=,rss=,vsz= -p 89745
89745  105600 499113104
```

RSS is in kibibytes: 105,600 KiB, about 103 MB, while the heap holds 7 MB. The rest is committed-but-unused heap pages, class data, stacks and the rest. The second figure, `vsz`, is **virtual size**: address space the program has reserved, mostly never touched. Do not read it as memory used.

With 20 SSE clients connected, RSS read 126,432 KiB (+20,832 KiB, which also includes other growth such as the JIT's code cache, so it is not a clean cost per client) and the platform thread count went from 14 to 27. The comparison separates these effects: it reads **heap per connection** from the JVM after a forced collection, and **RSS per connection** from `ps`. A method can be cheap in one and costly in the other. In the README the event-stream server holds more heap per client (about 80,000 bytes) and the WebSocket server far less (about 21,500), yet the RSS per client is similar (about 6,500 to 9,400).

Why the comparison pre-touches the heap: with `-XX:+AlwaysPreTouch` the JVM makes the operating system really assign all heap pages when it starts. Without it, RSS would grow whenever the heap filled up, and that growth would be blamed on the connections.

### 5. Threads

A **thread** is one line of execution. A **platform thread** is a thread of the operating system; each costs a stack and a kernel entry, so a server cannot have a hundred thousand. A **virtual thread** is a lightweight thread that the JVM schedules on a small pool of platform threads; when it waits for the network, it is parked, costing almost nothing. The feed gives each connection one virtual thread. Real metrics: with no clients the node reported `seatwave_feed_handler_virtual_threads 1` (the metrics request itself) and `process_platform_threads 14`; with 20 clients, 21 and 27. So 1,000 clients mean about 1,001 virtual threads and a few dozen platform threads, as the README table shows (`vthr 1001`, `pthr` 21 to 26).

`jcmd <pid> Thread.print` prints every thread with its stack. With 20 clients connected I counted the lines that start a thread: 47, against `process_platform_threads` 27, because `Thread.print` also lists the JVM's own internal threads (for example the VM thread). The metric counts only Java threads.

## Walkthrough of this repository's use

The server exposes its own process numbers at `/metrics`. This is the code that writes them (it lives in `feed/FeedMetrics.java`):

```java
  }

  private static void appendProcess(StringBuilder text) {
    Runtime runtime = Runtime.getRuntime();
    line(text, "process_platform_threads", ManagementFactory.getThreadMXBean().getThreadCount());
    line(text, "process_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory());
    line(text, "process_available_processors", runtime.availableProcessors());
    OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean();
    if (system instanceof com.sun.management.OperatingSystemMXBean process) {
      line(text, "process_cpu_nanoseconds_total", process.getProcessCpuTime());
    }
    if (system instanceof UnixOperatingSystemMXBean unix) {
      line(text, "process_open_fds", unix.getOpenFileDescriptorCount());
      line(text, "process_max_fds", unix.getMaxFileDescriptorCount());
```

Line by line. `ManagementFactory.getThreadMXBean().getThreadCount()` is the live platform thread count. `runtime.totalMemory() - runtime.freeMemory()` is the heap in use. `availableProcessors()` is the logical processor count. The operating-system bean, cast to the JDK's `com.sun.management` version, gives `getProcessCpuTime()`: CPU time of the process, in nanoseconds. On Unix (`UnixOperatingSystemMXBean`) it also gives the open and the maximum file-descriptor count. These are the five `process_*` lines the comparison subtracts. (On Windows the last two lines are simply missing.)

The comparison client uses them like this: CPU from the counter before and after the measured phase; threads and fds from a reading taken with every client connected and idle; heap from a reading right after a forced garbage collection (the admin route `settle`); RSS from `ps`.

## Try it yourself

All of these only read. Start a node, read it with the tools, stop it.

1. Start a node in a copy of the repository (use unusual ports so you do not clash with the real ones). Copy `config/serve.properties` to `config/measure.properties` and change the two ports and the origin, for example `18090` and `18091`, and `serve.seconds=150`. Then `./gradlew -q serve --args='config/measure.properties'`. It prints:

```text
SeatWave live feed on http://127.0.0.1:18090 and ws://127.0.0.1:18091 for 150 s; on-sale at 20 actions per second; at most 5000 connections
```

2. In another terminal, find the JVM and read it:

```text
jcmd -l
curl -s http://127.0.0.1:18090/metrics | grep -E '^process_'
ps -o pid=,rss=,vsz= -p <pid>
jcmd <pid> GC.heap_info
```

Output from my run (the pid, memory and times differ on every run). `jcmd -l` listed `89745 io.plainround.seatwave.live.ServeCli config/measure.properties` among the JVMs on the machine, and the metrics were:

```text
process_platform_threads 14
process_heap_used_bytes 38758968
process_available_processors 12
process_cpu_nanoseconds_total 615062000
process_open_fds 22
process_max_fds 1048576
```

3. Hold some connections and watch the fds and threads move. Get the position from the seat map, then open 20 streams:

```text
POS=$(curl -s http://127.0.0.1:18090/v1/events/1/seat-map | sed 's/.*"position":"\([^"]*\)".*/\1/')
for i in $(seq 20); do curl -s -N --max-time 25 "http://127.0.0.1:18090/v1/events/1/seat-changes/stream?after=$POS" >/dev/null & done
```

While they ran, `curl -s http://127.0.0.1:18090/metrics` showed `seatwave_feed_open_connections{method="sse"} 20`, `seatwave_feed_handler_virtual_threads 21`, `process_platform_threads 27` and `process_open_fds 43`. After they ended: `process_open_fds 23`, `seatwave_feed_open_connections{method="sse"} 0`.

(A first attempt with the position `00000000.0` opened no connection: that position belongs to another stream, so the server answered with a reload and closed the stream. This is the feed refusing to guess.)

4. Compute the cores yourself. Read `process_cpu_nanoseconds_total` twice, a few seconds apart, and divide the difference by 1e9 and by the seconds between the readings. Mine gave 0.0041 cores.

5. Stop the node (it stops itself after `serve.seconds`, or press Ctrl+C in its terminal, or `kill <pid>`).

I did not run `ulimit -n` with a lower value, `launchctl limit` with arguments that set anything, or `sysctl -w`: those change settings, and these notes change none.

## Mistakes people make

- **Reporting the average.** It hides the tail; report p50, p99 and max.
- **Taking p99 of too few samples.** With 50 samples p99 is the maximum. The comparison collects tens of thousands of delays per method.
- **Confusing virtual size with memory.** `vsz` is reserved address space. Look at RSS.
- **Reading the heap without collecting first.** Garbage not yet freed inflates it.
- **Forgetting that the client uses file descriptors too.** A load test needs the limit in both processes.
- **Raising `ulimit -n` and thinking the system limit changed.** It changes only that shell and its children, within the hard limit.
- **Reading CPU as a percentage of one core.** `cores = 1.73` means 173 percent of one core. The ceiling is the number of processors.
- **Measuring the server and the clients in one process.** Their threads, heap and CPU add up. The comparison starts the server as a child process.

## Check yourself

1. A server answered 1,000 requests; the 990th fastest took 40 ms and the slowest took 900 ms. What are p99 and max? (p99 is 40 ms, max is 900 ms.)
2. Why do 1,000 client connections need about 2,000 file descriptors in total? (One per connection in the server process and one in the client process.)
3. A counter of CPU time grows by 6 seconds over 3 seconds of real time. How many cores were used? (2.)
4. Why can a process have a resident set of 100 MB while its heap holds 7 MB? (Thread stacks, socket buffers, code cache, class data, committed but unused heap pages and native libraries are outside the used heap.)
5. What does `kern.ipc.somaxconn` limit, and how does the fleet respect it? (The length of the queue of connections waiting to be accepted; the fleet connects 250 clients per second instead of all at once.)

## Further reading

- The `ulimit` utility, POSIX Shell Command Language: https://pubs.opengroup.org/onlinepubs/9799919799/utilities/ulimit.html
- The `jcmd` tool, JDK 25: https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html
- `com.sun.management.UnixOperatingSystemMXBean`, JDK 25 API: https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/com/sun/management/UnixOperatingSystemMXBean.html
- `java.lang.management.ThreadMXBean`, JDK 25 API: https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/ThreadMXBean.html
- Garbage-First (G1) collector, JDK 25 guide: https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html
- `getrlimit`, POSIX: https://pubs.opengroup.org/onlinepubs/9799919799/functions/getrlimit.html
