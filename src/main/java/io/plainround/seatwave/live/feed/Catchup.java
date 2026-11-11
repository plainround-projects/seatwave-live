package io.plainround.seatwave.live.feed;

import java.util.List;
import java.util.Objects;

/**
 * What the feed can give a client that names the position it has: the changes after it, or an
 * instruction to reload the whole seat map. There is no third answer, so no code path can hand a
 * client a gap.
 */
public sealed interface Catchup {

  /**
   * Every change after the client's position, in order, with none missing.
   *
   * @param changes the changes, possibly none
   * @param position the position after the last of them (the client's own if there are none)
   */
  record Changes(List<SeatChange> changes, FeedPosition position) implements Catchup {

    /** Copies the list, so it cannot change later. */
    public Changes {
      changes = List.copyOf(changes);
      Objects.requireNonNull(position, "position");
    }
  }

  /**
   * The feed no longer holds every change after the client's position (or the position belongs to
   * another stream), so the client must load the whole seat map again.
   *
   * @param position the feed's current position, for information
   */
  record ReloadRequired(FeedPosition position) implements Catchup {

    /** Checks the field. */
    public ReloadRequired {
      Objects.requireNonNull(position, "position");
    }
  }
}
