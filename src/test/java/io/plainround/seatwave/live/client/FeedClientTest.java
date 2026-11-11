package io.plainround.seatwave.live.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.wire.FeedJson;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeedClientTest {

  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final String OLD_MAP =
      "{\"eventId\":1,\"position\":\"0000abcd.10\",\"sections\":1,\"seatsPerSection\":5,"
          + "\"states\":\"aaaaa\"}";
  private static final String NEW_MAP =
      "{\"eventId\":1,\"position\":\"0000abcd.20\",\"sections\":1,\"seatsPerSection\":5,"
          + "\"states\":\"hhaaa\"}";

  @Test
  void triesAFailedReloadAgainInsteadOfFollowingFromTheOldPosition() throws Exception {
    // The seat map answers: the old map, then one failure, then the new map.
    AtomicInteger requests = new AtomicInteger();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
    server.createContext(
        "/",
        exchange -> {
          int request = requests.incrementAndGet();
          if (request == 2) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
          }
          byte[] body = (request == 1 ? OLD_MAP : NEW_MAP).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    ReloadThenStop connection = new ReloadThenStop();
    try (HttpClient http = HttpClient.newHttpClient()) {
      FeedEndpoints endpoints = new FeedEndpoints(server.getAddress().getPort(), 1);
      FeedClient client =
          new FeedClient(
              connection,
              new SeatMapReader(http, endpoints, new FeedJson(), WAIT),
              new Backoff(Duration.ofMillis(1), Duration.ofMillis(5), 1),
              new AtomicBoolean(),
              new ClientStats(10));

      Thread running = Thread.ofVirtual().start(client);
      running.join(WAIT);

      assertThat(running.isAlive()).isFalse();
      assertThat(connection.followedFrom)
          .extracting(FeedPosition::written)
          .containsExactly("0000abcd.10", "0000abcd.20");
      assertThat(client.map().states()).isEqualTo("hhaaa");
    } finally {
      server.stop(0);
    }
  }

  // The first follow is told to reload; the second records its position and stops the client.
  private static final class ReloadThenStop implements FeedConnection {

    private final List<FeedPosition> followedFrom = new CopyOnWriteArrayList<>();

    @Override
    public Ending follow(FeedPosition from, MessageSink sink) {
      followedFrom.add(from);
      if (followedFrom.size() == 1) {
        return Ending.RELOAD;
      }
      Thread.currentThread().interrupt();
      return Ending.DISCONNECTED;
    }

    @Override
    public void abort() {
      // Never silent in this test.
    }
  }
}
