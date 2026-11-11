package io.plainround.seatwave.live.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.Raw;
import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.feed.FeedPosition;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventStreamRouteTest {

  @Test
  void sendsRetryThenOneEventPerChangeWithItsPositionAsTheId() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      FeedPosition start = node.parts().feed().position();
      stream.send(request("after=" + start.written(), ""));
      String head = stream.readUntil("retry: 1000\n\n");

      node.parts().store().hold(0);
      String event = stream.readUntil("\n\n");

      assertThat(head)
          .contains("HTTP/1.1 200", "Content-type: text/event-stream", "Cache-control: no-store");
      assertThat(event)
          .contains(
              "id: " + start.next().written() + "\n",
              "event: seat\n",
              "data: {\"type\":\"seat\"",
              "\"seat\":\"A-1\"");
    }
  }

  @Test
  void resumesFromLastEventIdEvenWhenTheUrlCarriesAnOlderAfter() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      FeedPosition start = node.parts().feed().position();
      for (int seat = 0; seat < 3; seat++) {
        node.parts().store().hold(seat);
      }
      FeedPosition second = new FeedPosition(start.streamId(), 2);

      stream.send(
          request("after=" + start.written(), "Last-Event-ID: " + second.written() + "\r\n"));
      String events = stream.readUntil("\"seat\":\"A-3\"");

      assertThat(events)
          .doesNotContain("\"seat\":\"A-1\"", "\"seat\":\"A-2\"")
          .contains("id: " + start.streamId() + ".3");
    }
  }

  @Test
  void tellsAClientTooFarBehindToReloadAndEndsTheStream() throws Exception {
    try (LiveFeedNode node =
            LiveFeedNode.start(TestSettings.with(2, 64, Duration.ofSeconds(2)), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      FeedPosition start = node.parts().feed().position();
      for (int seat = 0; seat < 5; seat++) {
        node.parts().store().hold(seat);
      }

      stream.send(request("after=" + start.written(), ""));
      String reply = stream.readUntil("\r\n0\r\n\r\n");

      assertThat(reply)
          .contains(
              "event: reload\n",
              "\"type\":\"reload\"",
              "\"position\":\"" + start.streamId() + ".5\"");
    }
  }

  @Test
  void sendsACommentLineAsAHeartbeatWhenNothingHappens() throws Exception {
    try (LiveFeedNode node =
            LiveFeedNode.start(
                TestSettings.with(100, 64, Duration.ofMillis(100)), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      stream.send(request("after=" + node.parts().feed().position().written(), ""));

      assertThat(stream.readUntil(": heartbeat\n\n")).endsWith(": heartbeat\n\n");
    }
  }

  @Test
  void refusesAMalformedLastEventId() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw stream = Raw.connect(node.httpPort())) {
      stream.send(request("", "Last-Event-ID: seven\r\n"));

      assertThat(stream.readUntil("}")).contains("HTTP/1.1 400", "Last-Event-ID must be");
    }
  }

  private static String request(String query, String extraHeaders) {
    return "GET /v1/events/1/seat-changes/stream"
        + (query.isEmpty() ? "" : "?" + query)
        + " HTTP/1.1\r\n"
        + "Host: 127.0.0.1\r\nAccept: text/event-stream\r\n"
        + extraHeaders
        + "\r\n";
  }
}
