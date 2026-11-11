# Concurrency in the feed: threads, locks, bounded queues, timeouts and interruption

## What it is

**Concurrency** means a program does several things during the same period of time. In Java the unit is the **thread**: one line of execution with its own stack of method calls. A **process** (here, one running Java program) has many threads. They share the same memory, which is both the power and the danger: if two threads change the same variable at once, the result can be wrong in ways that appear one run in a thousand.

A live feed is a concurrent program by nature. One thread makes seat changes (the on-sale). Hundreds or thousands of other threads each serve one client. Each of them waits for something: a change, a heartbeat, the network. The feed has to share one stream of changes between all of them without losing a change, repeating one, or letting one slow client hold up the rest.

This note teaches the tools from zero, in the order this repository uses them, and shows the repository's own lines for each.

## Why this repository uses it

The guarantee of Module 8 is: every change reaches every watching client exactly once, or the client is told to reload; no silent gap; and a slow client costs a bounded amount of memory and cannot slow the others. Each part of that sentence is a concurrency decision. The numbering of changes needs a lock. The per-client waiting line needs a bound. The cutting-off of a stuck client needs a timeout and an interrupt. The cheap thread per connection needs virtual threads.

## Where it appears

| Idea | Where in the code |
|---|---|
| Virtual threads, one per connection | `http/FeedHttpServer.java`, `websocket/WebSocketServer.java`, `websocket/WebSocketSession.java` |
| Platform threads for timers | `feed/OnSaleSimulator.java`, `LiveFeedNode.java` |
| Locks and conditions | `feed/SeatFeed.java`, `feed/Subscriber.java`, `feed/SeatStore.java`, `feed/OnSaleSimulator.java` |
| Atomics, adders, `volatile` | `feed/FeedMetrics.java`, `feed/Subscriber.java`, `websocket/WebSocketSession.java` |
| Bounded queue per connection | `feed/Subscriber.java` |
| Bounded connections, ring, batch, frame, head | `feed/ConnectionLimit.java`, `feed/ReplayBuffer.java`, `config/LiveSettings.java`, `websocket/FrameCodec.java`, `websocket/Handshake.java` |
| Timeouts | `feed/SeatFeed.java`, `feed/Subscriber.java`, `websocket/WebSocketSession.java`, `LiveFeedNode.java` |
| Interruption | `http/LongPollRoute.java`, `http/EventStreamRoute.java`, `feed/Subscriber.java`, `feed/ConnectionLimit.java` |

## How it works underneath

**Platform threads and virtual threads.** A **platform thread** is a thread of the operating system. It has a large stack (hundreds of kilobytes of address space) and the operating system schedules it, so a program can have a few thousand at most. A **virtual thread** (JDK 21, JEP 444) is a thread that the JDK schedules itself, on top of a small set of platform threads called **carriers**. Its stack lives in ordinary memory and grows as needed. When a virtual thread does something that waits (reading a socket, waiting on a lock or a condition, sleeping), the JDK **parks** it: it takes it off the carrier, which then runs another virtual thread. When the wait ends, the virtual thread continues. A program can have a million. That is why this feed can write the simplest code, "one thread per connection that blocks until there is something to do", and still hold thousands of open connections.

**Shared memory and the race.** Suppose two threads both do `count = count + 1`. Each reads the old value, adds one and writes back; if they interleave, one increment is lost. This is a **race condition**. Three families of tools prevent it: **locks** (only one thread at a time runs the guarded code), **atomic** variables (single steps that cannot be interleaved) and **immutable** data (nobody changes it, so nobody can race).

**Visibility.** A thread may keep a copy of a variable in a processor register or cache. A change by another thread may not be seen for a while, or in the order it was made. Java's **memory model** says when a change must become visible: when a thread releases a lock and another acquires the same lock; when a thread writes and another reads a `volatile` variable; when an atomic operation is used.

**A queue between a producer and a consumer.** The on-sale thread produces changes. Each connection's writer thread consumes them at the speed of its client's network. If production is faster than consumption for long enough, the waiting line grows. If the line is unbounded, memory is the limit, and when memory runs out the whole process fails for everyone. A **bounded** queue turns that into a decision that is made in advance: what to do when it is full.

## Walkthrough of this repository's use

### Threads in the feed

Every connection runs on a virtual thread. The HTTP server is given an executor that starts one for each request:

