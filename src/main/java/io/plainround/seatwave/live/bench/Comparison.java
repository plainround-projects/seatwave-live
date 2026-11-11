package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.client.ClientFleet;
import io.plainround.seatwave.live.client.FeedClient;
import io.plainround.seatwave.live.client.FeedEndpoints;
import io.plainround.seatwave.live.client.FleetPlan;
import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.wire.SnapshotView;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One measured run of one delivery method, in six phases: start a fresh server and pause its
 * on-sale; read its memory with no clients; connect every client and read it again (the cost of
 * idle open connections); run the on-sale through a warm-up and a measured phase (delay, CPU,
 * rates); drop every connection at once and time the recovery; pause and compare every client's
 * seat map with the store's.
 *
 * <p>Not thread-safe: one comparison runs at a time.
 */
public final class Comparison {

  private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL = Duration.ofMillis(50);
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double MICROS_PER_SECOND = 1_000_000.0;
  // Each client keeps up to this many delays; more are counted but not kept.
  private static final int DELAYS_PER_CLIENT = 20_000;
  private static final long CLIENT_SEED = 8;
  private static final int FIRST_SHARE = 10;

  private final CompareSettings settings;
  private final LiveSettings serverSettings;

  /**
   * Creates the comparison.
   *
   * @param settings the comparison's settings
   * @param serverSettings the node's settings, already read from {@code settings.serverSettings()}
   */
  public Comparison(CompareSettings settings, LiveSettings serverSettings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.serverSettings = Objects.requireNonNull(serverSettings, "serverSettings");
  }

  /**
   * Runs one method once.
   *
   * @param method the delivery method
   * @param run the run's number, from 1
   * @return what it measured
   * @throws IOException if the server cannot start or stops answering
   * @throws InterruptedException if interrupted
   */
  public MethodResult run(DeliveryMethod method, int run) throws IOException, InterruptedException {
    try (ServerHandle server = startServer(method, run);
        HttpClient http = HttpClient.newHttpClient()) {
      FeedEndpoints endpoints = new FeedEndpoints(server.httpPort(), server.webSocketPort());
      FeedAdmin admin = new FeedAdmin(http, endpoints, ADMIN_TIMEOUT);
      admin.awaitHealthy(settings.phases().settleTimeout());
      admin.admin("pause");
      // A tenth of the clients connect first, so one-time costs (classes, caches, buffers that are
      // made once) are paid before the first reading; the rest then cost only per connection.
      int firstClients = Math.max(1, settings.clients() / FIRST_SHARE);
      Fleets fleets = new Fleets(new ArrayList<>());
      try {
        fleets.add(ClientFleet.start(plan(method, endpoints, firstClients, 0)));
        Probe probe = new Probe(server, admin, fleets);
        awaitTrue(() ->
            fleets.countLoaded() == firstClients && openConnections(probe, method) >= firstClients);
        long heapFirst = admin.admin("settle");
        OptionalLong residentFirst = resident(server);
        fleets.add(
            ClientFleet.start(
                plan(method, endpoints, settings.clients() - firstClients, firstClients)));
        return measure(method, run, probe, new Reading(firstClients, heapFirst, residentFirst));
      } finally {
        fleets.stop();
      }
    }
  }

