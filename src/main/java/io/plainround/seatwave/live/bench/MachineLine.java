package io.plainround.seatwave.live.bench;

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Locale;

/** The line every measuring tool prints first: the JDK, the system and this process's limits. */
public final class MachineLine {

  private MachineLine() {}

  /**
   * Describes this machine and process.
   *
   * @return for example {@code Java 25.0.2+10-LTS on Mac OS X aarch64, 12 processors; this process
   *     may open 10240 files}
   */
  public static String describe() {
    OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean();
    String files =
        system instanceof UnixOperatingSystemMXBean unix
            ? "; this process may open " + unix.getMaxFileDescriptorCount() + " files"
            : "";
    return String.format(
        Locale.ROOT,
        "Java %s on %s %s, %d processors%s",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        Runtime.getRuntime().availableProcessors(),
        files);
  }
}
