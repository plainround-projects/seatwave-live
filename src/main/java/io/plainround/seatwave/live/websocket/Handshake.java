package io.plainround.seatwave.live.websocket;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The WebSocket opening handshake (RFC 6455): reads the client's HTTP upgrade request, checks it,
 * and writes either {@code 101 Switching Protocols} with the accept value, or an HTTP refusal.
 *
 * <p>The checks, in order: the request is a {@code GET} of the feed's socket path; it asks for
 * {@code Upgrade: websocket} and {@code Connection: Upgrade}; its version is 13 (else {@code 426}
 * with the supported version); its key is 16 bytes in base64; and, when a browser sends an {@code
 * Origin}, it is the one allowed origin (else {@code 403}), because a browser lets any page open a
 * WebSocket to any server and only the server can refuse. Stateless.
 */
public final class Handshake {

  /** The path the WebSocket feed answers on. */
  public static final String PATH = "/v1/events/1/seat-changes/socket";

  // RFC 6455 fixes this GUID: the accept value is SHA-1 of the key followed by it, in base64.
  private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private static final int MAX_HEAD_BYTES = 8_192;
  private static final int KEY_BYTES = 16;
  private static final String VERSION = "13";
  private static final int BAD_REQUEST = 400;
  private static final int FORBIDDEN = 403;
  private static final int NOT_FOUND = 404;
  private static final int UPGRADE_REQUIRED = 426;
  private static final int HEADER_END = 4;

  private Handshake() {}

  /**
   * Reads and checks the opening handshake.
   *
   * @param in the connection's input, positioned at the start of the request
   * @param allowedOrigin the one browser origin that may connect
   * @return the request, or the refusal to send
   * @throws IOException if reading fails or the peer closes early
   */
  public static Result<HandshakeRequest, HandshakeRefusal> read(
      InputStream in, String allowedOrigin) throws IOException {
    Objects.requireNonNull(allowedOrigin, "allowedOrigin");
    String head = readHead(in);
    if (head.isEmpty()) {
      return refuse(BAD_REQUEST, "the request head is larger than " + MAX_HEAD_BYTES + " bytes");
    }
    String[] lines = head.split("\r\n");
    String[] requestLine = lines[0].split(" ");
    if (requestLine.length != 3 || !"GET".equals(requestLine[0])) {
      return refuse(BAD_REQUEST, "a WebSocket handshake is a GET request");
    }
    String target = requestLine[1];
    int question = target.indexOf('?');
    String path = question < 0 ? target : target.substring(0, question);
    if (!PATH.equals(path)) {
      return refuse(NOT_FOUND, "no WebSocket at " + path);
    }
    Map<String, String> headers = headers(lines);
    if (!"websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""))
        || !headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade")) {
      return refuse(BAD_REQUEST, "Upgrade: websocket and Connection: Upgrade are required");
    }
    if (!VERSION.equals(headers.get("sec-websocket-version"))) {
      return refuse(UPGRADE_REQUIRED, "only WebSocket version 13 is supported");
    }
    String key = headers.getOrDefault("sec-websocket-key", "");
    if (!isValidKey(key)) {
      return refuse(BAD_REQUEST, "Sec-WebSocket-Key must be 16 bytes in base64");
    }
    String origin = headers.get("origin");
    if (origin != null && !origin.equals(allowedOrigin)) {
      return refuse(FORBIDDEN, "origin " + origin + " may not open this feed");
    }
    return Result.success(
        new HandshakeRequest(question < 0 ? "" : target.substring(question + 1), key));
  }

  /**
   * The {@code Sec-WebSocket-Accept} value for a key.
   *
   * @param key the client's {@code Sec-WebSocket-Key}
   * @return base64 of SHA-1 of the key and the RFC's GUID
   */
  public static String acceptFor(String key) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
      return Base64.getEncoder().encodeToString(digest);
    } catch (NoSuchAlgorithmException missing) {
      // Every JDK must provide SHA-1 (the MessageDigest documentation lists it as required).
      throw new IllegalStateException("SHA-1 is not available", missing);
    }
  }

  /**
   * The {@code 101} reply that completes the handshake.
   *
   * @param request the accepted request
   * @return the reply's bytes
   */
  public static byte[] accepted(HandshakeRequest request) {
    String reply =
        "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + acceptFor(request.key())
            + "\r\n\r\n";
    return reply.getBytes(StandardCharsets.US_ASCII);
  }

  /**
   * The HTTP reply that refuses the handshake.
   *
   * @param refusal the status and reason
   * @param retryAfterSeconds the Retry-After value for a 503, ignored otherwise
   * @return the reply's bytes
   */
  public static byte[] refused(HandshakeRefusal refusal, long retryAfterSeconds) {
    byte[] body = (refusal.reason() + "\n").getBytes(StandardCharsets.UTF_8);
    StringBuilder reply =
        new StringBuilder("HTTP/1.1 ").append(refusal.status()).append(" Refused\r\n");
    if (refusal.status() == UPGRADE_REQUIRED) {
      reply.append("Sec-WebSocket-Version: ").append(VERSION).append("\r\n");
    }
    if (refusal.status() == ServiceUnavailable.STATUS) {
      reply.append("Retry-After: ").append(retryAfterSeconds).append("\r\n");
    }
    reply.append("Content-Type: text/plain; charset=utf-8\r\n");
    reply.append("Content-Length: ").append(body.length).append("\r\n");
    reply.append("Connection: close\r\n\r\n");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(reply.toString().getBytes(StandardCharsets.US_ASCII));
    bytes.writeBytes(body);
    return bytes.toByteArray();
  }

  private static Result<HandshakeRequest, HandshakeRefusal> refuse(int status, String reason) {
    return Result.error(new HandshakeRefusal(status, reason));
  }

  // Reads up to the blank line that ends the head; an empty string means the head was too large.
  private static String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int matched = 0;
    byte[] end = {'\r', '\n', '\r', '\n'};
    while (matched < HEADER_END) {
      int value = in.read();
      if (value < 0) {
        throw new IOException("the peer closed the connection during the handshake");
      }
      head.write(value);
      matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
      if (head.size() > MAX_HEAD_BYTES) {
        return "";
      }
    }
    return head.toString(StandardCharsets.US_ASCII);
  }

  private static Map<String, String> headers(String[] lines) {
    Map<String, String> headers = new HashMap<>();
    for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
      int colon = lines[lineIndex].indexOf(':');
      if (colon > 0) {
        headers.put(
            lines[lineIndex].substring(0, colon).strip().toLowerCase(Locale.ROOT),
            lines[lineIndex].substring(colon + 1).strip());
      }
    }
    return headers;
  }

  private static boolean isValidKey(String key) {
    try {
      return Base64.getDecoder().decode(key).length == KEY_BYTES;
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
  }
}
