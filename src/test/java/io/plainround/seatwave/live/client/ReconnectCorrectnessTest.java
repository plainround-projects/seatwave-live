package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.SeatMapSnapshot;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReconnectCorrectnessTest {

  private static final int CLIENTS = 10;
  // About a quarter of a second of the test on-sale's 200 actions per second.
  private static final int CHANGES_AFTER_DROP = 50;

  @ParameterizedTest
  @EnumSource(DeliveryMethod.class)
  void endsWithTheStoresSeatMapAfterEveryConnectionIsDroppedTwice(DeliveryMethod method)
      throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC())) {
      ClientFleet fleet = ClientFleet.start(plan(method, node));
      try {
        node.startOnSale();
        await().atMost(Duration.ofSeconds(10)).until(() -> fleet.countAtLeast(20) == CLIENTS);
        node.parts().limit().dropAll();
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> fleet.countAtLeast(node.parts().feed().position().sequence()) == CLIENTS);
        long droppedAt = node.parts().feed().position().sequence();
        node.parts().limit().dropAll();
        // Changes keep coming while the clients reconnect, so they resume into a moving feed.
        await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () -> node.parts().feed().position().sequence() >= droppedAt + CHANGES_AFTER_DROP);
        // Pausing waits for a tick already running, so no change can follow the snapshot.
        node.parts().simulator().pause();
        SeatMapSnapshot store = node.parts().store().snapshot();
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> fleet.countAtLeast(store.position().sequence()) == CLIENTS);
        fleet.stop();
        long reconnects = 0;
        for (FeedClient client : fleet.clients()) {
          assertThat(client.map().states()).isEqualTo(store.states());
          assertThat(client.stats().gaps()).isZero();
          reconnects += client.stats().reconnects();
        }
        // Every push client loses its stream both times. A dropped long poll is a GET, which the
        // JDK's HTTP client sends again by itself, so the long-poll client never sees the drop.
        assertThat(reconnects)
            .isGreaterThanOrEqualTo(method == DeliveryMethod.LONG_POLLING ? 0 : 2L * CLIENTS);
      } finally {
        fleet.stop();
      }
    }
  }

  private static FleetPlan plan(DeliveryMethod method, LiveFeedNode node) {
    return new FleetPlan(
        method,
        CLIENTS,
        new FeedEndpoints(node.httpPort(), node.webSocketPort()),
        1_000,
        new FleetPlan.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(6)),
        new FleetPlan.BackoffRange(Duration.ofMillis(20), Duration.ofMillis(200)),
        10_000,
        1);
  }
}
