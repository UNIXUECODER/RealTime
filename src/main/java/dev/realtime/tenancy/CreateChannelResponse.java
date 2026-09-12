package dev.realtime.tenancy;

/** {@code apiKey} is the raw, unhashed key — shown exactly once, here, at creation. It's never retrievable again. */
public record CreateChannelResponse(String channelId, String name, String apiKey) {
}
