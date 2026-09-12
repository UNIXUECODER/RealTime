package dev.realtime.archive;

/** One replayed event, regardless of whether it was actually served from Redis or Postgres. */
public record ReplayedEvent(String id, String payload, String receivedAt) {
}
