package io.plainround.seatwave.live;

import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.SeatLayout;
import java.time.Duration;

/** Small, fast settings for tests: ports chosen by the system, a small map, short timings. */
public final class TestSettings {

  public static final int SECTIONS = 2;
  public static final int SEATS_PER_SECTION = 50;

  private TestSettings() {}

  public static LiveSettings small() {
    return with(1_000, 64, Duration.ofSeconds(2));
  }

  public static LiveSettings with(int replayCapacity, int clientQueueCapacity, Duration heartbeat) {
    return new LiveSettings(
        new LiveSettings.Network(0, 0, 128, "http://127.0.0.1:8090"),
        new LiveSettings.Limits(200, replayCapacity, clientQueueCapacity, 32),
        new LiveSettings.Timing(
            heartbeat, Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(1)),
        new SeatLayout(SECTIONS, SEATS_PER_SECTION),
        new LiveSettings.OnSale(Duration.ofMillis(300), 200, 30, 8),
        true,
        Duration.ofSeconds(1));
  }

  /** A fast on-sale over Module 1's 20,000 seats, with a small client queue and write timeout. */
  public static LiveSettings busy(int replayCapacity) {
    LiveSettings base = small();
    return new LiveSettings(
        base.network(),
        new LiveSettings.Limits(200, replayCapacity, 64, 32),
        base.timing(),
        new SeatLayout(20, 1_000),
        new LiveSettings.OnSale(Duration.ofSeconds(2), 2_000, 30, 8),
        true,
        base.serveTime());
  }

  public static LiveSettings withConnectionLimit(int maxConnections) {
    LiveSettings base = small();
    return new LiveSettings(
        base.network(),
        new LiveSettings.Limits(maxConnections, 1_000, 64, 32),
        base.timing(),
        base.layout(),
        base.onSale(),
        base.adminEnabled(),
        base.serveTime());
  }
}
