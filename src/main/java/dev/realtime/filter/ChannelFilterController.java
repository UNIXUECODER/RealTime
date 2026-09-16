package dev.realtime.filter;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.realtime.auth.CurrentTenant;
import dev.realtime.tenancy.ChannelService;

import reactor.core.publisher.Mono;

/**
 * Rules themselves still live in the temporary in-memory {@link ChannelFilterStore}
 * (spec §14 item 4, planned for M6b) — the storage layer hasn't changed. M6a adds
 * tenant ownership verification: {@code channelId} now has to correspond to a real
 * channel owned by the caller's tenant, the same standard every other {@code
 * /channels/**} endpoint now holds to. Ad-hoc test channel strings used before M6a
 * (e.g. "test", "m3-verify") will no longer pass this check.
 */
@RestController
@RequestMapping("/channels/{channelId}/filters")
public class ChannelFilterController {

    private final ChannelFilterStore store;
    private final ChannelService channelService;
    private final CurrentTenant currentTenant;
    private final FilterEngine filterEngine;

    public ChannelFilterController(
            ChannelFilterStore store,
            ChannelService channelService,
            CurrentTenant currentTenant,
            FilterEngine filterEngine) {
        this.store = store;
        this.channelService = channelService;
        this.currentTenant = currentTenant;
        this.filterEngine = filterEngine;
    }

    @PutMapping
    public Mono<Void> setRules(@PathVariable String channelId, @RequestBody(required = false) List<FilterRule> rules) {
        return verifyOwnership(channelId)
                .then(Mono.fromRunnable(() -> {
                    List<FilterRule> safeRules = rules == null ? List.of() : rules;
                    filterEngine.validate(safeRules);
                    store.setRules(channelId, safeRules);
                }));
    }

    @GetMapping
    public Mono<List<FilterRule>> getRules(@PathVariable String channelId) {
        return verifyOwnership(channelId)
                .then(Mono.fromCallable(() -> store.getRules(channelId)));
    }

    @DeleteMapping
    public Mono<Void> clearRules(@PathVariable String channelId) {
        return verifyOwnership(channelId)
                .then(Mono.fromRunnable(() -> store.clear(channelId)));
    }

    private Mono<Void> verifyOwnership(String channelId) {
        return currentTenant.id()
                .flatMap(tenantId -> channelService.requireOwnedChannel(tenantId, channelId))
                .then();
    }
}

