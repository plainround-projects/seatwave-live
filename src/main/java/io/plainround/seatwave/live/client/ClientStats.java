package io.plainround.seatwave.live.client;

import java.util.Arrays;

/**
 * What one client saw: messages, gaps, duplicates, reloads, reconnects, and the delivery delay of
 * every change received while recording was on.
 *
 * <p>Delays go into a fixed-size array; any beyond its capacity are counted but not kept, so a long
 * run cannot grow the client's memory. Not thread-safe: written by one client thread and read after
 * that thread has stopped.
 */
public final class ClientStats {

  private final long[] delaysMicros;
  private int delayCount;
  private long delaysDropped;
  private long changesApplied;
  private long duplicates;
  private long gaps;
  private long reloads;
  private long reconnects;
  private long longestOutageMillis;

  /**
   * Creates empty statistics.
   *
   * @param delayCapacity how many delays to keep
   */
  public ClientStats(int delayCapacity) {
    this.delaysMicros = new long[delayCapacity];
  }

  void recordDelay(long micros) {
    if (delayCount < delaysMicros.length) {
      delaysMicros[delayCount++] = micros;
    } else {
      delaysDropped++;
    }
  }

  void recordApplied(Applied applied) {
    switch (applied) {
      case APPLIED -> changesApplied++;
      case DUPLICATE -> duplicates++;
      case GAP -> gaps++;
    }
  }

  void recordReload() {
    reloads++;
  }

  void recordReconnect(long outageMillis) {
    reconnects++;
    longestOutageMillis = Math.max(longestOutageMillis, outageMillis);
  }

  /**
   * The delays kept, in microseconds.
   *
   * @return a copy
   */
  public long[] delaysMicros() {
    return Arrays.copyOf(delaysMicros, delayCount);
  }

  /**
   * Delays not kept because the array was full.
   *
   * @return the count
   */
  public long delaysDropped() {
    return delaysDropped;
  }

  /**
   * Changes applied to the map.
   *
   * @return the count
   */
  public long changesApplied() {
    return changesApplied;
  }

  /**
   * Changes received twice and ignored.
   *
   * @return the count
   */
  public long duplicates() {
    return duplicates;
  }

  /**
   * Changes that arrived after a missing one.
   *
   * @return the count; the feed's invariant says it is always 0
   */
  public long gaps() {
    return gaps;
  }

  /**
   * Reloads of the whole seat map after the first load.
   *
   * @return the count
   */
  public long reloads() {
    return reloads;
  }

  /**
   * Connections established again after one ended.
   *
   * @return the count
   */
  public long reconnects() {
    return reconnects;
  }

  /**
   * The longest time from a connection ending to the next one being established.
   *
   * @return milliseconds
   */
  public long longestOutageMillis() {
    return longestOutageMillis;
  }
}
