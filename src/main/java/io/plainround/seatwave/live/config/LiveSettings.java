package io.plainround.seatwave.live.config;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.feed.SeatLayout;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Everything a live-feed process needs that may differ between runs, read from one settings file
 * with no defaults in code: a missing key is a problem, never a silent fallback.
 *
 * @param network the ports and the allowed WebSocket origin
 * @param limits the bounds on connections, buffers and batches
 * @param timing heartbeats, timeouts and the long-poll hold
 * @param layout the event's seat map
 * @param onSale the simulated on-sale that changes seats
 * @param adminEnabled whether the lab's admin routes (pause, resume, drop connections, settle)
 *     exist
 * @param serveTime how long {@code serve} runs before it stops by itself
 */
public record LiveSettings(
    Network network,
    Limits limits,
    Timing timing,
    SeatLayout layout,
    OnSale onSale,
    boolean adminEnabled,
    Duration serveTime) {

  private static final int LOWEST_UNPRIVILEGED_PORT = 1_024;
  private static final int HIGHEST_PORT = 65_535;
  // macOS caps the accept backlog at kern.ipc.somaxconn; a larger request is silently lowered.
  private static final int MAX_BACKLOG = 4_096;
  private static final int MAX_CONNECTIONS = 100_000;
  private static final int MAX_REPLAY = 10_000_000;
  private static final int MAX_QUEUE = 100_000;
  private static final int MAX_BATCH = 10_000;
  private static final int MAX_TIMEOUT_MILLIS = 600_000;
  private static final int MAX_ACTIONS_PER_SECOND = 5_000;
  private static final int MAX_PERCENT = 100;
  private static final int MAX_SERVE_SECONDS = 86_400;
  // The page that may open a WebSocket is served by this feed on the loopback address.
  private static final Pattern LOOPBACK_ORIGIN =
      Pattern.compile("http://127\\.0\\.0\\.1:[0-9]{2,5}");

  /** Checks the fields. */
  public LiveSettings {
    Objects.requireNonNull(network, "network");
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(timing, "timing");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(onSale, "onSale");
    Objects.requireNonNull(serveTime, "serveTime");
    if (serveTime.isNegative() || serveTime.isZero()) {
      throw new IllegalArgumentException("serveTime must be positive");
    }
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings file
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<LiveSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    Network network =
        new Network(
            reader.intBetween("http.port", LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT),
            reader.intBetween("websocket.port", LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT),
            reader.intBetween("http.backlog", 1, MAX_BACKLOG),
            reader
                .matching("websocket.allowedOrigin", LOOPBACK_ORIGIN, "http://127.0.0.1:<port>")
                // A missing or invalid origin is already a problem line; this stand-in only lets
                // the reading go on, so every other problem is reported in the same run.
                .transform(text -> text.isEmpty() ? "http://127.0.0.1:10" : text));
    Limits limits =
        new Limits(
            reader.intBetween("feed.maxConnections", 1, MAX_CONNECTIONS),
            reader.intBetween("feed.replayCapacity", 1, MAX_REPLAY),
            reader.intBetween("feed.clientQueueCapacity", 1, MAX_QUEUE),
            reader.intBetween("feed.maxBatch", 1, MAX_BATCH));
    Timing timing =
        new Timing(
            reader.millisBetween("feed.heartbeatMillis", 1, MAX_TIMEOUT_MILLIS),
            reader.millisBetween("feed.writeTimeoutMillis", 1, MAX_TIMEOUT_MILLIS),
            reader.millisBetween("feed.longPollHoldMillis", 1, MAX_TIMEOUT_MILLIS),
            reader.millisBetween("feed.retryMillis", 1, MAX_TIMEOUT_MILLIS));
    int sections = reader.intBetween("store.sections", 1, SeatLayout.MAX_SECTIONS);
    int seatsPerSection =
        reader.intBetween("store.seatsPerSection", 1, SeatLayout.MAX_SEATS_PER_SECTION);
    OnSale onSale =
        new OnSale(
            Duration.ofSeconds(reader.intBetween("store.holdSeconds", 1, MAX_SERVE_SECONDS)),
            reader.intBetween("onSale.actionsPerSecond", 1, MAX_ACTIONS_PER_SECOND),
            reader.intBetween("onSale.confirmPercent", 0, MAX_PERCENT),
            reader.intBetween("onSale.seed", 0, Integer.MAX_VALUE));
    boolean admin = reader.flag("admin.enabled");
    Duration serve = Duration.ofSeconds(reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS));
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new LiveSettings(
            network,
            limits,
            timing,
            new SeatLayout(sections, seatsPerSection),
            onSale,
            admin,
            serve));
  }

  /**
   * Where the feed listens.
   *
   * @param httpPort the JDK HTTP server's port for long polling, server-sent events and the page; 0
   *     lets the system pick one (tests only)
   * @param websocketPort the WebSocket server's port; 0 lets the system pick one (tests only)
   * @param backlog how many connections may wait to be accepted
   * @param allowedOrigin the one browser origin allowed to open a WebSocket
   */
  public record Network(int httpPort, int websocketPort, int backlog, String allowedOrigin) {

    /** Checks the fields. */
    public Network {
      if (httpPort < 0
          || httpPort > HIGHEST_PORT
          || websocketPort < 0
          || websocketPort > HIGHEST_PORT) {
        throw new IllegalArgumentException("ports must be from 0 to 65535");
      }
      if (backlog < 1 || backlog > MAX_BACKLOG) {
        throw new IllegalArgumentException("backlog out of range: " + backlog);
      }
      if (!LOOPBACK_ORIGIN
          .matcher(Objects.requireNonNull(allowedOrigin, "allowedOrigin"))
          .matches()) {
        throw new IllegalArgumentException("allowedOrigin must be http://127.0.0.1:<port>");
      }
    }
  }

  /**
   * The bounds that keep memory and connections finite.
   *
   * @param maxConnections the most feed connections open at once, all methods together
   * @param replayCapacity how many recent changes are kept for clients that resume
   * @param clientQueueCapacity how many changes one push connection may have waiting
   * @param maxBatch the most changes one write or one long-poll reply carries
   */
  public record Limits(
      int maxConnections, int replayCapacity, int clientQueueCapacity, int maxBatch) {

    /** Checks the fields. */
    public Limits {
      if (maxConnections < 1 || replayCapacity < 1 || clientQueueCapacity < 1 || maxBatch < 1) {
        throw new IllegalArgumentException("every limit must be at least 1");
      }
    }
  }

  /**
   * The feed's clocks.
   *
   * @param heartbeat how long a push connection may stay silent before the feed sends a heartbeat
   * @param writeTimeout how long one write may block before the connection is dropped
   * @param longPollHold how long a long poll is held when there is no news
   * @param retry the reconnection delay sent to server-sent events clients
   */
  public record Timing(
      Duration heartbeat, Duration writeTimeout, Duration longPollHold, Duration retry) {

    /** Checks the fields. */
    public Timing {
      for (Duration duration : List.of(heartbeat, writeTimeout, longPollHold, retry)) {
        if (duration.isNegative() || duration.isZero()) {
          throw new IllegalArgumentException("every timing must be positive");
        }
      }
    }
  }

  /**
   * The simulated on-sale.
   *
   * @param holdTime how long a hold lasts
   * @param actionsPerSecond holds and confirms per second
   * @param confirmPercent the share of actions that confirm a hold
   * @param seed the seed of the simulator's random choices
   */
  public record OnSale(Duration holdTime, int actionsPerSecond, int confirmPercent, int seed) {

    /** Checks the fields. */
    public OnSale {
      Objects.requireNonNull(holdTime, "holdTime");
      if (actionsPerSecond < 1 || actionsPerSecond > MAX_ACTIONS_PER_SECOND) {
        throw new IllegalArgumentException("actionsPerSecond out of range: " + actionsPerSecond);
      }
      if (confirmPercent < 0 || confirmPercent > MAX_PERCENT || seed < 0) {
        throw new IllegalArgumentException("confirmPercent or seed out of range");
      }
    }
  }
}
