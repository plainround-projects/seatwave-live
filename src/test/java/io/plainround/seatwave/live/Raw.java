package io.plainround.seatwave.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** A raw TCP client for tests that must see the exact bytes a server sends. */
public final class Raw implements AutoCloseable {

  private final Socket socket;

  private Raw(Socket socket) {
    this.socket = socket;
  }

  public static Raw connect(int port) throws IOException {
    Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
    socket.setSoTimeout(5_000);
    return new Raw(socket);
  }

  public void send(String text) throws IOException {
    socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  public void sendBytes(byte[] bytes) throws IOException {
    socket.getOutputStream().write(bytes);
    socket.getOutputStream().flush();
  }

  /** Reads until the text appears or the stream ends; returns everything read. */
  public String readUntil(String text) throws IOException {
    ByteArrayOutputStream read = new ByteArrayOutputStream();
    InputStream in = socket.getInputStream();
    try {
      while (!read.toString(StandardCharsets.UTF_8).contains(text)) {
        int value = in.read();
        if (value < 0) {
          break;
        }
        read.write(value);
      }
    } catch (SocketTimeoutException timedOut) {
      read.writeBytes(("<timeout>").getBytes(StandardCharsets.US_ASCII));
    }
    return read.toString(StandardCharsets.UTF_8);
  }

  public InputStream input() throws IOException {
    return socket.getInputStream();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
