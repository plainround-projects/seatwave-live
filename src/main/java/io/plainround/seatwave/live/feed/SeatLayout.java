package io.plainround.seatwave.live.feed;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shape of the event's seat map: sections lettered from A, each with seats numbered from 1, as
 * in Module 1 (20 sections of 1,000 seats, labels such as {@code A-7}). Every seat also has an
 * index, section by section and then by number, which is its place in the compact seat map.
 *
 * @param sections how many sections, 1 to 20 (A to T)
 * @param seatsPerSection how many seats each section has, 1 to 1,000
 */
public record SeatLayout(int sections, int seatsPerSection) {

  /** Module 1's arena: sections A to T. */
  public static final int MAX_SECTIONS = 20;

  /** Module 1's arena: 1,000 seats per section. */
  public static final int MAX_SEATS_PER_SECTION = 1_000;

  private static final Pattern LABEL = Pattern.compile("([A-T])-([1-9][0-9]{0,3})");
  private static final char FIRST_SECTION = 'A';

  /** Checks the fields, so an impossible layout cannot exist. */
  public SeatLayout {
    if (sections < 1 || sections > MAX_SECTIONS) {
      throw new IllegalArgumentException("sections out of range: " + sections);
    }
    if (seatsPerSection < 1 || seatsPerSection > MAX_SEATS_PER_SECTION) {
      throw new IllegalArgumentException("seatsPerSection out of range: " + seatsPerSection);
    }
  }

  /**
   * How many seats the event has.
   *
   * @return sections times seats per section
   */
  public int seatCount() {
    return sections * seatsPerSection;
  }

  /**
   * The label of a seat, such as {@code A-7}.
   *
   * @param seatIndex the seat's index, from 0
   * @return the label
   * @throws IllegalArgumentException if the index is outside the map
   */
  public String label(int seatIndex) {
    if (!contains(seatIndex)) {
      throw new IllegalArgumentException("no seat at index " + seatIndex);
    }
    char section = (char) (FIRST_SECTION + seatIndex / seatsPerSection);
    return String.format(Locale.ROOT, "%c-%d", section, seatIndex % seatsPerSection + 1);
  }

  /**
   * The index of a labelled seat.
   *
   * @param label a label such as {@code A-7}
   * @return the index, or empty if the label is malformed or names no seat of this layout
   */
  public Optional<Integer> indexOf(String label) {
    Matcher matcher = LABEL.matcher(Objects.requireNonNull(label, "label"));
    if (!matcher.matches()) {
      return Optional.empty();
    }
    int section = matcher.group(1).charAt(0) - FIRST_SECTION;
    int number = Integer.parseInt(matcher.group(2));
    if (section >= sections || number > seatsPerSection) {
      return Optional.empty();
    }
    return Optional.of(section * seatsPerSection + number - 1);
  }

  /**
   * Tells whether an index names a seat of this layout.
   *
   * @param seatIndex the index
   * @return true when it is from 0 to the seat count less one
   */
  public boolean contains(int seatIndex) {
    return seatIndex >= 0 && seatIndex < seatCount();
  }
}
