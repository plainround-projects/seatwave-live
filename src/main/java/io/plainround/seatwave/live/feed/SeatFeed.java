package io.plainround.seatwave.live.feed;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The seat-change publisher: it numbers every change the store makes, keeps the recent ones in a
 * bounded replay buffer, and hands each one to every subscribed connection.
 *
 * <p><b>The invariant.</b> A client that names its last position receives every later change
 * exactly once, or is told to reload the seat map; never a silent gap. It holds because three
 * things happen under one lock: numbering a change and appending it to the buffer ({@link
 * #publish}); reading a catch-up from the buffer ({@link #awaitChangesAfter}); and, for a push
 * connection, reading its catch-up and registering it for live changes in the same step ({@link
 * #subscribe}). No change can fall between the catch-up and the live stream, and none can be in
 * both.
 *
 * <p>Thread-safe. The lock is held only to number, copy references and offer to bounded queues,
 * never across I/O: writers run on their own threads and take changes from their subscriber.
 */
public final class SeatFeed {

  private static final int STREAM_ID_BYTES = 4;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition published = lock.newCondition();
  private final List<Subscriber> subscribers = new ArrayList<>();
  private final ReplayBuffer buffer;
  private final String streamId;
  private final FeedMetrics metrics;
  private final int queueCapacity;
  private final int maxBatch;

  /**
   * Creates an empty feed with a new random stream identifier.
   *
   * @param replayCapacity how many recent changes to keep for clients that resume
   * @param queueCapacity how many changes one push connection may have waiting before it is told to
   *     reload
   * @param maxBatch the most changes one write or one long-poll reply carries
   * @param metrics where the feed counts what it does
   */
  public SeatFeed(int replayCapacity, int queueCapacity, int maxBatch, FeedMetrics metrics) {
    this.streamId = HexFormat.of().formatHex(new SecureRandom().generateSeed(STREAM_ID_BYTES));
    this.buffer = new ReplayBuffer(streamId, replayCapacity);
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.queueCapacity = queueCapacity;
    this.maxBatch = maxBatch;
  }

  /**
   * The position of the newest change.
   *
   * @return the position; sequence 0 before the first change
   */
  public FeedPosition position() {
    lock.lock();
    try {
      return buffer.position();
    } finally {
      lock.unlock();
    }
  }

  /**
   * The changes after a client's position, waiting until there is at least one or the wait ends:
   * the heart of a long poll.
   *
   * @param known the client's position
   * @param wait the longest time to hold the request
   * @return changes (none if the wait ended first), or a reload instruction
   * @throws InterruptedException if the request is being dropped
   */
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

  /**
   * Registers a push connection that already has the seat map up to {@code known}: its subscriber
   * starts with every change after that position, then receives every new change.
   *
   * @param known the client's position
   * @return the subscriber; if the buffer no longer covers the position, it is already marked for
   *     reload
   */
  public Subscriber subscribe(FeedPosition known) {
    Objects.requireNonNull(known, "known");
    Subscriber subscriber = new Subscriber(queueCapacity, maxBatch, metrics);
    lock.lock();
    try {
      FeedPosition reached = known;
      // A catch-up longer than one batch is read in batches; all of it is read under this one
      // lock, so no change can be published in between.
      while (true) {
        Catchup catchup = buffer.after(reached, maxBatch);
        if (catchup instanceof Catchup.ReloadRequired) {
          subscriber.markForReload();
          break;
        }
        Catchup.Changes changes = (Catchup.Changes) catchup;
        subscriber.addAll(changes.changes());
        if (changes.changes().isEmpty()) {
          break;
        }
        reached = changes.position();
      }
      subscribers.add(subscriber);
      return subscriber;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes a push connection that ended.
   *
   * @param subscriber the subscriber {@link #subscribe} returned
   */
  public void unsubscribe(Subscriber subscriber) {
    lock.lock();
    try {
      subscribers.remove(subscriber);
    } finally {
      lock.unlock();
    }
  }

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

  /**
   * The stream identifier of this feed, part of every position.
   *
   * @return eight lowercase hexadecimal digits
   */
  public String streamId() {
    return streamId;
  }

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
}
