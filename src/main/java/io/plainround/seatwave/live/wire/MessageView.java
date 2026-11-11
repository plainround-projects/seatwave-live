package io.plainround.seatwave.live.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One feed message as JSON, the same shape for all three methods: a seat change ({@code type} is
 * {@code seat}) or an instruction to reload the seat map ({@code type} is {@code reload}, and only
 * {@code position} is present).
 *
 * @param type {@code seat} or {@code reload}
 * @param position the feed position written as {@code <stream>.<sequence>}
 * @param seat the seat's label, such as {@code A-7}; absent in a reload
 * @param state {@code available}, {@code held} or {@code sold}; absent in a reload
 * @param changedAt when the store made the change, ISO-8601 in UTC; absent in a reload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageView(
    String type, String position, String seat, String state, String changedAt) {

  /** The type of a seat change. */
  public static final String SEAT = "seat";

  /** The type of a reload instruction. */
  public static final String RELOAD = "reload";
}
