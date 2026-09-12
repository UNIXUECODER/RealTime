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
 *   <li><b>Dashboard/management path</b> ({@code /channels/**}, minus the sub-paths
 *       noted below): JWT bearer auth, handled here by Spring Security.</li>
 *   <li><b>Ingest path</b> ({@code /webhook/**}): per-channel API key, checked as
 *       ordinary application logic in {@code WebhookAuthService} — not modeled as a
 *       Spring Security {@code Authentication} at all, since it's a fundamentally
 *       different shape (one secret per channel, not per user) and forcing it into the
 *       same abstraction wouldn't simplify anything.</li>
 * </ul>
 *
 * <p><b>M5 scope boundary (spec §14, item 5):</b> {@code /ws/**}, {@code
 * /channels/*&#47;events}, and {@code /channels/*&#47;filters} are explicitly permitted
 * through unauthenticated — matching their behavior in every milestone before this one.
 * Closing this gap is M6 work, once the dashboard needs one coherent security story.
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
                        .pathMatchers("/ws/**").permitAll()
                        .pathMatchers("/channels/*/filters/**").permitAll()
                        .pathMatchers("/channels/*/events").permitAll()
                        // Order matters — the specific sub-path permits above must come
                        // first; Spring Security evaluates these in declaration order
                        // and stops at the first match.
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
