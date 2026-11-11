package io.plainround.seatwave.live.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * Exponential backoff with full jitter: the wait before reconnect attempt {@code n} is a random
 * time from zero to {@code min(cap, base × 2^n)}. The doubling stops a failing server from being
 * hit harder; the randomness spreads clients that failed at the same moment, so their reconnects do
 * not arrive together (the guidelines' rule 66: backoff, jitter and a cap).
 *
 * <p>Not thread-safe: each client owns one, with its own seeded generator, so runs repeat.
 */
public final class Backoff {

  // Beyond 2^20 the doubling has long passed any sane cap; stopping here avoids overflow.
  private static final int MAX_DOUBLINGS = 20;

  private final Duration base;
  private final Duration cap;
  private final Random random;

  /**
   * Creates the backoff.
   *
   * @param base the ceiling of the first wait
   * @param cap the largest ceiling of any wait
   * @param seed the seed of this client's random waits
   */
  public Backoff(Duration base, Duration cap, long seed) {
    this.base = Objects.requireNonNull(base, "base");
    this.cap = Objects.requireNonNull(cap, "cap");
    if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
      throw new IllegalArgumentException("base must be positive and cap at least base");
    }
    this.random = new Random(seed);
  }

  /**
   * The wait before an attempt.
   *
   * @param attempt 0 for the first retry, then 1, 2 and so on
   * @return a random wait from zero to the attempt's ceiling
   */
  public Duration delay(int attempt) {
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    long ceilingMillis =
        Math.min(cap.toMillis(), base.toMillis() << Math.min(attempt, MAX_DOUBLINGS));
    return Duration.ofMillis(random.nextLong(ceilingMillis + 1));
  }
}
