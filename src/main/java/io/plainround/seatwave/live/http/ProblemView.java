package io.plainround.seatwave.live.http;

/**
 * An error body in the problem details format (RFC 9457), the one error shape of Module 7's version
 * 1 API.
 *
 * @param type a URI naming the kind of problem, under {@code https://seatwave.example/problems/}
 * @param title a short summary for people
 * @param status the HTTP status code
 * @param detail what went wrong with this request
 * @param instance a URN that also appears in the server's log line for the request
 */
public record ProblemView(String type, String title, int status, String detail, String instance) {}
