package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;

/**
 * One delivery method's way of following the feed from a position until the connection ends: {@link
 * LongPollConnection}, {@link EventStreamConnection} or {@link WebSocketConnection}. The reconnect,
 * backoff and seat-map logic around it is the same for all three, in {@link FeedClient}.
 */
public interface FeedConnection {

  /**
   * Follows the feed, handing every message to the sink.
   *
   * @param from the position of the last change the client has
   * @param sink where messages go
   * @return why the connection ended
   * @throws InterruptedException if the client is being stopped
   */
  Ending follow(FeedPosition from, MessageSink sink) throws InterruptedException;

  /**
   * Ends the connection being followed, from another thread, so {@link #follow} returns {@link
   * Ending#DISCONNECTED}. Used when the link has been silent longer than three heartbeats: a peer
   * or a middlebox may have dropped it without telling anyone.
   */
  void abort();
}
