package dev.realtime.auth;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Extracts the tenant ID from the validated JWT in the security context — never from a
 * client-supplied field (spec §10). Used by every tenant-scoped controller
 * ({@code ChannelController}, and as of M6a, {@code ReplayController} and
 * {@code ChannelFilterController}) — extracted here once these three needed the
 * identical three lines.
 */
@Component
public class CurrentTenant {

    public Mono<Long> id() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (AuthenticatedPrincipal) ctx.getAuthentication().getPrincipal())
                .map(AuthenticatedPrincipal::tenantId);
    }
}
