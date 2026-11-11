package io.plainround.seatwave.live;

import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.FeedMetrics;
import io.plainround.seatwave.live.feed.OnSaleSimulator;
import io.plainround.seatwave.live.feed.SeatFeed;
import io.plainround.seatwave.live.feed.SeatStore;
import io.plainround.seatwave.live.http.FeedHttpServer;
import io.plainround.seatwave.live.http.Parts;
import io.plainround.seatwave.live.websocket.WebSocketServer;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One SeatWave live-feed node: the seat store, the feed, the simulated on-sale, the HTTP server
 * (long polling and server-sent events) and the WebSocket server, wired together and closed
 * together. The on-sale does not start until {@link #startOnSale()}, so tests can make every change
 * themselves.
 *
 * <p>Thread-safe: every part it holds documents its own thread-safety.
 */
public final class LiveFeedNode implements AutoCloseable {

  // The stuck-writer check runs four times per write timeout, so a stuck write is found within
  // a quarter of the timeout after it expires.
  private static final int CHECKS_PER_WRITE_TIMEOUT = 4;

  private final Parts parts;
  private final WebSocketServer webSocketServer;
  private final FeedHttpServer httpServer;
  private final ScheduledExecutorService watchdog;

  private LiveFeedNode(
      Parts parts,
      WebSocketServer webSocketServer,
      FeedHttpServer httpServer,
      ScheduledExecutorService watchdog) {
    this.parts = parts;
    this.webSocketServer = webSocketServer;
    this.httpServer = httpServer;
    this.watchdog = watchdog;
  }

  /**
   * Starts a node.
   *
   * @param settings every setting of the node
   * @param clock the store's clock
   * @return the running node
   * @throws IOException if a port cannot be bound
   */
  public static LiveFeedNode start(LiveSettings settings, Clock clock) throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(clock, "clock");
    FeedMetrics metrics = new FeedMetrics();
    SeatFeed feed =
        new SeatFeed(
            settings.limits().replayCapacity(),
            settings.limits().clientQueueCapacity(),
            settings.limits().maxBatch(),
            metrics);
    SeatStore store = new SeatStore(settings.layout(), settings.onSale().holdTime(), clock, feed);
    OnSaleSimulator simulator =
        new OnSaleSimulator(
            store,
            settings.onSale().actionsPerSecond(),
            settings.onSale().confirmPercent(),
            settings.onSale().seed());
    Parts parts =
        new Parts(
            store,
            feed,
            simulator,
            new ConnectionLimit(settings.limits().maxConnections(), metrics),
            metrics,
            new FeedJson());
    WebSocketServer webSocketServer = WebSocketServer.start(settings, parts);
    FeedHttpServer httpServer = FeedHttpServer.start(settings, parts, webSocketServer.port());
    Duration writeTimeout = settings.timing().writeTimeout();
    long periodNanos = Math.max(1, writeTimeout.toNanos() / CHECKS_PER_WRITE_TIMEOUT);
    ScheduledExecutorService watchdog =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().name("stuck-writers").daemon().unstarted(runnable));
    watchdog.scheduleAtFixedRate(
        () -> feed.disconnectStuckWriters(writeTimeout),
        periodNanos,
        periodNanos,
        TimeUnit.NANOSECONDS);
    return new LiveFeedNode(parts, webSocketServer, httpServer, watchdog);
  }

  /** Starts the simulated on-sale at the configured rate. */
  public void startOnSale() {
    parts.simulator().start();
  }

  /**
   * The parts of the node, for tests and the lab tools.
   *
   * @return the store, feed, simulator, limit, metrics and codec
   */
  public Parts parts() {
    return parts;
  }

  /**
   * The HTTP port.
   *
   * @return the port
   */
  public int httpPort() {
    return httpServer.port();
  }

  /**
   * The WebSocket port.
   *
   * @return the port
   */
  public int webSocketPort() {
    return webSocketServer.port();
  }

  @Override
  public void close() throws IOException {
    parts.simulator().close();
    watchdog.shutdownNow();
    parts.limit().dropAll();
    httpServer.close();
    webSocketServer.close();
  }
}
