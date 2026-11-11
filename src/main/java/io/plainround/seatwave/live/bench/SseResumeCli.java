package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Lab 2: {@code ./gradlew -q sseResume --args='config/sse-resume.properties 500'} (the node's
 * settings and the changes made while the client is away).
 */
public final class SseResumeCli {

  private static final int USAGE = 2;

  private SseResumeCli() {}

  /**
   * Runs the lab.
   *
   * @param arguments the settings file and the changes made while away (1 to 9,999)
   * @throws IOException if the file cannot be read or the node fails
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 2 || !arguments[1].matches("[1-9][0-9]{0,3}")) {
      System.err.println("usage: sseResume <settings file> <changes while away>");
      System.exit(USAGE);
    }
    switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
        problems.forEach(problem -> System.err.println("settings: " + problem));
        System.exit(USAGE);
      }
      case Result.Success<LiveSettings, List<String>>(LiveSettings settings) ->
          new SseResume(settings, Integer.parseInt(arguments[1]))
              .run()
              .forEach(System.out::println);
    }
  }
}
