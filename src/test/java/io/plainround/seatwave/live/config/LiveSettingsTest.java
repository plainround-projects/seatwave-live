package io.plainround.seatwave.live.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LiveSettingsTest {

  @Test
  void readsEveryShippedSettingsFile() throws IOException {
    for (String file :
        List.of("serve", "compare-server", "sse-resume", "slow-consumer", "wire-bytes")) {
      Result<LiveSettings, List<String>> settings =
          LiveSettings.from(
              new SettingsReader(SettingsReader.load(Path.of("config", file + ".properties"))));

      assertThat(settings.discardError()).as(file).isPresent();
    }
  }

  @Test
  void listsEveryMissingAndInvalidKeyInOneRun() throws IOException {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.remove("feed.replayCapacity");
    properties.setProperty("http.port", "80");
    properties.setProperty("websocket.allowedOrigin", "https://evil.example");
    properties.setProperty("admin.enabled", "yes");
    properties.setProperty("feed.maxBatch", "many");

    Result<LiveSettings, List<String>> settings = LiveSettings.from(new SettingsReader(properties));

    assertThat(problemsOf(settings))
        .containsExactlyInAnyOrder(
            "http.port must be from 1024 to 65535, but was 80",
            "websocket.allowedOrigin must be http://127.0.0.1:<port>, but was https://evil.example",
            "feed.replayCapacity is missing",
            "feed.maxBatch must be a whole number, but was many",
            "admin.enabled must be true or false, but was yes");
  }

  @Test
  void readsTheCompareAndLabSettings() throws IOException {
    SettingsReader rates =
        new SettingsReader(SettingsReader.load(Path.of("config/polling-rates.properties")));

    assertThat(
            io.plainround.seatwave.live.bench.CompareSettings.from(
                    new SettingsReader(SettingsReader.load(Path.of("config/compare.properties"))))
                .discardError())
        .isPresent();
    assertThat(io.plainround.seatwave.live.bench.PollingRatesCli.report(rates)).hasSize(9);
    assertThat(rates.problems()).isEmpty();
  }

  private static List<String> problemsOf(Result<LiveSettings, List<String>> settings) {
    return switch (settings) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> problems;
      case Result.Success<LiveSettings, List<String>> success -> List.of();
    };
  }
}
