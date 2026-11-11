package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lab 1: prints the request rates of short and long polling against a push method, for a busy
 * on-sale and a quiet evening: {@code ./gradlew -q pollingRates
 * --args='config/polling-rates.properties'}.
 */
public final class PollingRatesCli {

  private static final int USAGE = 2;
  private static final int MAX_FANS = 100_000_000;
  private static final int MAX_MILLIS = 3_600_000;
  private static final int MAX_CHANGES_PER_MINUTE = 10_000_000;
  private static final double SECONDS_PER_MINUTE = 60.0;
  private static final double PERCENT = 100.0;

  private PollingRatesCli() {}

  /**
   * Prints the table.
   *
   * @param arguments the settings file
   * @throws IOException if it cannot be read
   */
  public static void main(String[] arguments) throws IOException {
    if (arguments.length != 1) {
      System.err.println("usage: pollingRates <settings file>");
      System.exit(USAGE);
    }
    SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
    List<String> lines = report(reader);
    if (!reader.problems().isEmpty()) {
      reader.problems().forEach(problem -> System.err.println("settings: " + problem));
      System.exit(USAGE);
    }
    lines.forEach(System.out::println);
  }

  /**
   * Builds the table from the settings.
   *
   * @param reader the settings; problems are left in it for the caller
   * @return the lines, or an empty list if a setting is missing or invalid
   */
  public static List<String> report(SettingsReader reader) {
    int fans = reader.intBetween("rates.fans", 1, MAX_FANS);
    Duration interval = reader.millisBetween("rates.pollIntervalMillis", 1, MAX_MILLIS);
    Duration roundTrip = reader.millisBetween("rates.roundTripMillis", 1, MAX_MILLIS);
    Duration hold = reader.millisBetween("rates.longPollHoldMillis", 1, MAX_MILLIS);
    int busyPerMinute = reader.intBetween("rates.busyChangesPerMinute", 1, MAX_CHANGES_PER_MINUTE);
    int quietPerMinute =
        reader.intBetween("rates.quietChangesPerMinute", 1, MAX_CHANGES_PER_MINUTE);
    if (!reader.problems().isEmpty()) {
      return List.of();
    }
    PollingRates rates = new PollingRates(fans, interval, roundTrip, hold);
    List<String> lines = new ArrayList<>();
    lines.add(
        String.format(
            Locale.ROOT,
            "%,d fans; short poll every %d ms; long poll held up to %d ms; round trip %d ms",
            fans,
            interval.toMillis(),
            hold.toMillis(),
            roundTrip.toMillis()));
    lines.add(String.format(Locale.ROOT, "%-34s %14s %14s", "", "busy on-sale", "quiet evening"));
    double busy = busyPerMinute / SECONDS_PER_MINUTE;
    double quiet = quietPerMinute / SECONDS_PER_MINUTE;
    lines.add(row("seat changes per second", busy, quiet, "%,14.3f"));
    lines.add(
        row(
            "short polling, requests per second",
            rates.shortPollingRequestsPerSecond(),
            rates.shortPollingRequestsPerSecond(),
            "%,14.0f"));
    lines.add(
        row(
            "short polls that find nothing, %",
            PERCENT * rates.emptyShare(busy), PERCENT * rates.emptyShare(quiet), "%14.1f"));
    lines.add(
        row(
            "long polling, requests per second",
            rates.longPollingRequestsPerSecond(busy),
            rates.longPollingRequestsPerSecond(quiet),
            "%,14.0f"));
    lines.add(row("long polling, connections held", fans, fans, "%,14.0f"));
    lines.add(
        row(
            "push, messages per second",
            rates.pushMessagesPerSecond(busy),
            rates.pushMessagesPerSecond(quiet),
            "%,14.0f"));
    lines.add(row("push, connections held", fans, fans, "%,14.0f"));
    return List.copyOf(lines);
  }

  private static String row(String label, double busy, double quiet, String cell) {
    return String.format(Locale.ROOT, "%-34s " + cell + " " + cell, label, busy, quiet);
  }
}
