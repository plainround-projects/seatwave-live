package io.plainround.seatwave.live.http;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.Delivery;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedMetrics;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatChange;
import io.plainround.seatwave.live.feed.SeatFeed;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.feed.Subscriber;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET /v1/events/1/seat-changes/stream}: server-sent events. One long response in the {@code
 * text/event-stream} format of the WHATWG HTML Living Standard: a {@code retry} line, then one
 * event per change with the change's position as its {@code id}, a comment line as a heartbeat when
 * nothing happens, and a {@code reload} event when the client fell too far behind.
 *
 * <p>The resume position comes from the {@code Last-Event-ID} header when present, else from the
 * {@code after} query parameter. The header wins because a browser reconnects to the same URL with
 * the old {@code after} but sends the newest id it saw in the header.
 *
 * <p>Thread-safe: each stream is served by its own virtual thread, the only writer to its response.
 */
final class EventStreamRoute {

  private static final Logger LOG = LoggerFactory.getLogger(EventStreamRoute.class);
  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
  // Streaming responses have no length; the JDK server then uses chunked transfer coding.
  private static final long STREAMING = 0;

  private final SeatFeed feed;
  private final SeatLayout layout;
  private final ConnectionLimit limit;
  private final FeedMetrics metrics;
  private final FeedJson json;
  private final Replies replies;
  private final Duration heartbeat;
  private final Duration retry;

  EventStreamRoute(Parts parts, Replies replies, Duration heartbeat, Duration retry) {
    this.feed = parts.feed();
    this.layout = parts.layout();
    this.limit = parts.limit();
    this.metrics = parts.metrics();
    this.json = parts.json();
    this.replies = replies;
    this.heartbeat = heartbeat;
    this.retry = retry;
  }

  void serve(HttpExchange exchange, String correlationId) throws IOException {
    Result<FeedPosition, String> asked = positionFrom(exchange);
    if (asked instanceof Result.Error<FeedPosition, String>(String detail)) {
      replies.problem(exchange, Problem.INVALID_REQUEST, detail, correlationId);
      return;
    }
    FeedPosition position = asked.recoverError(detail -> feed.position());
    Thread writer = Thread.currentThread();
    Optional<ConnectionLimit.Ticket> admitted =
        limit.admit(DeliveryMethod.SERVER_SENT_EVENTS, writer::interrupt);
    if (admitted.isEmpty()) {
      replies.problem(exchange, Problem.FEED_FULL, "too many open feed connections", correlationId);
      return;
    }
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      Subscriber subscriber = feed.subscribe(position);
      try {
        stream(exchange, subscriber);
      } finally {
        feed.unsubscribe(subscriber);
      }
    } catch (InterruptedException dropped) {
      Thread.currentThread().interrupt();
    } catch (IOException clientGone) {
      // A client that closes its stream, or a write the watchdog interrupted, ends here.
      LOG.debug("event stream ended: {}", clientGone.toString());
    }
  }

  private void stream(HttpExchange exchange, Subscriber subscriber)
      throws IOException, InterruptedException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(HttpStatus.OK, STREAMING);
    OutputStream out = exchange.getResponseBody();
    write(
        subscriber,
        out,
        ("retry: " + retry.toMillis() + "\n\n").getBytes(StandardCharsets.UTF_8),
        0);
    while (true) {
      switch (subscriber.next(heartbeat)) {
        case Delivery.Batch batch -> write(subscriber, out, events(batch), batch.changes().size());
        case Delivery.Idle idle -> write(subscriber, out, HEARTBEAT, 0);
        case Delivery.Woken woken -> {
          // Nobody wakes an event stream; nothing to send.
        }
        case Delivery.Reload reload -> {
          byte[] event = event("reload", null, json.bytes(json.reload(feed.position())));
          write(subscriber, out, event, 0);
          metrics.recordReloadSent(DeliveryMethod.SERVER_SENT_EVENTS);
          out.close();
          return;
        }
      }
    }
  }

  private byte[] events(Delivery.Batch batch) {
    ByteArrayOutputStream events = new ByteArrayOutputStream();
    for (SeatChange change : batch.changes()) {
      events.writeBytes(
          event("seat", change.position().written(), json.bytes(json.message(change, layout))));
    }
    return events.toByteArray();
  }

  private static byte[] event(String type, String id, byte[] data) {
    StringBuilder event = new StringBuilder();
    if (id != null) {
      event.append("id: ").append(id).append('\n');
    }
    event.append("event: ").append(type).append('\n');
    event.append("data: ").append(new String(data, StandardCharsets.UTF_8)).append("\n\n");
    return event.toString().getBytes(StandardCharsets.UTF_8);
  }

  private void write(Subscriber subscriber, OutputStream out, byte[] bytes, int changes)
      throws IOException {
    subscriber.beginWrite(Thread.currentThread());
    try {
      out.write(bytes);
      // One flush per batch: the changes that queued while the last write ran leave together.
      out.flush();
    } finally {
      subscriber.endWrite();
    }
    metrics.recordDelivered(DeliveryMethod.SERVER_SENT_EVENTS, changes, bytes.length);
  }

  private static Result<FeedPosition, String> positionFrom(HttpExchange exchange) {
    return switch (Query.parse(exchange.getRequestURI().getRawQuery(), Set.of("after"))) {
      case Result.Error<Query, String>(String detail) -> Result.error(detail);
      case Result.Success<Query, String>(Query query) -> {
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        yield lastEventId != null
            ? FeedPosition.parse(lastEventId).mapError(detail -> "Last-Event-ID " + detail)
            : Positions.after(query);
      }
    };
  }
}
