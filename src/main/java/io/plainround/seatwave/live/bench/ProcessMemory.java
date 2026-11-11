package io.plainround.seatwave.live.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * The resident memory of a process, from the operating system's {@code ps}, because the JVM reports
 * only its heap: thread stacks, socket buffers and other native memory are counted only here.
 * Stateless.
 */
public final class ProcessMemory {

  private static final long BYTES_PER_KIB = 1_024;
  private static final long WAIT_SECONDS = 5;

  private ProcessMemory() {}

  /**
   * Reads a process's resident set size.
   *
   * @param processId the process
   * @return the bytes in memory, or empty if {@code ps} could not tell
   * @throws InterruptedException if interrupted while waiting for {@code ps}
   */
  public static OptionalLong residentBytes(long processId) throws InterruptedException {
    // The id is a number this program got from the JDK, passed as its own argument: no shell, no
    // text joined from input.
    List<String> command = List.of("ps", "-o", "rss=", "-p", Long.toString(processId));
    try {
      Process ps = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output =
          new String(ps.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
      if (!ps.waitFor(WAIT_SECONDS, TimeUnit.SECONDS) || ps.exitValue() != 0 || output.isEmpty()) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(Long.parseLong(output) * BYTES_PER_KIB);
    } catch (IOException | NumberFormatException unreadable) {
      return OptionalLong.empty();
    }
  }
}
