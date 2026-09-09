package dev.realtime.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the default idempotency fingerprint: SHA-256 of {@code (channelId, rawBody)}.
 *
 * <p>This is the fallback used when a channel has no configured {@code dedup_field}
 * override (spec §4) — the override path (a sender-provided idempotency key or a
 * JSONPath into the body) lands once channels are a real, persisted concept (M5).
 * Until then, every channel uses this default: zero cooperation required from the
 * sender, catches the common case — a byte-identical retry — automatically.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(String channelId, String rawBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(channelId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0); // separator — avoids ("ab","c") colliding with ("a","bc")
            byte[] hash = digest.digest(rawBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm on every conformant JVM — this branch
            // is unreachable in practice, but the checked exception has to go somewhere.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
