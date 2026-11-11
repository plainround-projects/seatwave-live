package io.plainround.seatwave.live.bench;

import java.time.Duration;
import java.util.Objects;

/**
 * Lab 1's arithmetic: the request rate of short polling and of long polling for many fans, and the
 * messages a push method sends instead. Changes are assumed to arrive at random, at a steady
 * average rate (a Poisson process), so the chance that an interval of t seconds holds no change is
 * e^(−rate × t).
 *
 * @param fans the clients watching
 * @param pollInterval how often a short-polling client asks
 * @param roundTrip the network round trip of one request (Module 5)
 * @param hold how long the server holds a long poll with no news
 */
public record PollingRates(int fans, Duration pollInterval, Duration roundTrip, Duration hold) {

  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  /** Checks the fields. */
  public PollingRates {
    if (fans < 1) {
      throw new IllegalArgumentException("fans must be at least 1");
    }
    for (Duration duration :
        java.util.List.of(
            Objects.requireNonNull(pollInterval, "pollInterval"),
            Objects.requireNonNull(roundTrip, "roundTrip"),
            Objects.requireNonNull(hold, "hold"))) {
      if (duration.isNegative() || duration.isZero()) {
        throw new IllegalArgumentException("every duration must be positive");
      }
    }
  }

  /**
   * Short polling: every fan asks once per interval, whatever happens.
   *
   * @return requests per second
   */
  public double shortPollingRequestsPerSecond() {
    return fans / seconds(pollInterval);
  }

  /**
   * The share of short polls that find nothing new.
   *
   * @param changesPerSecond the average rate of seat changes
   * @return a number from 0 to 1
   */
  public double emptyShare(double changesPerSecond) {
    return Math.exp(-changesPerSecond * seconds(pollInterval));
  }

  /**
   * Long polling: each fan's next request leaves one round trip after the previous one was
   * answered, and the server answers at the next change or at the end of the hold, whichever is
   * first. The expected wait for the next change, cut at the hold, is (1 − e^(−rate × hold)) ÷
   * rate.
   *
   * @param changesPerSecond the average rate of seat changes
   * @return requests per second, all fans together
   */
  public double longPollingRequestsPerSecond(double changesPerSecond) {
    double holdSeconds = seconds(hold);
    double expectedWait = (1 - Math.exp(-changesPerSecond * holdSeconds)) / changesPerSecond;
    return fans / (expectedWait + seconds(roundTrip));
  }

  /**
   * A push method that sends every change to every fan.
   *
   * @param changesPerSecond the average rate of seat changes
   * @return messages per second, all fans together
   */
  public double pushMessagesPerSecond(double changesPerSecond) {
    return fans * changesPerSecond;
  }

  private static double seconds(Duration duration) {
    return duration.toNanos() / NANOS_PER_SECOND;
  }
}
