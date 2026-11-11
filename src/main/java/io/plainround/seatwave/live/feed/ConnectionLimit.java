package io.plainround.seatwave.live.feed;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The hard limit on open feed connections, shared by the three methods, and the list of those
 * connections so they can all be dropped at once (for the reconnect test, and at shutdown).
 *
 * <p>Each open connection costs a file descriptor, kernel buffers and memory (Module 1); a node
 * that accepts every connection fails for all of them when one of those runs out. This limit fails
 * the extra connection alone, at once, with 503 and Retry-After.
 *
 * <p>Thread-safe: a semaphore and a concurrent set.
 */
public final class ConnectionLimit {

  private final Semaphore slots;
  private final Set<Ticket> open = ConcurrentHashMap.newKeySet();
  private final FeedMetrics metrics;

  /**
   * Creates the limit.
   *
   * @param maxConnections the most connections open at once, across all methods
   * @param metrics where opens, closes and refusals are counted
   */
  public ConnectionLimit(int maxConnections, FeedMetrics metrics) {
    if (maxConnections < 1) {
      throw new IllegalArgumentException("maxConnections must be at least 1");
    }
    this.slots = new Semaphore(maxConnections);
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

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

  /**
   * How many connections are open now.
   *
   * @return the count
   */
  public int openCount() {
    return open.size();
  }

  /** One admitted connection; closing it frees the slot exactly once. */
  public final class Ticket implements AutoCloseable {

    private final DeliveryMethod method;
    private final Runnable dropAction;

    private Ticket(DeliveryMethod method, Runnable dropAction) {
      this.method = method;
      this.dropAction = dropAction;
    }

    @Override
    public void close() {
      if (open.remove(this)) {
        slots.release();
        metrics.recordClosed(method);
      }
    }
  }
}
