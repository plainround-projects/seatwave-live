package io.plainround.seatwave.live.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedPositionTest {

  @Test
  void readsBackWhatItWrites() {
    FeedPosition position = new FeedPosition("3f9a2c1e", 42);

    Result<FeedPosition, String> read = FeedPosition.parse(position.written());

    assertThat(position.written()).isEqualTo("3f9a2c1e.42");
    assertThat(read.discardError()).contains(position);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "3f9a2c1e",
        "3f9a2c1e.",
        "3F9A2C1E.1",
        "3f9a2c1.1",
        "3f9a2c1e.-1",
        "3f9a2c1e.01",
        "3f9a2c1e.1234567890123456789",
        "3f9a2c1e.1 ",
        "../etc.1"
      })
  void refusesAMalformedPosition(String written) {
    assertThat(FeedPosition.parse(written).isError()).isTrue();
  }

  @Test
  void refusesANegativeSequenceAndABadStream() {
    assertThatThrownBy(() -> new FeedPosition("3f9a2c1e", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FeedPosition("xyz", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void movesOneChangeForward() {
    assertThat(new FeedPosition("3f9a2c1e", 7).next()).isEqualTo(new FeedPosition("3f9a2c1e", 8));
  }
}
