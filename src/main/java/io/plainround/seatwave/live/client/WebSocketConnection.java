package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.MessageView;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WebSockets with the JDK's own client, {@code java.net.http.WebSocket}. The client asks for one
 * message at a time ({@code request(1)}) and asks for the next only after the client thread has
 * applied it, so a slow client lets TCP push back on the server instead of buffering without bound.
 *
 * <p>Thread-safe in the way it is used: the listener runs on the HTTP client's threads and only
 * hands complete messages to a small queue that the client thread takes from.
 */
public final class WebSocketConnection implements FeedConnection {

  // The client requests one message at a time, so a few slots are enough for a message, a ping
  // marker and a close marker.
  private static final int INBOX_SLOTS = 8;
  private static final String CLOSED = "\u0000closed";
  private static final String PING = "\u0000ping";

  private final HttpClient http;
  private final FeedEndpoints endpoints;
  private final FeedJson json;
  private final Duration openTimeout;
  private volatile WebSocket current;
  private volatile BlockingQueue<String> currentInbox;

  /**
   * Creates the connection.
   *
   * @param http the shared HTTP client
   * @param endpoints the feed's addresses
   * @param json the codec
   * @param openTimeout the longest wait for the opening handshake
   */
  public WebSocketConnection(
      HttpClient http, FeedEndpoints endpoints, FeedJson json, Duration openTimeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.json = Objects.requireNonNull(json, "json");
    this.openTimeout = Objects.requireNonNull(openTimeout, "openTimeout");
  }

  @Override
  public Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException {
    BlockingQueue<String> inbox = new ArrayBlockingQueue<>(INBOX_SLOTS);
    WebSocket socket;
    try {
      socket =
          http.newWebSocketBuilder()
              .connectTimeout(openTimeout)
              .buildAsync(endpoints.socket(from), new Listener(inbox))
              .get(openTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException failed) {
      return Ending.DISCONNECTED;
    }
    current = socket;
    currentInbox = inbox;
    try {
      sink.connected();
      while (true) {
        String text = inbox.take();
        if (CLOSED.equals(text)) {
          return Ending.DISCONNECTED;
        }
        if (PING.equals(text)) {
          sink.heartbeat();
          continue;
        }
        MessageView message = json.read(text.getBytes(StandardCharsets.UTF_8), MessageView.class);
        if (sink.accept(message) == Verdict.RELOAD) {
          return Ending.RELOAD;
        }
        socket.request(1);
      }
    } catch (IOException malformed) {
      return Ending.DISCONNECTED;
    } finally {
      current = null;
      currentInbox = null;
      socket.abort();
    }
  }

  @Override
  public void abort() {
    BlockingQueue<String> inbox = currentInbox;
    WebSocket socket = current;
    if (socket != null) {
      socket.abort();
    }
    if (inbox != null) {
      // The listener may never hear of an abort; this wakes the client thread for certain.
      inbox.offer(CLOSED);
    }
  }

  private static final class Listener implements WebSocket.Listener {

    private final BlockingQueue<String> inbox;
    private final StringBuilder partial = new StringBuilder();

    private Listener(BlockingQueue<String> inbox) {
      this.inbox = inbox;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (!last) {
        // A message may arrive in parts; ask for the next part at once.
        webSocket.request(1);
        return null;
      }
      String message = partial.toString();
      partial.setLength(0);
      // At most one message is outstanding at a time, so the offer always finds a free slot.
      inbox.offer(message);
      return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
      // The JDK client answers the ping with a pong by itself. The ping used up the request
      // count, so ask again; and tell the client thread the link is alive.
      inbox.offer(PING);
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      inbox.offer(CLOSED);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      inbox.offer(CLOSED);
    }
  }
}
