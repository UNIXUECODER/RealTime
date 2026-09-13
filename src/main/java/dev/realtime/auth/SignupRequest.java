package dev.realtime.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignupRequest(
        @NotBlank String tenantName,
        @NotBlank @Email String email,
        @NotBlank String password) {

    // Records auto-generate toString() over every field — without this override, a
    // stray log statement or exception message anywhere this object passes through
    // would put the plaintext password straight into the logs.
    @Override
    public String toString() {
        return "SignupRequest[tenantName=%s, email=%s, password=REDACTED]".formatted(tenantName, email);
    }
}

