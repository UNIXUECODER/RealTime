package dev.realtime.archive;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XTrimOptions;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Enforces the Redis Stream hot-retention window via a scheduled {@code XTRIM MINID}
 * sweep — the real "two-tier trim" from spec §3 (a statable duration, not just a rough
 * entry-count cap). Uses {@code RedisStreamCommands.XTrimOptions}/{@code TrimOptions},
 * added to Spring Data Redis's reactive API in 4.1 — verified against a real Redis
 * instance before shipping, given how recent this API is.
 *
 * <p>Approximate trimming ({@code .approximate()}, Redis's own "~" flag) rather than
 * exact: lets Redis evict in whole radix-tree macro-nodes instead of guaranteeing a
 * byte-precise cutoff, the right tradeoff for a periodic background sweep where being
 * off by a few entries at the boundary doesn't matter.
 *
 * <p>v1 limitation (deliberate, flagged): trims every channel it knows about via
 * {@link ChannelRegistry}, which only tracks channels seen since this instance started
 * (in-memory, like {@link dev.realtime.filter.ChannelFilterStore}) — a real registry of
 * every channel that ever existed arrives with M5's persisted channels.
 */
@Component
public class RetentionTrimmer {

    private static final Logger log = LoggerFactory.getLogger(RetentionTrimmer.class);

    private final ReactiveRedisTemplate<String, String> redis;
    private final ChannelRegistry channelRegistry;
    private final Duration hotWindow;

    public RetentionTrimmer(
            ReactiveRedisTemplate<String, String> redis,
            ChannelRegistry channelRegistry,
            @Value("${realtime.archive.hot-window:24h}") Duration hotWindow) {
        this.redis = redis;
        this.channelRegistry = channelRegistry;
        this.hotWindow = hotWindow;
    }

    @Scheduled(fixedDelayString = "${realtime.archive.trim-interval:1h}")
    public void trim() {
        Flux.fromIterable(channelRegistry.knownChannelIds())
                .flatMap(this::trimChannel)
                .subscribe();
    }

    private Mono<Long> trimChannel(String channelId) {
        String streamKey = "stream:channel:" + channelId;
        RecordId minId = RecordId.of(Instant.now().minus(hotWindow).toEpochMilli(), 0);
        XTrimOptions options = XTrimOptions.of(TrimOptions.minId(minId).approximate());

        return redis.opsForStream().trim(streamKey, options)
                .doOnNext(removed -> {
                    if (removed > 0) {
                        log.debug("Trimmed {} entr{} from {}", removed, removed == 1 ? "y" : "ies", streamKey);
                    }
                })
                .onErrorResume(error -> {
                    log.error("Trim failed for {}", streamKey, error);
                    return Mono.empty();
                });
    }
}
