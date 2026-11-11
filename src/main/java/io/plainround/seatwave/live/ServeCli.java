package io.plainround.seatwave.live;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Runs one live-feed node with its simulated on-sale for the configured time: {@code ./gradlew -q
 * serve --args='config/serve.properties'}. It prints one line when it is ready, which the
 * comparison waits for when it runs the node as a separate process.
 */
public final class ServeCli {

  private static final int USAGE = 2;

  private ServeCli() {}

  /**
   * Starts the node.
   *
   * @param arguments the settings file
   * @throws IOException if the file cannot be read or a port cannot be bound
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: serve <settings file>");
      System.exit(USAGE);
    }
    switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
        problems.forEach(problem -> System.err.println("settings: " + problem));
        System.exit(USAGE);
      }
      case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> serve(settings);
    }
  }

  private static void serve(LiveSettings settings) throws IOException, InterruptedException {
    try (LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC())) {
      node.startOnSale();
      System.out.println(
          String.format(
              Locale.ROOT,
              "SeatWave live feed on http://127.0.0.1:%d and ws://127.0.0.1:%d for %d s; "
                  + "on-sale at %d actions per second; at most %d connections",
              node.httpPort(),
              node.webSocketPort(),
              settings.serveTime().toSeconds(),
              settings.onSale().actionsPerSecond(),
              settings.limits().maxConnections()));
      System.out.flush();
      Thread.sleep(settings.serveTime());
    }
  }
}
