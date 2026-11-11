package io.plainround.seatwave.live.bench;

import io.plainround.seatwave.live.feed.DeliveryMethod;
import java.util.Objects;

/**
 * Everything one run of one delivery method measured.
 *
 * @param method the delivery method
 * @param run the run's number, from 1
 * @param clients the clients started
 * @param connected the clients that had loaded the map when measuring began
 * @param delay the delivery delay of every change every client received while recording
 * @param load the rates and bytes during the measured phase
 * @param cost what the open connections cost the server
 * @param reconnect what happened when every connection was dropped at once
 * @param correctness whether every client ended with the store's seat map
 */
public record MethodResult(
    DeliveryMethod method,
    int run,
    int clients,
    int connected,
    LatencySummary delay,
    Load load,
    ServerCost cost,
    Reconnect reconnect,
    Correctness correctness) {

  /** Checks the fields. */
  public MethodResult {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(delay, "delay");
    Objects.requireNonNull(load, "load");
    Objects.requireNonNull(cost, "cost");
    Objects.requireNonNull(reconnect, "reconnect");
    Objects.requireNonNull(correctness, "correctness");
  }

  /**
   * Rates during the measured phase.
   *
   * @param changesPerSecond seat changes the store made per second
   * @param deliveriesPerSecond changes written to clients per second, all clients together
   * @param requestsPerSecond long-poll replies per second, or new push connections per second
   * @param bytesPerDelivery bytes the server wrote per delivered change (body and framing it writes
   *     itself; HTTP headers and chunk sizes added by the JDK server are not counted here)
   */
  public record Load(
      double changesPerSecond,
      double deliveriesPerSecond,
      double requestsPerSecond,
      double bytesPerDelivery) {}

  /**
   * The server's cost.
   *
   * @param cpuCores the server process's CPU time per second of the measured phase
   * @param cpuMicrosPerDelivery that CPU time divided by the changes delivered
   * @param heapBytesPerConnection live heap with every client connected and idle, less the heap
   *     with none, per client
   * @param residentBytesPerConnection the same for the process's resident memory; -1 when the
   *     server shares the process with the clients
   * @param platformThreads the server's platform threads with every client connected
   * @param virtualThreads the server's connection threads with every client connected
   * @param openFileDescriptors the server's open file descriptors with every client connected
   * @param maxFileDescriptors the server process's file-descriptor limit
   */
  public record ServerCost(
      double cpuCores,
      double cpuMicrosPerDelivery,
      long heapBytesPerConnection,
      long residentBytesPerConnection,
      int platformThreads,
      int virtualThreads,
      long openFileDescriptors,
      long maxFileDescriptors) {}

  /**
   * The drop of every connection at once.
   *
   * @param dropped connections the server dropped
   * @param recoveryMillis from the drop until every client had applied a change made after it
   * @param serverReconnects connections the server accepted in that time, for every method
   * @param clientReconnects reconnects the clients counted themselves
   * @param reloads full seat-map reloads in the whole run
   */
  public record Reconnect(
      long dropped,
      long recoveryMillis,
      long serverReconnects,
      long clientReconnects,
      long reloads) {}

  /**
   * The end-of-run check against the store.
   *
   * @param gaps changes that arrived after a missing one, all clients together
   * @param duplicates changes received twice
   * @param correctMaps clients whose final map equalled the store's
   */
  public record Correctness(long gaps, long duplicates, int correctMaps) {}
}
