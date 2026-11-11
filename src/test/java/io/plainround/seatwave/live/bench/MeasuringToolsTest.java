package io.plainround.seatwave.live.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeasuringToolsTest {

  @TempDir Path folder;

  @Test
  void runsTheComparisonForEveryMethodAndEveryClientEndsWithTheStoresMap() throws Exception {
    CompareSettings settings =
        new CompareSettings(
            Path.of("config/compare-server.properties"),
            List.of(DeliveryMethod.values()),
            10,
            1,
            new CompareSettings.Phases(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(10)),
            new CompareSettings.Fleet(
                200,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(6),
                Duration.ofMillis(20),
                Duration.ofMillis(200)),
            false,
            256,
            folder);

    List<String> lines = ComparisonCli.run(settings, TestSettings.small());

    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("long-polling med    10"));
    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("sse          med    10"));
    assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("websocket    med    10"));
    assertThat(
            lines.stream()
                .filter(line -> line.contains(" med "))
                .map(line -> line.substring(line.length() - 7).strip()))
        .containsOnly("10");
    assertThat(folder.resolve("live-feed-comparison.csv")).exists();
  }

  @Test
  void startsTheFeedInAProcessOfItsOwnAndReadsItsMemory() throws Exception {
    Path file = Path.of("config/compare-server.properties");
    LiveSettings settings =
        LiveSettings.from(new SettingsReader(SettingsReader.load(file)))
            .discardError()
            .orElseThrow();

    try (ChildProcessServer server =
        ChildProcessServer.start(file, settings, 128, folder.resolve("server.log"))) {
      assertThat(server.httpPort()).isEqualTo(8092);
      assertThat(server.webSocketPort()).isEqualTo(8093);
      assertThat(ProcessMemory.residentBytes(server.processId().orElseThrow()).orElseThrow())
          .isPositive();
    }
  }

  @Test
  void showsTheSlowConsumersQueueStoppingAtItsBound() throws Exception {
    List<String> lines =
        new SlowConsumer(TestSettings.busy(10_000), 3, Duration.ofSeconds(2)).run();

    assertThat(lines).contains("deepest client queue: 64 (bound 64)");
    assertThat(lines)
        .anySatisfy(line ->
            assertThat(line)
                .startsWith("queue overflows (client told to reload): ")
                .doesNotEndWith(": 0"));
    assertThat(lines).anySatisfy(line -> assertThat(line).endsWith("3 of 3 match the store"));
  }

  @Test
  void resumesAnEventStreamWithoutAGapAndReloadsAfterALongAbsence() throws Exception {
    List<String> lines = new SseResume(TestSettings.busy(2_000), 3_000).run();

    assertThat(lines)
        .anySatisfy(line ->
            assertThat(line)
                .startsWith("scenario 1")
                .contains("duplicates 0, gaps 0, reloads 0")
                .endsWith("map equals store: yes"));
    assertThat(lines)
        .anySatisfy(line ->
            assertThat(line)
                .startsWith("scenario 2")
                .contains("gaps 0, reloads 1")
                .endsWith("map equals store: yes"));
  }

  @Test
  void countsTheBytesOnTheWireOfEveryMethod() throws Exception {
    List<String> lines = new WireBytesProbe(TestSettings.small(), 5).measure();

    assertThat(lines).hasSize(4);
    assertThat(lines.get(1)).startsWith("long-polling");
    assertThat(lines.get(3)).startsWith("websocket");
    assertThat(MachineLine.describe()).startsWith("Java 25");
  }
}
