package io.plainround.seatwave.live.bench;

import java.io.IOException;
import java.util.OptionalLong;

/**
 * A running feed node the comparison measures: in this process (tests) or, for real measurements,
 * in a process of its own, so the clients' threads and heap are not counted as the server's.
 */
public interface ServerHandle extends AutoCloseable {

  /**
   * The HTTP port.
   *
   * @return the port
   */
  int httpPort();

  /**
   * The WebSocket port.
   *
   * @return the port
   */
  int webSocketPort();

  /**
   * The server's operating-system process id, when it runs alone.
   *
   * @return the id, or empty when the server shares this process
   */
  OptionalLong processId();

  @Override
  void close() throws IOException;
}
