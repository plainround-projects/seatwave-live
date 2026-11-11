package io.plainround.seatwave.live.feed;

import java.util.Objects;

/**
 * The whole seat map at one feed position: every seat's state, and the position of the last change
 * it includes. A client that loads it and then asks for the changes after its position has every
 * change exactly once.
 *
 * @param position the position of the last change included
 * @param layout the shape of the map
 * @param states one {@link SeatState#code()} per seat, in index order
 */
public record SeatMapSnapshot(FeedPosition position, SeatLayout layout, String states) {

  /** Checks that the states match the layout. */
  public SeatMapSnapshot {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(states, "states");
    if (states.length() != layout.seatCount()) {
      throw new IllegalArgumentException("one state per seat is required");
    }
    if (!states.chars().allMatch(code -> SeatState.fromCode((char) code).isPresent())) {
      throw new IllegalArgumentException("states may hold only a, h and s");
    }
  }
}
