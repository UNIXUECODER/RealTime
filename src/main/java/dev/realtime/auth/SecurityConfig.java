package dev.realtime.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;

/**
 * Two distinct trust domains, per spec §10, handled by two distinct mechanisms:
 * <ul>
 *   <li><b>Dashboard/management path</b> ({@code /channels/**}, including replay and
 *       filter-config as of M6a): JWT bearer auth, handled here by Spring Security.
 *       Fine-grained tenant ownership (does *this* channel belong to *this* caller) is
 *       then checked at the service layer via {@code ChannelService.requireOwnedChannel}
 *       — Security only establishes "who is this," not "do they own this resource."</li>
 *   <li><b>Ingest path</b> ({@code /webhook/**}): per-channel API key, checked as
 *       ordinary application logic in {@code WebhookAuthService} — not modeled as a
 *       Spring Security {@code Authentication} at all, since it's a fundamentally
 *       different shape (one secret per channel, not per user) and forcing it into the
 *       same abstraction wouldn't simplify anything.</li>
 *   <li><b>WebSocket path</b> ({@code /ws/**}): JWT again, but via a {@code ?token=}
 *       query param rather than a header, since browsers can't set custom headers on a
 *       WS handshake. Security's header-based mechanism can't gate this path at all, so
 *       it stays {@code permitAll()} here and {@code ChannelAccessService} checks it
 *       manually inside {@code ChannelWebSocketHandler} instead — same architectural
 *       pattern as the webhook path's own API-key check.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveAuthenticationManager authenticationManager,
            BearerTokenServerAuthenticationConverter authenticationConverter) {

        AuthenticationWebFilter authenticationWebFilter = new AuthenticationWebFilter(authenticationManager);
        authenticationWebFilter.setServerAuthenticationConverter(authenticationConverter);

        return http
                // Stateless, token-based API — no session/cookie, so no CSRF surface.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/auth/**").permitAll()
                        .pathMatchers("/webhook/**").permitAll()
                        // WS can't carry a bearer header — permitAll() here is
                        // deliberate, not a gap; see class Javadoc and ChannelAccessService.
                        .pathMatchers("/ws/**").permitAll()
                        // As of M6a, every /channels/** path (CRUD, replay, filter-config
                        // alike) requires a valid JWT — no more sub-path carve-outs.
                        .pathMatchers("/channels/**").authenticated()
                        // Deny-by-default for anything not explicitly categorized above,
                        // rather than defaulting new/forgotten endpoints to open.
                        .anyExchange().authenticated())
                .addFilterAt(authenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
