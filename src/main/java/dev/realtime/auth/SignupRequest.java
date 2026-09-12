package dev.realtime.auth;

public record SignupRequest(String tenantName, String email, String password) {
}
