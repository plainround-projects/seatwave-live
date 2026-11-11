package io.plainround.seatwave.live.wire;

/**
 * The whole seat map as JSON.
 *
 * @param eventId the event
 * @param position the feed position of the last change the map includes
 * @param sections how many sections, lettered from A
 * @param seatsPerSection how many seats each section has
 * @param states one letter per seat ({@code a} available, {@code h} held, {@code s} sold), section
 *     by section, then by seat number
 */
public record SnapshotView(
    int eventId, String position, int sections, int seatsPerSection, String states) {}