```java
    this.server = server;
    // Virtual threads make a held long poll or an open stream cost a parked thread, not a platform
    // thread (Module 4); the connection limit, not the thread count, bounds the work.
    this.workers = Executors.newVirtualThreadPerTaskExecutor();
    this.parts = parts;
```

The WebSocket server does the same and starts its accept loop on a virtual thread as well:

```java
  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket socket = listener.accept();
        socket.setTcpNoDelay(true);
        sessions.submit(() -> new WebSocketSession(socket, parts, settings).run());
      } catch (IOException acceptFailed) {
        if (!listener.isClosed()) {
          LOG.warn("accept failed: {}", acceptFailed.toString());
        }
      }
    }
  }
```

Each WebSocket session starts a second virtual thread for reading, and keeps its own thread as the only writer:

```java
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      out.write(Handshake.accepted(request));
      out.flush();
      socket.setSoTimeout((int) heartbeat.multipliedBy(MISSED_HEARTBEATS_BEFORE_CLOSE).toMillis());
      Subscriber subscriber = parts.feed().subscribe(position.get());
      Thread reader = Thread.ofVirtual().name("ws-reader").start(() -> readFrames(in, subscriber));
      try {
        writeFrames(out, subscriber);
      } catch (InterruptedException dropped) {
        Thread.currentThread().interrupt();
      } finally {
        parts.feed().unsubscribe(subscriber);
        closeQuietly();
        reader.interrupt();
      }
    }
  }
```

The two timers are on platform threads, named so they can be found in a thread dump, and marked daemon (a daemon thread does not keep the program alive when the main thread ends):

```java
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> Thread.ofPlatform().name("on-sale").daemon().unstarted(runnable));
  // Held for a whole tick and by pause, so a tick already running finishes before pause returns.
  private final ReentrantLock tickLock = new ReentrantLock();
  private boolean paused;
```

The on-sale's fields end with its own lock, `tickLock`, and the `paused` flag that the lock guards. They are explained under "Locks, conditions and the lock order" below.

```java
    Duration writeTimeout = settings.timing().writeTimeout();
    long periodNanos = Math.max(1, writeTimeout.toNanos() / CHECKS_PER_WRITE_TIMEOUT);
    ScheduledExecutorService watchdog =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().name("stuck-writers").daemon().unstarted(runnable));
    watchdog.scheduleAtFixedRate(
        () -> feed.disconnectStuckWriters(writeTimeout),
        periodNanos,
        periodNanos,
        TimeUnit.NANOSECONDS);
    return new LiveFeedNode(parts, webSocketServer, httpServer, watchdog);
```

On a real node I asked `/metrics` for the thread counts while 200 clients had an event stream open. `seatwave_feed_handler_virtual_threads` went from 1 (the metrics request itself) to 201, `process_open_fds` (open file descriptors) from 22 to 222, and `process_platform_threads` from 14 to 26: 200 more connections, 12 more platform threads. I did not look into what the 12 are; the JDK's own scheduler for virtual threads keeps a pool of carrier threads, which is the likely reason. The JVM's own thread count does not include virtual threads, which is why `FeedMetrics` counts them itself:

```java
  /**
   * Counts a virtual thread that serves a connection, from start to end, because the JVM's own
   * thread count does not include virtual threads.
   *
   * @param running true when the thread starts, false when it ends
   */
  public void recordHandlerThread(boolean running) {
    if (running) {
      handlerThreads.incrementAndGet();
    } else {
      handlerThreads.decrementAndGet();
    }
  }
```

### Locks, conditions and the lock order

A **lock** is an object with two operations: `lock()` (wait until no other thread holds it, then hold it) and `unlock()`. The code between them is a **critical section**. The idiom is always the same:

```text
lock.lock();
try { ... guarded work ... } finally { lock.unlock(); }
```

`finally` makes sure the unlock happens even if the work throws. This repository uses `ReentrantLock` (from `java.util.concurrent.locks`): "reentrant" means the thread that holds it may take it again without blocking itself.

A **condition** belongs to a lock. A thread that holds the lock can call `await` on the condition: it gives up the lock, sleeps, and takes the lock back before returning, after another thread called `signal` or `signalAll`, or the time ran out. The check is always in a loop, because a thread can wake up without a signal (a spurious wakeup) and because by the time it runs again the situation may have changed.

There are four locks, with four jobs.

```java
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition published = lock.newCondition();
  private final List<Subscriber> subscribers = new ArrayList<>();
```

