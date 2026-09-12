package dev.realtime.tenancy;

import java.util.List;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.realtime.auth.AuthenticatedPrincipal;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/channels")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @PostMapping
    public Mono<CreateChannelResponse> create(@RequestBody CreateChannelRequest request) {
        return currentTenantId().flatMap(tenantId -> channelService.create(tenantId, request.name()));
    }

    @GetMapping
    public Mono<List<ChannelDto>> list() {
        return currentTenantId().flatMap(channelService::listForTenant);
    }

    @GetMapping("/{channelId}")
    public Mono<ChannelDto> get(@PathVariable String channelId) {
        return currentTenantId().flatMap(tenantId -> channelService.get(tenantId, channelId));
    }

    @DeleteMapping("/{channelId}")
    public Mono<Void> delete(@PathVariable String channelId) {
        return currentTenantId().flatMap(tenantId -> channelService.delete(tenantId, channelId));
    }

    /** Sourced from the validated JWT in the security context — never from a path/body field the client controls (spec §10). */
    private Mono<Long> currentTenantId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (AuthenticatedPrincipal) ctx.getAuthentication().getPrincipal())
                .map(AuthenticatedPrincipal::tenantId);
    }
}
