package io.plainround.seatwave.live.client;

/** How one connection to the feed ended. */
public enum Ending {
  /** The feed said the map must be loaded again, or the client found a gap. */
  RELOAD,
  /** The connection failed or closed; the client backs off and reconnects. */
  DISCONNECTED
}
