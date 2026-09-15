package dev.realtime.tenancy;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.auth.CurrentTenant;
import dev.realtime.ingest.JsonValidator;

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

    /**
     * Backs the dashboard's "send test event" button (M6b) — JWT + ownership
     * authenticated via {@link ChannelService#sendTestEvent}, not the channel's own API
     * key, since the raw key is shown once at creation and is never retrievable again.
     * An empty body falls back to a canned payload with a fresh timestamp each time, so
     * repeated clicks on the untouched default never collide with the ingest
     * pipeline's own dedup window; a person deliberately resubmitting identical custom
     * JSON to test dedup behavior sees the real {@code DUPLICATE} outcome, not a
     * server-side workaround masking it.
     */
    @PostMapping("/{channelId}/test-event")
    public Mono<TestEventResponse> sendTestEvent(
            @PathVariable String channelId,
            @RequestBody(required = false) Mono<String> body) {
        return currentTenant.id().flatMap(tenantId ->
                (body == null ? Mono.just("") : body.defaultIfEmpty(""))
                        .map(payload -> payload.isBlank() ? defaultTestPayload() : payload)
                        .flatMap(payload -> {
                            if (!JsonValidator.isValid(payload)) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Test event body must be valid JSON"));
                            }
                            return channelService.sendTestEvent(tenantId, channelId, payload);
                        }))
                .map(TestEventResponse::new);
    }

    private String defaultTestPayload() {
        return "{\"type\":\"test.event\",\"message\":\"Hello from RealTime!\",\"sentAt\":\"" + Instant.now() + "\"}";
    }
}

