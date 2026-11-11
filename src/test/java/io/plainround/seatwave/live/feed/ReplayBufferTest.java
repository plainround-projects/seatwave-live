package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReplayBufferTest {

  private static final String STREAM = "0000abcd";

  @Test
  void returnsEveryChangeAfterAPositionItStillHolds() {
    ReplayBuffer buffer = filled(5, 10);

    Catchup catchup = buffer.after(new FeedPosition(STREAM, 7), 100);

    assertThat(catchup).isInstanceOf(Catchup.Changes.class);
    Catchup.Changes changes = (Catchup.Changes) catchup;
    assertThat(changes.changes())
        .extracting(change -> change.position().sequence())
        .containsExactly(8L, 9L, 10L);
    assertThat(changes.position().sequence()).isEqualTo(10);
  }

  @Test
  void returnsNothingToAClientThatIsCurrent() {
    Catchup catchup = filled(5, 10).after(new FeedPosition(STREAM, 10), 100);

    assertThat(((Catchup.Changes) catchup).changes()).isEmpty();
  }

  @Test
  void stopsAtTheBatchSize() {
    Catchup.Changes changes =
        (Catchup.Changes) filled(10, 10).after(new FeedPosition(STREAM, 0), 4);

    assertThat(changes.changes()).hasSize(4);
    assertThat(changes.position().sequence()).isEqualTo(4);
  }

  @Test
  void tellsAClientWhoseNextChangeWasOverwrittenToReload() {
    ReplayBuffer buffer = filled(5, 10);

    assertThat(buffer.after(new FeedPosition(STREAM, 5), 100)).isInstanceOf(Catchup.Changes.class);
    assertThat(buffer.after(new FeedPosition(STREAM, 4), 100))
        .isInstanceOf(Catchup.ReloadRequired.class);
  }

  @Test
  void tellsAClientFromAnotherStreamOrFromTheFutureToReload() {
    ReplayBuffer buffer = filled(5, 3);

    assertThat(buffer.after(new FeedPosition("ffffffff", 2), 100))
        .isInstanceOf(Catchup.ReloadRequired.class);
    assertThat(buffer.after(new FeedPosition(STREAM, 4), 100))
        .isInstanceOf(Catchup.ReloadRequired.class);
  }

  @Test
  void refusesAChangeOutOfOrder() {
    ReplayBuffer buffer = filled(5, 2);

    assertThatThrownBy(() -> buffer.append(change(4))).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ReplayBuffer(STREAM, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ReplayBuffer filled(int capacity, int changes) {
    ReplayBuffer buffer = new ReplayBuffer(STREAM, capacity);
    for (long sequence = 1; sequence <= changes; sequence++) {
      buffer.append(change(sequence));
    }
    return buffer;
  }

  private static SeatChange change(long sequence) {
    return new SeatChange(new FeedPosition(STREAM, sequence), 0, SeatState.HELD, Instant.EPOCH);
  }
}
