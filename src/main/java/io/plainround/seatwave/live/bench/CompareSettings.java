package io.plainround.seatwave.live.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.config.SettingsReader;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The comparison's settings, read from one file with no defaults in code.
 *
 * @param serverSettings the feed node's own settings file (admin routes on)
 * @param methods the delivery methods to measure, in order
 * @param clients how many clients per method
 * @param runs how many runs per method; the report shows each run and the median
 * @param phases how long each phase of a run lasts
 * @param fleet how clients connect, time out and back off
 * @param childProcess whether the node runs in a process of its own (true for real numbers)
 * @param childHeapMegabytes the node's largest heap when it runs alone
 * @param output the folder for the CSV file and the node's logs
 */
public record CompareSettings(
    Path serverSettings,
    List<DeliveryMethod> methods,
    int clients,
    int runs,
    Phases phases,
    Fleet fleet,
    boolean childProcess,
    int childHeapMegabytes,
    Path output) {

  private static final int MAX_CLIENTS = 50_000;
  private static final int MAX_RUNS = 20;
  private static final int MAX_SECONDS = 3_600;
  private static final int MAX_MILLIS = 600_000;
  private static final int MAX_HEAP_MEGABYTES = 32_768;
  private static final int MAX_CONNECTS_PER_SECOND = 10_000;
  private static final Pattern METHODS =
      Pattern.compile("(long-polling|sse|websocket)(,(long-polling|sse|websocket))*");
  private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_./-]{1,200}");

  /** Checks the fields. */
  public CompareSettings {
    Objects.requireNonNull(serverSettings, "serverSettings");
    methods = List.copyOf(methods);
    Objects.requireNonNull(phases, "phases");
    Objects.requireNonNull(fleet, "fleet");
    Objects.requireNonNull(output, "output");
    if (methods.isEmpty() || clients < 1 || runs < 1 || childHeapMegabytes < 1) {
      throw new IllegalArgumentException("methods, clients, runs and heap must be positive");
    }
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings file
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<CompareSettings, List<String>> from(SettingsReader reader) {
    String server = reader.matching("compare.serverSettings", PATH, "a relative file path");
    String methodList =
        reader.matching(
            "compare.methods", METHODS, "a comma-separated list of long-polling, sse, websocket");
    int clients = reader.intBetween("compare.clients", 1, MAX_CLIENTS);
    int runs = reader.intBetween("compare.runs", 1, MAX_RUNS);
    Phases phases =
        new Phases(
            Duration.ofSeconds(reader.intBetween("compare.warmupSeconds", 1, MAX_SECONDS)),
            Duration.ofSeconds(reader.intBetween("compare.measureSeconds", 1, MAX_SECONDS)),
            Duration.ofSeconds(reader.intBetween("compare.settleTimeoutSeconds", 1, MAX_SECONDS)));
    Fleet fleet =
        new Fleet(
            reader.intBetween("compare.connectsPerSecond", 1, MAX_CONNECTS_PER_SECOND),
            reader.millisBetween("compare.connectTimeoutMillis", 1, MAX_MILLIS),
            reader.millisBetween("compare.requestTimeoutMillis", 1, MAX_MILLIS),
            reader.millisBetween("compare.silenceLimitMillis", 1, MAX_MILLIS),
            reader.millisBetween("compare.backoffBaseMillis", 1, MAX_MILLIS),
            reader.millisBetween("compare.backoffCapMillis", 1, MAX_MILLIS));
    boolean child = reader.flag("compare.childProcess");
    int heap = reader.intBetween("compare.childHeapMegabytes", 1, MAX_HEAP_MEGABYTES);
    String output = reader.matching("compare.output", PATH, "a relative folder path");
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    List<DeliveryMethod> methods = new ArrayList<>();
    for (String label : methodList.split(",")) {
      methods.add(
          Arrays.stream(DeliveryMethod.values())
              .filter(method -> method.label().equals(label))
              .findFirst()
              .orElseThrow());
    }
    return Result.success(
        new CompareSettings(
            Path.of(server), methods, clients, runs, phases, fleet, child, heap, Path.of(output)));
  }

  /**
   * How long each phase of a run lasts.
   *
   * @param warmup changes flow but delays are not recorded, so the JIT and the caches settle
   * @param measure delays, CPU and rates are recorded
   * @param settleTimeout the longest wait for clients to connect or catch up
   */
  public record Phases(Duration warmup, Duration measure, Duration settleTimeout) {

    /** Checks the fields. */
    public Phases {
      Objects.requireNonNull(warmup, "warmup");
      Objects.requireNonNull(measure, "measure");
      Objects.requireNonNull(settleTimeout, "settleTimeout");
    }
  }

  /**
   * How the clients connect.
   *
   * @param connectsPerSecond how many clients start per second
   * @param connectTimeout the TCP connect timeout
   * @param requestTimeout the longest wait for a reply or a stream's headers
   * @param silenceLimit the longest a push connection may stay silent before the client drops it
   * @param backoffBase the first reconnect wait's ceiling
   * @param backoffCap the largest reconnect wait's ceiling
   */
  public record Fleet(
      int connectsPerSecond,
      Duration connectTimeout,
      Duration requestTimeout,
      Duration silenceLimit,
      Duration backoffBase,
      Duration backoffCap) {

    /** Checks the fields. */
    public Fleet {
      Objects.requireNonNull(connectTimeout, "connectTimeout");
      Objects.requireNonNull(requestTimeout, "requestTimeout");
      Objects.requireNonNull(silenceLimit, "silenceLimit");
      Objects.requireNonNull(backoffBase, "backoffBase");
      Objects.requireNonNull(backoffCap, "backoffCap");
    }
  }
}
