package io.plainround.seatwave.live.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The most recent changes of the feed, in a fixed-size ring: when it is full, the oldest change is
 * overwritten. A client that reconnects within the buffer's window resumes from it; one that has
 * been away longer is told to reload.
 *
 * <p>Not thread-safe: {@link SeatFeed} uses it only while holding its lock.
 */
final class ReplayBuffer {

  private final SeatChange[] ring;
  private final String streamId;
  private long lastSequence;

  ReplayBuffer(String streamId, int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    this.streamId = Objects.requireNonNull(streamId, "streamId");
    this.ring = new SeatChange[capacity];
  }

  void append(SeatChange change) {
    // The feed numbers changes; a hole here would be a bug, never a client's mistake.
    if (change.position().sequence() != lastSequence + 1) {
      throw new IllegalStateException("changes must arrive in order without gaps");
    }
    lastSequence = change.position().sequence();
    ring[slot(lastSequence)] = change;
  }

  FeedPosition position() {
    return new FeedPosition(streamId, lastSequence);
  }

  Catchup after(FeedPosition known, int maxChanges) {
    if (!known.streamId().equals(streamId) || known.sequence() > lastSequence) {
      // Another stream, or a sequence this stream never reached: the client's map came from an
      // earlier process, and replaying from it would apply the wrong history.
      return new Catchup.ReloadRequired(position());
    }
    long oldestKept = Math.max(1, lastSequence - ring.length + 1);
    if (known.sequence() + 1 < oldestKept) {
      return new Catchup.ReloadRequired(position());
    }
    long lastToSend = Math.min(lastSequence, known.sequence() + maxChanges);
    List<SeatChange> changes = new ArrayList<>();
    for (long sequence = known.sequence() + 1; sequence <= lastToSend; sequence++) {
      changes.add(ring[slot(sequence)]);
    }
    return new Catchup.Changes(changes, new FeedPosition(streamId, lastToSend));
  }

  private int slot(long sequence) {
    return (int) (sequence % ring.length);
  }
}
