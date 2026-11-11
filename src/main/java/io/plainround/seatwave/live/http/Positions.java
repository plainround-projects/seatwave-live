package io.plainround.seatwave.live.http;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.feed.FeedPosition;

/** Reads the {@code after} parameter that names a client's position. */
final class Positions {

  private Positions() {}

  static Result<FeedPosition, String> after(Query query) {
    return query
        .get("after")
        .map(written -> FeedPosition.parse(written).mapError(detail -> "after " + detail))
        .orElse(Result.error("after is required: the position of the last change you have"));
  }
}
