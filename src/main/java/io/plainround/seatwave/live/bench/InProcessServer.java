package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.config.LiveSettings;
import java.io.IOException;
import java.time.Clock;
import java.util.OptionalLong;

/**
 * A feed node in this process, with its on-sale running. Used by tests, where a second JVM per run
 * would cost seconds; its process numbers include the clients, so the real comparison never uses
 * it.
 */
public final class InProcessServer implements ServerHandle {

  private final LiveFeedNode node;

  private InProcessServer(LiveFeedNode node) {
    this.node = node;
  }

  /**
   * Starts a node and its on-sale.
   *
   * @param settings the node's settings
   * @return the running server
   * @throws IOException if a port cannot be bound
   */
  public static InProcessServer start(LiveSettings settings) throws IOException {
    LiveFeedNode node = LiveFeedNode.start(settings, Clock.systemUTC());
    node.startOnSale();
    return new InProcessServer(node);
  }

  /**
   * The node, for tools that act on it directly.
   *
   * @return the node
   */
  public LiveFeedNode node() {
    return node;
  }

  @Override
  public int httpPort() {
    return node.httpPort();
  }

  @Override
  public int webSocketPort() {
    return node.webSocketPort();
  }

  @Override
  public OptionalLong processId() {
    return OptionalLong.empty();
  }

  @Override
  public void close() throws IOException {
    node.close();
  }
}