The **feed's lock** makes numbering a change, appending it to the ring and offering it to every subscriber one indivisible step. Its condition `published` is what long polls wait on. Here is the guarded step, with the comment about the order of the locks:

```java
  // Called by SeatStore while it holds its own lock, so changes are numbered in the order the store
  // made them. The lock order is always store, then feed.
  SeatChange publish(int seatIndex, SeatState state, Instant changedAt) {
    lock.lock();
    try {
      SeatChange change = new SeatChange(buffer.position().next(), seatIndex, state, changedAt);
      buffer.append(change);
      for (Subscriber subscriber : subscribers) {
        // A bounded, non-blocking offer: a slow client can fill only its own queue, never stop
        // this loop or the store behind it.
        subscriber.offer(change);
      }
      published.signalAll();
      metrics.recordPublished();
      return change;
    } finally {
      lock.unlock();
    }
  }
```

The **store's lock** makes "check the seat's state and change it" one indivisible step, so two fans cannot both hold the same seat. Every change is published while it is held, so the feed's order is the store's order. The store calls into the feed, so the rule is **store, then feed**. If any code ever took the feed's lock first and then asked for the store's, one thread could hold the feed and wait for the store while another held the store and waited for the feed: a **deadlock**, where nobody ever moves again. `SeatStore.snapshot` follows the same order: it holds the store's lock and calls `feed.position()`.

```java
  /**
   * The whole map and the feed position it matches, read in one step.
   *
   * @return the snapshot
   */
  public SeatMapSnapshot snapshot() {
    lock.lock();
    try {
      // Under the store's lock no change can be made, so the position read here is exactly the
      // last change the states include.
      StringBuilder codes = new StringBuilder(states.length);
      for (SeatState state : states) {
        codes.append(state.code());
      }
      return new SeatMapSnapshot(feed.position(), layout, codes.toString());
    } finally {
      lock.unlock();
    }
  }
```

Each subscriber has a **third lock**, taken by the feed during `offer` (feed, then subscriber) and by the writer in `next` (subscriber only). The writer never asks for the feed's lock while holding its own, so the order feed, then subscriber, cannot be reversed.

The simulated on-sale has the **fourth lock**, `tickLock`. A tick holds it from start to end, and `pause()` takes it to set the flag:

```java
  public void pause() {
    tickLock.lock();
    try {
      paused = true;
    } finally {
      tickLock.unlock();
    }
  }
```

```java
  public boolean tick() {
    tickLock.lock();
    try {
      return !paused && act();
    } finally {
      tickLock.unlock();
    }
  }
```

Why a lock and not a `volatile` flag, as in version 1.0.0? A flag can only stop the *next* tick. A tick that has already read "not paused" goes on and changes a seat, maybe after `pause()` has returned and after the caller has taken the store's snapshot. The reconnect test then saw a client's map one seat away from a snapshot that was already out of date, about twice in 65 runs. With the lock, `pause()` cannot take it while a tick runs, so it returns only after that tick has finished, and every later tick sees `paused` and changes nothing. "Paused" now means: no tick is running and none will start. `paused` needs no `volatile` any more, because every read and write of it happens under the lock, and a lock makes a write visible to the next thread that takes it. The lock order is simulator, then store, then feed: a tick holds `tickLock` while it calls the store, and no code that holds the store's or the feed's lock ever asks for `tickLock`.

The long poll's wait shows a condition in a loop:

```java
  public Catchup awaitChangesAfter(FeedPosition known, Duration wait) throws InterruptedException {
    Objects.requireNonNull(known, "known");
    long remainingNanos = wait.toNanos();
    lock.lockInterruptibly();
    try {
      Catchup catchup = buffer.after(known, maxBatch);
      while (catchup instanceof Catchup.Changes changes
          && changes.changes().isEmpty()
          && remainingNanos > 0) {
        remainingNanos = published.awaitNanos(remainingNanos);
        catchup = buffer.after(known, maxBatch);
      }
      return catchup;
    } finally {
      lock.unlock();
    }
  }
```

`published.awaitNanos(remainingNanos)` releases the feed's lock while waiting, so `publish` can run. It returns how much of the wait is left, which the loop uses; when it is zero or less the wait is over. `lockInterruptibly` is explained under "Interruption" below.

