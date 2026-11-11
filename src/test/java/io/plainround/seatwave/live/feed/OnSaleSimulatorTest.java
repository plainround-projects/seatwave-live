package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.live.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
  void pauseWaitsForATickAlreadyRunningSoNoChangeFollowsIt() throws Exception {
    StallingClock stalling = new StallingClock(Instant.parse("2026-11-11T10:00:00Z"));
    SeatFeed stallingFeed = new SeatFeed(10_000, 100, 64, new FeedMetrics());
    SeatStore stallingStore =
        new SeatStore(new SeatLayout(1, 100), Duration.ofSeconds(4), stalling, stallingFeed);
    try (OnSaleSimulator simulator = new OnSaleSimulator(stallingStore, 50, 0, 8)) {
      Thread ticking = Thread.ofPlatform().start(simulator::tick);
      assertThat(stalling.entered.await(5, TimeUnit.SECONDS)).isTrue();
      Thread pausing = Thread.ofPlatform().start(simulator::pause);
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> pausing.getState() == Thread.State.WAITING || !pausing.isAlive());

      boolean pauseWaitedForTheTick = pausing.isAlive();
      stalling.release.countDown();
      ticking.join();
      pausing.join();
      long afterPause = stallingFeed.position().sequence();

      assertThat(pauseWaitedForTheTick).isTrue();
      assertThat(afterPause).isEqualTo(1);
      assertThat(simulator.tick()).isFalse();
      assertThat(stallingStore.snapshot().position().sequence()).isEqualTo(afterPause);
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

  // A clock whose first reading blocks until the test releases it, so a tick can be held inside the
  // store while another thread pauses the simulator.
  private static final class StallingClock extends Clock {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final Instant now;

    StallingClock(Instant now) {
      this.now = now;
    }

    @Override
    public Instant instant() {
      if (entered.getCount() > 0) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
