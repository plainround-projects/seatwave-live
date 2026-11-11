package io.plainround.seatwave.live.bench;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A TCP relay on 127.0.0.1 that copies bytes between a client and the feed and counts them in each
 * direction: the exact bytes on the wire, HTTP headers, chunk sizes and WebSocket frame headers
 * included, which neither the JDK's HTTP client nor its server reports.
 *
 * <p>Thread-safe: the counters are adders; each relayed connection has its own two threads.
 */
public final class ByteCountingRelay implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ByteCountingRelay.class);
  private static final int BUFFER_BYTES = 16_384;

  private final ServerSocket listener;
  private final int targetPort;
  private final LongAdder towardServer = new LongAdder();
  private final LongAdder towardClient = new LongAdder();

  private ByteCountingRelay(ServerSocket listener, int targetPort) {
    this.listener = listener;
    this.targetPort = targetPort;
  }

  /**
   * Starts a relay to a port on 127.0.0.1.
   *
   * @param targetPort the feed's port
   * @return the relay, listening on a port the system chose
   * @throws IOException if it cannot listen
   */
  public static ByteCountingRelay start(int targetPort) throws IOException {
    ServerSocket listener = new ServerSocket();
    listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    ByteCountingRelay relay = new ByteCountingRelay(listener, targetPort);
    Thread.ofVirtual().name("relay-accept").start(relay::acceptLoop);
    return relay;
  }

  /**
   * The port clients connect to.
   *
   * @return the port
   */
  public int port() {
    return listener.getLocalPort();
  }

  /**
   * Bytes sent from clients to the feed since the last reset.
   *
   * @return the count
   */
  public long bytesTowardServer() {
    return towardServer.sum();
  }

  /**
   * Bytes sent from the feed to clients since the last reset.
   *
   * @return the count
   */
  public long bytesTowardClient() {
    return towardClient.sum();
  }

  /** Sets both counts to zero. */
  public void reset() {
    towardServer.reset();
    towardClient.reset();
  }

  @Override
  public void close() throws IOException {
    listener.close();
  }

  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        Socket server = new Socket(InetAddress.getLoopbackAddress(), targetPort);
        client.setTcpNoDelay(true);
        server.setTcpNoDelay(true);
        Thread.ofVirtual().start(() -> pump(client, server, towardServer));
        Thread.ofVirtual().start(() -> pump(server, client, towardClient));
      } catch (IOException closed) {
        // The relay was closed, or the feed refused this one connection.
        LOG.debug("relay connection not made: {}", closed.toString());
      }
    }
  }

  private static void pump(Socket from, Socket to, LongAdder counted) {
    byte[] buffer = new byte[BUFFER_BYTES];
    try (from;
        to) {
      InputStream in = from.getInputStream();
      OutputStream out = to.getOutputStream();
      int read;
      while ((read = in.read(buffer)) >= 0) {
        out.write(buffer, 0, read);
        out.flush();
        counted.add(read);
      }
    } catch (IOException ended) {
      // One side closed; closing both (by the try) ends the other direction too.
      LOG.debug("relay direction ended: {}", ended.toString());
    }
  }
}
