package dev.realtime.archive;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the M6c (F-02) cold-first pagination budget in {@link
 * ReplayService#replay} — the one piece of this batch with genuinely new merge logic,
 * covered here ahead of the fuller hot/cold boundary matrix F-20 adds later (empty
 * stream, no-gap hot-only trivial case, F-07's same-millisecond regression, etc.).
 * Reuses the {@code ReactiveRedisTemplate}/{@code ReactiveStreamOperations} mocking
 * pattern {@code IngestServiceTest} already established, rather than a fresh one.
 */
class ReplayServiceTest {

    private static final String CHANNEL_ID = "chan-1";
    private static final String STREAM_KEY = "stream:channel:chan-1";

    private ReactiveStreamOperations<String, String, String> streamOps;
    private ArchivedEventRepository archiveRepository;
    private ReplayService replayService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        streamOps = mock(ReactiveStreamOperations.class);
        when(redis.<String, String>opsForStream()).thenReturn(streamOps);
        archiveRepository = mock(ArchivedEventRepository.class);
        replayService = new ReplayService(redis, archiveRepository);
    }

    private void stubEarliestHotId(String earliestId) {
        MapRecord<String, String, String> record = hotRecord(earliestId);
        when(streamOps.read(any(StreamReadOptions.class), eq(StreamOffset.create(STREAM_KEY, ReadOffset.from("0")))))
                .thenReturn(Flux.just(record));
    }

    private void stubEmptyHotStream() {
        when(streamOps.read(any(StreamReadOptions.class), eq(StreamOffset.create(STREAM_KEY, ReadOffset.from("0")))))
                .thenReturn(Flux.empty());
    }

    @SafeVarargs
    private void stubHotRange(String since, MapRecord<String, String, String>... records) {
        when(streamOps.read(any(StreamReadOptions.class), eq(StreamOffset.create(STREAM_KEY, ReadOffset.from(since)))))
                .thenReturn(Flux.fromArray(records));
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, String, String> hotRecord(String id) {
        MapRecord<String, String, String> record = mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of(id));
        when(record.getValue()).thenReturn(Map.of("payload", "{}", "received_at", "2026-01-01T00:00:00Z"));
        return record;
    }

    private ArchivedEvent archivedEvent(String redisStreamId, long epochMilli) {
        return ArchivedEvent.builder()
                .channelId(CHANNEL_ID)
                .redisStreamId(redisStreamId)
                .payload("{}")
                .receivedAt(Instant.ofEpochMilli(epochMilli))
                .build();
    }

    @Test
    void coldAloneFillingLimitNeverQueriesHotRange() {
        // Gap exists: earliest hot id (5000-0) is well after `since` (500-0).
        stubEarliestHotId("5000-0");

        List<ArchivedEvent> coldRows = List.of(
                archivedEvent("1000-0", 1000),
                archivedEvent("2000-0", 2000));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(archiveRepository.findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(
                eq(CHANNEL_ID), eq(Instant.ofEpochMilli(500)), eq(Instant.ofEpochMilli(5000)), pageableCaptor.capture()))
                .thenReturn(coldRows);

        StepVerifier.create(replayService.replay(CHANNEL_ID, "500-0", 2))
                .assertNext(events -> {
                    assertThat(events).extracting(ReplayedEvent::id).containsExactly("1000-0", "2000-0");
                })
                .verifyComplete();

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(3);
        // Cold alone filled the budget (2 rows >= limit of 2) — the real hot-range read
        // (offset "500-0") must never happen, only the cheap earliestHotId probe (offset "0").
        verify(streamOps, times(1)).read(any(StreamReadOptions.class), any(StreamOffset.class));
    }

    @Test
    void coldShortOfLimitFillsRemainderFromHot() {
        // Gap exists: earliest hot id (5000-0) is well after `since` (500-0).
        stubEarliestHotId("5000-0");

        // Cold returns only 1 row for a limit of 3 — hot must fill the remaining 2.
        List<ArchivedEvent> coldRows = List.of(archivedEvent("1000-0", 1000));
        when(archiveRepository.findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(
                eq(CHANNEL_ID), eq(Instant.ofEpochMilli(500)), eq(Instant.ofEpochMilli(5000)), any(Pageable.class)))
                .thenReturn(coldRows);
        stubHotRange("500-0", hotRecord("5000-0"), hotRecord("5001-0"));

        StepVerifier.create(replayService.replay(CHANNEL_ID, "500-0", 3))
                .assertNext(events -> assertThat(events)
                        .extracting(ReplayedEvent::id)
                        .containsExactly("1000-0", "5000-0", "5001-0"))
                .verifyComplete();

        // Both the earliestHotId probe (offset "0") and the actual hot-range read
        // (offset "500-0") must have happened — cold alone wasn't enough.
        verify(streamOps, times(1)).read(any(StreamReadOptions.class), eq(StreamOffset.create(STREAM_KEY, ReadOffset.from("500-0"))));
    }

    @Test
    void noGapUsesHotOnlyAndNeverQueriesArchive() {
        // `since` is already at-or-after the earliest hot id — nothing was trimmed before it.
        stubEarliestHotId("500-0");
        stubHotRange("500-0", hotRecord("500-0"), hotRecord("600-0"));

        StepVerifier.create(replayService.replay(CHANNEL_ID, "500-0", 500))
                .assertNext(events -> assertThat(events)
                        .extracting(ReplayedEvent::id)
                        .containsExactly("500-0", "600-0"))
                .verifyComplete();

        verify(archiveRepository, never()).findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(
                any(), any(), any(), any());
        verify(archiveRepository, never()).findByChannelIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(
                any(), any(), any());
    }

    @Test
    void emptyHotStreamUsesColdOnlyWithGreaterThanEqualVariant() {
        // Hot stream trimmed away entirely (or never written) — serve completely from Postgres.
        stubEmptyHotStream();

        List<ArchivedEvent> coldRows = List.of(archivedEvent("1000-0", 1000), archivedEvent("2000-0", 2000));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(archiveRepository.findByChannelIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(
                eq(CHANNEL_ID), eq(Instant.ofEpochMilli(500)), pageableCaptor.capture()))
                .thenReturn(coldRows);

        StepVerifier.create(replayService.replay(CHANNEL_ID, "500-0", 500))
                .assertNext(events -> assertThat(events)
                        .extracting(ReplayedEvent::id)
                        .containsExactly("1000-0", "2000-0"))
                .verifyComplete();

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(501);
        verify(archiveRepository, never()).findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(
                any(), any(), any(), any());
    }

    @Test
    void sameMillisecondColdEventsWithHigherSequenceAreRetainedWhenHotStreamEmpty() {
        // F-07 regression test: `since` is "1000-0". Archived events exist in the same millisecond:
        // "1000-0" (cursor itself) and "1000-1" (higher sequence).
        // GreaterThanEqual query fetches both; sequence-aware filter drops "1000-0" and retains "1000-1".
        stubEmptyHotStream();

        List<ArchivedEvent> coldRows = List.of(
                archivedEvent("1000-0", 1000),
                archivedEvent("1000-1", 1000),
                archivedEvent("1000-2", 1000));
        when(archiveRepository.findByChannelIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(
                eq(CHANNEL_ID), eq(Instant.ofEpochMilli(1000)), any(Pageable.class)))
                .thenReturn(coldRows);

        StepVerifier.create(replayService.replay(CHANNEL_ID, "1000-0", 500))
                .assertNext(events -> assertThat(events)
                        .extracting(ReplayedEvent::id)
                        .containsExactly("1000-1", "1000-2"))
                .verifyComplete();
    }

    @Test
    void sameMillisecondColdEventsWithHigherSequenceAreRetainedWhenGapExists() {
        // F-07 regression test for gap branch: `since` is "1000-0", earliest hot ID is "5000-0".
        // Archived events at 1000ms ("1000-0", "1000-1") returned by Between query;
        // sequence-aware filter drops "1000-0" and keeps "1000-1".
        stubEarliestHotId("5000-0");

        List<ArchivedEvent> coldRows = List.of(
                archivedEvent("1000-0", 1000),
                archivedEvent("1000-1", 1000));
        when(archiveRepository.findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(
                eq(CHANNEL_ID), eq(Instant.ofEpochMilli(1000)), eq(Instant.ofEpochMilli(5000)), any(Pageable.class)))
                .thenReturn(coldRows);
        stubHotRange("1000-0", hotRecord("5000-0"));

        StepVerifier.create(replayService.replay(CHANNEL_ID, "1000-0", 10))
                .assertNext(events -> assertThat(events)
                        .extracting(ReplayedEvent::id)
                        .containsExactly("1000-1", "5000-0"))
                .verifyComplete();
    }

    @Test
    void coldNotExhaustedNeverCallsHotRangeEvenIfColdShortOfLimit() {
        // Isolates reachedBoundary: limit is 2, since is "1000-2", earliest hot ID is "5000-0".
        // seq = 2, extra = 3, fetchSize = 5.
        // Postgres returns a full page of 5 rows, but 4 of them are <= since ("1000-2"):
        // [1000-0, 1000-1, 1000-2, 1000-2, 1001-0].
        // After filtering, cold.size() is 1, which is strictly LESS than limit (1 < 2)!
        // Under naive `cold.size() >= limit`, this would falsely trigger hotRange.
        // But because entities.size() == fetchSize (5 == 5) and IDs are < 5000-0, reachedBoundary is false!
        // ReplayService correctly returns [1001-0] without calling hotRange.
        stubEarliestHotId("5000-0");

        List<ArchivedEvent> coldRows = List.of(
                archivedEvent("1000-0", 1000),
                archivedEvent("1000-1", 1000),
                archivedEvent("1000-2", 1000),
                archivedEvent("1000-2", 1000),
                archivedEvent("1001-0", 1001));
        when(archiveRepository.findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(
                eq(CHANNEL_ID), eq(Instant.ofEpochMilli(1000)), eq(Instant.ofEpochMilli(5000)), any(Pageable.class)))
                .thenReturn(coldRows);

        StepVerifier.create(replayService.replay(CHANNEL_ID, "1000-2", 2))
                .assertNext(events -> assertThat(events)
                        .extracting(ReplayedEvent::id)
                        .containsExactly("1001-0"))
                .verifyComplete();

        // Hot stream range read must NOT happen — cold archive is not exhausted despite cold.size() < limit!
        verify(streamOps, never()).read(any(StreamReadOptions.class), eq(StreamOffset.create(STREAM_KEY, ReadOffset.from("1000-2"))));
    }

    @Test
    void sameMillisecondEventsAreReturnedInStrictSequenceOrder() {
        // Deterministic ordering: even if database returns same-ms rows out of sequence order,
        // ReplayService guarantees strict sequence-sorted results.
        stubEmptyHotStream();

        List<ArchivedEvent> outOfOrderRows = List.of(
                archivedEvent("1000-2", 1000),
                archivedEvent("1000-0", 1000),
                archivedEvent("1000-1", 1000));
        when(archiveRepository.findByChannelIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(
                eq(CHANNEL_ID), eq(Instant.ofEpochMilli(1000)), any(Pageable.class)))
                .thenReturn(outOfOrderRows);

        StepVerifier.create(replayService.replay(CHANNEL_ID, "1000-0", 500))
                .assertNext(events -> assertThat(events)
                        .extracting(ReplayedEvent::id)
                        .containsExactly("1000-1", "1000-2"))
                .verifyComplete();
    }

    @Test
    void emptyChannelReturnsEmptyList() {
        // Genuinely fresh channel: no hot data and no cold archive data (F-20 matrix edge).
        stubEmptyHotStream();
        when(archiveRepository.findByChannelIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(
                eq(CHANNEL_ID), eq(Instant.ofEpochMilli(0)), any(Pageable.class)))
                .thenReturn(List.of());

        StepVerifier.create(replayService.replay(CHANNEL_ID, "0", 500))
                .assertNext(events -> assertThat(events).isEmpty())
                .verifyComplete();
    }
}

