package io.plainround.seatwave.live.feed;

import java.time.Instant;
import java.util.Objects;

/**
 * One change of one seat's state, numbered by the feed in the order the store made it.
 *
 * @param position the feed position of this change; its sequence is one more than the change before
 *     it
 * @param seatIndex the seat, as an index into the seat layout
 * @param state the seat's new state
 * @param changedAt when the store made the change, in UTC; the delivery delay is measured from here
 */
public record SeatChange(FeedPosition position, int seatIndex, SeatState state, Instant changedAt) {

  /** Checks the fields. */
  public SeatChange {
    Objects.requireNonNull(position, "position");
    if (position.sequence() < 1) {
      throw new IllegalArgumentException("a change has a sequence of 1 or more");
    }
    if (seatIndex < 0) {
      throw new IllegalArgumentException("seatIndex must not be negative: " + seatIndex);
    }
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(changedAt, "changedAt");
  }
}
