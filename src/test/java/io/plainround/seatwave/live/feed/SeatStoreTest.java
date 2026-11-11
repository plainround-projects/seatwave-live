package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SeatStoreTest {

  private static final Duration HOLD = Duration.ofSeconds(300);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
  private final SeatFeed feed = new SeatFeed(100, 10, 10, new FeedMetrics());
  private final SeatStore store = new SeatStore(new SeatLayout(1, 10), HOLD, clock, feed);

  @Test
  void holdsAnAvailableSeatAndPublishesTheChange() {
    Result<SeatChange, SeatRefusal> held = store.hold(3);

    assertThat(held.discardError())
        .hasValueSatisfying(change -> {
          assertThat(change.state()).isEqualTo(SeatState.HELD);
          assertThat(change.position().sequence()).isEqualTo(1);
          assertThat(change.changedAt()).isEqualTo(clock.instant());
        });
    assertThat(store.snapshot().states()).isEqualTo("aaahaaaaaa");
  }

  @Test
  void refusesToHoldATakenOrUnknownSeat() {
    store.hold(3);

    assertThat(store.hold(3)).isEqualTo(Result.error(SeatRefusal.SEAT_TAKEN));
    assertThat(store.hold(10)).isEqualTo(Result.error(SeatRefusal.UNKNOWN_SEAT));
    assertThat(store.hold(-1)).isEqualTo(Result.error(SeatRefusal.UNKNOWN_SEAT));
    assertThat(feed.position().sequence()).isEqualTo(1);
  }

  @Test
  void confirmsAHoldThatHasNotExpired() {
    store.hold(3);
    clock.advance(HOLD.minusSeconds(1));

    assertThat(store.confirm(3).discardError())
        .hasValueSatisfying(change -> assertThat(change.state()).isEqualTo(SeatState.SOLD));
    assertThat(store.confirm(3)).isEqualTo(Result.error(SeatRefusal.NOT_HELD));
    assertThat(store.confirm(11)).isEqualTo(Result.error(SeatRefusal.UNKNOWN_SEAT));
  }

  @Test
  void refusesToConfirmAnExpiredHoldAndExpiresIt() {
    store.hold(3);
    clock.advance(HOLD);

    assertThat(store.confirm(3)).isEqualTo(Result.error(SeatRefusal.NOT_HELD));
    assertThat(store.expireDue()).isEqualTo(1);
    assertThat(store.snapshot().states()).isEqualTo("aaaaaaaaaa");
    assertThat(feed.position().sequence()).isEqualTo(2);
  }

  @Test
  void neverExpiresAConfirmedSeatOrANewerHoldOfTheSameSeat() {
    store.hold(1);
    store.hold(2);
    store.confirm(2);
    clock.advance(HOLD);
    store.expireDue();
    store.hold(1);
    clock.advance(Duration.ofSeconds(1));

    assertThat(store.expireDue()).isZero();
    assertThat(store.snapshot().states()).isEqualTo("ahsaaaaaaa");
  }

  @Test
  void letsExactlyOneOfManyRacingFansHoldASeat() throws Exception {
    int fans = 50;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try (ExecutorService racers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int fan = 0; fan < fans; fan++) {
        results.add(
            racers.submit(() -> {
              start.await();
              return !store.hold(5).isError();
            }));
      }
      start.countDown();
    }

    long winners = results.stream().filter(result -> result.resultNow()).count();

    assertThat(winners).isEqualTo(1);
    assertThat(feed.position().sequence()).isEqualTo(1);
  }

  @Test
  void takesASnapshotAtExactlyThePositionOfItsLastChange() {
    store.hold(0);
    store.hold(9);

    SeatMapSnapshot snapshot = store.snapshot();

    assertThat(snapshot.position()).isEqualTo(feed.position());
    assertThat(snapshot.position().sequence()).isEqualTo(2);
    assertThat(snapshot.states()).isEqualTo("haaaaaaaah");
  }
}
