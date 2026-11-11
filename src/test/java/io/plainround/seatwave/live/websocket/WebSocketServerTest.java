package io.plainround.seatwave.live.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.live.LiveFeedNode;
import io.plainround.seatwave.live.Raw;
import io.plainround.seatwave.live.TestSettings;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedPosition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebSocketServerTest {

  private static final Duration WAIT = Duration.ofSeconds(5);

  @Test
  void deliversEveryChangeToTheJdkClientAsATextFrame() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC())) {
      Recorder recorder = new Recorder();
      WebSocket socket = open(http, node, node.parts().feed().position(), recorder);

      node.parts().store().hold(0);
      node.parts().store().hold(1);

      assertThat(recorder.take()).contains("\"seat\":\"A-1\"");
      assertThat(recorder.take()).contains("\"seat\":\"A-2\"");
      socket
          .sendClose(WebSocket.NORMAL_CLOSURE, "done")
          .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(recorder.take()).isEqualTo("close 1000");
    }
  }

  @Test
  void pingsAnIdleClientThatAnswersAndKeepsItOpen() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node =
            LiveFeedNode.start(
                TestSettings.with(100, 64, Duration.ofMillis(100)), Clock.systemUTC())) {
      Recorder recorder = new Recorder();
      open(http, node, node.parts().feed().position(), recorder);

      assertThat(recorder.take()).isEqualTo("ping");
      Thread.sleep(500);
      assertThat(node.parts().metrics().openConnections(DeliveryMethod.WEBSOCKET)).isEqualTo(1);
    }
  }

  @Test
  void sendsAReloadAndClosesForAPositionTheBufferNoLongerHolds() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node =
            LiveFeedNode.start(
                TestSettings.with(2, 64, Duration.ofSeconds(2)), Clock.systemUTC())) {
      FeedPosition start = node.parts().feed().position();
      for (int seat = 0; seat < 5; seat++) {
        node.parts().store().hold(seat);
      }
      Recorder recorder = new Recorder();

      open(http, node, start, recorder);

      assertThat(recorder.take()).contains("\"type\":\"reload\"");
      assertThat(recorder.take()).isEqualTo("close 1000");
    }
  }

  @Test
  void refusesAPageFromAnotherOriginAndAMissingPosition() throws Exception {
    try (LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw other = Raw.connect(node.webSocketPort());
        Raw noPosition = Raw.connect(node.webSocketPort())) {
      other.send(
          handshake(
              "?after=" + node.parts().feed().position().written(),
              "Origin: https://evil.example\r\n"));
      noPosition.send(handshake("", ""));

      assertThat(other.readUntil("\r\n\r\n")).startsWith("HTTP/1.1 403");
      assertThat(noPosition.readUntil("\r\n\r\n")).startsWith("HTTP/1.1 400");
    }
  }

  @Test
  void closesAClientThatSendsAnUnmaskedFrameOrADataMessage() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node = LiveFeedNode.start(TestSettings.small(), Clock.systemUTC());
        Raw unmasked = Raw.connect(node.webSocketPort())) {
      unmasked.send(handshake("?after=" + node.parts().feed().position().written(), ""));
      unmasked.readUntil("\r\n\r\n");
      unmasked.sendBytes(new byte[] {(byte) 0x89, 0});
      Recorder recorder = new Recorder();
      WebSocket chatty = open(http, node, node.parts().feed().position(), recorder);
      chatty.sendText("hello", true);

      assertThat(unmasked.input().readNBytes(4)).containsExactly(0x88, 2, 0x03, 0xEA);
      assertThat(recorder.take()).isEqualTo("close 1003");
    }
  }

  @Test
  void refusesAWebSocketOverTheConnectionLimit() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient();
        LiveFeedNode node =
            LiveFeedNode.start(TestSettings.withConnectionLimit(1), Clock.systemUTC())) {
      open(http, node, node.parts().feed().position(), new Recorder());
      await()
          .atMost(WAIT)
          .until(() -> node.parts().metrics().openConnections(DeliveryMethod.WEBSOCKET) == 1);

      try (Raw second = Raw.connect(node.webSocketPort())) {
        second.send(handshake("?after=" + node.parts().feed().position().written(), ""));

        assertThat(second.readUntil("\r\n\r\n"))
            .startsWith("HTTP/1.1 503")
            .contains("Retry-After: 1");
      }
    }
  }

  private static WebSocket open(
      HttpClient http, LiveFeedNode node, FeedPosition after, Recorder recorder)
      throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
    URI uri =
        URI.create(
            "ws://127.0.0.1:"
                + node.webSocketPort()
                + Handshake.PATH
                + "?after="
                + after.written());
    return http.newWebSocketBuilder()
        .buildAsync(uri, recorder)
        .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  private static String handshake(String query, String extra) {
    return "GET "
        + Handshake.PATH
        + query
        + " HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
        + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
        + extra
        + "\r\n";
  }

  private static final class Recorder implements WebSocket.Listener {

    private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

    String take() throws InterruptedException {
      String event = events.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      return event == null ? "nothing" : event;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      events.add(data.toString());
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
      events.add("ping");
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      events.add("close " + statusCode);
      return null;
    }
  }
}
