package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OnSaleSimulatorTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
  private final SeatFeed feed = new SeatFeed(10_000, 100, 64, new FeedMetrics());
  private final SeatStore store =
      new SeatStore(new SeatLayout(1, 100), Duration.ofSeconds(4), clock, feed);

  @Test
  void makesHoldsConfirmsAndExpiriesThatTheStoreAllows() {
    try (OnSaleSimulator simulator = new OnSaleSimulator(store, 50, 30, 8)) {
      for (int tick = 0; tick < 200; tick++) {
        simulator.tick();
        clock.advance(Duration.ofMillis(20));
      }
    }

    String states = store.snapshot().states();

    assertThat(states).contains("s");
    assertThat(feed.position().sequence()).isGreaterThan(100);
  }

  @Test
  void changesNothingWhilePaused() {
    try (OnSaleSimulator simulator = new OnSaleSimulator(store, 50, 30, 8)) {
      simulator.tick();
      simulator.pause();
      clock.advance(Duration.ofSeconds(10));

      assertThat(simulator.tick()).isFalse();
      assertThat(feed.position().sequence()).isEqualTo(1);
      simulator.resume();
      assertThat(simulator.tick()).isTrue();
    }
  }

  @Test
  void repeatsTheSameChoicesForTheSameSeed() {
    SeatFeed otherFeed = new SeatFeed(10_000, 100, 64, new FeedMetrics());
    SeatStore other =
        new SeatStore(new SeatLayout(1, 100), Duration.ofSeconds(4), clock, otherFeed);
    try (OnSaleSimulator first = new OnSaleSimulator(store, 50, 30, 8);
        OnSaleSimulator second = new OnSaleSimulator(other, 50, 30, 8)) {
      for (int tick = 0; tick < 50; tick++) {
        first.tick();
        second.tick();
      }
    }

    assertThat(other.snapshot().states()).isEqualTo(store.snapshot().states());
  }
}
