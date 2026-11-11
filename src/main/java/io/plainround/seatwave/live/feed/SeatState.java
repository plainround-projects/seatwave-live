package io.plainround.seatwave.live.feed;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three states a seat shows on the seat map, with the names Module 7's version 1 API uses for
 * them ({@code available}, {@code held}, {@code sold}) and a one-letter code for the compact seat
 * map.
 */
public enum SeatState {
  /** Nobody holds or owns the seat. */
  AVAILABLE("available", 'a'),
  /** A fan holds the seat until the hold expires or is confirmed. */
  HELD("held", 'h'),
  /** The seat is booked. */
  SOLD("sold", 's');

  private final String wireName;
  private final char code;

  SeatState(String wireName, char code) {
    this.wireName = wireName;
    this.code = code;
  }

  /**
   * The name used in JSON, as in Module 7's contract.
   *
   * @return {@code available}, {@code held} or {@code sold}
   */
  public String wireName() {
    return wireName;
  }

  /**
   * The one-letter code used in the compact seat map.
   *
   * @return {@code a}, {@code h} or {@code s}
   */
  public char code() {
    return code;
  }

  /**
   * Finds the state with a JSON name.
   *
   * @param wireName the name
   * @return the state, or empty for any other text
   */
  public static Optional<SeatState> fromWireName(String wireName) {
    return Arrays.stream(values()).filter(state -> state.wireName.equals(wireName)).findFirst();
  }

  /**
   * Finds the state with a one-letter code.
   *
   * @param code the code
   * @return the state, or empty for any other character
   */
  public static Optional<SeatState> fromCode(char code) {
    return Arrays.stream(values()).filter(state -> state.code == code).findFirst();
  }
}
