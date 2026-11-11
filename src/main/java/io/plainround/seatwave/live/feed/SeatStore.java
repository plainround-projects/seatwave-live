package io.plainround.seatwave.live.feed;

import com.github.sviperll.result4j.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The event's seats and their states, in memory: holds that expire, and confirms. Every state
 * change is published to the feed inside the store's lock, so the feed's order is the store's
 * order.
 *
 * <p>This store stands in for Module 7's database tables to keep the module about delivery. Its
 * rules are Module 1's: a seat is held only if it is available, and confirmed only by a hold that
 * has not expired. One lock guards the whole map; in production the database's conditional {@code
 * UPDATE} enforces the rule and an outbox or change stream feeds the publisher (Module 27).
 *
 * <p>Thread-safe: every method takes the lock.
 */
public final class SeatStore {

  private final ReentrantLock lock = new ReentrantLock();
  private final SeatLayout layout;
  private final SeatState[] states;
  private final Instant[] holdExpiry;
  // Holds in the order they were made; with one hold time for all, that is also expiry order.
  private final ArrayDeque<HoldEntry> holdsByExpiry = new ArrayDeque<>();
  private final Duration holdTime;
  private final Clock clock;
  private final SeatFeed feed;

  /**
   * Creates a store with every seat available.
   *
   * @param layout the seat map's shape
   * @param holdTime how long a hold lasts before it expires
   * @param clock the source of the time, injected so tests control it
   * @param feed where every change is published
   */
  public SeatStore(SeatLayout layout, Duration holdTime, Clock clock, SeatFeed feed) {
    this.layout = Objects.requireNonNull(layout, "layout");
    this.holdTime = Objects.requireNonNull(holdTime, "holdTime");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.feed = Objects.requireNonNull(feed, "feed");
    if (holdTime.isNegative() || holdTime.isZero()) {
      throw new IllegalArgumentException("holdTime must be positive");
    }
    this.states = new SeatState[layout.seatCount()];
    this.holdExpiry = new Instant[layout.seatCount()];
    Arrays.fill(states, SeatState.AVAILABLE);
  }

  /**
   * Holds an available seat.
   *
   * @param seatIndex the seat
   * @return the change made, or why not
   */
  public Result<SeatChange, SeatRefusal> hold(int seatIndex) {
    if (!layout.contains(seatIndex)) {
      return Result.error(SeatRefusal.UNKNOWN_SEAT);
    }
    lock.lock();
    try {
      if (states[seatIndex] != SeatState.AVAILABLE) {
        return Result.error(SeatRefusal.SEAT_TAKEN);
      }
      Instant now = clock.instant();
      holdExpiry[seatIndex] = now.plus(holdTime);
      holdsByExpiry.add(new HoldEntry(seatIndex, holdExpiry[seatIndex]));
      return Result.success(change(seatIndex, SeatState.HELD, now));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Confirms a held seat whose hold has not expired.
   *
   * @param seatIndex the seat
   * @return the change made, or why not
   */
  public Result<SeatChange, SeatRefusal> confirm(int seatIndex) {
    if (!layout.contains(seatIndex)) {
      return Result.error(SeatRefusal.UNKNOWN_SEAT);
    }
    lock.lock();
    try {
      Instant now = clock.instant();
      if (states[seatIndex] != SeatState.HELD || !now.isBefore(holdExpiry[seatIndex])) {
        return Result.error(SeatRefusal.NOT_HELD);
      }
      holdExpiry[seatIndex] = null;
      return Result.success(change(seatIndex, SeatState.SOLD, now));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Makes every hold whose time has passed available again.
   *
   * @return how many holds expired
   */
  public int expireDue() {
    lock.lock();
    try {
      Instant now = clock.instant();
      int expired = 0;
      while (!holdsByExpiry.isEmpty() && !holdsByExpiry.peek().expiresAt().isAfter(now)) {
        HoldEntry entry = holdsByExpiry.poll();
        // An entry is stale when its seat was confirmed, or expired and held again since: only the
        // seat's current hold may expire it.
        if (states[entry.seatIndex()] == SeatState.HELD
            && entry.expiresAt().equals(holdExpiry[entry.seatIndex()])) {
          holdExpiry[entry.seatIndex()] = null;
          change(entry.seatIndex(), SeatState.AVAILABLE, now);
          expired++;
        }
      }
      return expired;
    } finally {
      lock.unlock();
    }
  }

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

  /**
   * The seat map's shape.
   *
   * @return the layout
   */
  public SeatLayout layout() {
    return layout;
  }

  private SeatChange change(int seatIndex, SeatState state, Instant now) {
    states[seatIndex] = state;
    return feed.publish(seatIndex, state, now);
  }

  private record HoldEntry(int seatIndex, Instant expiresAt) {}
}
