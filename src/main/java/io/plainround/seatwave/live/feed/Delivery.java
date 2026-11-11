package io.plainround.seatwave.live.feed;

import java.util.List;

/** What a connection's writer should send next, as {@link Subscriber#next} decides it. */
public sealed interface Delivery {

  /**
   * Changes to send, in order.
   *
   * @param changes one or more changes
   */
  record Batch(List<SeatChange> changes) implements Delivery {

    /** Copies the list. */
    public Batch {
      changes = List.copyOf(changes);
    }
  }

  /** The client fell too far behind: tell it to reload the seat map, then end the connection. */
  record Reload() implements Delivery {}

  /** Nothing happened for a heartbeat interval: send a heartbeat so idle links stay open. */
  record Idle() implements Delivery {}

  /** Another part of the connection asked the writer to wake, for example to answer a ping. */
  record Woken() implements Delivery {}
}
