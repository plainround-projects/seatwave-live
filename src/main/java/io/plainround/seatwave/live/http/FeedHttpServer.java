package io.plainround.seatwave.live.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.live.config.LiveSettings;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The feed's HTTP side, on the JDK's own server ({@code com.sun.net.httpserver}, an exported and
 * supported JDK module) bound to 127.0.0.1 only, with one virtual thread per request: long polling,
 * server-sent events, the seat map, the browser page, health, metrics and the lab's admin routes.
 *
 * <p>A held long poll or an open event stream parks its virtual thread, which costs about a
 * kilobyte of heap (Module 4), not a platform thread. Thread-safe: the routes hold no mutable
 * state.
 */
public final class FeedHttpServer implements AutoCloseable {

  /** The one event the feed serves: Module 1's "Arena Night". */
  public static final int EVENT_ID = 1;

  private static final Logger LOG = LoggerFactory.getLogger(FeedHttpServer.class);
  private static final Pattern EVENT_PATH =
      Pattern.compile("/v1/events/([0-9]{1,9})/(seat-map|seat-changes|seat-changes/stream)");
  private static final int STOP_DELAY_SECONDS = 0;
  private static final Map<String, String> PAGES =
      Map.of(
          "/", "web/live-seat-map.html",
          "/live-seat-map.js", "web/live-seat-map.js");

  private final HttpServer server;
  private final ExecutorService workers;
  private final Parts parts;
  private final Replies replies;
  private final LongPollRoute longPoll;
  private final EventStreamRoute eventStream;
  private final boolean adminEnabled;
  private final String contentSecurityPolicy;

  private FeedHttpServer(HttpServer server, Parts parts, LiveSettings settings, int webSocketPort) {
    this.server = server;
    // Virtual threads make a held long poll or an open stream cost a parked thread, not a platform
    // thread (Module 4); the connection limit, not the thread count, bounds the work.
    this.workers = Executors.newVirtualThreadPerTaskExecutor();
    this.parts = parts;
    this.replies = new Replies(parts.json(), settings.timing().retry());
    this.longPoll = new LongPollRoute(parts, replies, settings.timing().longPollHold());
    this.eventStream =
        new EventStreamRoute(
            parts, replies, settings.timing().heartbeat(), settings.timing().retry());
    this.adminEnabled = settings.adminEnabled();
    // The page may load only its own script and talk only to this feed and its WebSocket port.
    this.contentSecurityPolicy =
        "default-src 'self'; connect-src 'self' ws://127.0.0.1:" + webSocketPort;
  }

  /**
   * Starts the server on 127.0.0.1.
   *
   * @param settings the port, backlog and timings
   * @param parts the store, feed, limit, metrics and codec
   * @param webSocketPort the WebSocket server's port, named in the page's security policy
   * @return the running server
   * @throws IOException if the port cannot be bound
   */
  public static FeedHttpServer start(LiveSettings settings, Parts parts, int webSocketPort)
      throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(parts, "parts");
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.network().httpPort()),
            settings.network().backlog());
    FeedHttpServer feedServer = new FeedHttpServer(server, parts, settings, webSocketPort);
    server.setExecutor(feedServer.workers);
    server.createContext("/", feedServer::serve);
    server.start();
    return feedServer;
  }

  /**
   * The port the server listens on.
   *
   * @return the port
   */
  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(STOP_DELAY_SECONDS);
    workers.shutdownNow();
  }

  private void serve(HttpExchange exchange) {
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);
    parts.metrics().recordHandlerThread(true);
    try (exchange) {
      route(exchange, correlationId);
    } catch (IOException clientGone) {
      LOG.debug("request ended early: {}", clientGone.toString());
    } catch (RuntimeException bug) {
      LOG.error("unexpected failure", bug);
    } finally {
      parts.metrics().recordHandlerThread(false);
      MDC.remove("correlationId");
    }
  }

  private void route(HttpExchange exchange, String correlationId) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    if (path.startsWith("/admin/")) {
      admin(exchange, path, method, correlationId);
      return;
    }
    if (!"GET".equals(method)) {
      replies.problem(
          exchange, Problem.METHOD_NOT_ALLOWED, method + " is not allowed", correlationId);
      return;
    }
    Matcher event = EVENT_PATH.matcher(path);
    if (event.matches()) {
      if (Integer.parseInt(event.group(1)) != EVENT_ID) {
        replies.problem(exchange, Problem.NOT_FOUND, "no event " + event.group(1), correlationId);
        return;
      }
      switch (event.group(2)) {
        case "seat-map" ->
            replies.json(
                exchange, HttpStatus.OK, parts.json().snapshot(parts.store().snapshot(), EVENT_ID));
        case "seat-changes" -> longPoll.serve(exchange, correlationId);
        default -> eventStream.serve(exchange, correlationId);
      }
      return;
    }
    switch (path) {
      case "/health" ->
          replies.text(exchange, "text/plain", "ok\n".getBytes(StandardCharsets.UTF_8));
      case "/metrics" ->
          replies.text(
              exchange,
              "text/plain; version=0.0.4",
              parts.metrics().render().getBytes(StandardCharsets.UTF_8));
      default -> page(exchange, path, correlationId);
    }
  }

  private void page(HttpExchange exchange, String path, String correlationId) throws IOException {
    String resource = PAGES.get(path);
    if (resource == null) {
      replies.problem(exchange, Problem.NOT_FOUND, "nothing at " + path, correlationId);
      return;
    }
    byte[] body;
    try (InputStream in = FeedHttpServer.class.getClassLoader().getResourceAsStream(resource)) {
      body = Objects.requireNonNull(in, resource).readAllBytes();
    }
    exchange.getResponseHeaders().set("Content-Security-Policy", contentSecurityPolicy);
    String type =
        resource.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
    replies.text(exchange, type, body);
  }

  // The lab's control routes: they exist only when admin.enabled=true, on 127.0.0.1, so the
  // comparison can pause the on-sale, drop every connection and read the heap after a collection.
  private void admin(HttpExchange exchange, String path, String method, String correlationId)
      throws IOException {
    if (!adminEnabled) {
      replies.problem(exchange, Problem.NOT_FOUND, "nothing at " + path, correlationId);
      return;
    }
    if (!"POST".equals(method)) {
      exchange.getResponseHeaders().set("Allow", "POST");
      replies.problem(
          exchange, Problem.METHOD_NOT_ALLOWED, method + " is not allowed", correlationId);
      return;
    }
    String reply =
        switch (path) {
          case "/admin/pause" -> {
            parts.simulator().pause();
            yield "{\"onSale\":\"paused\"}";
          }
          case "/admin/resume" -> {
            parts.simulator().resume();
            yield "{\"onSale\":\"running\"}";
          }
          case "/admin/drop-connections" -> "{\"dropped\":" + parts.limit().dropAll() + "}";
          case "/admin/settle" -> "{\"heapUsedBytes\":" + settledHeapBytes() + "}";
          default -> "";
        };
    if (reply.isEmpty()) {
      replies.problem(exchange, Problem.NOT_FOUND, "nothing at " + path, correlationId);
      return;
    }
    replies.json(exchange, HttpStatus.OK, reply.getBytes(StandardCharsets.UTF_8));
  }

  // A full collection first, so the heap number counts live objects, not garbage waiting to go.
  private static long settledHeapBytes() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    memory.gc();
    return memory.getHeapMemoryUsage().getUsed();
  }
}
