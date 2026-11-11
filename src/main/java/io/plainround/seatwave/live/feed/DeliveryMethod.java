package io.plainround.seatwave.live.feed;

/** The three ways the feed delivers seat changes, with the label used in metrics and reports. */
public enum DeliveryMethod {
  /** A request held open until there is news or a timeout, then asked again. */
  LONG_POLLING("long-polling"),
  /** One long HTTP response that carries events (the WHATWG event stream format). */
  SERVER_SENT_EVENTS("sse"),
  /** An upgraded two-way connection carrying frames (RFC 6455). */
  WEBSOCKET("websocket");

  private final String label;

  DeliveryMethod(String label) {
    this.label = label;
  }

  /**
   * The low-cardinality label used in metric names and reports.
   *
   * @return {@code long-polling}, {@code sse} or {@code websocket}
   */
  public String label() {
    return label;
  }
}
