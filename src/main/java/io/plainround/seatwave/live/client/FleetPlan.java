package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.time.Duration;
import java.util.Objects;

/**
 * What a {@link ClientFleet} starts.
 *
 * @param method the delivery method every client uses
 * @param clients how many clients
 * @param endpoints the feed's addresses
 * @param connectsPerSecond how many clients start per second
 * @param timeouts the connect and request timeouts
 * @param backoff the reconnect backoff's base and cap
 * @param delayCapacity how many delays each client keeps
 * @param seed the first client's backoff seed; client n uses seed + n
 */
public record FleetPlan(
    DeliveryMethod method,
    int clients,
    FeedEndpoints endpoints,
    int connectsPerSecond,
    Timeouts timeouts,
    BackoffRange backoff,
    int delayCapacity,
    long seed) {

  /** Checks the fields. */
  public FleetPlan {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(endpoints, "endpoints");
    Objects.requireNonNull(timeouts, "timeouts");
    Objects.requireNonNull(backoff, "backoff");
    if (clients < 1 || connectsPerSecond < 1 || delayCapacity < 1) {
      throw new IllegalArgumentException(
          "clients, connectsPerSecond and delayCapacity must be positive");
    }
  }

  Duration connectTimeout() {
    return timeouts.connect();
  }

  Duration requestTimeout() {
    return timeouts.request();
  }

  Duration silenceLimit() {
    return timeouts.silence();
  }

  Duration backoffBase() {
    return backoff.base();
  }

  Duration backoffCap() {
    return backoff.cap();
  }

  /**
   * The client's timeouts.
   *
   * @param connect the TCP connect timeout
   * @param request the longest wait for a reply, or for a stream's headers; longer than the
   *     long-poll hold
   * @param silence the longest a push connection may stay silent (no change, no heartbeat) before
   *     the client drops it and reconnects; about three server heartbeats
   */
  public record Timeouts(Duration connect, Duration request, Duration silence) {

    /** Checks the fields. */
    public Timeouts {
      Objects.requireNonNull(connect, "connect");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(silence, "silence");
    }
  }

  /**
   * The reconnect backoff.
   *
   * @param base the ceiling of the first wait
   * @param cap the largest ceiling
   */
  public record BackoffRange(Duration base, Duration cap) {

    /** Checks the fields. */
    public BackoffRange {
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(cap, "cap");
    }
  }
}
