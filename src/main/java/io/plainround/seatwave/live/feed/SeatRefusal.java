package io.plainround.seatwave.live.feed;

/** Why the seat store refused a change; expected outcomes, not errors. */
public enum SeatRefusal {
  /** The seat is held or sold, so it cannot be held. */
  SEAT_TAKEN,
  /** The seat is not held, or its hold has expired, so it cannot be confirmed. */
  NOT_HELD,
  /** No seat has that index. */
  UNKNOWN_SEAT
}
