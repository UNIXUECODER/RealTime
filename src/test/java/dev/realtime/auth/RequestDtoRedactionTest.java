package dev.realtime.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates M6a's password-redaction fix: Java records auto-generate a
 * {@code toString()} over every field, which would put plaintext passwords
 * into any log line or exception message. Both DTOs now override it.
 */
class RequestDtoRedactionTest {

    @Test
    void signupRequestRedactsPassword() {
        SignupRequest request = new SignupRequest("Acme Corp", "user@acme.com", "s3cret!");
        String str = request.toString();

        assertThat(str).contains("user@acme.com");
        assertThat(str).contains("Acme Corp");
        assertThat(str).contains("REDACTED");
        assertThat(str).doesNotContain("s3cret!");
    }

    @Test
    void loginRequestRedactsPassword() {
        LoginRequest request = new LoginRequest("user@acme.com", "p@ssw0rd");
        String str = request.toString();

        assertThat(str).contains("user@acme.com");
        assertThat(str).contains("REDACTED");
        assertThat(str).doesNotContain("p@ssw0rd");
    }
}
