package dev.realtime.auth;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public class JwtReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    private final JwtService jwtService;

    public JwtReactiveAuthenticationManager(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String token = (String) authentication.getCredentials();
        // jwtService.validate(...) throws a JwtException subtype (expired, malformed,
        // wrong signature) for anything invalid — Mono.fromCallable turns that into an
        // error signal, which AuthenticationWebFilter maps to a 401 automatically.
        return Mono.fromCallable(() -> jwtService.validate(token))
                .map(JwtAuthenticationToken::new);
    }
}
