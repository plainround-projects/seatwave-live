package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SeatLayoutTest {

  private final SeatLayout arena = new SeatLayout(20, 1_000);

  @Test
  void namesSeatsSectionBySectionAsModuleOneDoes() {
    assertThat(arena.seatCount()).isEqualTo(20_000);
    assertThat(arena.label(0)).isEqualTo("A-1");
    assertThat(arena.label(1_006)).isEqualTo("B-7");
    assertThat(arena.label(19_999)).isEqualTo("T-1000");
    assertThat(arena.indexOf("B-7")).contains(1_006);
  }

  @Test
  void findsNoSeatForAMalformedOrMissingLabel() {
    SeatLayout small = new SeatLayout(2, 50);

    assertThat(small.indexOf("C-1")).isEmpty();
    assertThat(small.indexOf("A-51")).isEmpty();
    assertThat(small.indexOf("A-0")).isEmpty();
    assertThat(small.indexOf("a-1")).isEmpty();
    assertThatThrownBy(() -> small.label(100)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAnImpossibleLayout() {
    assertThatThrownBy(() -> new SeatLayout(0, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SeatLayout(21, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SeatLayout(1, 1_001)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsEveryStateByItsNameAndCode() {
    for (SeatState state : SeatState.values()) {
      assertThat(SeatState.fromWireName(state.wireName())).contains(state);
      assertThat(SeatState.fromCode(state.code())).contains(state);
    }
    assertThat(SeatState.fromWireName("taken")).isEmpty();
    assertThat(SeatState.fromCode('x')).isEmpty();
  }
}
