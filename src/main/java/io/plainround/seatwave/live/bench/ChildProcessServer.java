package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.ServeCli;
import io.plainround.seatwave.live.config.LiveSettings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A feed node in a JVM of its own, started with the same {@code java} and class path as this one,
 * so its threads, heap and CPU time are the server's alone. It is ready when it prints its first
 * line.
 */
public final class ChildProcessServer implements ServerHandle {

  private static final String READY = "SeatWave live feed on";
  private static final Duration STOP_WAIT = Duration.ofSeconds(10);

  private final Process process;
  private final LiveSettings settings;

  private ChildProcessServer(Process process, LiveSettings settings) {
    this.process = process;
    this.settings = settings;
  }

  /**
   * Starts the node and waits until it is ready.
   *
   * @param settingsFile the node's settings file, which must enable the admin routes
   * @param settings the same settings, already read, for the ports
   * @param heapMegabytes the node's largest heap
   * @param logFile where the node's standard error goes
   * @return the running server
   * @throws IOException if the process cannot start or is not ready in time
   * @throws InterruptedException if interrupted while waiting
   */
  public static ChildProcessServer start(
      Path settingsFile, LiveSettings settings, int heapMegabytes, Path logFile)
      throws IOException, InterruptedException {
    Objects.requireNonNull(settingsFile, "settingsFile");
    Objects.requireNonNull(settings, "settings");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    List<String> command =
        List.of(
            java,
            // A fixed heap that is touched at start, so the resident memory measured later grows
            // only with what connections add outside the heap, not with the heap filling in.
            "-Xms" + heapMegabytes + "m",
            "-Xmx" + heapMegabytes + "m",
            "-XX:+AlwaysPreTouch",
            "-cp",
            System.getProperty("java.class.path"),
            ServeCli.class.getName(),
            settingsFile.toString());
    Process process =
        new ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            .start();
    CompletableFuture<String> firstLine =
        CompletableFuture.supplyAsync(() -> readFirstLine(process));
    try {
      String line = firstLine.get(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
      if (!line.startsWith(READY)) {
        process.destroyForcibly();
        throw new IOException("the feed process did not start: " + line + "; see " + logFile);
      }
    } catch (ExecutionException | TimeoutException notReady) {
      process.destroyForcibly();
      throw new IOException("the feed process was not ready in time; see " + logFile, notReady);
    }
    return new ChildProcessServer(process, settings);
  }

  @Override
  public int httpPort() {
    return settings.network().httpPort();
  }

  @Override
  public int webSocketPort() {
    return settings.network().websocketPort();
  }

  @Override
  public OptionalLong processId() {
    return OptionalLong.of(process.pid());
  }

  @Override
  public void close() throws IOException {
    process.destroy();
    try {
      if (!process.waitFor(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private static String readFirstLine(Process process) {
    try {
      BufferedReader output =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      String line = output.readLine();
      return line == null ? "(no output)" : line;
    } catch (IOException unreadable) {
      return "(unreadable output: " + unreadable.getMessage() + ")";
    }
  }
}
