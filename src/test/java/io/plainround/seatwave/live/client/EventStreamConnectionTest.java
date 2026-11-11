package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import io.plainround.seatwave.live.wire.MessageView;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventStreamConnectionTest {

  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final FeedPosition START = new FeedPosition("0000abcd", 10);

  @Test
  void readsADataFieldWithOrWithoutTheSpaceAfterTheColon() throws Exception {
    String stream =
        "data:{\"type\":\"seat\",\"position\":\"0000abcd.11\",\"seat\":\"A-1\",\"state\":\"held\","
            + "\"changedAt\":\"2026-11-11T10:00:00Z\"}\n\n"
            + "data: {\"type\":\"seat\",\"position\":\"0000abcd.12\",\"seat\":\"A-2\","
            + "\"state\":\"held\",\"changedAt\":\"2026-11-11T10:00:00Z\"}\n\n";

    List<MessageView> received = follow(stream);

    assertThat(received)
        .extracting(MessageView::position)
        .containsExactly("0000abcd.11", "0000abcd.12");
  }

  @Test
  void joinsTheDataLinesOfOneEventWithALineFeed() throws Exception {
    // The line feed between the two data lines falls inside a JSON string, where JSON forbids it:
    // a browser's JSON.parse refuses this event, and so must the client. Joined without the line
    // feed, the two halves would wrongly make a valid change.
    String stream =
        "data: {\"type\":\"seat\",\"position\":\"0000abcd.11\",\"seat\":\"A-\n"
            + "data: 1\",\"state\":\"held\",\"changedAt\":\"2026-11-11T10:00:00Z\"}\n\n";

    List<MessageView> received = follow(stream);

    assertThat(received).isEmpty();
  }

  // Serves one event stream on the loopback address and follows it to its end.
  private static List<MessageView> follow(String stream) throws IOException, InterruptedException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = stream.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    Recorder recorder = new Recorder();
    try (HttpClient http = HttpClient.newHttpClient()) {
      new EventStreamConnection(
              http, new FeedEndpoints(server.getAddress().getPort(), 1), new FeedJson(), WAIT)
          .follow(START, recorder);
    } finally {
      server.stop(0);
    }
    return recorder.received;
  }

  private static final class Recorder implements MessageSink {

    private final List<MessageView> received = new ArrayList<>();

    @Override
    public void connected() {
      // Nothing to record: the test looks only at the messages.
    }

    @Override
    public Verdict accept(MessageView message) {
      received.add(message);
      return Verdict.CONTINUE;
    }

    @Override
    public void heartbeat() {
      // The streams in these tests carry no comments.
    }
  }
}
