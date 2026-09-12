package dev.realtime.auth;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Wraps either a raw, not-yet-validated bearer token, or (after validation) the resulting principal. */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principalOrToken;

    /** Pre-authentication: just the raw token extracted from the request. */
    public JwtAuthenticationToken(String rawToken) {
        super(List.of());
        this.principalOrToken = rawToken;
        setAuthenticated(false);
    }

    /** Post-authentication: the validated principal, after {@code JwtService.validate} succeeded. */
    public JwtAuthenticationToken(AuthenticatedPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.principalOrToken = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return isAuthenticated() ? null : principalOrToken;
    }

    @Override
    public Object getPrincipal() {
        return principalOrToken;
    }
}
