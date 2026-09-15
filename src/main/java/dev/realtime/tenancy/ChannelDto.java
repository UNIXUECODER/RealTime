package dev.realtime.tenancy;

/** {@code apiKeySuffix} is the last 4 characters of the channel's active key, for
 * masked display (e.g. "rtk_...a1b2") — never the full key. See V4 migration. */
public record ChannelDto(String channelId, String name, String createdAt, String apiKeySuffix) {
}
