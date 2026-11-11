package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Turns the runs into the printed table and the comparison CSV: one line per run, then one line per
 * method with the median of its runs. Stateless.
 */
public final class ComparisonReport {

  private static final String CSV_HEADER =
      "method,run,clients,connected,changes_per_s,deliveries_per_s,requests_per_s,"
          + "server_bytes_per_delivery,delay_p50_ms,delay_p99_ms,delay_max_ms,delay_samples,"
          + "cpu_cores,cpu_us_per_delivery,heap_bytes_per_connection,"
          + "resident_bytes_per_connection,platform_threads,virtual_threads,open_fds,max_fds,"
          + "dropped,recovery_ms,server_reconnects,client_reconnects,reloads,gaps,duplicates,"
          + "correct_maps";
  private static final String TABLE_HEADER =
      String.format(
          Locale.ROOT,
          "%-12s %3s %5s %8s %10s %9s %8s %8s %8s %6s %7s %8s %8s %4s %4s %4s %7s %6s %5s %4s %7s",
          "method",
          "run",
          "conn",
          "changes/s",
          "deliver/s",
          "req/s",
          "p50 ms",
          "p99 ms",
          "max ms",
          "cores",
          "us/del",
          "heap/c",
          "rss/c",
          "pthr",
          "vthr",
          "fds",
          "recov ms",
          "reconn",
          "reload",
          "gaps",
          "correct");

  private ComparisonReport() {}

  /**
   * The table: a header, one line per run, then the medians.
   *
   * @param results every run of every method
   * @return the lines to print
   */
  public static List<String> table(List<MethodResult> results) {
    List<String> lines = new ArrayList<>();
    lines.add(TABLE_HEADER);
    results.forEach(
        result -> lines.add(row(result.method().label(), Integer.toString(result.run()), result)));
    lines.add("median of the runs:");
    for (DeliveryMethod method : DeliveryMethod.values()) {
      List<MethodResult> runs =
          results.stream().filter(result -> result.method() == method).toList();
      if (!runs.isEmpty()) {
        lines.add(row(method.label(), "med", median(runs)));
      }
    }
    return List.copyOf(lines);
  }

  /**
   * Writes the CSV file, one line per run.
   *
   * @param results every run
   * @param file where to write it
   * @throws IOException if the file cannot be written
   */
  public static void writeCsv(List<MethodResult> results, Path file) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add(CSV_HEADER);
    for (MethodResult result : results) {
      lines.add(
          String.join(
              ",",
              result.method().label(),
              Integer.toString(result.run()),
              Integer.toString(result.clients()),
              Integer.toString(result.connected()),
              decimal(result.load().changesPerSecond()),
              decimal(result.load().deliveriesPerSecond()),
              decimal(result.load().requestsPerSecond()),
              decimal(result.load().bytesPerDelivery()),
              decimal(result.delay().p50Millis()),
              decimal(result.delay().p99Millis()),
              decimal(result.delay().maxMillis()),
              Long.toString(result.delay().count()),
              decimal(result.cost().cpuCores()),
              decimal(result.cost().cpuMicrosPerDelivery()),
              Long.toString(result.cost().heapBytesPerConnection()),
              Long.toString(result.cost().residentBytesPerConnection()),
              Integer.toString(result.cost().platformThreads()),
              Integer.toString(result.cost().virtualThreads()),
              Long.toString(result.cost().openFileDescriptors()),
              Long.toString(result.cost().maxFileDescriptors()),
              Long.toString(result.reconnect().dropped()),
              Long.toString(result.reconnect().recoveryMillis()),
              Long.toString(result.reconnect().serverReconnects()),
              Long.toString(result.reconnect().clientReconnects()),
              Long.toString(result.reconnect().reloads()),
              Long.toString(result.correctness().gaps()),
              Long.toString(result.correctness().duplicates()),
              Integer.toString(result.correctness().correctMaps())));
    }
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.write(file, lines, StandardCharsets.UTF_8);
  }

  /**
   * The run whose values are each the median of the runs (for each column on its own).
   *
   * @param runs one or more runs of one method
   * @return a result made of the medians
   */
  public static MethodResult median(List<MethodResult> runs) {
    MethodResult first = runs.getFirst();
    return new MethodResult(
        first.method(),
        0,
        first.clients(),
        (int) median(runs, MethodResult::connected),
        new LatencySummary(
            (long) median(runs, result -> result.delay().count()),
            median(runs, result -> result.delay().p50Millis()),
            median(runs, result -> result.delay().p99Millis()),
            median(runs, result -> result.delay().maxMillis())),
        new MethodResult.Load(
            median(runs, result -> result.load().changesPerSecond()),
            median(runs, result -> result.load().deliveriesPerSecond()),
            median(runs, result -> result.load().requestsPerSecond()),
            median(runs, result -> result.load().bytesPerDelivery())),
        new MethodResult.ServerCost(
            median(runs, result -> result.cost().cpuCores()),
            median(runs, result -> result.cost().cpuMicrosPerDelivery()),
            (long) median(runs, result -> result.cost().heapBytesPerConnection()),
            (long) median(runs, result -> result.cost().residentBytesPerConnection()),
            (int) median(runs, result -> result.cost().platformThreads()),
            (int) median(runs, result -> result.cost().virtualThreads()),
            (long) median(runs, result -> result.cost().openFileDescriptors()),
            (long) median(runs, result -> result.cost().maxFileDescriptors())),
        new MethodResult.Reconnect(
            (long) median(runs, result -> result.reconnect().dropped()),
            (long) median(runs, result -> result.reconnect().recoveryMillis()),
            (long) median(runs, result -> result.reconnect().serverReconnects()),
            (long) median(runs, result -> result.reconnect().clientReconnects()),
            (long) median(runs, result -> result.reconnect().reloads())),
        new MethodResult.Correctness(
            (long) median(runs, result -> result.correctness().gaps()),
            (long) median(runs, result -> result.correctness().duplicates()),
            (int) median(runs, result -> result.correctness().correctMaps())));
  }

  private static String row(String method, String run, MethodResult result) {
    return String.format(
        Locale.ROOT,
        "%-12s %3s %5d %8.1f %10.1f %9.1f %8.2f %8.2f %8.2f %6.3f %7.1f %8d %8s %4d %4d %4d %7d %6d %5d %4d %7d",
        method,
        run,
        result.connected(),
        result.load().changesPerSecond(),
        result.load().deliveriesPerSecond(),
        result.load().requestsPerSecond(),
        result.delay().p50Millis(),
        result.delay().p99Millis(),
        result.delay().maxMillis(),
        result.cost().cpuCores(),
        result.cost().cpuMicrosPerDelivery(),
        result.cost().heapBytesPerConnection(),
        result.cost().residentBytesPerConnection() < 0
            ? "n/a"
            : Long.toString(result.cost().residentBytesPerConnection()),
        result.cost().platformThreads(),
        result.cost().virtualThreads(),
        result.cost().openFileDescriptors(),
        result.reconnect().recoveryMillis(),
        result.reconnect().clientReconnects(),
        result.reconnect().reloads(),
        result.correctness().gaps(),
        result.correctness().correctMaps());
  }

  private static double median(List<MethodResult> runs, ToDoubleFunction<MethodResult> column) {
    List<Double> sorted =
        runs.stream().map(column::applyAsDouble).sorted(Comparator.naturalOrder()).toList();
    int middle = sorted.size() / 2;
    return sorted.size() % 2 == 1
        ? sorted.get(middle)
        : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
  }

  private static String decimal(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }
}
