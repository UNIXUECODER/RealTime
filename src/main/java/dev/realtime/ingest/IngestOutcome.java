package dev.realtime.ingest;

/** Result of an ingest attempt — all three still return 202 to the sender (spec §6); the
 * distinction exists for logging/observability, not for the HTTP response shape. */
public enum IngestOutcome {
    ACCEPTED,
    DUPLICATE,
    FILTERED
}
