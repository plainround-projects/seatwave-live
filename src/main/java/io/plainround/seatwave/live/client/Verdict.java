package io.plainround.seatwave.live.client;

/** What a client tells its connection after handling one message. */
public enum Verdict {
  /** Keep following the feed. */
  CONTINUE,
  /** Stop and reload the seat map. */
  RELOAD
}
