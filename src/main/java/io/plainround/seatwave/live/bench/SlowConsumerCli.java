package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs the slow-consumer experiment: {@code ./gradlew -q slowConsumer
 * --args='config/slow-consumer.properties 50 10'} (the node's settings, the normal clients, the
 * seconds to run).
 */
public final class SlowConsumerCli {

  private static final int USAGE = 2;
  private static final String SMALL_NUMBER = "[1-9][0-9]{0,3}";

  private SlowConsumerCli() {}

  /**
   * Runs the experiment.
   *
   * @param arguments the settings file, the normal clients (1 to 9,999), the seconds (1 to 9,999)
   * @throws IOException if the file cannot be read or the node fails
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 3
        || !arguments[1].matches(SMALL_NUMBER)
        || !arguments[2].matches(SMALL_NUMBER)) {
      System.err.println("usage: slowConsumer <settings file> <normal clients> <seconds>");
      System.exit(USAGE);
    }
    switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
        problems.forEach(problem -> System.err.println("settings: " + problem));
        System.exit(USAGE);
      }
      case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> {
        System.out.println(MachineLine.describe());
        new SlowConsumer(
                settings,
                Integer.parseInt(arguments[1]),
                Duration.ofSeconds(Integer.parseInt(arguments[2])))
            .run()
            .forEach(System.out::println);
      }
    }
  }
}
