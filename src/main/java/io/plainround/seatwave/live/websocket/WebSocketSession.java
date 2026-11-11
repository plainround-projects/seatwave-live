package io.plainround.seatwave.live.websocket;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.live.feed.ConnectionLimit;
import io.plainround.seatwave.live.feed.Delivery;
import io.plainround.seatwave.live.feed.DeliveryMethod;
import io.plainround.seatwave.live.feed.FeedPosition;
import io.plainround.seatwave.live.feed.SeatChange;
import io.plainround.seatwave.live.feed.Subscriber;
import io.plainround.seatwave.live.http.Parts;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One WebSocket connection, from the handshake to the close, on two virtual threads: this one is
 * the only writer (changes, pings, pongs, close); a reader thread reads the client's control frames
 * and hands anything that needs an answer to the writer by waking it.
 *
 * <p><b>Heartbeats.</b> When nothing is sent for a heartbeat interval the writer sends a ping. The
 * reader's socket timeout is three heartbeats: a client that answers nothing for that long (a phone
 * that lost its network, a link a middlebox dropped without telling anyone) is closed, which frees
 * its slot and its memory.
 *
 * <p>Thread-safe: only the writer writes to the socket; the reader shares two atomic references.
 */
final class WebSocketSession {

  private static final Logger LOG = LoggerFactory.getLogger(WebSocketSession.class);
  // Clients send only control frames, which RFC 6455 limits to 125 bytes.
  private static final int MAX_CLIENT_PAYLOAD = 125;
  private static final int MISSED_HEARTBEATS_BEFORE_CLOSE = 3;
  private static final byte[] PING_PAYLOAD = "hb".getBytes(StandardCharsets.US_ASCII);
  private static final String AFTER_PREFIX = "after=";
  private static final int BAD_REQUEST = 400;

  private final Socket socket;
  private final Parts parts;
  private final String allowedOrigin;
  private final Duration heartbeat;
  private final Duration handshakeTimeout;
  private final long retryAfterSeconds;
  private final AtomicReference<byte[]> pongDue = new AtomicReference<>();
  private final AtomicReference<CloseCode> closeDue = new AtomicReference<>();

  WebSocketSession(Socket socket, Parts parts, SessionSettings settings) {
    this.socket = socket;
    this.parts = parts;
    this.allowedOrigin = settings.allowedOrigin();
    this.heartbeat = settings.heartbeat();
    this.handshakeTimeout = settings.handshakeTimeout();
    this.retryAfterSeconds = Math.max(1, settings.retryAfter().toSeconds());
  }

  void run() {
    parts.metrics().recordHandlerThread(true);
    try (socket) {
      // A client that opens a socket and sends nothing must not hold a thread for ever.
      socket.setSoTimeout((int) handshakeTimeout.toMillis());
      InputStream in = new BufferedInputStream(socket.getInputStream());
      OutputStream out = socket.getOutputStream();
      Result<HandshakeRequest, HandshakeRefusal> handshake = Handshake.read(in, allowedOrigin);
      switch (handshake) {
        case Result.Error<HandshakeRequest, HandshakeRefusal>(HandshakeRefusal refusal) ->
            out.write(Handshake.refused(refusal, retryAfterSeconds));
        case Result.Success<HandshakeRequest, HandshakeRefusal>(HandshakeRequest request) ->
            open(request, in, out);
      }
    } catch (IOException ended) {
      LOG.debug("websocket ended: {}", ended.toString());
    } finally {
      parts.metrics().recordHandlerThread(false);
    }
  }

  private void open(HandshakeRequest request, InputStream in, OutputStream out) throws IOException {
    Optional<FeedPosition> position = positionOf(request.rawQuery());
    if (position.isEmpty()) {
      out.write(
          Handshake.refused(new HandshakeRefusal(BAD_REQUEST, "after=<position> is required"), 0));
      return;
    }
    Optional<ConnectionLimit.Ticket> admitted =
        parts.limit().admit(DeliveryMethod.WEBSOCKET, this::closeQuietly);
    if (admitted.isEmpty()) {
      out.write(Handshake.refused(ServiceUnavailable.refusal(), retryAfterSeconds));
      return;
    }
    try (ConnectionLimit.Ticket _ = admitted.get()) {
      out.write(Handshake.accepted(request));
      out.flush();
      socket.setSoTimeout((int) heartbeat.multipliedBy(MISSED_HEARTBEATS_BEFORE_CLOSE).toMillis());
      Subscriber subscriber = parts.feed().subscribe(position.get());
      Thread reader = Thread.ofVirtual().name("ws-reader").start(() -> readFrames(in, subscriber));
      try {
        writeFrames(out, subscriber);
      } catch (InterruptedException dropped) {
        Thread.currentThread().interrupt();
      } finally {
        parts.feed().unsubscribe(subscriber);
        closeQuietly();
        reader.interrupt();
      }
    }
  }

