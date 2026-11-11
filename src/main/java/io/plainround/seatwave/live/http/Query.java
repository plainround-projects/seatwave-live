package io.plainround.seatwave.live.http;

import com.github.sviperll.result4j.Result;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The query parameters of one request, checked against an allow-list: an unknown or repeated
 * parameter is refused, so a typo is visible instead of ignored.
 *
 * @param values the parameters by name
 */
record Query(Map<String, String> values) {

  // A query longer than this cannot be a valid feed request.
  private static final int MAX_LENGTH = 256;

  Query {
    values = Map.copyOf(values);
  }

  static Result<Query, String> parse(String rawQuery, Set<String> allowed) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return Result.success(new Query(Map.of()));
    }
    if (rawQuery.length() > MAX_LENGTH) {
      return Result.error("the query is longer than " + MAX_LENGTH + " characters");
    }
    Map<String, String> values = new HashMap<>();
    for (String pair : rawQuery.split("&", -1)) {
      int equals = pair.indexOf('=');
      String name =
          URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
      String value =
          equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
      if (!allowed.contains(name)) {
        return Result.error("parameter " + name + " is not part of this request");
      }
      if (values.put(name, value) != null) {
        return Result.error("parameter " + name + " is repeated");
      }
    }
    return Result.success(new Query(values));
  }

  Optional<String> get(String name) {
    return Optional.ofNullable(values.get(name));
  }
}
