package io.plainround.seatwave.live.feed;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One open push connection's place in the feed: a bounded queue of changes waiting to be written,
 * filled by the feed and drained by the connection's single writer thread.
 *
 * <p><b>The slow-consumer rule.</b> The queue holds at most {@code capacity} changes. When a change
 * arrives and the queue is full, the subscriber drops every queued change and marks itself for
 * reload: the writer's next delivery is {@link Delivery.Reload}, after which the connection ends
 * and the client loads the seat map again. Memory per slow client is therefore bounded by the
 * capacity, never by how slow it is. A writer that stays blocked in one write longer than the write
 * timeout is disconnected by {@link SeatFeed#disconnectStuckWriters}.
 *
 * <p>Thread-safe: the feed offers under its own lock, the writer polls, and any thread may wake the
 * writer; the internal lock is held only to move references, never across I/O.
 */
public final class Subscriber {

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

  Subscriber(int capacity, int maxBatch, FeedMetrics metrics) {
    if (capacity < 1 || maxBatch < 1) {
      throw new IllegalArgumentException("capacity and maxBatch must be at least 1");
    }
    this.capacity = capacity;
    this.maxBatch = maxBatch;
    this.queue = new ArrayDeque<>(capacity);
    this.metrics = metrics;
  }

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

  /**
   * Makes a waiting {@link #next} return {@link Delivery.Woken} at once (or after the queued
   * changes), so the writer can send a control message that another thread asked for.
   */
  public void wake() {
    lock.lock();
    try {
      woken = true;
      ready.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Marks the start of one write, so a stuck writer can be found and disconnected.
   *
   * @param writerThread the thread doing the write
   */
  public void beginWrite(Thread writerThread) {
    writer = writerThread;
    writeStartedNanos = System.nanoTime();
  }

  /** Marks the end of the current write. */
  public void endWrite() {
    writeStartedNanos = NOT_WRITING;
  }

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

  void markForReload() {
    lock.lock();
    try {
      queue.clear();
      reloadPending = true;
      ready.signal();
    } finally {
      lock.unlock();
    }
  }

  void addAll(List<SeatChange> changes) {
    changes.forEach(this::offer);
  }

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
