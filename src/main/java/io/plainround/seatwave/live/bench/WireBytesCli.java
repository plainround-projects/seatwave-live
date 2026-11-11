package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Prints the bytes on the wire per seat change for each method: {@code ./gradlew -q wireBytes
 * --args='config/wire-bytes.properties 200'}.
 */
public final class WireBytesCli {

  private static final int USAGE = 2;
  private static final int MAX_CHANGES = 100_000;

  private WireBytesCli() {}

  /**
   * Runs the probe.
   *
   * @param arguments the node's settings file and the number of changes
   * @throws IOException if the file cannot be read or the node fails
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 2
        || !arguments[1].matches("[1-9][0-9]{0,5}")
        || Integer.parseInt(arguments[1]) > MAX_CHANGES) {
      System.err.println("usage: wireBytes <settings file> <changes, 1 to " + MAX_CHANGES + ">");
      System.exit(USAGE);
    }
    switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
        problems.forEach(problem -> System.err.println("settings: " + problem));
        System.exit(USAGE);
      }
      case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> {
        System.out.println(MachineLine.describe());
        new WireBytesProbe(settings, Integer.parseInt(arguments[1]))
            .measure()
            .forEach(System.out::println);
      }
    }
  }
}
