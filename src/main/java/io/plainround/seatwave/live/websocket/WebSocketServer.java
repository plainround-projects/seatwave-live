package io.plainround.seatwave.live.websocket;

import io.plainround.seatwave.live.config.LiveSettings;
import io.plainround.seatwave.live.http.Parts;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The feed's WebSocket server: the server side of RFC 6455 written on the JDK's own sockets, bound
 * to 127.0.0.1, with one virtual thread per connection and a second one for its reader.
 *
 * <p>Why not the JDK's HTTP server: it cannot hand a connection over after {@code 101 Switching
 * Protocols}, so the WebSocket feed listens on its own port. The JDK has a WebSocket client ({@code
 * java.net.http.WebSocket}) but no server; this class and its few helpers are that server.
 *
 * <p>Thread-safe: the accept loop runs on one thread; each session is confined to its own threads.
 */
public final class WebSocketServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(WebSocketServer.class);
  // A client gets this long to send its handshake once its TCP connection is open.
  private static final java.time.Duration HANDSHAKE_TIMEOUT = java.time.Duration.ofSeconds(5);

  private final ServerSocket listener;
  private final ExecutorService sessions = Executors.newVirtualThreadPerTaskExecutor();
  private final Parts parts;
  private final SessionSettings settings;

  private WebSocketServer(ServerSocket listener, Parts parts, SessionSettings settings) {
    this.listener = listener;
    this.parts = parts;
    this.settings = settings;
  }

  /**
   * Starts listening on 127.0.0.1.
   *
   * @param settings the port, backlog, allowed origin and timings
   * @param parts the feed and its limit
   * @return the running server
   * @throws IOException if the port cannot be bound
   */
  public static WebSocketServer start(LiveSettings settings, Parts parts) throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(parts, "parts");
    ServerSocket listener = new ServerSocket();
    listener.bind(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.network().websocketPort()),
        settings.network().backlog());
    WebSocketServer server =
        new WebSocketServer(
            listener,
            parts,
            new SessionSettings(
                settings.network().allowedOrigin(),
                settings.timing().heartbeat(),
                HANDSHAKE_TIMEOUT,
                settings.timing().retry()));
    Thread.ofVirtual().name("ws-accept").start(server::acceptLoop);
    return server;
  }

  /**
   * The port the server listens on.
   *
   * @return the port
   */
  public int port() {
    return listener.getLocalPort();
  }

  @Override
  public void close() throws IOException {
    listener.close();
    sessions.shutdownNow();
  }

  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket socket = listener.accept();
        socket.setTcpNoDelay(true);
        sessions.submit(() -> new WebSocketSession(socket, parts, settings).run());
      } catch (IOException acceptFailed) {
        if (!listener.isClosed()) {
          LOG.warn("accept failed: {}", acceptFailed.toString());
        }
      }
    }
  }
}
