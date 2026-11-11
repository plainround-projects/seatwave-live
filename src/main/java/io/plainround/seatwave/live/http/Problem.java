package io.plainround.seatwave.live.http;

/** The kinds of problem the feed answers with, each with its status and title. */
public enum Problem {
  /** A parameter or header breaks the feed's contract. */
  INVALID_REQUEST(400, "invalid-request", "The request does not match the feed's contract"),
  /** No such event, or no such path. */
  NOT_FOUND(404, "not-found", "Nothing is published at this path"),
  /** The path exists but not for this method. */
  METHOD_NOT_ALLOWED(405, "method-not-allowed", "This path does not allow the method"),
  /** The connection limit is reached; try again after Retry-After. */
  FEED_FULL(503, "feed-full", "The feed has as many open connections as it allows");

  private static final String BASE = "https://seatwave.example/problems/";

  private final int status;
  private final String slug;
  private final String title;

  Problem(int status, String slug, String title) {
    this.status = status;
    this.slug = slug;
    this.title = title;
  }

  /**
   * The HTTP status code.
   *
   * @return the code
   */
  public int status() {
    return status;
  }

  /**
   * The problem's type URI.
   *
   * @return the URI as text
   */
  public String type() {
    return BASE + slug;
  }

  /**
   * The problem's title.
   *
   * @return the title
   */
  public String title() {
    return title;
  }
}
