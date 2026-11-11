package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.wire.MessageView;

/** Receives what a {@link FeedConnection} reads; implemented by {@link FeedClient}. */
public interface MessageSink {

  /** Called once per connection, when it is established and before its first message. */
  void connected();

  /**
   * Handles one message.
   *
   * @param message a seat change or a reload instruction
   * @return whether to keep following
   */
  Verdict accept(MessageView message);

  /** Called for a heartbeat (an event-stream comment or a WebSocket ping): the link is alive. */
  void heartbeat();
}
