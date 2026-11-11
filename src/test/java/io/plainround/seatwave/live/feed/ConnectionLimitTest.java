package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConnectionLimitTest {

  private final FeedMetrics metrics = new FeedMetrics();
  private final ConnectionLimit limit = new ConnectionLimit(2, metrics);

  @Test
  void refusesTheConnectionOverTheLimitAndFreesASlotOnce() {
    Optional<ConnectionLimit.Ticket> first = limit.admit(DeliveryMethod.WEBSOCKET, () -> {});
    Optional<ConnectionLimit.Ticket> second =
        limit.admit(DeliveryMethod.SERVER_SENT_EVENTS, () -> {});

    assertThat(limit.admit(DeliveryMethod.LONG_POLLING, () -> {})).isEmpty();
    first.orElseThrow().close();
    first.orElseThrow().close();
    assertThat(limit.openCount()).isEqualTo(1);
    assertThat(limit.admit(DeliveryMethod.LONG_POLLING, () -> {})).isPresent();
    assertThat(limit.admit(DeliveryMethod.LONG_POLLING, () -> {})).isEmpty();
    assertThat(second).isPresent();
    assertThat(metrics.render()).contains("seatwave_feed_refused_total{method=\"long-polling\"} 2");
  }

  @Test
  void dropsEveryOpenConnection() {
    AtomicInteger dropped = new AtomicInteger();
    limit.admit(DeliveryMethod.WEBSOCKET, dropped::incrementAndGet);
    limit.admit(DeliveryMethod.WEBSOCKET, dropped::incrementAndGet);

    assertThat(limit.dropAll()).isEqualTo(2);
    assertThat(dropped).hasValue(2);
  }
}
