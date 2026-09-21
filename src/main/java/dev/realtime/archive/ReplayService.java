package dev.realtime.archive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    public Mono<List<ReplayedEvent>> replay(String channelId, String since, int limit) {
        String streamKey = "stream:channel:" + channelId;

        return earliestHotId(streamKey)
                .flatMap(earliestHotIdOpt -> {
                    if (earliestHotIdOpt.isEmpty()) {
                        // Hot stream is empty (trimmed entirely or never written).
                        // Serve completely from the Postgres archive.
                        return coldRange(channelId, since, null, limit)
                                .map(ColdSlice::events);
                    }

                    String earliestHotId = earliestHotIdOpt.get();
                    if (StreamIds.compare(since, earliestHotId) >= 0) {
                        // Nothing was trimmed before `since` — hot data alone covers it.
                        return hotRange(streamKey, since, limit);
                    }

                    // A gap exists between `since` and what the hot stream still has. Cold-first
                    // budget (M6c, F-02): events are strictly chronological, so cold is always
                    // older than hot — pull up to `limit` from cold first, and only touch hot
                    // (extra Redis I/O) if cold has reached the hot boundary and didn't fill the budget.
                    // If cold has not reached the boundary, client continues paging cold without skipping.
                    return coldRange(channelId, since, earliestHotId, limit)
                            .flatMap(coldSlice -> {
                                List<ReplayedEvent> cold = coldSlice.events();
                                if (!coldSlice.reachedBoundary() || cold.size() >= limit) {
                                    return Mono.just(cold);
                                }

                                return hotRange(streamKey, since, limit - cold.size())
                                        .map(liveTail -> {
                                            List<ReplayedEvent> combined = new ArrayList<>(cold);
                                            combined.addAll(liveTail);
                                            return combined;
                                        });
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

    private Mono<List<ReplayedEvent>> hotRange(String streamKey, String since, int limit) {
        return redis.<String, String>opsForStream()
                .read(StreamReadOptions.empty().count(limit), StreamOffset.create(streamKey, ReadOffset.from(since)))
                .map(this::toDto)
                .collectList();
    }

    private record ColdSlice(List<ReplayedEvent> events, boolean exhausted, boolean reachedBoundary) {}

    private Mono<ColdSlice> coldRange(String channelId, String since, String earliestHotId, int limit) {
        Instant sinceInstant = StreamIds.timestampOf(since);
        Instant until = earliestHotId == null ? null : StreamIds.timestampOf(earliestHotId);
        long seq = StreamIds.sequenceOf(since);
        int extra = (int) Math.min(seq + 1, 100);
        int fetchSize = limit + extra;
        Pageable pageable = PageRequest.of(0, fetchSize);

        return Mono.fromCallable(() -> until == null
                        ? archiveRepository.findByChannelIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(channelId, sinceInstant, pageable)
                        : archiveRepository.findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(channelId, sinceInstant, until, pageable))
                .subscribeOn(Schedulers.boundedElastic())
                .map(entities -> {
                    boolean exhausted = entities.size() < fetchSize;
                    boolean reachedBoundary = exhausted
                            || (earliestHotId != null && entities.stream().anyMatch(e -> StreamIds.compare(e.getRedisStreamId(), earliestHotId) >= 0));

                    List<ReplayedEvent> filtered = entities.stream()
                            .filter(e -> StreamIds.compare(e.getRedisStreamId(), since) > 0)
                            .filter(e -> earliestHotId == null || StreamIds.compare(e.getRedisStreamId(), earliestHotId) < 0)
                            .sorted((a, b) -> StreamIds.compare(a.getRedisStreamId(), b.getRedisStreamId()))
                            .limit(limit)
                            .map(this::toDto)
                            .toList();

                    return new ColdSlice(filtered, exhausted, reachedBoundary);
                });
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
