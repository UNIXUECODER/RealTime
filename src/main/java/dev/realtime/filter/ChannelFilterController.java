package dev.realtime.filter;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Temporary endpoint for configuring channel filter rules directly — stands in for the
 * "seed data or a raw insert" the roadmap calls for at M3, since there's no persisted
 * channel concept yet (M5). Not authenticated, not validated beyond basic shape;
 * replaced by the real dashboard/API in M5/M6.
 */
@RestController
@RequestMapping("/channels/{channelId}/filters")
public class ChannelFilterController {

    private final ChannelFilterStore store;

    public ChannelFilterController(ChannelFilterStore store) {
        this.store = store;
    }

    @PutMapping
    public Mono<Void> setRules(@PathVariable String channelId, @RequestBody List<FilterRule> rules) {
        return Mono.fromRunnable(() -> store.setRules(channelId, rules));
    }

    @GetMapping
    public Mono<List<FilterRule>> getRules(@PathVariable String channelId) {
        return Mono.just(store.getRules(channelId));
    }

    @DeleteMapping
    public Mono<Void> clearRules(@PathVariable String channelId) {
        return Mono.fromRunnable(() -> store.clear(channelId));
    }
}
