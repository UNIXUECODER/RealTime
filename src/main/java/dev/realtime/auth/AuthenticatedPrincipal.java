package dev.realtime.auth;

/** The claims extracted from a validated JWT — {@code tenantId} is what every tenant-scoped query keys off. */
public record AuthenticatedPrincipal(Long userId, Long tenantId) {
}
