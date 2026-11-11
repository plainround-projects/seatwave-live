package io.plainround.seatwave.live.feed;

import com.github.sviperll.result4j.Result;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A place in the feed: the stream it belongs to and the sequence number of the last change a client
 * has. It is the feed's cursor (Module 7's idea: a cursor names a place instead of counting items),
 * written as {@code <stream>.<sequence>}, for example {@code 3f9a2c1e.42}.
 *
 * <p>The stream identifier changes every time the feed starts, because the replay buffer lives in
 * memory: a position from an earlier process would otherwise point at changes that never happened
 * in this one, a silent gap. A client with another stream's position is told to reload.
 *
 * @param streamId eight lowercase hexadecimal digits, chosen at random when the feed starts
 * @param sequence the number of the last change applied; 0 means before the first change
 */
public record FeedPosition(String streamId, long sequence) {

  private static final Pattern STREAM_ID = Pattern.compile("[0-9a-f]{8}");
  // A sequence is at most 18 digits, so it always fits in a long.
  private static final Pattern WRITTEN = Pattern.compile("([0-9a-f]{8})\\.(0|[1-9][0-9]{0,17})");

  /** Checks the fields. */
  public FeedPosition {
    if (!STREAM_ID.matcher(Objects.requireNonNull(streamId, "streamId")).matches()) {
      throw new IllegalArgumentException("streamId must be eight lowercase hex digits");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative: " + sequence);
    }
  }

  /**
   * Reads a position as a client sent it, in a query parameter or a {@code Last-Event-ID} header.
   *
   * @param written the text
   * @return the position, or a sentence saying what is wrong
   */
  public static Result<FeedPosition, String> parse(String written) {
    Objects.requireNonNull(written, "written");
    Matcher matcher = WRITTEN.matcher(written);
    if (!matcher.matches()) {
      return Result.error("must be <8 hex digits>.<sequence>, as the feed sent it");
    }
    return Result.success(new FeedPosition(matcher.group(1), Long.parseLong(matcher.group(2))));
  }

  /**
   * The position as clients see it.
   *
   * @return {@code <stream>.<sequence>}
   */
  public String written() {
    return streamId + "." + sequence;
  }

  /**
   * The position one change later in the same stream.
   *
   * @return the next position
   */
  public FeedPosition next() {
    return new FeedPosition(streamId, Math.addExact(sequence, 1));
  }
}
