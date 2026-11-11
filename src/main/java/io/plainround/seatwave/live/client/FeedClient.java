package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.wire.MessageView;
import io.plainround.seatwave.live.wire.SnapshotView;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One fan's client: it loads the seat map, follows the feed with one delivery method, applies every
 * change in order, and after a dropped connection backs off with jitter and resumes from the last
 * change it applied. A reload instruction, or a gap it finds itself, makes it load the map again:
 * it fails safe, never applying a change it cannot place.
 *
 * <p>It records the delivery delay of every change, from the store's {@code changedAt} to the
 * moment the client has applied it, on the same machine's clock. Not thread-safe: it runs on one
 * thread; {@link #map()} positions may be read from others.
 */
public final class FeedClient implements Runnable {

  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final long NANOS_PER_MICRO = 1_000;

  private final FeedConnection connection;
  private final SeatMapReader seatMaps;
  private final Backoff backoff;
  private final AtomicBoolean recording;
  private final ClientSeatMap map = new ClientSeatMap();
  private final ClientStats stats;
  private final Sink sink = new Sink();
  private int failures;
  private boolean needsMap = true;
  private long disconnectedAtNanos;
  private volatile long lastHeardNanos;
  private volatile boolean following;

  /**
   * Creates a client; it does nothing until {@link #run()}.
   *
   * @param connection the delivery method
   * @param seatMaps where the seat map is read
   * @param backoff this client's reconnect waits
   * @param recording when true, delays are recorded (false during warm-up)
   * @param stats where the client counts what it saw
   */
  public FeedClient(
      FeedConnection connection,
      SeatMapReader seatMaps,
      Backoff backoff,
      AtomicBoolean recording,
      ClientStats stats) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.seatMaps = Objects.requireNonNull(seatMaps, "seatMaps");
    this.backoff = Objects.requireNonNull(backoff, "backoff");
    this.recording = Objects.requireNonNull(recording, "recording");
    this.stats = Objects.requireNonNull(stats, "stats");
  }

  /** Follows the feed until the thread is interrupted. */
  @Override
  public void run() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        if (needsMap && !loadMap()) {
          continue;
        }
        Ending ending;
        try {
          ending = connection.follow(map.position(), sink);
        } finally {
          following = false;
        }
        switch (ending) {
          case RELOAD -> {
            stats.recordReload();
            // The map is stale until a reload succeeds: a failed reload is tried again, never
            // followed from the old position.
            needsMap = true;
          }
          case DISCONNECTED -> {
            if (disconnectedAtNanos == 0) {
              disconnectedAtNanos = System.nanoTime();
            }
            Thread.sleep(backoff.delay(failures++));
          }
        }
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * The client's seat map.
   *
   * @return the map
   */
  public ClientSeatMap map() {
    return map;
  }

  /**
   * What the client saw.
   *
   * @return the statistics; read them after the client's thread has stopped
   */
  public ClientStats stats() {
    return stats;
  }

  /**
   * Ends the current connection if it has been silent longer than the limit; called by the fleet's
   * watchdog thread.
   *
   * @param silenceLimit the longest silence allowed, about three server heartbeats
   * @return true if the connection was aborted
   */
  public boolean abortIfSilent(Duration silenceLimit) {
    if (!following || System.nanoTime() - lastHeardNanos < silenceLimit.toNanos()) {
      return false;
    }
    connection.abort();
    return true;
  }

  private boolean loadMap() throws InterruptedException {
    Optional<SnapshotView> snapshot = seatMaps.read();
    if (snapshot.isEmpty()) {
      Thread.sleep(backoff.delay(failures++));
      return false;
    }
    map.load(snapshot.get());
    needsMap = false;
    return true;
  }

  private long delayMicros(String changedAt) {
    if (changedAt == null) {
      throw new IllegalArgumentException("a seat change has a changedAt");
    }
    try {
      Instant changed = Instant.parse(changedAt);
      Duration delay = Duration.between(changed, Instant.now());
      return delay.getSeconds() * MICROS_PER_SECOND + delay.getNano() / NANOS_PER_MICRO;
    } catch (DateTimeParseException malformed) {
      throw new IllegalArgumentException("changedAt is not an ISO-8601 instant", malformed);
    }
  }

  private final class Sink implements MessageSink {

    @Override
    public void heartbeat() {
      lastHeardNanos = System.nanoTime();
    }

    @Override
    public void connected() {
      lastHeardNanos = System.nanoTime();
      following = true;
      if (disconnectedAtNanos != 0) {
        stats.recordReconnect(Duration.ofNanos(System.nanoTime() - disconnectedAtNanos).toMillis());
        disconnectedAtNanos = 0;
      }
      failures = 0;
    }

    @Override
    public Verdict accept(MessageView message) {
      lastHeardNanos = System.nanoTime();
      if (MessageView.RELOAD.equals(message.type())) {
        return Verdict.RELOAD;
      }
      Applied applied = map.apply(message);
      stats.recordApplied(applied);
      if (applied == Applied.GAP) {
        // Fail safe: a map with a hole is reloaded, never patched over.
        return Verdict.RELOAD;
      }
      if (applied == Applied.APPLIED && recording.get()) {
        stats.recordDelay(delayMicros(message.changedAt()));
      }
      return Verdict.CONTINUE;
    }
  }
}
