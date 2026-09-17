package dev.realtime.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;

import dev.realtime.archive.ArchivedEvent;
import dev.realtime.archive.ArchiveWriter;
import dev.realtime.filter.ChannelFilterStore;
import dev.realtime.filter.FilterEngine;
import dev.realtime.filter.FilterRule;

import reactor.core.publisher.Mono;

/**
 * Core of M1 (extended in M3 with filtering, M4 with archiving, M5 with real channel
 * validation upstream in {@link WebhookAuthService}): get an event into the channel's
 * Redis Stream, exactly once, with zero cooperation required from the sender — unless a
 * channel filter rejects it first.
 *
 * <p>Order of operations, each intentional:
 * <ol>
 *   <li>Channel filter evaluation (M3, spec §5) — cheap, in-memory, evaluated first so a
 *       rejected event never consumes dedup key space or a Redis round-trip for
 *       something about to be discarded anyway.</li>
 *   <li>{@code SET fingerprint:{hash} 1 NX EX <ttl>} — atomic check-and-set. If this key
 *       already existed, the event is a duplicate (a retried webhook delivery) and is
 *       deliberately <em>not</em> re-added (spec §4).</li>
 *   <li>{@code XADD stream:channel:{id} MAXLEN ~ <cap> * payload <body> received_at
 *       <instant>} — the single source of truth for both live fan-out (M2) and replay
 *       (M4), immediately followed by enqueueing the same event for async archiving
 *       (M4, spec §9 — best effort, never blocks this response). The approximate
 *       {@code MAXLEN} cap (M6c, F-01) is the safety net spec §3 always called for
 *       alongside {@link dev.realtime.archive.RetentionTrimmer}'s scheduled sweep — a
 *       bursty channel no longer grows unbounded for up to an hour between sweeps.</li>
 * </ol>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ReactiveRedisTemplate<String, String> redis;
    private final FilterEngine filterEngine;
    private final ChannelFilterStore filterStore;
    private final ArchiveWriter archiveWriter;
    private final Duration dedupTtl;
    private final long streamMaxlen;

    public IngestService(
            ReactiveRedisTemplate<String, String> redis,
            FilterEngine filterEngine,
            ChannelFilterStore filterStore,
            ArchiveWriter archiveWriter,
            @Value("${realtime.ingest.dedup-ttl}") Duration dedupTtl,
            @Value("${realtime.archive.stream-maxlen:50000}") long streamMaxlen) {
        if (streamMaxlen <= 0) {
            throw new IllegalArgumentException("streamMaxlen must be positive: " + streamMaxlen);
        }
        this.redis = redis;
        this.filterEngine = filterEngine;
        this.filterStore = filterStore;
        this.archiveWriter = archiveWriter;
        this.dedupTtl = dedupTtl;
        this.streamMaxlen = streamMaxlen;
    }

    /**
     * Ingests one webhook payload for a channel. All three outcomes result in the same
     * 202 to the sender (spec §6) — this return value exists for logging/observability,
     * not to change the HTTP response shape.
     */
    public Mono<IngestOutcome> ingest(String channelId, String rawBody) {
        List<FilterRule> rules = filterStore.getRules(channelId);
        if (!filterEngine.matches(rawBody, rules)) {
            log.debug("Event filtered for channel {}", channelId);
            return Mono.just(IngestOutcome.FILTERED);
        }

        String dedupKey = "fingerprint:" + Fingerprint.of(channelId, rawBody);

        return redis.opsForValue()
                .setIfAbsent(dedupKey, "1", dedupTtl)
                .flatMap(isNew -> isNew
                        ? appendToStream(channelId, rawBody).thenReturn(IngestOutcome.ACCEPTED)
                        : Mono.just(IngestOutcome.DUPLICATE));
    }

    private Mono<RecordId> appendToStream(String channelId, String rawBody) {
        String streamKey = "stream:channel:" + channelId;
        Instant receivedAt = Instant.now();
        Map<String, String> fields = Map.of(
                "payload", rawBody,
                "received_at", receivedAt.toString());

        return redis.opsForStream()
                .add(streamKey, fields, XAddOptions.maxlen(streamMaxlen).approximateTrimming(true))
                .doOnNext(recordId -> archiveWriter.enqueue(ArchivedEvent.builder()
                        .channelId(channelId)
                        .redisStreamId(recordId.getValue())
                        .payload(rawBody)
                        .receivedAt(receivedAt)
                        .build()));
    }
}


