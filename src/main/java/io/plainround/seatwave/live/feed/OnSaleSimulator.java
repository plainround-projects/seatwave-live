package io.plainround.seatwave.live.feed;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Plays an on-sale against the seat store: at a fixed rate it either holds a random available seat
 * or confirms one of its holds, and on every tick it lets due holds expire. The changes the feed
 * publishes are therefore real store changes (holds, confirms, expiries), not invented events.
 *
 * <p>The random choices come from a seeded generator, so two runs make the same choices in the same
 * order. Thread-safe: a tick runs under the simulator's own lock, and pause takes the same lock, so
 * when {@link #pause()} returns no tick is running and none will change a seat until {@link
 * #resume()}.
 */
public final class OnSaleSimulator implements AutoCloseable {

  private static final int PERCENT = 100;
  // A random seat may be taken; after this many tries the tick gives up rather than scan the map.
  private static final int HOLD_ATTEMPTS = 8;

  private final SeatStore store;
  private final Random random;
  private final int confirmPercent;
  private final Duration tickInterval;
  private final ArrayDeque<Integer> heldByUs = new ArrayDeque<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> Thread.ofPlatform().name("on-sale").daemon().unstarted(runnable));
  // Held for a whole tick and by pause, so a tick already running finishes before pause returns.
  private final ReentrantLock tickLock = new ReentrantLock();
  private boolean paused;

  /**
   * Creates the simulator; it does nothing until {@link #start()}.
   *
   * @param store the seat store to act on
   * @param actionsPerSecond how many holds or confirms to make per second
   * @param confirmPercent the share of actions that confirm a hold instead of making one
   * @param seed the seed of the random choices
   */
  public OnSaleSimulator(SeatStore store, int actionsPerSecond, int confirmPercent, long seed) {
    this.store = Objects.requireNonNull(store, "store");
    if (actionsPerSecond < 1) {
      throw new IllegalArgumentException("actionsPerSecond must be at least 1");
    }
    if (confirmPercent < 0 || confirmPercent > PERCENT) {
      throw new IllegalArgumentException("confirmPercent must be from 0 to 100");
    }
    this.confirmPercent = confirmPercent;
    this.random = new Random(seed);
    this.tickInterval = Duration.ofSeconds(1).dividedBy(actionsPerSecond);
  }

  /** Starts acting at the configured rate. */
  public void start() {
    scheduler.scheduleAtFixedRate(
        this::tick, tickInterval.toNanos(), tickInterval.toNanos(), TimeUnit.NANOSECONDS);
  }

  /**
   * Stops every change, expiries included, so the seat map stands still and clients can catch up
   * and be compared with the store. It waits for a tick that is already running to finish, so a
   * snapshot taken after it returns is the last state until {@link #resume()}.
   */
  public void pause() {
    tickLock.lock();
    try {
      paused = true;
    } finally {
      tickLock.unlock();
    }
  }

  /** Starts making holds and confirms again. */
  public void resume() {
    tickLock.lock();
    try {
      paused = false;
    } finally {
      tickLock.unlock();
    }
  }

  /**
   * Runs one tick at once, on the caller's thread; used by tests and by {@link #start()}.
   *
   * @return true if the tick made a hold or a confirm
   */
  public boolean tick() {
    tickLock.lock();
    try {
      return !paused && act();
    } finally {
      tickLock.unlock();
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  private boolean act() {
    store.expireDue();
    if (!heldByUs.isEmpty() && random.nextInt(PERCENT) < confirmPercent) {
      // A confirm of an expired hold is refused by the store; that is the rule working, not a bug.
      return !store.confirm(heldByUs.poll()).isError();
    }
    for (int attempt = 0; attempt < HOLD_ATTEMPTS; attempt++) {
      int seatIndex = random.nextInt(store.layout().seatCount());
      if (!store.hold(seatIndex).isError()) {
        heldByUs.add(seatIndex);
        return true;
      }
    }
    return false;
  }
}