  private MethodResult measure(DeliveryMethod method, int run, Probe probe, Reading first)
      throws IOException, InterruptedException {
    awaitTrue(() ->
        probe.fleet.countLoaded() == settings.clients()
            && openConnections(probe, method) >= settings.clients());
    int connected = probe.fleet.countLoaded();
    int added = Math.max(1, connected - first.clients());
    long heapConnected = probe.admin.admin("settle");
    OptionalLong residentConnected = resident(probe.server);
    Map<String, Double> idle = probe.admin.metrics();
    probe.admin.admin("resume");
    Thread.sleep(settings.phases().warmup());
    probe.fleet.record(true);
    Map<String, Double> before = probe.admin.metrics();
    long startNanos = System.nanoTime();
    Thread.sleep(settings.phases().measure());
    Map<String, Double> after = probe.admin.metrics();
    double seconds = (System.nanoTime() - startNanos) / NANOS_PER_SECOND;
    probe.fleet.record(false);
    MethodResult.Reconnect reconnect = dropAndRecover(probe, after);
    Correctness correctness = finish(probe);
    MethodResult.Load load = load(method, before, after, seconds);
    double cores =
        (value(after, "process_cpu_nanoseconds_total")
                - value(before, "process_cpu_nanoseconds_total"))
            / NANOS_PER_SECOND
            / seconds;
    MethodResult.ServerCost cost =
        new MethodResult.ServerCost(
            cores,
            load.deliveriesPerSecond() == 0
                ? 0
                : cores * MICROS_PER_SECOND / load.deliveriesPerSecond(),
            (heapConnected - first.heapBytes()) / added,
            first.residentBytes().isPresent() && residentConnected.isPresent()
                ? (residentConnected.getAsLong() - first.residentBytes().getAsLong()) / added
                : -1,
            (int) value(idle, "process_platform_threads"),
            (int) value(idle, "seatwave_feed_handler_virtual_threads"),
            (long) value(idle, "process_open_fds"),
            (long) value(idle, "process_max_fds"));
    return new MethodResult(
        method,
        run,
        settings.clients(),
        connected,
        correctness.delay,
        load,
        cost,
        new MethodResult.Reconnect(
            reconnect.dropped(),
            reconnect.recoveryMillis(),
            reconnect.serverReconnects(),
            correctness.clientReconnects,
            correctness.reloads),
        new MethodResult.Correctness(
            correctness.gaps, correctness.duplicates, correctness.correctMaps));
  }

  private MethodResult.Reconnect dropAndRecover(Probe probe, Map<String, Double> before)
      throws IOException, InterruptedException {
    long published = (long) value(probe.admin.metrics(), "seatwave_feed_changes_published_total");
    long dropNanos = System.nanoTime();
    long dropped = probe.admin.admin("drop-connections");
    // Recovered: every client has applied a change made after the drop, so each one has
    // reconnected, resumed and caught up.
    awaitTrue(() -> probe.fleet.countAtLeast(published + 1) == probe.fleet.size());
    long recoveryMillis = Duration.ofNanos(System.nanoTime() - dropNanos).toMillis();
    Map<String, Double> after = probe.admin.metrics();
    long accepted = 0;
    for (DeliveryMethod each : DeliveryMethod.values()) {
      String name = "seatwave_feed_connections_total{method=\"" + each.label() + "\"}";
      accepted += (long) (value(after, name) - value(before, name));
    }
    return new MethodResult.Reconnect(dropped, recoveryMillis, accepted, 0, 0);
  }

  private Correctness finish(Probe probe) throws IOException, InterruptedException {
    probe.admin.admin("pause");
    SnapshotView store = probe.admin.seatMap();
    long finalSequence =
        Long.parseLong(store.position().substring(store.position().indexOf('.') + 1));
    awaitTrue(() -> probe.fleet.countAtLeast(finalSequence) == probe.fleet.size());
    probe.fleet.stop();
    Correctness correctness = new Correctness();
    List<long[]> delays = new ArrayList<>();
    for (FeedClient client : probe.fleet.clients()) {
      if (client.map().states().equals(store.states())) {
        correctness.correctMaps++;
      }
      correctness.gaps += client.stats().gaps();
      correctness.duplicates += client.stats().duplicates();
      correctness.reloads += client.stats().reloads();
      correctness.clientReconnects += client.stats().reconnects();
      delays.add(client.stats().delaysMicros());
    }
    long[] all = delays.stream().flatMapToLong(java.util.Arrays::stream).toArray();
    correctness.delay = LatencySummary.ofMicros(all);
    return correctness;
  }

