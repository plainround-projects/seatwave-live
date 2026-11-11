package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.client.Backoff;
import io.plainround.seatwave.live.client.ClientStats;
import io.plainround.seatwave.live.client.EventStreamConnection;
import io.plainround.seatwave.live.client.FeedClient;
import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.client.SeatMapReader;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.SeatMapSnapshot;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lab 2: one server-sent events client, resumed with {@code Last-Event-ID}. In the first scenario
 * the server drops the stream twice while the on-sale runs, inside the replay buffer's window; the
 * client resumes and ends with every change exactly once. In the second, the client is away while
 * more changes happen than the buffer holds; the server tells it to reload, and it still ends with
 * the store's map.
 */
public final class SseResume {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL = Duration.ofMillis(10);
  private static final Duration BETWEEN_DROPS = Duration.ofSeconds(1);
  private static final int DROPS = 2;
  private static final int DELAYS = 100_000;

  private final LiveSettings settings;
  private final int changesWhileAway;

  /**
   * Creates the lab run.
   *
   * @param settings the node's settings; the replay capacity must be below {@code changesWhileAway}
   * @param changesWhileAway how many changes the second scenario makes while the client is away
   */
  public SseResume(LiveSettings settings, int changesWhileAway) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.changesWhileAway = changesWhileAway;
  }

  /**
   * Runs both scenarios.
   *
   * @return the report's lines
   * @throws IOException if the node cannot start
   * @throws InterruptedException if interrupted
   */
  public List<String> run() throws IOException, InterruptedException {
    List<String> lines = new ArrayList<>();
    lines.add(
        String.format(
            Locale.ROOT,
            "replay buffer: %d changes; on-sale %d actions per second",
            settings.limits().replayCapacity(),
            settings.onSale().actionsPerSecond()));
    try (LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC());
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      FeedClient client = client(node, http);
      Thread follower = Thread.ofVirtual().start(client);
      awaitTrue(() -> client.map().isLoaded());
      node.startOnSale();
      for (int drop = 0; drop < DROPS; drop++) {
        Thread.sleep(BETWEEN_DROPS);
        lines.add(
            String.format(
                Locale.ROOT,
                "drop %d: the server closed %d stream(s) at sequence %d",
                drop + 1,
                node.parts().limit().dropAll(),
                node.parts().feed().position().sequence()));
      }
      Thread.sleep(BETWEEN_DROPS);
      node.parts().simulator().pause();
      lines.add(result("scenario 1, resumed inside the buffer", node, client, follower));
      // Scenario 2: the client is away while more changes happen than the buffer holds.
      for (int seat = 0, made = 0;
          made < changesWhileAway && seat < settings.layout().seatCount();
          seat++) {
        if (!node.parts().store().hold(seat).isError()) {
          made++;
        }
      }
      lines.add(
          String.format(
              Locale.ROOT,
              "while the client was away: %d holds, feed now at sequence %d",
              changesWhileAway,
              node.parts().feed().position().sequence()));
      Thread back = Thread.ofVirtual().start(client);
      lines.add(result("scenario 2, away longer than the buffer", node, client, back));
    }
    return List.copyOf(lines);
  }

  private String result(String scenario, LiveFeedNode node, FeedClient client, Thread follower)
      throws InterruptedException {
    SeatMapSnapshot store = node.parts().store().snapshot();
    awaitTrue(() -> client.map().sequence() >= store.position().sequence());
    follower.interrupt();
    follower.join(TIMEOUT);
    ClientStats stats = client.stats();
    return String.format(
        Locale.ROOT,
        "%s: applied %d, duplicates %d, gaps %d, reloads %d, reconnects %d; client at %s, store at %s; map equals store: %s",
        scenario,
        stats.changesApplied(),
        stats.duplicates(),
        stats.gaps(),
        stats.reloads(),
        stats.reconnects(),
        client.map().position().written(),
        store.position().written(),
        client.map().states().equals(store.states()) ? "yes" : "no");
  }

  private static FeedClient client(LiveFeedNode node, HttpClient http) {
    FeedEndpoints endpoints = new FeedEndpoints(node.httpPort(), node.webSocketPort());
    FeedJson json = new FeedJson();
    return new FeedClient(
        new EventStreamConnection(http, endpoints, json, TIMEOUT),
        new SeatMapReader(http, endpoints, json, TIMEOUT),
        new Backoff(Duration.ofMillis(50), Duration.ofSeconds(1), 1),
        new AtomicBoolean(),
        new ClientStats(DELAYS));
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(POLL);
    }
  }
}
