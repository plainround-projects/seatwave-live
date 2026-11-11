package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackoffTest {

  private static final Duration BASE = Duration.ofMillis(100);
  private static final Duration CAP = Duration.ofSeconds(5);

  @Test
  void waitsAtMostTheDoublingCeilingAndNeverMoreThanTheCap() {
    Backoff backoff = new Backoff(BASE, CAP, 1);

    for (int attempt = 0; attempt < 30; attempt++) {
      long ceiling = Math.min(CAP.toMillis(), BASE.toMillis() << Math.min(attempt, 20));
      for (int sample = 0; sample < 100; sample++) {
        assertThat(backoff.delay(attempt).toMillis()).isBetween(0L, ceiling);
      }
    }
  }

  @Test
  void spreadsClientsThatFailedAtTheSameMoment() {
    List<Long> firstWaits = new ArrayList<>();
    for (int client = 0; client < 1_000; client++) {
      firstWaits.add(new Backoff(BASE, CAP, client).delay(3).toMillis());
    }

    long distinct = firstWaits.stream().distinct().count();

    assertThat(distinct).isGreaterThan(500);
    assertThat(firstWaits.stream().mapToLong(Long::longValue).average().orElseThrow())
        .isBetween(300.0, 500.0);
  }

  @Test
  void repeatsItsWaitsForTheSameSeed() {
    assertThat(new Backoff(BASE, CAP, 7).delay(4)).isEqualTo(new Backoff(BASE, CAP, 7).delay(4));
  }

  @Test
  void refusesANegativeAttemptOrAnUpsideDownRange() {
    assertThatThrownBy(() -> new Backoff(BASE, CAP, 1).delay(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Backoff(CAP, BASE, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Backoff(Duration.ZERO, CAP, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
