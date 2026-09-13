package dev.realtime.tenancy;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.realtime.auth.CurrentTenant;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/channels")
public class ChannelController {

    private final ChannelService channelService;
    private final CurrentTenant currentTenant;

    public ChannelController(ChannelService channelService, CurrentTenant currentTenant) {
        this.channelService = channelService;
        this.currentTenant = currentTenant;
    }

    @PostMapping
    public Mono<CreateChannelResponse> create(@Valid @RequestBody CreateChannelRequest request) {
        return currentTenant.id().flatMap(tenantId -> channelService.create(tenantId, request.name()));
    }

    @GetMapping
    public Mono<List<ChannelDto>> list() {
        return currentTenant.id().flatMap(channelService::listForTenant);
    }

    @GetMapping("/{channelId}")
    public Mono<ChannelDto> get(@PathVariable String channelId) {
        return currentTenant.id().flatMap(tenantId -> channelService.get(tenantId, channelId));
    }

    @DeleteMapping("/{channelId}")
    public Mono<Void> delete(@PathVariable String channelId) {
        return currentTenant.id().flatMap(tenantId -> channelService.delete(tenantId, channelId));
    }
}

