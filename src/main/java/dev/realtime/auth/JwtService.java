package dev.realtime.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and validates HS256 JWTs. Deliberately not using Spring Security's
 * OAuth2-resource-server machinery — that's built for validating tokens issued by an
 * external authorization server; here, this service both issues and validates its own
 * tokens, which is simpler to do directly with JJWT.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(
            @Value("${realtime.auth.jwt-secret}") String secret,
            @Value("${realtime.auth.jwt-expiry:24h}") Duration expiry) {
        // HS256 requires a key of at least 256 bits (32 bytes) — JJWT throws
        // WeakKeyException at first use if the configured secret is shorter.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = expiry;
    }

    public String issue(Long userId, Long tenantId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException if the token is malformed, expired, or signed with a different key. */
    public AuthenticatedPrincipal validate(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = Long.valueOf(claims.getSubject());
        Long tenantId = ((Number) claims.get("tenantId")).longValue();
        return new AuthenticatedPrincipal(userId, tenantId);
    }
}
