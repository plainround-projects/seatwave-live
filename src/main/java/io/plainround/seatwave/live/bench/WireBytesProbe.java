package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.client.ClientFleet;
import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.client.FleetPlan;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.SeatFeed;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Measures the bytes on the wire per seat change for each method: one client through a {@link
 * ByteCountingRelay}, and changes made one at a time, each waited for before the next, so every
 * long poll carries exactly one change (the worst case for long polling; under load it batches).
 */
public final class WireBytesProbe {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Duration POLL = Duration.ofMillis(2);

  private final LiveSettings settings;
  private final int changes;

  /**
   * Creates the probe.
   *
   * @param settings the node's settings
   * @param changes how many changes to measure per method
   */
  public WireBytesProbe(LiveSettings settings, int changes) {
    this.settings = Objects.requireNonNull(settings, "settings");
    if (changes < 1) {
      throw new IllegalArgumentException("changes must be at least 1");
    }
    this.changes = changes;
  }

  /**
   * Measures every method.
   *
   * @return one line per method, after a header
   * @throws IOException if the node cannot start
   * @throws InterruptedException if interrupted
   */
  public List<String> measure() throws IOException, InterruptedException {
    List<String> lines = new ArrayList<>();
    lines.add(
        String.format(
            Locale.ROOT,
            "%-13s %18s %18s %16s   (%d changes, one at a time, one client)",
            "method",
            "client->server B",
            "server->client B",
            "total B/change",
            changes));
    for (DeliveryMethod method : DeliveryMethod.values()) {
      lines.add(measure(method));
    }
    return List.copyOf(lines);
  }

  private String measure(DeliveryMethod method) throws IOException, InterruptedException {
    try (LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC());
        ByteCountingRelay http = ByteCountingRelay.start(node.httpPort());
        ByteCountingRelay socket = ByteCountingRelay.start(node.webSocketPort())) {
      ClientFleet fleet =
          ClientFleet.start(
              new FleetPlan(
                  method,
                  1,
                  new FeedEndpoints(http.port(), socket.port()),
                  1,
                  new FleetPlan.Timeouts(WAIT, WAIT.multipliedBy(4), WAIT.multipliedBy(9)),
                  new FleetPlan.BackoffRange(Duration.ofMillis(50), Duration.ofSeconds(1)),
                  changes,
                  1));
      try {
        awaitTrue(
            () -> node.parts().metrics().openConnections(method) == 1 && fleet.countLoaded() == 1);
        // Setup (the seat map, the handshake) is not part of the cost of one update.
        http.reset();
        socket.reset();
        SeatFeed feed = node.parts().feed();
        for (int change = 0; change < changes; change++) {
          node.parts().store().hold(change);
          long sequence = feed.position().sequence();
          awaitTrue(() -> fleet.countAtLeast(sequence) == 1);
        }
        // The next long poll is already on its way when the last change arrives; let it land.
        awaitTrue(() ->
            method != DeliveryMethod.LONG_POLLING
                || node.parts().metrics().openConnections(method) == 1);
        long up = http.bytesTowardServer() + socket.bytesTowardServer();
        long down = http.bytesTowardClient() + socket.bytesTowardClient();
        return String.format(
            Locale.ROOT,
            "%-13s %18.1f %18.1f %16.1f",
            method.label(),
            up / (double) changes,
            down / (double) changes,
            (up + down) / (double) changes);
      } finally {
        fleet.stop();
      }
    }
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(POLL);
    }
  }
}
