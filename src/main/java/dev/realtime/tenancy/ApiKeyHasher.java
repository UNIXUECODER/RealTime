package dev.realtime.tenancy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API keys are high-entropy random secrets, not human-chosen passwords — unlike user
 * passwords (see {@code AuthService}, which uses BCrypt specifically because passwords
 * are guessable and need deliberate slowness), a fast SHA-256 hash is standard,
 * sufficient practice here. Same hashing pattern as {@code dev.realtime.ingest.Fingerprint}.
 */
public final class ApiKeyHasher {

    private static final String PREFIX = "rtk_"; // "realtime key" — scan-recognizable, same convention as Stripe/GitHub tokens

    private ApiKeyHasher() {
    }

    public static String generate() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
