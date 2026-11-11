package io.plainround.seatwave.live.feed;

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The feed's counters and gauges, rendered in the Prometheus text format at {@code /metrics}.
 * Labels are the delivery method only, never a client or a seat, so the number of series stays
 * fixed.
 *
 * <p>Thread-safe: every value is an atomic counter or adder.
 */
public final class FeedMetrics {

  private final LongAdder changesPublished = new LongAdder();
  private final LongAdder overflows = new LongAdder();
  private final LongAdder stuckDisconnects = new LongAdder();
  private final LongAdder longPollReplies = new LongAdder();
  private final AtomicInteger handlerThreads = new AtomicInteger();
  private final AtomicInteger deepestQueue = new AtomicInteger();
  private final Map<DeliveryMethod, AtomicInteger> openConnections = perMethod(AtomicInteger::new);
  private final Map<DeliveryMethod, LongAdder> connectionsAccepted = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> connectionsRefused = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> changesDelivered = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> reloadsSent = perMethod(LongAdder::new);
  private final Map<DeliveryMethod, LongAdder> bytesWritten = perMethod(LongAdder::new);

  /** Counts one change numbered by the feed. */
  public void recordPublished() {
    changesPublished.increment();
  }

  /** Counts one subscriber whose bounded queue filled, so it was told to reload. */
  public void recordOverflow() {
    overflows.increment();
  }

  /** Counts one connection dropped because a single write stayed blocked too long. */
  public void recordStuckDisconnect() {
    stuckDisconnects.increment();
  }

  /**
   * Remembers the deepest any subscriber's queue has been, to show the bound holds.
   *
   * @param depth the queue's length after an offer
   */
  public void recordQueueDepth(int depth) {
    deepestQueue.accumulateAndGet(depth, Math::max);
  }

  /** Counts one long-poll reply sent, with or without changes. */
  public void recordLongPollReply() {
    longPollReplies.increment();
  }

  /**
   * Counts changes and bytes written to one client.
   *
   * @param method how they were delivered
   * @param changes how many changes the write carried
   * @param bytes how many bytes the feed wrote for them, framing included
   */
  public void recordDelivered(DeliveryMethod method, int changes, long bytes) {
    changesDelivered.get(method).add(changes);
    bytesWritten.get(method).add(bytes);
  }

  /**
   * Counts one reload instruction sent.
   *
   * @param method how it was delivered
   */
  public void recordReloadSent(DeliveryMethod method) {
    reloadsSent.get(method).increment();
  }

  /**
   * Counts a connection admitted under the limit.
   *
   * @param method its delivery method
   */
  public void recordOpened(DeliveryMethod method) {
    connectionsAccepted.get(method).increment();
    openConnections.get(method).incrementAndGet();
  }

  /**
   * Counts a connection that ended.
   *
   * @param method its delivery method
   */
  public void recordClosed(DeliveryMethod method) {
    openConnections.get(method).decrementAndGet();
  }

  /**
   * Counts a connection refused because the limit was reached.
   *
   * @param method its delivery method
   */
  public void recordRefused(DeliveryMethod method) {
    connectionsRefused.get(method).increment();
  }

  /**
   * Counts a virtual thread that serves a connection, from start to end, because the JVM's own
   * thread count does not include virtual threads.
   *
   * @param running true when the thread starts, false when it ends
   */
  public void recordHandlerThread(boolean running) {
    if (running) {
      handlerThreads.incrementAndGet();
    } else {
      handlerThreads.decrementAndGet();
    }
  }

  /**
   * The connections open now for one method.
   *
   * @param method the method
   * @return the count
   */
  public int openConnections(DeliveryMethod method) {
    return openConnections.get(method).get();
  }

  /**
   * Renders every counter and the process's own numbers.
   *
   * @return Prometheus text, one sample per line
   */
  public String render() {
    StringBuilder text = new StringBuilder();
    line(text, "seatwave_feed_changes_published_total", changesPublished.sum());
    line(text, "seatwave_feed_queue_overflows_total", overflows.sum());
    line(text, "seatwave_feed_stuck_writer_disconnects_total", stuckDisconnects.sum());
    line(text, "seatwave_feed_long_poll_replies_total", longPollReplies.sum());
    line(text, "seatwave_feed_handler_virtual_threads", handlerThreads.get());
    line(text, "seatwave_feed_deepest_client_queue", deepestQueue.get());
    for (DeliveryMethod method : DeliveryMethod.values()) {
      String label = "{method=\"" + method.label() + "\"}";
      line(text, "seatwave_feed_open_connections" + label, openConnections.get(method).get());
      line(text, "seatwave_feed_connections_total" + label, connectionsAccepted.get(method).sum());
      line(text, "seatwave_feed_refused_total" + label, connectionsRefused.get(method).sum());
      line(
          text,
          "seatwave_feed_changes_delivered_total" + label,
          changesDelivered.get(method).sum());
      line(text, "seatwave_feed_reloads_sent_total" + label, reloadsSent.get(method).sum());
      line(text, "seatwave_feed_bytes_written_total" + label, bytesWritten.get(method).sum());
    }
    appendProcess(text);
    return text.toString();
  }

  private static void appendProcess(StringBuilder text) {
    Runtime runtime = Runtime.getRuntime();
    line(text, "process_platform_threads", ManagementFactory.getThreadMXBean().getThreadCount());
    line(text, "process_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory());
    line(text, "process_available_processors", runtime.availableProcessors());
    OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean();
    if (system instanceof com.sun.management.OperatingSystemMXBean process) {
      line(text, "process_cpu_nanoseconds_total", process.getProcessCpuTime());
    }
    if (system instanceof UnixOperatingSystemMXBean unix) {
      line(text, "process_open_fds", unix.getOpenFileDescriptorCount());
      line(text, "process_max_fds", unix.getMaxFileDescriptorCount());
    }
  }

  private static void line(StringBuilder text, String name, long value) {
    // The text format ends every line with a line feed on every platform, never with %n's
    // platform separator, which is a carriage return and a line feed on Windows.
    text.append(String.format(Locale.ROOT, "%s %d\n", name, value));
  }

  private static <T> Map<DeliveryMethod, T> perMethod(java.util.function.Supplier<T> maker) {
    Map<DeliveryMethod, T> values = new EnumMap<>(DeliveryMethod.class);
    for (DeliveryMethod method : DeliveryMethod.values()) {
      values.put(method, maker.get());
    }
    return values;
  }
}
