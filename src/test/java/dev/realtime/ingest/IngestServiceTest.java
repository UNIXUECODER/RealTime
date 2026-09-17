package dev.realtime.ingest;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.data.redis.core.ReactiveValueOperations;

import dev.realtime.archive.ArchiveWriter;
import dev.realtime.archive.ArchivedEvent;
import dev.realtime.filter.ChannelFilterStore;
import dev.realtime.filter.FilterEngine;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated unit tests for {@link IngestService}.
 * Verifies filter enforcement, fingerprint deduplication, stream append with MAXLEN cap (F-01),
 * and downstream ArchiveWriter enqueueing.
 */
class IngestServiceTest {

    private ReactiveRedisTemplate<String, String> redis;
    private ReactiveValueOperations<String, String> valueOps;
    private ReactiveStreamOperations<String, Object, Object> streamOps;
    private FilterEngine filterEngine;
    private ChannelFilterStore filterStore;
    private ArchiveWriter archiveWriter;
    private IngestService ingestService;

    private static final long STREAM_MAXLEN = 50_000L;
    private static final Duration DEDUP_TTL = Duration.ofMinutes(5);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(ReactiveRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        streamOps = mock(ReactiveStreamOperations.class);
        filterEngine = mock(FilterEngine.class);
        filterStore = mock(ChannelFilterStore.class);
        archiveWriter = mock(ArchiveWriter.class);

        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForStream()).thenReturn(streamOps);

        ingestService = new IngestService(
                redis,
                filterEngine,
                filterStore,
                archiveWriter,
                DEDUP_TTL,
                STREAM_MAXLEN
        );
    }

    @Test
    void constructorRejectsNonPositiveStreamMaxlen() {
        assertThatThrownBy(() -> new IngestService(
                redis, filterEngine, filterStore, archiveWriter, DEDUP_TTL, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamMaxlen must be positive: 0");

        assertThatThrownBy(() -> new IngestService(
                redis, filterEngine, filterStore, archiveWriter, DEDUP_TTL, -50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamMaxlen must be positive: -50");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestAcceptedPassesMaxlenCapAndEnqueuesArchive() {
        String channelId = "ch-test-1";
        String rawBody = "{\"event\":\"order.created\",\"amount\":42}";

        when(filterStore.getRules(channelId)).thenReturn(Collections.emptyList());
        when(filterEngine.matches(eq(rawBody), any())).thenReturn(true);
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(DEDUP_TTL))).thenReturn(Mono.just(true));

        RecordId recordId = RecordId.of("1726500000000-0");
        ArgumentCaptor<XAddOptions> optionsCaptor = ArgumentCaptor.forClass(XAddOptions.class);
        ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);

        when(streamOps.add(eq("stream:channel:" + channelId), fieldsCaptor.capture(), optionsCaptor.capture()))
                .thenReturn(Mono.just(recordId));

        StepVerifier.create(ingestService.ingest(channelId, rawBody))
                .expectNext(IngestOutcome.ACCEPTED)
                .verifyComplete();

        // 1. Verify Redis stream append with approximate maxlen cap (F-01)
        XAddOptions capturedOptions = optionsCaptor.getValue();
        assertThat(capturedOptions).isNotNull();
        assertThat(capturedOptions.hasMaxlen()).isTrue();
        assertThat(capturedOptions.getMaxlen()).isEqualTo(STREAM_MAXLEN);
        assertThat(capturedOptions.isApproximateTrimming()).isTrue();

        Map<String, String> capturedFields = fieldsCaptor.getValue();
        assertThat(capturedFields).containsEntry("payload", rawBody);
        assertThat(capturedFields).containsKey("received_at");

        // 2. Verify ArchiveWriter enqueue
        ArgumentCaptor<ArchivedEvent> archiveCaptor = ArgumentCaptor.forClass(ArchivedEvent.class);
        verify(archiveWriter).enqueue(archiveCaptor.capture());
        ArchivedEvent archived = archiveCaptor.getValue();
        assertThat(archived.getChannelId()).isEqualTo(channelId);
        assertThat(archived.getRedisStreamId()).isEqualTo("1726500000000-0");
        assertThat(archived.getPayload()).isEqualTo(rawBody);
    }

    @Test
    void ingestDuplicateDropsAndDoesNotAppendOrArchive() {
        String channelId = "ch-test-1";
        String rawBody = "{\"event\":\"order.created\"}";

        when(filterStore.getRules(channelId)).thenReturn(Collections.emptyList());
        when(filterEngine.matches(eq(rawBody), any())).thenReturn(true);
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(DEDUP_TTL))).thenReturn(Mono.just(false));

        StepVerifier.create(ingestService.ingest(channelId, rawBody))
                .expectNext(IngestOutcome.DUPLICATE)
                .verifyComplete();

        verify(streamOps, never()).add(anyString(), anyMap(), any(XAddOptions.class));
        verify(archiveWriter, never()).enqueue(any());
    }

    @Test
    void ingestFilteredDropsAndDoesNotDeduplicateOrAppend() {
        String channelId = "ch-test-1";
        String rawBody = "{\"event\":\"order.ignored\"}";

        when(filterStore.getRules(channelId)).thenReturn(Collections.emptyList());
        when(filterEngine.matches(eq(rawBody), any())).thenReturn(false);

        StepVerifier.create(ingestService.ingest(channelId, rawBody))
                .expectNext(IngestOutcome.FILTERED)
                .verifyComplete();

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(streamOps, never()).add(anyString(), anyMap(), any(XAddOptions.class));
        verify(archiveWriter, never()).enqueue(any());
    }
}
