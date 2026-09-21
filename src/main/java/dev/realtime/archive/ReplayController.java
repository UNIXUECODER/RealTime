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
 *
 * <p>M6c (F-02) adds {@code limit}: previously unbounded, so a single request spanning a
 * large range could load the whole thing into heap. A client wanting more than one page
 * just re-calls with {@code since} set to the last ID it received — the existing
 * cursor-based model already supports this without an offset scheme.
 */
@RestController
@RequestMapping("/channels/{channelId}/events")
public class ReplayController {

    /** Page size when the client doesn't specify one. */
    public static final int DEFAULT_LIMIT = 500;

    /** Upper bound on requested page size — keeps one request from forcing an unbounded scan. */
    public static final int MAX_LIMIT = 5000;

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
            @RequestParam String since,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
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
                    // Write-time guard (F-02): rejected explicitly rather than silently
                    // clamped, matching how every other bad-input case in this codebase
                    // has been handled (filters, since above) — the client learns its
                    // request was wrong instead of quietly getting a different one served.
                    if (limit < 1 || limit > MAX_LIMIT) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "limit must be between 1 and %d (received %d)".formatted(MAX_LIMIT, limit));
                    }
                    return replayService.replay(channelId, since, limit);
                }));
    }
}

