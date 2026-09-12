package dev.realtime.archive;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * {@code since} is a Redis Stream ID, the same format used throughout the system
 * (WS resume's {@code last_id}, {@code ArchivedEvent.redisStreamId}) — one consistent
 * position marker across the whole stack, hot or cold.
 */
@RestController
@RequestMapping("/channels/{channelId}/events")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @GetMapping
    public Mono<List<ReplayedEvent>> replay(
            @PathVariable String channelId,
            @RequestParam String since) {
        return replayService.replay(channelId, since);
    }
}