**Held briefly, never across I/O.** The Javadoc says "The lock is held only to number, copy references and offer to bounded queues, never across I/O". This is the rule that keeps a slow network from slowing the feed. A lock that is held while writing to a socket would make every other thread that needs it wait for the slowest client. In this code, no lock is held while a socket is read or written: `Subscriber.next` returns a batch and releases its lock before the writer writes; the watchdog copies the subscriber list under the feed's lock and releases the lock before it interrupts anyone:

```java
  /**
   * Interrupts the writer of every subscriber whose current write has lasted longer than the
   * timeout, which closes that connection.
   *
   * @param writeTimeout the longest one write may take
   * @return how many writers were interrupted
   */
  public int disconnectStuckWriters(Duration writeTimeout) {
    return disconnectStuckWriters(writeTimeout, System.nanoTime());
  }

  // The time of the check is a parameter, so whether a write counts as stuck depends only on the
  // two readings of System.nanoTime, never on how long the check itself takes to run.
  int disconnectStuckWriters(Duration writeTimeout, long nowNanos) {
    List<Subscriber> current;
    lock.lock();
    try {
      current = List.copyOf(subscribers);
    } finally {
      lock.unlock();
    }
    int interrupted = 0;
    for (Subscriber subscriber : current) {
      if (subscriber.interruptIfStuck(nowNanos, writeTimeout)) {
        metrics.recordStuckDisconnect();
        interrupted++;
      }
    }
    return interrupted;
  }
```

The public method reads the clock once, with `System.nanoTime()`, and hands the reading to a package-private one that does the work. Whether a write counts as stuck then depends only on two readings of the clock, the one taken when the write began and the one taken here, and not on how long the loop itself takes to run. The test of this method uses that: it passes a reading of its own and gets the same answer on a fast machine and a loaded one (`tests-feed-walkthrough.md`).

### Atomics and `volatile`

An **atomic** class such as `AtomicInteger` offers operations like `incrementAndGet()` that happen as one step, so two threads cannot lose an increment. A **`LongAdder`** is a counter made for many threads adding at once: it spreads the additions over several cells and adds them up when you read it, so threads do not collide on one memory location. It is the right choice for counters that are written often and read rarely.

```java
  private final LongAdder changesPublished = new LongAdder();
  private final LongAdder overflows = new LongAdder();
  private final LongAdder stuckDisconnects = new LongAdder();
  private final LongAdder longPollReplies = new LongAdder();
  private final AtomicInteger handlerThreads = new AtomicInteger();
  private final AtomicInteger deepestQueue = new AtomicInteger();
  private final Map<DeliveryMethod, AtomicInteger> openConnections = perMethod(AtomicInteger::new);
  private final Map<DeliveryMethod, LongAdder> connectionsAccepted = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> connectionsRefused = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> changesDelivered = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> reloadsSent = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> bytesWritten = perMethod(LongAdder::new);
```

`recordQueueDepth` uses `accumulateAndGet(depth, Math::max)`, which keeps the larger of two numbers as one step even if two threads race:

```java
  /**
   * Remembers the deepest any subscriber's queue has been, to show the bound holds.
   *
   * @param depth the queue's length after an offer
   */
  public void recordQueueDepth(int depth) {
    deepestQueue.accumulateAndGet(depth, Math::max);
  }
```

