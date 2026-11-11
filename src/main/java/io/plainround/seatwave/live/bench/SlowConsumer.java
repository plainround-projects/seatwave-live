package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.client.ClientFleet;
import io.plainround.seatwave.live.client.FeedClient;
import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.client.FleetPlan;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatMapSnapshot;
import io.plainround.seatwave.live.websocket.Handshake;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The slow-consumer experiment: normal event-stream clients beside one event-stream client and one
 * WebSocket client that connect and then never read, while the on-sale runs fast. It shows the
 * bound: each slow client's queue stops at its capacity, it is told to reload, its blocked write is
 * cut after the write timeout, and the normal clients keep their delay and end with the store's
 * map.
 */
public final class SlowConsumer {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final Duration POLL = Duration.ofMillis(20);
  // A small receive buffer fills the kernel's buffers sooner, so the server's write blocks sooner.
  private static final int SLOW_RECEIVE_BUFFER_BYTES = 1_024;
  private static final int DELAYS_PER_CLIENT = 100_000;

  private final LiveSettings settings;
  private final int normalClients;
  private final Duration runTime;

  /**
   * Creates the experiment.
   *
   * @param settings the node's settings (a small client queue and a short write timeout show the
   *     bound quickly)
   * @param normalClients how many clients read normally
   * @param runTime how long the on-sale runs with the slow clients connected
   */
  public SlowConsumer(LiveSettings settings, int normalClients, Duration runTime) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.normalClients = normalClients;
    this.runTime = Objects.requireNonNull(runTime, "runTime");
  }

  /**
   * Runs it.
   *
   * @return the report's lines
   * @throws IOException if the node cannot start
   * @throws InterruptedException if interrupted
   */
  public List<String> run() throws IOException, InterruptedException {
    try (LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC())) {
      FeedEndpoints endpoints = new FeedEndpoints(node.httpPort(), node.webSocketPort());
      ClientFleet fleet = ClientFleet.start(plan(endpoints));
      try (Socket slowStream = openSlowStream(node);
          Socket slowSocket = openSlowSocket(node)) {
        awaitTrue(() -> fleet.countLoaded() == normalClients);
        long heapBefore = settledHeap();
        fleet.record(true);
        node.startOnSale();
        Thread.sleep(runTime);
        long heapAfter = settledHeap();
        node.parts().simulator().pause();
        SeatMapSnapshot store = node.parts().store().snapshot();
        awaitTrue(() -> fleet.countAtLeast(store.position().sequence()) == normalClients);
        fleet.stop();
        return report(
            node,
            fleet,
            store,
            heapBefore,
            heapAfter,
            slowStream.isConnected() && slowSocket.isConnected());
      } finally {
        fleet.stop();
      }
    }
  }

  private List<String> report(
      LiveFeedNode node,
      ClientFleet fleet,
      SeatMapSnapshot store,
      long heapBefore,
      long heapAfter,
      boolean slowClientsStayedOpen) {
    Map<String, Long> metrics = metrics(node);
    long correct =
        fleet.clients().stream()
            .filter(client -> client.map().states().equals(store.states()))
            .count();
    long[] delays =
        fleet.clients().stream()
            .map(FeedClient::stats)
            .flatMapToLong(stats -> Arrays.stream(stats.delaysMicros()))
            .toArray();
    LatencySummary delay = LatencySummary.ofMicros(delays);
    List<String> lines = new ArrayList<>();
    lines.add(
        String.format(
            Locale.ROOT,
            "settings: client queue %d changes, write timeout %d ms, on-sale %d actions per second for %d s",
            settings.limits().clientQueueCapacity(),
            settings.timing().writeTimeout().toMillis(),
            settings.onSale().actionsPerSecond(),
            runTime.toSeconds()));
    lines.add(
        String.format(
            Locale.ROOT,
            "changes published: %d",
            metrics.get("seatwave_feed_changes_published_total")));
    lines.add(
        String.format(
            Locale.ROOT,
            "deepest client queue: %d (bound %d)",
            metrics.get("seatwave_feed_deepest_client_queue"),
            settings.limits().clientQueueCapacity()));
    lines.add(
        String.format(
            Locale.ROOT,
            "queue overflows (client told to reload): %d",
            metrics.get("seatwave_feed_queue_overflows_total")));
    lines.add(
        String.format(
            Locale.ROOT,
            "stuck writes cut after the write timeout: %d",
            metrics.get("seatwave_feed_stuck_writer_disconnects_total")));
    lines.add(
        String.format(
            Locale.ROOT,
            "open connections at the end: sse %d, websocket %d (the %d normal clients are sse)",
            node.parts().metrics().openConnections(DeliveryMethod.SERVER_SENT_EVENTS),
            node.parts().metrics().openConnections(DeliveryMethod.WEBSOCKET),
            normalClients));
    lines.add(
        String.format(
            Locale.ROOT,
            "slow clients' own sockets still open on their side: %s",
            slowClientsStayedOpen ? "yes" : "no"));
    lines.add(
        String.format(
            Locale.ROOT,
            "heap after a collection: %d KB before, %d KB after",
            heapBefore / 1_024,
            heapAfter / 1_024));
    lines.add(
        String.format(
            Locale.ROOT,
            "normal clients: delay p50 %.2f ms, p99 %.2f ms, max %.2f ms (%d samples); %d of %d match the store",
            delay.p50Millis(),
            delay.p99Millis(),
            delay.maxMillis(),
            delay.count(),
            correct,
            normalClients));
    return List.copyOf(lines);
  }

  private FleetPlan plan(FeedEndpoints endpoints) {
    return new FleetPlan(
        DeliveryMethod.SERVER_SENT_EVENTS,
        normalClients,
        endpoints,
        normalClients,
        new FleetPlan.Timeouts(
            WAIT, WAIT.multipliedBy(2), settings.timing().heartbeat().multipliedBy(3)),
        new FleetPlan.BackoffRange(Duration.ofMillis(100), Duration.ofSeconds(2)),
        DELAYS_PER_CLIENT,
        1);
  }

  // An event-stream request sent by hand, after which nothing is ever read.
  private Socket openSlowStream(LiveFeedNode node) throws IOException {
    Socket socket = slowSocket(node.httpPort());
    FeedPosition now = node.parts().feed().position();
    send(
        socket,
        "GET /v1/events/1/seat-changes/stream?after="
            + now.written()
            + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n");
    return socket;
  }

  // A WebSocket handshake sent by hand, after which nothing is ever read (not even the 101).
  private Socket openSlowSocket(LiveFeedNode node) throws IOException {
    Socket socket = slowSocket(node.webSocketPort());
    FeedPosition now = node.parts().feed().position();
    send(
        socket,
        "GET "
            + Handshake.PATH
            + "?after="
            + now.written()
            + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
    return socket;
  }

  private static Socket slowSocket(int port) throws IOException {
    Socket socket = new Socket();
    socket.setReceiveBufferSize(SLOW_RECEIVE_BUFFER_BYTES);
    socket.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    return socket;
  }

  private static void send(Socket socket, String request) throws IOException {
    OutputStream out = socket.getOutputStream();
    out.write(request.getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  private static Map<String, Long> metrics(LiveFeedNode node) {
    Map<String, Long> values = new java.util.HashMap<>();
    node.parts()
        .metrics()
        .render()
        .lines()
        .forEach(line -> {
          int space = line.lastIndexOf(' ');
          values.put(line.substring(0, space), Long.parseLong(line.substring(space + 1)));
        });
    return values;
  }

  private static long settledHeap() {
    ManagementFactory.getMemoryMXBean().gc();
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(POLL);
    }
  }
}
