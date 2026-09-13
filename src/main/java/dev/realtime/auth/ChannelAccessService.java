package dev.realtime.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.tenancy.ChannelService;

import io.jsonwebtoken.JwtException;

import reactor.core.publisher.Mono;

/**
 * Bridges JWT auth into WebSocket handshakes specifically — browsers can't set custom
 * headers on a WS upgrade request, so the standard workaround is a token query param
 * instead of the {@code Authorization} header {@link BearerTokenServerAuthenticationConverter}
 * expects. Because of that, {@code /ws/**} stays {@code permitAll()} in
 * {@code SecurityConfig} (Security's header-based mechanism can't gate it anyway) and
 * this service does the check manually inside {@code ChannelWebSocketHandler} instead —
 * the same architectural pattern the webhook path already uses for its own API-key auth.
 */
@Component
public class ChannelAccessService {

    private final JwtService jwtService;
    private final ChannelService channelService;

    public ChannelAccessService(JwtService jwtService, ChannelService channelService) {
        this.jwtService = jwtService;
        this.channelService = channelService;
    }

    /** True only if the token is valid AND the channel belongs to that token's tenant. */
    public Mono<Boolean> hasAccess(String channelPublicId, String token) {
        if (token == null || token.isBlank()) {
            return Mono.just(false);
        }

        return Mono.fromCallable(() -> jwtService.validate(token))
                .onErrorResume(JwtException.class, e -> Mono.empty())
                .flatMap(principal -> channelService.requireOwnedChannel(principal.tenantId(), channelPublicId)
                        .map(channel -> true)
                        .onErrorResume(ResponseStatusException.class, e -> Mono.just(false)))
                .defaultIfEmpty(false);
    }
}
