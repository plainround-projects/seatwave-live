package io.plainround.seatwave.live.client;

import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.feed.SeatState;
import io.plainround.seatwave.live.wire.MessageView;
import io.plainround.seatwave.live.wire.SnapshotView;
import java.util.Objects;

/**
 * A client's copy of the seat map: loaded from a snapshot, then kept current by applying changes in
 * sequence order. It checks every change against its own position, so a gap or a duplicate is
 * counted and never silently applied.
 *
 * <p>Not thread-safe: one client thread owns it; {@link #sequence()} may be read by any thread.
 */
public final class ClientSeatMap {

  private char[] states = new char[0];
  private SeatLayout layout = new SeatLayout(1, 1);
  private String streamId = "00000000";
  private volatile long sequence;
  private boolean loaded;

  /**
   * Replaces the map with a snapshot.
   *
   * @param snapshot the seat map as the feed sent it
   * @throws IllegalArgumentException if the snapshot is malformed
   */
  public void load(SnapshotView snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    SeatLayout snapshotLayout = new SeatLayout(snapshot.sections(), snapshot.seatsPerSection());
    if (snapshot.states() == null || snapshot.states().length() != snapshotLayout.seatCount()) {
      throw new IllegalArgumentException("the snapshot does not have one state per seat");
    }
    FeedPosition position =
        FeedPosition.parse(String.valueOf(snapshot.position()))
            .orOnErrorThrow(IllegalArgumentException::new);
    layout = snapshotLayout;
    states = snapshot.states().toCharArray();
    streamId = position.streamId();
    sequence = position.sequence();
    loaded = true;
  }

  /**
   * Applies one seat change.
   *
   * @param message a message of type {@code seat}
   * @return whether it was applied, a duplicate, or after a gap
   * @throws IllegalArgumentException if the message is malformed
   */
  public Applied apply(MessageView message) {
    FeedPosition position =
        FeedPosition.parse(String.valueOf(message.position()))
            .orOnErrorThrow(IllegalArgumentException::new);
    if (!position.streamId().equals(streamId)) {
      // A change from another stream cannot be placed in this map's history.
      return Applied.GAP;
    }
    if (position.sequence() <= sequence) {
      return Applied.DUPLICATE;
    }
    if (position.sequence() != sequence + 1) {
      return Applied.GAP;
    }
    int seatIndex =
        layout
            .indexOf(String.valueOf(message.seat()))
            .orElseThrow(() -> new IllegalArgumentException("unknown seat " + message.seat()));
    SeatState state =
        SeatState.fromWireName(String.valueOf(message.state()))
            .orElseThrow(() -> new IllegalArgumentException("unknown state " + message.state()));
    states[seatIndex] = state.code();
    sequence = position.sequence();
    return Applied.APPLIED;
  }

  /**
   * Tells whether a snapshot has been loaded.
   *
   * @return true after the first {@link #load}
   */
  public boolean isLoaded() {
    return loaded;
  }

  /**
   * The position of the last change applied.
   *
   * @return the position
   */
  public FeedPosition position() {
    return new FeedPosition(streamId, sequence);
  }

  /**
   * The sequence of the last change applied, safe to read from another thread.
   *
   * @return the sequence
   */
  public long sequence() {
    return sequence;
  }

  /**
   * The map as one letter per seat, the snapshot's format, to compare with the store.
   *
   * @return the states
   */
  public String states() {
    return new String(states);
  }
}
