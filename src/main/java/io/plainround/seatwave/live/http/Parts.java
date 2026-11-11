package io.plainround.seatwave.live.http;

import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.FeedMetrics;
import io.plainround.seatwave.live.feed.OnSaleSimulator;
import io.plainround.seatwave.live.feed.SeatFeed;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.feed.SeatStore;
import io.plainround.seatwave.live.wire.FeedJson;
import java.util.Objects;

/**
 * The shared parts of one feed node that the HTTP and WebSocket servers use.
 *
 * @param store the seat store
 * @param feed the publisher
 * @param simulator the simulated on-sale, for the admin routes
 * @param limit the connection limit
 * @param metrics the counters
 * @param json the JSON codec
 */
public record Parts(
    SeatStore store,
    SeatFeed feed,
    OnSaleSimulator simulator,
    ConnectionLimit limit,
    FeedMetrics metrics,
    FeedJson json) {

  /** Checks the fields. */
  public Parts {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(feed, "feed");
    Objects.requireNonNull(simulator, "simulator");
    Objects.requireNonNull(limit, "limit");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(json, "json");
  }

  /**
   * The seat map's shape.
   *
   * @return the store's layout
   */
  public SeatLayout layout() {
    return store.layout();
  }
}
