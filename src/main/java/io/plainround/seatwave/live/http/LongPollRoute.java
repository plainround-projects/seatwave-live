package io.plainround.seatwave.live.http;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import io.plainround.seatwave.live.feed.Catchup;
import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedMetrics;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatFeed;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * {@code GET /v1/events/1/seat-changes?after=<position>}: long polling. The request is held until
 * the feed has a change after the client's position or the hold ends, then answered with every
 * change after it (up to one batch), or with an instruction to reload.
 *
 * <p>A held request costs a parked virtual thread and an open connection, and counts against the
 * connection limit like a push connection. A limit of long polling on this server: a client that
 * goes away while its request is held is noticed only when the reply is written, up to one hold
 * later, because the JDK's HTTP server tells a handler nothing about the connection until it
 * writes. Until then the request keeps its thread and its place under the limit. Thread-safe: no
 * state of its own.
 */
final class LongPollRoute {

  private final SeatFeed feed;
  private final SeatLayout layout;
  private final ConnectionLimit limit;
  private final FeedMetrics metrics;
  private final FeedJson json;
  private final Replies replies;
  private final Duration hold;

  LongPollRoute(Parts parts, Replies replies, Duration hold) {
    this.feed = parts.feed();
    this.layout = parts.layout();
    this.limit = parts.limit();
    this.metrics = parts.metrics();
    this.json = parts.json();
    this.replies = replies;
    this.hold = hold;
  }

  void serve(HttpExchange exchange, String correlationId) throws IOException {
    Result<FeedPosition, String> asked = positionFrom(exchange.getRequestURI().getRawQuery());
    if (asked instanceof Result.Error<FeedPosition, String>(String detail)) {
      replies.problem(exchange, Problem.INVALID_REQUEST, detail, correlationId);
      return;
    }
    FeedPosition position = asked.recoverError(detail -> feed.position());
    Thread handler = Thread.currentThread();
    Optional<ConnectionLimit.Ticket> admitted =
        limit.admit(DeliveryMethod.LONG_POLLING, handler::interrupt);
    if (admitted.isEmpty()) {
      replies.problem(exchange, Problem.FEED_FULL, "too many open feed connections", correlationId);
      return;
    }
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      Catchup catchup = feed.awaitChangesAfter(position, hold);
      byte[] body = json.longPoll(catchup, layout, position);
      replies.json(exchange, HttpStatus.OK, body);
      metrics.recordLongPollReply();
      switch (catchup) {
        case Catchup.Changes changes ->
            metrics.recordDelivered(
                DeliveryMethod.LONG_POLLING, changes.changes().size(), body.length);
        case Catchup.ReloadRequired reload -> metrics.recordReloadSent(DeliveryMethod.LONG_POLLING);
      }
    } catch (InterruptedException dropped) {
      // The connection is being dropped (the lab's drop, or shutdown): stop and send nothing.
      Thread.currentThread().interrupt();
    }
  }

  private static Result<FeedPosition, String> positionFrom(String rawQuery) {
    return switch (Query.parse(rawQuery, Set.of("after"))) {
      case Result.Error<Query, String>(String detail) -> Result.error(detail);
      case Result.Success<Query, String>(Query query) -> Positions.after(query);
    };
  }
}
