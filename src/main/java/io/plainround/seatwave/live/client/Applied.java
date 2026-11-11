package io.plainround.seatwave.live.client;

/** What applying one change did to a client's seat map. */
public enum Applied {
  /** The change was the next one, and the map now includes it. */
  APPLIED,
  /** The map already had this change; it was ignored. */
  DUPLICATE,
  /** A change was missing before this one, so the map can no longer be trusted. */
  GAP
}
