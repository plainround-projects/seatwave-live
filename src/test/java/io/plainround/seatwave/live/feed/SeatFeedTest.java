package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SeatFeedTest {

  private static final Duration SHORT = Duration.ofMillis(50);
  private static final Duration LONG = Duration.ofSeconds(5);

  private final FeedMetrics metrics = new FeedMetrics();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));

  @Test
  void givesASubscriberEveryChangeExactlyOnceWhileChangesArePublishedAsItJoins() throws Exception {
    SeatFeed feed = new SeatFeed(100_000, 100_000, 64, metrics);
    SeatStore store = new SeatStore(new SeatLayout(20, 1_000), Duration.ofMinutes(5), clock, feed);
    int holds = 5_000;
    Thread publisher =
        Thread.ofVirtual()
            .start(() -> {
              for (int seat = 0; seat < holds; seat++) {
                store.hold(seat);
              }
            });
    List<Long> seen = new ArrayList<>();
    for (int joiner = 0; joiner < 20; joiner++) {
      FeedPosition known = feed.position();
      Subscriber subscriber =
          feed.subscribe(new FeedPosition(feed.streamId(), Math.max(0, known.sequence() - 3)));
      seen.add(drainUntil(subscriber, known.sequence() + 1));
    }
    publisher.join();
    Subscriber last = feed.subscribe(new FeedPosition(feed.streamId(), 0));

    assertThat(seen).allMatch(gapless -> gapless == 1L);
    assertThat(sequencesIn(last, holds)).isEqualTo(holds);
  }

  @Test
  void tellsASubscriberWhoseQueueFillsToReloadAndKeepsTheQueueBounded() throws Exception {
    SeatFeed feed = new SeatFeed(1_000, 4, 64, metrics);
    SeatStore store = new SeatStore(new SeatLayout(1, 100), Duration.ofMinutes(5), clock, feed);
    Subscriber slow = feed.subscribe(feed.position());

    for (int seat = 0; seat < 50; seat++) {
      store.hold(seat);
    }

    assertThat(slow.next(SHORT)).isInstanceOf(Delivery.Reload.class);
    assertThat(metrics.render())
        .contains("seatwave_feed_queue_overflows_total 1", "seatwave_feed_deepest_client_queue 4");
  }

  @Test
  void tellsASubscriberWithAnOldPositionToReloadAtOnce() throws Exception {
    SeatFeed feed = new SeatFeed(3, 100, 64, metrics);
    SeatStore store = new SeatStore(new SeatLayout(1, 10), Duration.ofMinutes(5), clock, feed);
    for (int seat = 0; seat < 6; seat++) {
      store.hold(seat);
    }

    Subscriber late = feed.subscribe(new FeedPosition(feed.streamId(), 1));

    assertThat(late.next(SHORT)).isInstanceOf(Delivery.Reload.class);
  }

  @Test
  void answersALongPollAtTheNextChangeOrAtTheEndOfTheHold() throws Exception {
    SeatFeed feed = new SeatFeed(100, 100, 64, metrics);
    SeatStore store = new SeatStore(new SeatLayout(1, 10), Duration.ofMinutes(5), clock, feed);
    FeedPosition start = feed.position();

    Catchup empty = feed.awaitChangesAfter(start, SHORT);
    CompletableFuture<Catchup> waiting =
        CompletableFuture.supplyAsync(() -> {
          try {
            return feed.awaitChangesAfter(start, LONG);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
        });
    Thread.sleep(SHORT);
    store.hold(1);

    assertThat(((Catchup.Changes) empty).changes()).isEmpty();
    assertThat(((Catchup.Changes) waiting.get(LONG.toMillis(), TimeUnit.MILLISECONDS)).changes())
        .hasSize(1);
    assertThat(((Catchup.Changes) feed.changesAfter(start)).changes()).hasSize(1);
  }

  @Test
  void wakesAWaitingWriterAndSaysIdleWhenNothingHappens() throws Exception {
    SeatFeed feed = new SeatFeed(100, 100, 64, metrics);
    Subscriber subscriber = feed.subscribe(feed.position());

    assertThat(subscriber.next(SHORT)).isInstanceOf(Delivery.Idle.class);
    subscriber.wake();
    assertThat(subscriber.next(LONG)).isInstanceOf(Delivery.Woken.class);
    feed.unsubscribe(subscriber);
  }

  @Test
  void interruptsOnlyAWriterStuckLongerThanTheTimeout() throws Exception {
    SeatFeed feed = new SeatFeed(100, 100, 64, metrics);
    Subscriber stuck = feed.subscribe(feed.position());
    Subscriber writing = feed.subscribe(feed.position());
    Thread blocked =
        Thread.ofVirtual()
            .start(() -> {
              try {
                Thread.sleep(LONG.multipliedBy(4));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    stuck.beginWrite(blocked);
    Thread.sleep(SHORT.multipliedBy(2));
    writing.beginWrite(Thread.currentThread());

    int interrupted = feed.disconnectStuckWriters(SHORT);
    blocked.join(LONG);

    assertThat(interrupted).isEqualTo(1);
    assertThat(blocked.isAlive()).isFalse();
    writing.endWrite();
    assertThat(metrics.render()).contains("seatwave_feed_stuck_writer_disconnects_total 1");
  }

  // Takes changes until the given sequence has arrived; returns 1 if every sequence came once.
  private static long drainUntil(Subscriber subscriber, long target) throws InterruptedException {
    long expected = -1;
    while (true) {
      Delivery delivery = subscriber.next(LONG);
      if (!(delivery instanceof Delivery.Batch batch)) {
        return 0;
      }
      for (SeatChange change : batch.changes()) {
        long sequence = change.position().sequence();
        if (expected != -1 && sequence != expected) {
          return 0;
        }
        expected = sequence + 1;
        if (sequence >= target) {
          return 1;
        }
      }
    }
  }

  private static long sequencesIn(Subscriber subscriber, int total) throws InterruptedException {
    long next = 1;
    while (next <= total) {
      Delivery delivery = subscriber.next(LONG);
      if (!(delivery instanceof Delivery.Batch batch)) {
        return -1;
      }
      for (SeatChange change : batch.changes()) {
        if (change.position().sequence() != next) {
          return -1;
        }
        next++;
      }
    }
    return next - 1;
  }
}
