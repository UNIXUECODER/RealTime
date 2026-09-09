package dev.realtime.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Core of M1: get an event into the channel's Redis Stream, exactly once, with zero
 * cooperation required from the sender.
 *
 * <p>Two Redis operations, both intentional:
 * <ol>
 *   <li>{@code SET fingerprint:{hash} 1 NX EX <ttl>} — atomic check-and-set. If this key
 *       already existed, the event is a duplicate (a retried webhook delivery) and is
 *       deliberately <em>not</em> re-added (spec §4).</li>
 *   <li>{@code XADD stream:channel:{id} * payload <body> received_at <instant>} — the
 *       single source of truth for both live fan-out (M2) and replay (M4).</li>
 * </ol>
 */
@Service
public class IngestService {

    private final ReactiveRedisTemplate<String, String> redis;
    private final Duration dedupTtl;

    public IngestService(
            ReactiveRedisTemplate<String, String> redis,
            @Value("${realtime.ingest.dedup-ttl}") Duration dedupTtl) {
        this.redis = redis;
        this.dedupTtl = dedupTtl;
    }

    /**
     * Ingests one webhook payload for a channel.
     *
     * @return {@code true} if this was a new event (written to the stream), {@code false}
     *         if it was a duplicate fingerprint within the dedup window and was
     *         intentionally not re-added.
     */
    public Mono<Boolean> ingest(String channelId, String rawBody) {
        String dedupKey = "fingerprint:" + Fingerprint.of(channelId, rawBody);

        return redis.opsForValue()
                .setIfAbsent(dedupKey, "1", dedupTtl)
                .flatMap(isNew -> isNew
                        ? appendToStream(channelId, rawBody).thenReturn(true)
                        : Mono.just(false));
    }

    private Mono<?> appendToStream(String channelId, String rawBody) {
        String streamKey = "stream:channel:" + channelId;
        Map<String, String> fields = Map.of(
                "payload", rawBody,
                "received_at", Instant.now().toString());

        return redis.opsForStream().add(streamKey, fields);
    }
}
