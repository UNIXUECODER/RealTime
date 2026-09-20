package dev.realtime.archive;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.auth.CurrentTenant;
import dev.realtime.tenancy.ChannelService;

import reactor.core.publisher.Mono;

/**
 * {@code since} is a Redis Stream ID, the same format used throughout the system
 * (WS resume's {@code last_id}, {@code ArchivedEvent.redisStreamId}) — one consistent
 * position marker across the whole stack, hot or cold.
 *
 * <p>M6a adds tenant ownership verification (spec §10) — a plain {@code @RestController}
 * path, unlike the WebSocket handler, so an ordinary {@code Authorization: Bearer}
 * header works fine here; no query-param workaround needed.
 */
@RestController
@RequestMapping("/channels/{channelId}/events")
public class ReplayController {

    private final ReplayService replayService;
    private final ChannelService channelService;
    private final CurrentTenant currentTenant;

    public ReplayController(ReplayService replayService, ChannelService channelService, CurrentTenant currentTenant) {
        this.replayService = replayService;
        this.channelService = channelService;
        this.currentTenant = currentTenant;
    }

    @GetMapping
    public Mono<List<ReplayedEvent>> replay(
            @PathVariable String channelId,
            @RequestParam String since) {
        return currentTenant.id()
                .flatMap(tenantId -> channelService.requireOwnedChannel(tenantId, channelId))
                .then(Mono.defer(() -> {
                    // Write-time guard (F-08): without it, a malformed `since` reached
                    // StreamIds.parse's Long.parseLong uncaught, surfacing as an opaque
                    // 500 instead of a 400 naming the actual problem.
                    if (!StreamIds.isValid(since)) {
                        String sanitized = since.length() > 50 ? since.substring(0, 50) + "..." : since;
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid stream ID format: " + sanitized);
                    }
                    return replayService.replay(channelId, since);
                }));
    }
}

