package io.plainround.seatwave.live.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchReportsTest {

  @TempDir Path folder;

  @Test
  void readsPercentilesByNearestRank() {
    long[] delays = new long[100];
    for (int index = 0; index < delays.length; index++) {
      delays[index] = (index + 1) * 1_000L;
    }

    LatencySummary summary = LatencySummary.ofMicros(delays);

    assertThat(summary.p50Millis()).isEqualTo(50.0);
    assertThat(summary.p99Millis()).isEqualTo(99.0);
    assertThat(summary.maxMillis()).isEqualTo(100.0);
    assertThat(LatencySummary.ofMicros(new long[0]).count()).isZero();
  }

  @Test
  void computesTheRatesOfLabOne() {
    PollingRates rates =
        new PollingRates(
            200_000, Duration.ofSeconds(5), Duration.ofMillis(80), Duration.ofSeconds(25));

    assertThat(rates.shortPollingRequestsPerSecond()).isEqualTo(40_000.0);
    assertThat(rates.emptyShare(1 / 60.0)).isCloseTo(0.920, within(0.001));
    assertThat(rates.longPollingRequestsPerSecond(3_000)).isCloseTo(2_489_627, within(1.0));
    assertThat(rates.pushMessagesPerSecond(3_000)).isEqualTo(600_000_000.0);
  }

  @Test
  void writesOneCsvLinePerRunAndTakesTheMedianOfEachColumn() throws IOException {
    List<MethodResult> runs = List.of(result(1, 3.0), result(2, 1.0), result(3, 2.0));

    ComparisonReport.writeCsv(runs, folder.resolve("out.csv"));
    MethodResult median = ComparisonReport.median(runs);
    List<String> table = ComparisonReport.table(runs);

    assertThat(Files.readAllLines(folder.resolve("out.csv"), StandardCharsets.UTF_8)).hasSize(4);
    assertThat(median.delay().p99Millis()).isEqualTo(2.0);
    assertThat(table).hasSize(6).last().asString().startsWith("sse          med");
    assertThat(ComparisonReport.median(runs.subList(0, 2)).delay().p99Millis()).isEqualTo(2.0);
  }

  private static MethodResult result(int run, double p99) {
    return new MethodResult(
        DeliveryMethod.SERVER_SENT_EVENTS,
        run,
        10,
        10,
        new LatencySummary(100, 1.0, p99, p99),
        new MethodResult.Load(50, 500, 0, 150),
        new MethodResult.ServerCost(0.1, 10, 20_000, -1, 20, 10, 40, 10_240),
        new MethodResult.Reconnect(10, 100, 10, 10, 0),
        new MethodResult.Correctness(0, 0, 10));
  }
}
