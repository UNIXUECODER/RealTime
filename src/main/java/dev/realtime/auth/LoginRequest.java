package dev.realtime.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=%s, password=REDACTED]".formatted(email);
    }
}