A **`volatile`** field is one whose reads and writes are always seen by every thread at once, with no lock; it does not make `x = x + 1` safe, but a single write read by others is. The subscriber uses it for the two values the watchdog reads (the time the current write started, and the writer's thread):

```java
  private static final long NOT_WRITING = 0;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition ready = lock.newCondition();
  private final ArrayDeque<SeatChange> queue;
  private final int capacity;
  private final int maxBatch;
  private final FeedMetrics metrics;
  private boolean reloadPending;
  private boolean woken;
  private volatile long writeStartedNanos = NOT_WRITING;
  private volatile Thread writer;
```

An **`AtomicReference`** holds one object that threads can replace safely. The WebSocket session uses two as mailboxes between the reader thread and the writer thread:

```java
  private final Socket socket;
  private final Parts parts;
  private final String allowedOrigin;
  private final Duration heartbeat;
  private final Duration handshakeTimeout;
  private final long retryAfterSeconds;
  private final AtomicReference<byte[]> pongDue = new AtomicReference<>();
  private final AtomicReference<CloseCode> closeDue = new AtomicReference<>();

  WebSocketSession(Socket socket, Parts parts, SessionSettings settings) {
    this.socket = socket;
    this.parts = parts;
    this.allowedOrigin = settings.allowedOrigin();
    this.heartbeat = settings.heartbeat();
    this.handshakeTimeout = settings.handshakeTimeout();
    this.retryAfterSeconds = Math.max(1, settings.retryAfter().toSeconds());
  }
```

`getAndSet(null)` takes the waiting value and empties the mailbox as one step, so a ping that arrives at the same time is either taken now or left for the next round, never lost:

```java
      byte[] pong = pongDue.getAndSet(null);
      if (pong != null) {
        write(subscriber, out, FrameCodec.encode(Frame.PONG, pong), 0);
      }
```

### Why every per-connection queue is bounded

Every push connection has a `Subscriber` with a queue whose size is `feed.clientQueueCapacity` (1,024 in `config/serve.properties`, 256 in the slow-consumer lab). The feed puts each new change into every subscriber's queue with `offer`. The writer thread takes them out with `next`.

```java
  Subscriber(int capacity, int maxBatch, FeedMetrics metrics) {
    if (capacity < 1 || maxBatch < 1) {
      throw new IllegalArgumentException("capacity and maxBatch must be at least 1");
    }
    this.capacity = capacity;
    this.maxBatch = maxBatch;
    this.queue = new ArrayDeque<>(capacity);
    this.metrics = metrics;
  }
```

If the queue were unbounded, a client that stops reading would let it grow without end. At 20 changes per second a stalled client would grow its queue by 1,200 entries a minute, and at the lab's 500 per second by 30,000. A thousand stalled clients would multiply that. The process would run out of memory and fail for every client. The bound makes the worst case a number you choose.

There are other bounds, each one stopping a different kind of growth:

| What is bounded | Setting or constant | Code |
|---|---|---|
| Changes waiting for one client | `feed.clientQueueCapacity` | `feed/Subscriber.java` |
| Changes kept for resuming | `feed.replayCapacity` (a ring: the oldest is overwritten) | `feed/ReplayBuffer.java` |
| Changes per write or per long-poll reply | `feed.maxBatch` | `feed/Subscriber.java`, `feed/ReplayBuffer.java` |
| Open connections | `feed.maxConnections` | `feed/ConnectionLimit.java` |
| Query length | 256 characters | `http/Query.java` |
| Handshake head | 8,192 bytes | `websocket/Handshake.java` |
| Frame payload from a client | 125 bytes | `websocket/WebSocketSession.java`, `websocket/FrameCodec.java` |

**What happens when a queue fills.** The `offer` method decides:

```java
  void offer(SeatChange change) {
    lock.lock();
    try {
      if (reloadPending) {
        // The client will reload anyway; queuing more would only use memory.
        return;
      }
      if (queue.size() == capacity) {
        queue.clear();
        reloadPending = true;
        metrics.recordOverflow();
      } else {
        queue.add(change);
        metrics.recordQueueDepth(queue.size());
      }
      ready.signal();
    } finally {
      lock.unlock();
    }
  }
```

With a capacity of 4, the first four changes are queued. The fifth finds the queue full. Instead of queuing it, the subscriber throws away all four, sets `reloadPending`, and counts an overflow. From now on every `offer` returns at once. The writer's next call to `next` returns `Delivery.Reload`, and the writer sends one reload message, closes the connection, and the client loads the whole seat map and starts again. For a seat map this is the right policy: a client far behind needs the current state, not its backlog, and one reload costs less than replaying thousands of changes. The client's map is never wrong, because it never receives a partial history: it is told to start over.

The writer's side, the other half of the exchange:

```java
  /**
   * Waits for the next thing to send.
   *
   * @param heartbeat how long to wait before answering {@link Delivery.Idle}
   * @return a batch of up to the batch size, a reload, an idle tick, or a wake-up
   * @throws InterruptedException if the connection is being dropped
   */
  public Delivery next(Duration heartbeat) throws InterruptedException {
    long remainingNanos = heartbeat.toNanos();
    lock.lockInterruptibly();
    try {
      while (queue.isEmpty() && !reloadPending && !woken) {
        if (remainingNanos <= 0) {
          return new Delivery.Idle();
        }
        remainingNanos = ready.awaitNanos(remainingNanos);
      }
      if (reloadPending) {
        return new Delivery.Reload();
      }
      if (queue.isEmpty()) {
        woken = false;
        return new Delivery.Woken();
      }
      List<SeatChange> batch = new ArrayList<>(Math.min(maxBatch, queue.size()));
      while (!queue.isEmpty() && batch.size() < maxBatch) {
        batch.add(queue.poll());
      }
      return new Delivery.Batch(batch);
    } finally {
      lock.unlock();
    }
  }
```

`next` returns a batch of up to `maxBatch` changes at once, so a writer that was busy catches up with one write and one flush instead of one write per change.

A catch-up read at subscription time uses the same `offer`, so a catch-up longer than the queue's bound also ends in a reload. The bound applies from the first change.

The slow-consumer lab runs this for real: 50 normal clients and slow ones that stop reading, on-sale at 500 changes per second, queue bound 256, write timeout 2,000 ms. I ran it from the repository folder on 11 November 2026, with version 1.1.0:

```bash
./gradlew -q slowConsumer --args='config/slow-consumer.properties 50 10'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process may open 1048576 files
settings: client queue 256 changes, write timeout 2000 ms, on-sale 500 actions per second for 10 s
changes published: 6298
deepest client queue: 256 (bound 256)
queue overflows (client told to reload): 2
stuck writes cut after the write timeout: 2
open connections at the end: sse 50, websocket 0 (the 50 normal clients are sse)
slow clients' connections ended by the server (each read to its end): yes
heap after a collection: 57111 KB before, 59721 KB after
normal clients: delay p50 0.58 ms, p99 1.89 ms, max 32.96 ms (314900 samples); 50 of 50 match the store
```

The deepest queue was exactly the bound, never above it. Two overflows and two cut writes (the two slow clients), and both slow clients, reading at last, found that the server had closed their connections. The 50 normal clients still saw a median delay of 0.58 ms and all 50 ended with a map equal to the store's. The README has its own run of the same command; the figures differ a little on every run.

### How the code avoids one slow reader slowing the others

Follow what a slow client touches:

1. The **publisher** (inside `SeatFeed.publish`) only calls `Subscriber.offer`. `offer` takes that subscriber's own lock, adds a reference or clears the queue, and signals. It never touches a socket and never waits for the client. So the publisher's time per change does not depend on any client's speed:

```java
      for (Subscriber subscriber : subscribers) {
        // A bounded, non-blocking offer: a slow client can fill only its own queue, never stop
        // this loop or the store behind it.
        subscriber.offer(change);
      }
```

2. Each client's **writer** is its own virtual thread. If its socket stops accepting bytes, only that thread blocks. The other writers wait on their own subscribers and are not affected.
3. The **queue bound** stops its memory from growing.
4. The **watchdog** cuts off a writer that is blocked in one write for longer than `feed.writeTimeoutMillis`, which frees the thread, the socket and the connection slot.

### Timeouts on every blocking call

A **blocking call** is one that does not return until something happens. Any blocking call with no time limit is a place where a thread can wait for ever. Here is every place in these packages that blocks, and what ends the wait:

| Blocking call | What ends it |
|---|---|
| `SeatFeed.awaitChangesAfter` (long poll) | `awaitNanos(remaining)` for the hold time (`feed.longPollHoldMillis`); the loop stops when time is up. |
| `Subscriber.next` (push writer) | `awaitNanos(remaining)` for the heartbeat time (`feed.heartbeatMillis`); it returns `Delivery.Idle`. |
| Reading the WebSocket handshake | `socket.setSoTimeout(handshakeTimeout)`, 5 seconds. |
| Reading frames from a WebSocket client | `socket.setSoTimeout(3 x heartbeat)`; a `SocketTimeoutException` ends the connection with code 1001. |
| Writing to a client (SSE or WebSocket) | Java has no write timeout on a blocking socket, so the writer marks the start of each write and the watchdog interrupts a write that lasts longer than `feed.writeTimeoutMillis`. |
| Taking a lock | `lock()` or `lockInterruptibly()` with no limit, because every critical section is a few instructions and never waits for I/O or for another lock held across I/O. |
| Taking a connection slot | `Semaphore.tryAcquire()`, which does not wait: a full feed answers 503 at once. |
| `listener.accept()` in the WebSocket server | No timeout, by design: waiting for the next connection is the accept loop's job; `close()` ends it. |
| `Thread.sleep(serveTime)` in `ServeCli` | The time itself (`serve.seconds`). |

The three places where the code sets a time:

```java
  void run() {
    parts.metrics().recordHandlerThread(true);
    try (socket) {
      // A client that opens a socket and sends nothing must not hold a thread for ever.
      socket.setSoTimeout((int) handshakeTimeout.toMillis());
      InputStream in = new BufferedInputStream(socket.getInputStream());
      OutputStream out = socket.getOutputStream();
```

```java
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      out.write(Handshake.accepted(request));
      out.flush();
      socket.setSoTimeout((int) heartbeat.multipliedBy(MISSED_HEARTBEATS_BEFORE_CLOSE).toMillis());
      Subscriber subscriber = parts.feed().subscribe(position.get());
```

I did not check what read and idle timeouts the JDK's built-in HTTP server applies to a client that connects and sends nothing; this repository does not set any.

### Interruption

**Interrupting** a thread is the polite way to ask it to stop. `thread.interrupt()` sets a flag on the thread. A thread that is blocked in a call such as `awaitNanos`, `sleep` or `lockInterruptibly` wakes up and throws `InterruptedException`, which clears the flag. The rule for whoever catches it: either end the work, or set the flag again with `Thread.currentThread().interrupt()`, so the code above also sees it. Swallowing it hides the request to stop.

The long-poll route follows the rule:

```java
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      Catchup catchup = feed.awaitChangesAfter(position, hold);
      byte[] body = json.longPoll(catchup, layout, position);
      replies.json(exchange, HttpStatus.OK, body);
      metrics.recordLongPollReply();
      switch (catchup) {
        case Catchup.Changes changes ->
            metrics.recordDelivered(
                DeliveryMethod.LONG_POLLING, changes.changes().size(), body.length);
        case Catchup.ReloadRequired reload -> metrics.recordReloadSent(DeliveryMethod.LONG_POLLING);
      }
    } catch (InterruptedException dropped) {
      // The connection is being dropped (the lab's drop, or shutdown): stop and send nothing.
      Thread.currentThread().interrupt();
    }
  }
```

The ticket for the connection holds a `dropAction`, a piece of code that ends the connection from another thread. For the HTTP routes it is `handler::interrupt` or `writer::interrupt`, which wake a parked wait. For a WebSocket it is closing the socket:

```java
  /**
   * Admits one connection if a slot is free.
   *
   * @param method the connection's delivery method
   * @param dropAction how to end the connection from another thread, for example by interrupting
   *     its writer thread or closing its socket
   * @return a ticket to close when the connection ends, or empty when the limit is reached
   */
  public Optional<Ticket> admit(DeliveryMethod method, Runnable dropAction) {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(dropAction, "dropAction");
    if (!slots.tryAcquire()) {
      metrics.recordRefused(method);
      return Optional.empty();
    }
    Ticket ticket = new Ticket(method, dropAction);
    open.add(ticket);
    metrics.recordOpened(method);
    return Optional.of(ticket);
  }

  /**
   * Ends every open connection at once, as a crash or a careless deploy would.
   *
   * @return how many were dropped
   */
  public int dropAll() {
    int dropped = 0;
    for (Ticket ticket : Set.copyOf(open)) {
      ticket.dropAction.run();
      dropped++;
    }
    return dropped;
  }
```

When the watchdog finds a writer stuck in a write, it calls `interrupt()` on that thread. For a virtual thread that is blocked in a socket write, the JDK closes the socket and the write fails with an exception; that frees the thread and the connection:

```java
  boolean interruptIfStuck(long nowNanos, Duration writeTimeout) {
    long started = writeStartedNanos;
    Thread stuck = writer;
    if (started == NOT_WRITING || stuck == null) {
      return false;
    }
    if (nowNanos - started < writeTimeout.toNanos()) {
      return false;
    }
    // Interrupting a thread blocked in a socket write closes the socket (an interruptible channel
    // on the JDK's HTTP server, a virtual thread's socket on the WebSocket server), which frees the
    // thread and the connection.
    stuck.interrupt();
    return true;
  }
}
```

The lab above shows it working: "stuck writes cut after the write timeout: 2". The event-stream route treats the resulting `IOException` as an ordinary end:

```java
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      Subscriber subscriber = feed.subscribe(position);
      try {
        stream(exchange, subscriber);
      } finally {
        feed.unsubscribe(subscriber);
      }
    } catch (InterruptedException dropped) {
      Thread.currentThread().interrupt();
    } catch (IOException clientGone) {
      // A client that closes its stream, or a write the watchdog interrupted, ends here.
      LOG.debug("event stream ended: {}", clientGone.toString());
    }
  }
```

## Try it yourself

All run in a copy of the repository on the verification machine (an ARM-based Mac, Eclipse Temurin 25.0.2). Numbers differ on every run.

**1. The slow-consumer lab.** Shown above. It takes about 12 seconds. From the repository folder:

```bash
./gradlew -q slowConsumer --args='config/slow-consumer.properties 50 10'
```

Look for `deepest client queue: 256 (bound 256)` and `queue overflows (client told to reload): 2`.

**2. Virtual threads at work.** Start the node (`./gradlew -q serve --args='config/serve.properties'`), then read the thread numbers, open 200 event streams for six seconds, and read them again:

```bash
H=http://127.0.0.1:8090
curl -s $H/metrics | grep -E 'handler_virtual|platform_threads|open_conn.*sse|process_open_fds'
P=$(curl -s $H/v1/events/1/seat-map | sed 's/.*"position":"\([^"]*\)".*/\1/')
seq 1 200 | xargs -P 200 -I{} curl -s -N --max-time 6 "$H/v1/events/1/seat-changes/stream?after=$P" -o /dev/null &
sleep 3
curl -s $H/metrics | grep -E 'handler_virtual|platform_threads|open_conn.*sse|process_open_fds|deepest'
```

My run (on ports 18590 and 18591, because other work used 8090) printed, before and then during:

```text
seatwave_feed_handler_virtual_threads 1
seatwave_feed_open_connections{method="sse"} 0
process_platform_threads 14
process_open_fds 22
```

```text
seatwave_feed_handler_virtual_threads 201
seatwave_feed_deepest_client_queue 8
seatwave_feed_open_connections{method="sse"} 200
process_platform_threads 26
process_open_fds 222
```

After the streams ended, the virtual-thread count went back to 1 and the open count to 0. Stop the node with Ctrl+C.

**3. A silent WebSocket client is cut off after three heartbeats.** See `real-time-protocols.md`, "Try it yourself", command 4: with a heartbeat of 400 ms the server sent pings at 0.60 s and 1.00 s and a close with code 1001 at 1.22 s.

## Mistakes people make

- **An unbounded queue between a fast producer and a slow consumer.** It hides the problem until the process runs out of memory. Decide the bound and the action at the bound.
- **Holding a lock while doing I/O.** Every thread that needs the lock then waits for the slowest network. Hold locks for a few instructions.
- **Locks taken in different orders in different places.** That is how deadlocks start. Write the order down (here, in a comment) and keep it.
- **Waiting on a condition with `if`.** Use `while`; spurious wakeups and changed conditions are real.
- **Swallowing `InterruptedException`.** Restore the flag or stop.
- **A blocking call with no time limit.** One lost client then holds a thread for ever. Give every wait a limit, or a second thread that watches it.
- **Using `synchronized` or a long critical section with virtual threads without thinking.** This repository avoids both: it uses `ReentrantLock` and short sections. (From JDK 24 a virtual thread no longer stays pinned to its carrier while it waits inside `synchronized`, JEP 491; the repository does not rely on that.)
- **Counting threads with the JVM's own count when you use virtual threads.** It leaves them out. The feed counts its own.

## Check yourself

1. **Why does `Subscriber.offer` clear the queue when it is full instead of waiting for room?** Waiting would block the publisher, and with it every client. Clearing and marking for reload keeps the publisher fast and the memory bounded, and the client recovers with one reload.
2. **Which lock is taken first when both are needed?** The store's, then the feed's (and the on-sale's tick lock before both). The reverse order could deadlock.
3. **Why is `Condition.awaitNanos` called in a `while` loop?** A thread can wake without a signal, and the situation may have changed again before it runs; the condition must be checked again each time.
4. **How is a client that stopped reading cut off, when a blocking socket write has no timeout?** The writer marks the time each write begins; the watchdog, which runs four times per write timeout, interrupts a writer whose write is older than the timeout; the interrupt closes the socket.
5. **What does a thread have to do after catching `InterruptedException` if it does not stop?** Set the interrupt flag again with `Thread.currentThread().interrupt()`.

## Further reading

- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- JEP 491, Synchronize Virtual Threads without Pinning: https://openjdk.org/jeps/491
- Java SE 25 API, `java.util.concurrent`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/package-summary.html
- Java SE 25 API, `java.util.concurrent.locks`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/package-summary.html
- The Java Language Specification, Java SE 25, Chapter 17, Threads and Locks: https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html