  private static MethodResult.Load load(
      DeliveryMethod method,
      Map<String, Double> before,
      Map<String, Double> after,
      double seconds) {
    String label = "{method=\"" + method.label() + "\"}";
    double published = delta(before, after, "seatwave_feed_changes_published_total");
    double delivered = delta(before, after, "seatwave_feed_changes_delivered_total" + label);
    double bytes = delta(before, after, "seatwave_feed_bytes_written_total" + label);
    double requests =
        method == DeliveryMethod.LONG_POLLING
            ? delta(before, after, "seatwave_feed_long_poll_replies_total")
            : delta(before, after, "seatwave_feed_connections_total" + label);
    return new MethodResult.Load(
        published / seconds,
        delivered / seconds,
        requests / seconds,
        delivered == 0 ? 0 : bytes / delivered);
  }

  private ServerHandle startServer(DeliveryMethod method, int run)
      throws IOException, InterruptedException {
    if (!settings.childProcess()) {
      return InProcessServer.start(serverSettings);
    }
    Files.createDirectories(settings.output());
    return ChildProcessServer.start(
        settings.serverSettings(),
        serverSettings,
        settings.childHeapMegabytes(),
        settings.output().resolve("server-" + method.label() + "-run" + run + ".log"));
  }

  private FleetPlan plan(
      DeliveryMethod method, FeedEndpoints endpoints, int clients, int firstIndex) {
    CompareSettings.Fleet fleet = settings.fleet();
    return new FleetPlan(
        method,
        clients,
        endpoints,
        fleet.connectsPerSecond(),
        new FleetPlan.Timeouts(
            fleet.connectTimeout(), fleet.requestTimeout(), fleet.silenceLimit()),
        new FleetPlan.BackoffRange(fleet.backoffBase(), fleet.backoffCap()),
        DELAYS_PER_CLIENT,
        CLIENT_SEED + firstIndex);
  }

  private int openConnections(Probe probe, DeliveryMethod method)
      throws IOException, InterruptedException {
    return (int)
        value(
            probe.admin.metrics(),
            "seatwave_feed_open_connections{method=\"" + method.label() + "\"}");
  }

  private static OptionalLong resident(ServerHandle server) throws InterruptedException {
    OptionalLong processId = server.processId();
    return processId.isPresent()
        ? ProcessMemory.residentBytes(processId.getAsLong())
        : OptionalLong.empty();
  }

  private void awaitTrue(Check check) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + settings.phases().settleTimeout().toNanos();
    while (!check.holds()) {
      if (System.nanoTime() > deadline) {
        // The run goes on; the report shows how many clients connected or caught up.
        return;
      }
      Thread.sleep(POLL);
    }
  }

  private static double value(Map<String, Double> samples, String name) {
    return samples.getOrDefault(name, 0.0);
  }

  private static double delta(Map<String, Double> before, Map<String, Double> after, String name) {
    return value(after, name) - value(before, name);
  }

  @FunctionalInterface
  private interface Check {
    boolean holds() throws IOException, InterruptedException;
  }

  private record Probe(ServerHandle server, FeedAdmin admin, Fleets fleet) {}

  private record Reading(int clients, long heapBytes, OptionalLong residentBytes) {}

  // The two fleets of a run, counted as one.
  private record Fleets(List<ClientFleet> parts) {

    void add(ClientFleet fleet) {
      parts.add(fleet);
    }

    int size() {
      return parts.stream().mapToInt(part -> part.clients().size()).sum();
    }

    int countLoaded() {
      return parts.stream().mapToInt(ClientFleet::countLoaded).sum();
    }

    int countAtLeast(long sequence) {
      return parts.stream().mapToInt(part -> part.countAtLeast(sequence)).sum();
    }

    void record(boolean isRecording) {
      parts.forEach(part -> part.record(isRecording));
    }

    List<FeedClient> clients() {
      return parts.stream().flatMap(part -> part.clients().stream()).toList();
    }

    void stop() throws InterruptedException {
      for (ClientFleet part : parts) {
        part.stop();
      }
    }
  }

  // Filled once at the end of a run by the thread that ran it.
  private static final class Correctness {
    private int correctMaps;
    private long gaps;
    private long duplicates;
    private long reloads;
    private long clientReconnects;
    private LatencySummary delay = LatencySummary.ofMicros(new long[0]);
  }
}
