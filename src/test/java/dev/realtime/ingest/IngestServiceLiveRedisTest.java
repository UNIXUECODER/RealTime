package dev.realtime.ingest;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import dev.realtime.archive.ArchiveWriter;
import dev.realtime.filter.ChannelFilterStore;
import dev.realtime.filter.FilterEngine;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test against live Redis container (spec §3, F-01 acceptance criteria):
 * Proves that Spring Data Redis's ReactiveStreamOperations.add(..., XAddOptions) actually
 * sends MAXLEN ~ to the Redis wire and Redis actively evicts entries as the cap is exceeded.
 */
class IngestServiceLiveRedisTest {

    private LettuceConnectionFactory connectionFactory;
    private ReactiveRedisTemplate<String, String> redis;
    private String channelId;
    private String streamKey;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();

        RedisSerializationContext<String, String> context = RedisSerializationContext
                .<String, String>newSerializationContext(new StringRedisSerializer())
                .build();
        redis = new ReactiveRedisTemplate<>(connectionFactory, context);

        channelId = "live-trim-" + UUID.randomUUID().toString().substring(0, 8);
        streamKey = "stream:channel:" + channelId;
    }

    @AfterEach
    void tearDown() {
        if (redis != null && streamKey != null) {
            redis.delete(streamKey).block();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void liveRedisEnforcesMaxlenCapUnderBurst() {
        long testCap = 100L;
        int totalEvents = 300;

        FilterEngine filterEngine = mock(FilterEngine.class);
        ChannelFilterStore filterStore = mock(ChannelFilterStore.class);
        ArchiveWriter archiveWriter = mock(ArchiveWriter.class);

        when(filterStore.getRules(channelId)).thenReturn(Collections.emptyList());
        when(filterEngine.matches(any(), any())).thenReturn(true);

        IngestService ingestService = new IngestService(
                redis,
                filterEngine,
                filterStore,
                archiveWriter,
                Duration.ofMinutes(5),
                testCap
        );

        // Burst 300 distinct events through the live Java IngestService pipeline into real Redis
        Flux.range(1, totalEvents)
                .flatMap(i -> ingestService.ingest(channelId, "{\"event\":\"order.created\",\"seq\":" + i + "}"), 32)
                .blockLast(Duration.ofSeconds(30));

        // Query real XLEN from Redis
        Long xlen = redis.opsForStream().size(streamKey).block();
        System.out.println("LIVE REDIS VERIFICATION: Ingested " + totalEvents + " events with cap=" + testCap + " -> Final XLEN in Redis: " + xlen);

        assertThat(xlen).isNotNull();
        // Approximate trimming only releases whole macro-nodes (Redis's default
        // stream-node-max-entries is 100, not a number this test should assume or
        // hardcode — it's an internal implementation detail, not part of XADD's
        // documented contract, and could change between Redis versions). So the
        // exact post-trim length varies by run; what's actually guaranteed, and
        // what F-01's acceptance criterion asks for, is that trimming (a) never
        // removes below the cap and (b) prevents the unbounded growth a bare XADD
        // would otherwise produce — not a precise margin above the cap.
        assertThat(xlen)
                .as("XADD ... MAXLEN ~ must trim the stream, not let it grow unbounded to %d", totalEvents)
                .isGreaterThanOrEqualTo(testCap)
                .isLessThan((long) totalEvents);
    }
}
