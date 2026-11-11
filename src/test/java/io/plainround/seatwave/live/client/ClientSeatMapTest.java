package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.live.wire.MessageView;
import io.plainround.seatwave.live.wire.SnapshotView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientSeatMapTest {

  private final ClientSeatMap map = new ClientSeatMap();

  @BeforeEach
  void load() {
    map.load(new SnapshotView(1, "0000abcd.10", 1, 5, "aaaaa"));
  }

  @Test
  void appliesTheNextChange() {
    assertThat(map.apply(seat("0000abcd.11", "A-2", "held"))).isEqualTo(Applied.APPLIED);
    assertThat(map.states()).isEqualTo("ahaaa");
    assertThat(map.position().written()).isEqualTo("0000abcd.11");
  }

  @Test
  void ignoresAChangeItAlreadyHas() {
    assertThat(map.apply(seat("0000abcd.10", "A-2", "sold"))).isEqualTo(Applied.DUPLICATE);
    assertThat(map.states()).isEqualTo("aaaaa");
  }

  @Test
  void refusesAChangeAfterAGapOrFromAnotherStream() {
    assertThat(map.apply(seat("0000abcd.12", "A-2", "held"))).isEqualTo(Applied.GAP);
    assertThat(map.apply(seat("ffffffff.11", "A-2", "held"))).isEqualTo(Applied.GAP);
    assertThat(map.sequence()).isEqualTo(10);
  }

  @Test
  void refusesAMalformedChangeOrSnapshot() {
    assertThatThrownBy(() -> map.apply(seat("0000abcd.11", "Z-9", "held")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> map.apply(seat("0000abcd.11", "A-1", "taken")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> map.apply(seat("eleven", "A-1", "held")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ClientSeatMap().load(new SnapshotView(1, "0000abcd.1", 1, 5, "aaa")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MessageView seat(String position, String seat, String state) {
    return new MessageView(MessageView.SEAT, position, seat, state, "2026-11-11T10:00:00Z");
  }
}
