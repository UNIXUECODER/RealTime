package dev.realtime.web;

import java.time.Instant;

/**
 * Structured error body for every error response — {@code requestId} matches the
 * {@code X-Request-Id} response header (see {@link RequestIdWebFilter}) and the id
 * logged alongside the corresponding server-side log line (see
 * {@link GlobalErrorHandler}), so a client-reported failure and a server log entry can
 * always be tied together.
 */
public record ErrorResponse(
        String requestId,
        int status,
        String error,
        String message,
        Instant timestamp) {
}
