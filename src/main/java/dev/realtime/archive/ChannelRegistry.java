package dev.realtime.archive;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Tracks channel IDs seen by this instance since startup — a temporary stand-in for a
 * real channel registry until M5's persisted channels exist, in the same spirit as
 * {@link dev.realtime.filter.ChannelFilterStore}. Used by {@link RetentionTrimmer} to
 * know which streams to sweep.
 */
@Component
public class ChannelRegistry {

    private final Set<String> knownChannelIds = ConcurrentHashMap.newKeySet();

    public void register(String channelId) {
        knownChannelIds.add(channelId);
    }

    public Set<String> knownChannelIds() {
        return Set.copyOf(knownChannelIds);
    }
}
