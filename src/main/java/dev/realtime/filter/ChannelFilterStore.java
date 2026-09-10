package dev.realtime.filter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Temporary in-memory store for channel-level filter rules, keyed by channelId.
 *
 * <p>Not persisted, not tenant-scoped, lost on restart — a deliberate stand-in for the
 * "seed data or a raw insert" the roadmap allows for at M3, since there's no persisted
 * channel concept yet (that's M5). Rebuilding this on top of real, tenant-scoped
 * channels in M5 is expected, not a shortcut being smuggled in as permanent.
 */
@Component
public class ChannelFilterStore {

    private final Map<String, List<FilterRule>> rulesByChannel = new ConcurrentHashMap<>();

    public void setRules(String channelId, List<FilterRule> rules) {
        rulesByChannel.put(channelId, List.copyOf(rules));
    }

    public List<FilterRule> getRules(String channelId) {
        return rulesByChannel.getOrDefault(channelId, List.of());
    }

    public void clear(String channelId) {
        rulesByChannel.remove(channelId);
    }
}
