package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.config.SettingsReader;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs the live-feed comparison: {@code ./gradlew -q compare --args='config/compare.properties'}.
 * It prints the machine, the settings, one line per run and the medians, and writes the CSV file.
 */
public final class ComparisonCli {

  private static final int USAGE = 2;

  private ComparisonCli() {}

  /**
   * Runs the comparison.
   *
   * @param arguments the comparison's settings file
   * @throws IOException if a file cannot be read or written, or the server fails
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: compare <settings file>");
      System.exit(USAGE);
    }
    switch (CompareSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
      case Result.Error<CompareSettings, List<String>>(List<String> problems) -> fail(problems);
      case Result.Success<CompareSettings, List<String>>(CompareSettings settings) -> {
        switch (LiveSettings.from(
            new SettingsReader(SettingsReader.load(settings.serverSettings())))) {
          case Result.Error<LiveSettings, List<String>>(List<String> problems) -> fail(problems);
          case Result.Success<LiveSettings, List<String>>(LiveSettings server) ->
              run(settings, server).forEach(System.out::println);
        }
      }
    }
  }

  private static void fail(List<String> problems) {
    problems.forEach(problem -> System.err.println("settings: " + problem));
    System.exit(USAGE);
  }

  /**
   * Runs every method and run, and returns the printed lines.
   *
   * @param settings the comparison's settings
   * @param server the node's settings
   * @return the lines of the report
   * @throws IOException if the server fails or the CSV cannot be written
   * @throws InterruptedException if interrupted
   */
  public static List<String> run(CompareSettings settings, LiveSettings server)
      throws IOException, InterruptedException {
    List<String> lines = new ArrayList<>();
    lines.add(MachineLine.describe());
    lines.add(
        String.format(
            Locale.ROOT,
            "feed %s; %d clients per method, %d run(s) each; warm-up %d s, measured %d s; "
                + "on-sale %d actions per second over %d seats",
            settings.childProcess()
                ? "in its own JVM (fixed, pre-touched heap of "
                    + settings.childHeapMegabytes()
                    + " MB)"
                : "in this JVM (process numbers include the clients)",
            settings.clients(),
            settings.runs(),
            settings.phases().warmup().toSeconds(),
            settings.phases().measure().toSeconds(),
            server.onSale().actionsPerSecond(),
            server.layout().seatCount()));
    Comparison comparison = new Comparison(settings, server);
    List<MethodResult> results = new ArrayList<>();
    for (DeliveryMethod method : settings.methods()) {
      for (int run = 1; run <= settings.runs(); run++) {
        results.add(comparison.run(method, run));
      }
    }
    lines.addAll(ComparisonReport.table(results));
    Path csv = settings.output().resolve("live-feed-comparison.csv");
    ComparisonReport.writeCsv(results, csv);
    lines.add("csv: " + csv);
    return List.copyOf(lines);
  }
}
