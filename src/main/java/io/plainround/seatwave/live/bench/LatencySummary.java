package io.plainround.seatwave.live.bench;

import java.util.Arrays;
import java.util.Objects;

/**
 * The p50, p99 and largest of a set of delays, read by the nearest-rank method (Module 4's method):
 * the smallest value that at least that share of the values do not exceed.
 *
 * @param count how many delays were summarized; 0 means none, and every value is then 0
 * @param p50Millis the delay half of them did not exceed
 * @param p99Millis the delay 99 in 100 of them did not exceed
 * @param maxMillis the largest delay
 */
public record LatencySummary(long count, double p50Millis, double p99Millis, double maxMillis) {

  private static final double MICROS_PER_MILLI = 1_000.0;
  private static final int P50 = 50;
  private static final int P99 = 99;
  private static final double HUNDRED = 100.0;

  /** Checks the order of the values. */
  public LatencySummary {
    if (count < 0 || p99Millis < p50Millis || maxMillis < p99Millis) {
      throw new IllegalArgumentException("impossible summary");
    }
  }

  /**
   * Summarizes delays.
   *
   * @param delaysMicros the delays in microseconds, in any order; not changed
   * @return the summary
   */
  public static LatencySummary ofMicros(long[] delaysMicros) {
    Objects.requireNonNull(delaysMicros, "delaysMicros");
    if (delaysMicros.length == 0) {
      return new LatencySummary(0, 0, 0, 0);
    }
    long[] sorted = delaysMicros.clone();
    Arrays.sort(sorted);
    return new LatencySummary(
        sorted.length,
        nearestRank(sorted, P50) / MICROS_PER_MILLI,
        nearestRank(sorted, P99) / MICROS_PER_MILLI,
        sorted[sorted.length - 1] / MICROS_PER_MILLI);
  }

  private static long nearestRank(long[] sorted, int percent) {
    int rank = (int) Math.ceil(percent / HUNDRED * sorted.length);
    return sorted[Math.max(rank, 1) - 1];
  }
}
