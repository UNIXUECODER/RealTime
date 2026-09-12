package dev.realtime.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constructs {@link JwtService} directly with a fixed secret rather than via a Spring
 * context — this is pure logic (issue a token, validate it) with no need for Redis,
 * Postgres, or the web layer.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-key-that-is-at-least-32-bytes-long-for-hs256";

    private final JwtService jwtService = new JwtService(SECRET, Duration.ofHours(1));

    @Test
    void issuedTokenRoundTripsToTheSameUserAndTenant() {
        String token = jwtService.issue(42L, 7L);

        AuthenticatedPrincipal principal = jwtService.validate(token);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.tenantId()).isEqualTo(7L);
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtService differentSecret = new JwtService(
                "a-completely-different-secret-key-also-at-least-32-bytes-long",
                Duration.ofHours(1));

        String token = differentSecret.issue(1L, 1L);

        assertThatThrownBy(() -> jwtService.validate(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        // Crafted directly with JJWT rather than via JwtService.issue(), which only
        // ever sets a future expiry — this deterministically tests the expired-token
        // path without needing to sleep past a real expiry window.
        String expiredToken = Jwts.builder()
                .subject("1")
                .claim("tenantId", 1L)
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(2))))
                .expiration(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService.validate(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
