package io.plainround.seatwave.live.http;

import com.sun.net.httpserver.HttpExchange;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;

/**
 * Writes complete replies: JSON bodies, plain text and problem details. Every refusal goes through
 * {@link #problem}, so the feed has one error shape.
 *
 * <p>Thread-safe: no state besides the shared codec.
 */
final class Replies {

  private static final String JSON = "application/json";
  private static final String PROBLEM_JSON = "application/problem+json";

  private final FeedJson json;
  private final Duration retryAfter;

  Replies(FeedJson json, Duration retryAfter) {
    this.json = Objects.requireNonNull(json, "json");
    this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
  }

  void json(HttpExchange exchange, int status, byte[] body) throws IOException {
    send(exchange, status, JSON, body);
  }

  void text(HttpExchange exchange, String contentType, byte[] body) throws IOException {
    send(exchange, HttpStatus.OK, contentType, body);
  }

  void problem(HttpExchange exchange, Problem problem, String detail, String correlationId)
      throws IOException {
    if (problem == Problem.FEED_FULL) {
      // Module 4's rule: a refusal says when to come back, so clients do not retry at once.
      exchange
          .getResponseHeaders()
          .set("Retry-After", Long.toString(Math.max(1, retryAfter.toSeconds())));
    }
    if (problem == Problem.METHOD_NOT_ALLOWED
        && !exchange.getResponseHeaders().containsKey("Allow")) {
      exchange.getResponseHeaders().set("Allow", "GET");
    }
    ProblemView view =
        new ProblemView(
            problem.type(), problem.title(), problem.status(), detail, "urn:uuid:" + correlationId);
    send(exchange, problem.status(), PROBLEM_JSON, json.bytes(view));
  }

  private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    // Every feed reply is live state; a cache in between would serve an old seat map.
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
