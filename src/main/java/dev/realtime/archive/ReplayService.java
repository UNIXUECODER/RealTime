package dev.realtime.archive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Serves the replay API, transparently combining hot (Redis Stream) and cold (Postgres
 * archive) data — per spec §3, the customer-facing API never reveals which storage tier
 * actually served a given range.
 *
 * <p>Reuses the exact "XREAD without BLOCK" pattern verified working in M2's
 * {@code ChannelWebSocketHandler} for one-shot historical range reads, rather than the
 * generic {@code Range}/{@code Bound} API — an XRANGE-shaped call whose exact method
 * names I was less certain of, when an already-proven pattern covers the same need.
 */
@Service
public class ReplayService {

    private final ReactiveRedisTemplate<String, String> redis;
    private final ArchivedEventRepository archiveRepository;

    public ReplayService(ReactiveRedisTemplate<String, String> redis, ArchivedEventRepository archiveRepository) {
        this.redis = redis;
        this.archiveRepository = archiveRepository;
    }

    public Mono<List<ReplayedEvent>> replay(String channelId, String since) {
        String streamKey = "stream:channel:" + channelId;

        return earliestHotId(streamKey)
                .flatMap(earliestHotIdOpt -> {
                    Mono<List<ReplayedEvent>> hot = hotRange(streamKey, since);

                    if (earliestHotIdOpt.isEmpty()) {
                        // Hot stream is empty (trimmed entirely or never written).
                        // Serve completely from the Postgres archive.
                        return coldRange(channelId, since, null);
                    }

                    String earliestHotId = earliestHotIdOpt.get();
                    if (StreamIds.compare(since, earliestHotId) >= 0) {
                        // Nothing was trimmed before `since` — hot data alone covers it.
                        return hot;
                    }

                    // A gap exists between `since` and what the hot stream still has —
                    // that trimmed slice must come from the archive instead.
                    Instant until = StreamIds.timestampOf(earliestHotId);
                    return coldRange(channelId, since, until)
                            .map(cold -> cold.stream()
                                    .filter(e -> StreamIds.compare(e.id(), earliestHotId) < 0)
                                    .toList())
                            .zipWith(hot, (cold, liveTail) -> {
                                List<ReplayedEvent> combined = new ArrayList<>(cold);
                                combined.addAll(liveTail);
                                return combined;
                            });
                });
    }

    /** The oldest entry still present in the hot stream, or empty if the stream has nothing (trimmed away entirely, or never written). */
    private Mono<Optional<String>> earliestHotId(String streamKey) {
        return redis.<String, String>opsForStream()
                .read(StreamReadOptions.empty().count(1), StreamOffset.create(streamKey, ReadOffset.from("0")))
                .next()
                .map(record -> Optional.of(record.getId().getValue()))
                .defaultIfEmpty(Optional.empty());
    }

    private Mono<List<ReplayedEvent>> hotRange(String streamKey, String since) {
        return redis.<String, String>opsForStream()
                .read(StreamReadOptions.empty(), StreamOffset.create(streamKey, ReadOffset.from(since)))
                .map(this::toDto)
                .collectList();
    }

    private Mono<List<ReplayedEvent>> coldRange(String channelId, String since, Instant until) {
        Instant sinceInstant = StreamIds.timestampOf(since);

        return Mono.fromCallable(() -> until == null
                        ? archiveRepository.findByChannelIdAndReceivedAtGreaterThanOrderByReceivedAtAsc(channelId, sinceInstant)
                        : archiveRepository.findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(channelId, sinceInstant, until))
                .subscribeOn(Schedulers.boundedElastic())
                .map(entities -> entities.stream()
                        .map(this::toDto)
                        .filter(e -> StreamIds.compare(e.id(), since) > 0)
                        .toList());
    }

    private ReplayedEvent toDto(MapRecord<String, String, String> record) {
        return new ReplayedEvent(
                record.getId().getValue(),
                record.getValue().get("payload"),
                record.getValue().get("received_at"));
    }

    private ReplayedEvent toDto(ArchivedEvent entity) {
        return new ReplayedEvent(entity.getRedisStreamId(), entity.getPayload(), entity.getReceivedAt().toString());
    }
}
