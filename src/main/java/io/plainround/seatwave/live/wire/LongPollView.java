package io.plainround.seatwave.live.wire;

import java.util.List;

/**
 * The reply to one long poll.
 *
 * @param changes the changes after the position asked for, in order; empty when the hold ended with
 *     no news or when {@code reload} is true
 * @param next the position to ask from next time
 * @param reload true when the client must load the seat map again before asking
 */
public record LongPollView(List<MessageView> changes, String next, boolean reload) {

  /** Copies the list; a missing list is read as empty. */
  public LongPollView {
    changes = changes == null ? List.of() : List.copyOf(changes);
  }
}