  private void writeFrames(OutputStream out, Subscriber subscriber)
      throws IOException, InterruptedException {
    while (true) {
      Delivery delivery = subscriber.next(heartbeat);
      CloseCode close = closeDue.get();
      if (close != null) {
        write(subscriber, out, FrameCodec.encodeClose(close), 0);
        return;
      }
      byte[] pong = pongDue.getAndSet(null);
      if (pong != null) {
        write(subscriber, out, FrameCodec.encode(Frame.PONG, pong), 0);
      }
      switch (delivery) {
        case Delivery.Batch batch -> write(subscriber, out, frames(batch), batch.changes().size());
        case Delivery.Idle idle ->
            write(subscriber, out, FrameCodec.encode(Frame.PING, PING_PAYLOAD), 0);
        case Delivery.Woken woken -> {
          // The pong or close above was the reason.
        }
        case Delivery.Reload reload -> {
          byte[] message = parts.json().bytes(parts.json().reload(parts.feed().position()));
          ByteArrayOutputStream frames = new ByteArrayOutputStream();
          frames.writeBytes(FrameCodec.encode(Frame.TEXT, message));
          frames.writeBytes(FrameCodec.encodeClose(CloseCode.NORMAL));
          write(subscriber, out, frames.toByteArray(), 0);
          parts.metrics().recordReloadSent(DeliveryMethod.WEBSOCKET);
          return;
        }
      }
    }
  }

  private void readFrames(InputStream in, Subscriber subscriber) {
    try {
      while (true) {
        Frame frame = FrameCodec.read(in, MAX_CLIENT_PAYLOAD);
        switch (frame.opcode()) {
          case Frame.PING -> {
            pongDue.set(frame.payload());
            subscriber.wake();
          }
          case Frame.PONG -> {
            // Any frame resets the socket timeout; a pong needs nothing more.
          }
          case Frame.CLOSE -> {
            closeDue.set(CloseCode.NORMAL);
            subscriber.wake();
            return;
          }
          default ->
              throw new ProtocolViolation(
                  CloseCode.UNSUPPORTED_DATA, "the feed accepts no messages");
        }
      }
    } catch (ProtocolViolation violation) {
      closeDue.set(violation.closeCode());
      subscriber.wake();
    } catch (SocketTimeoutException silent) {
      // Three heartbeats without a word: the peer is gone, or a middlebox dropped the link.
      closeDue.set(CloseCode.GOING_AWAY);
      subscriber.wake();
    } catch (IOException ended) {
      // The peer closed, or the socket was closed to drop the connection: end the writer too.
      closeQuietly();
      closeDue.set(CloseCode.GOING_AWAY);
      subscriber.wake();
    }
  }

  private byte[] frames(Delivery.Batch batch) {
    ByteArrayOutputStream frames = new ByteArrayOutputStream();
    for (SeatChange change : batch.changes()) {
      byte[] message = parts.json().bytes(parts.json().message(change, parts.layout()));
      frames.writeBytes(FrameCodec.encode(Frame.TEXT, message));
    }
    return frames.toByteArray();
  }

  private void write(Subscriber subscriber, OutputStream out, byte[] bytes, int changes)
      throws IOException {
    subscriber.beginWrite(Thread.currentThread());
    try {
      out.write(bytes);
      out.flush();
    } finally {
      subscriber.endWrite();
    }
    parts.metrics().recordDelivered(DeliveryMethod.WEBSOCKET, changes, bytes.length);
  }

  private static Optional<FeedPosition> positionOf(String rawQuery) {
    if (!rawQuery.startsWith(AFTER_PREFIX) || rawQuery.contains("&")) {
      return Optional.empty();
    }
    return FeedPosition.parse(rawQuery.substring(AFTER_PREFIX.length())).discardError();
  }

  private void closeQuietly() {
    try {
      socket.close();
    } catch (IOException alreadyBroken) {
      // Closing a broken socket can fail; there is nothing left to free.
      LOG.debug("closing a websocket failed: {}", alreadyBroken.toString());
    }
  }
}
