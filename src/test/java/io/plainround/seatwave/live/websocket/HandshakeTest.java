package io.plainround.seatwave.live.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HandshakeTest {

  private static final String ORIGIN = "http://127.0.0.1:8090";
  // The example key and accept value printed in RFC 6455 itself.
  private static final String RFC_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

  @Test
  void computesTheAcceptValueOfTheRfcsExample() {
    assertThat(Handshake.acceptFor(RFC_KEY)).isEqualTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
  }

  @Test
  void acceptsAValidUpgradeFromTheAllowedOrigin() throws IOException {
    Result<HandshakeRequest, HandshakeRefusal> result =
        read(request("GET", Handshake.PATH + "?after=00000000.0", "13", RFC_KEY, ORIGIN));

    assertThat(result.discardError())
        .hasValueSatisfying(request -> {
          assertThat(request.rawQuery()).isEqualTo("after=00000000.0");
          assertThat(new String(Handshake.accepted(request), StandardCharsets.US_ASCII))
              .startsWith("HTTP/1.1 101 Switching Protocols\r\n")
              .contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n");
        });
  }

  @Test
  void refusesEachBrokenRuleWithItsStatus() throws IOException {
    assertThat(statusOf(request("POST", Handshake.PATH, "13", RFC_KEY, ORIGIN))).isEqualTo(400);
    assertThat(statusOf(request("GET", "/other", "13", RFC_KEY, ORIGIN))).isEqualTo(404);
    assertThat(statusOf(request("GET", Handshake.PATH, "8", RFC_KEY, ORIGIN))).isEqualTo(426);
    assertThat(statusOf(request("GET", Handshake.PATH, "13", "c2hvcnQ=", ORIGIN))).isEqualTo(400);
    assertThat(statusOf(request("GET", Handshake.PATH, "13", "not base64!", ORIGIN)))
        .isEqualTo(400);
    assertThat(statusOf(request("GET", Handshake.PATH, "13", RFC_KEY, "https://evil.example")))
        .isEqualTo(403);
    assertThat(
            statusOf(
                "GET "
                    + Handshake.PATH
                    + " HTTP/1.1\r\nHost: x\r\nSec-WebSocket-Version: 13\r\n\r\n"))
        .isEqualTo(400);
    assertThat(
            statusOf("GET " + Handshake.PATH + " HTTP/1.1\r\nX: " + "y".repeat(9_000) + "\r\n\r\n"))
        .isEqualTo(400);
  }

  @Test
  void writesARefusalTheClientCanRead() {
    String refused =
        new String(
            Handshake.refused(new HandshakeRefusal(426, "only version 13"), 1),
            StandardCharsets.UTF_8);
    String busy =
        new String(Handshake.refused(ServiceUnavailable.refusal(), 1), StandardCharsets.UTF_8);

    assertThat(refused)
        .startsWith("HTTP/1.1 426 ")
        .contains("Sec-WebSocket-Version: 13\r\n", "Connection: close");
    assertThat(busy).startsWith("HTTP/1.1 503 ").contains("Retry-After: 1\r\n");
  }

  private static int statusOf(String request) throws IOException {
    return switch (read(request)) {
      case Result.Error<HandshakeRequest, HandshakeRefusal>(HandshakeRefusal refusal) ->
          refusal.status();
      case Result.Success<HandshakeRequest, HandshakeRefusal> accepted -> 101;
    };
  }

  private static Result<HandshakeRequest, HandshakeRefusal> read(String request)
      throws IOException {
    return Handshake.read(
        new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII)), ORIGIN);
  }

  private static String request(
      String method, String target, String version, String key, String origin) {
    return method
        + " "
        + target
        + " HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n"
        + "Sec-WebSocket-Version: "
        + version
        + "\r\nSec-WebSocket-Key: "
        + key
        + "\r\nOrigin: "
        + origin
        + "\r\n\r\n";
  }
}
