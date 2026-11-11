package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.wire.FeedJson;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Many clients of one delivery method, each on its own virtual thread, sharing one JDK HTTP client
 * (a long-lived client is created once and shared, never per request). They are started at a steady
 * rate, so 1,000 connections do not arrive in one burst that overflows the accept backlog.
 *
 * <p>Thread-safe: the list of clients is fixed after start; recording and stopping are atomic
 * flags.
 */
public final class ClientFleet {

  private static final Duration STOP_WAIT = Duration.ofSeconds(10);
  private static final int SILENCE_CHECKS = 4;

  private final List<FeedClient> clients;
  private final List<Thread> threads;
  private final HttpClient http;
  private final AtomicBoolean recording;
  private final Thread watchdog;
  private final AtomicBoolean stopped = new AtomicBoolean();

  private ClientFleet(
      List<FeedClient> clients,
      List<Thread> threads,
      HttpClient http,
      AtomicBoolean recording,
      Thread watchdog) {
    this.clients = List.copyOf(clients);
    this.threads = List.copyOf(threads);
    this.http = http;
    this.recording = recording;
    this.watchdog = watchdog;
  }

  /**
   * Starts the clients, at the plan's rate.
   *
   * @param plan how many clients of which method, where, and how they back off
   * @return the running fleet
   * @throws InterruptedException if interrupted while starting them
   */
  public static ClientFleet start(FleetPlan plan) throws InterruptedException {
    Objects.requireNonNull(plan, "plan");
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(plan.connectTimeout())
            .build();
    FeedJson json = new FeedJson();
    AtomicBoolean recording = new AtomicBoolean();
    SeatMapReader seatMaps = new SeatMapReader(http, plan.endpoints(), json, plan.requestTimeout());
    List<FeedClient> clients = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    Duration gap = Duration.ofSeconds(1).dividedBy(plan.connectsPerSecond());
    for (int clientIndex = 0; clientIndex < plan.clients(); clientIndex++) {
      FeedClient client =
          new FeedClient(
              connection(plan, http, json),
              seatMaps,
              new Backoff(plan.backoffBase(), plan.backoffCap(), plan.seed() + clientIndex),
              recording,
              new ClientStats(plan.delayCapacity()));
      clients.add(client);
      threads.add(Thread.ofVirtual().name("client-" + clientIndex).start(client));
      Thread.sleep(gap);
    }
    Thread watchdog =
        Thread.ofVirtual()
            .name("client-silence-watchdog")
            .start(() -> watchSilence(clients, plan.silenceLimit()));
    return new ClientFleet(clients, threads, http, recording, watchdog);
  }

  /**
   * Turns delay recording on or off for every client.
   *
   * @param isRecording true to record
   */
  public void record(boolean isRecording) {
    recording.set(isRecording);
  }

  /**
   * The clients.
   *
   * @return the fixed list
   */
  public List<FeedClient> clients() {
    return clients;
  }

  /**
   * Counts the clients whose map has reached a sequence.
   *
   * @param sequence the sequence to reach
   * @return how many have it or later
   */
  public int countAtLeast(long sequence) {
    return (int) clients.stream().filter(client -> client.map().sequence() >= sequence).count();
  }

  /**
   * Counts the clients that have loaded a seat map.
   *
   * @return the count
   */
  public int countLoaded() {
    return (int) clients.stream().filter(client -> client.map().isLoaded()).count();
  }

  /**
   * Stops every client and waits for its thread to end, then shuts the HTTP client down. Only the
   * first call does this; a later one returns at once, so a caller may stop the fleet before
   * reading its clients and again in a {@code finally} block, and it is stopped once.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  public void stop() throws InterruptedException {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    watchdog.interrupt();
    threads.forEach(Thread::interrupt);
    for (Thread thread : threads) {
      thread.join(STOP_WAIT);
    }
    http.shutdownNow();
    http.awaitTermination(STOP_WAIT);
  }

  // A link that says nothing for about three heartbeats is treated as dead, as a production client
  // must: a phone network or a proxy can drop it without either side hearing a close.
  private static void watchSilence(List<FeedClient> clients, Duration silenceLimit) {
    Duration checkEvery = silenceLimit.dividedBy(SILENCE_CHECKS);
    try {
      while (true) {
        Thread.sleep(checkEvery);
        clients.forEach(client -> client.abortIfSilent(silenceLimit));
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private static FeedConnection connection(FleetPlan plan, HttpClient http, FeedJson json) {
    return switch (plan.method()) {
      case LONG_POLLING ->
          new LongPollConnection(http, plan.endpoints(), json, plan.requestTimeout());
      case SERVER_SENT_EVENTS ->
          new EventStreamConnection(http, plan.endpoints(), json, plan.requestTimeout());
      case WEBSOCKET ->
          new WebSocketConnection(http, plan.endpoints(), json, plan.requestTimeout());
    };
  }
}
