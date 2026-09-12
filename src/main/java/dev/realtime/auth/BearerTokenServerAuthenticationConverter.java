package dev.realtime.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** Extracts a raw bearer token from the Authorization header, if present. Absent header = no attempt at authentication, not an error — {@code authorizeExchange} decides per-path whether that's acceptable. */
@Component
public class BearerTokenServerAuthenticationConverter implements ServerAuthenticationConverter {

    private static final String PREFIX = "Bearer ";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(PREFIX)) {
            return Mono.empty();
        }
        return Mono.just(new JwtAuthenticationToken(header.substring(PREFIX.length())));
    }
}
