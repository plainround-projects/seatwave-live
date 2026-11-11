package io.plainround.seatwave.live.wire;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.plainround.seatwave.live.feed.Catchup;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatChange;
import io.plainround.seatwave.live.feed.SeatLayout;
import io.plainround.seatwave.live.feed.SeatMapSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * Turns feed values into JSON and back. Incoming JSON binds only to the known records of this
 * package, and an unknown field is an error, so nothing a server sends can build an arbitrary type.
 *
 * <p>Thread-safe: Jackson's {@link ObjectMapper} is safe to share once configured.
 */
public final class FeedJson {

  private final ObjectMapper mapper =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /**
   * One seat change as a message.
   *
   * @param change the change
   * @param layout the layout that names its seat
   * @return the message
   */
  public MessageView message(SeatChange change, SeatLayout layout) {
    return new MessageView(
        MessageView.SEAT,
        change.position().written(),
        layout.label(change.seatIndex()),
        change.state().wireName(),
        change.changedAt().toString());
  }

  /**
   * A reload instruction as a message.
   *
   * @param current the feed's current position
   * @return the message
   */
  public MessageView reload(FeedPosition current) {
    return new MessageView(MessageView.RELOAD, current.written(), null, null, null);
  }

  /**
   * The reply to a long poll.
   *
   * @param catchup what the feed had after the client's position
   * @param layout the layout that names the seats
   * @param asked the position the client asked from
   * @return the reply as JSON bytes
   */
  public byte[] longPoll(Catchup catchup, SeatLayout layout, FeedPosition asked) {
    LongPollView view =
        switch (catchup) {
          case Catchup.Changes changes ->
              new LongPollView(
                  changes.changes().stream().map(change -> message(change, layout)).toList(),
                  (changes.changes().isEmpty() ? asked : changes.position()).written(),
                  false);
          case Catchup.ReloadRequired reload ->
              new LongPollView(List.of(), reload.position().written(), true);
        };
    return bytes(view);
  }

  /**
   * The seat map.
   *
   * @param snapshot the map
   * @param eventId its event
   * @return the map as JSON bytes
   */
  public byte[] snapshot(SeatMapSnapshot snapshot, int eventId) {
    return bytes(
        new SnapshotView(
            eventId,
            snapshot.position().written(),
            snapshot.layout().sections(),
            snapshot.layout().seatsPerSection(),
            snapshot.states()));
  }

  /**
   * Any of this package's records as JSON.
   *
   * @param value the record
   * @return UTF-8 JSON bytes
   */
  public byte[] bytes(Object value) {
    try {
      return mapper.writeValueAsBytes(Objects.requireNonNull(value, "value"));
    } catch (IOException notSerializable) {
      // Only this package's records are written, so this is a programming error.
      throw new UncheckedIOException(
          "cannot write " + value.getClass().getSimpleName(), notSerializable);
    }
  }

  /**
   * Reads JSON into one of this package's records.
   *
   * @param json the bytes
   * @param type the record type expected
   * @param <T> the type
   * @return the record
   * @throws IOException if the JSON is malformed or does not match the record exactly
   */
  public <T> T read(byte[] json, Class<T> type) throws IOException {
    return mapper.readValue(Objects.requireNonNull(json, "json"), type);
  }
}
