package dev.realtime.archive;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Validates M6a hardening of {@link ArchiveWriter}: the buffer is now capped
 * (drop + log at capacity) and flush drains in a loop until empty per cycle.
 */
class ArchiveWriterTest {

    private ArchivedEventRepository repository;
    private ArchiveWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(ArchivedEventRepository.class);
        writer = new ArchiveWriter(repository);
    }

    private ArchivedEvent sampleEvent(String channelId, int n) {
        return ArchivedEvent.builder()
                .channelId(channelId)
                .redisStreamId("1000-" + n)
                .payload("{\"n\":" + n + "}")
                .receivedAt(Instant.now())
                .build();
    }

    @Test
    void enqueueAcceptsEventsUnderCap() {
        for (int i = 0; i < 100; i++) {
            writer.enqueue(sampleEvent("ch1", i));
        }
        writer.flush();
        // All 100 events should have been flushed to the repository.
        verify(repository).saveAll(anyList());
    }

    @Test
    void enqueueDropsEventsAtCapacity() {
        // MAX_BUFFER_SIZE is 10_000 — fill it up exactly.
        for (int i = 0; i < 10_000; i++) {
            writer.enqueue(sampleEvent("ch1", i));
        }
        // The 10_001st event should be dropped silently (with a log).
        writer.enqueue(sampleEvent("ch1", 10_001));

        writer.flush();

        // Verify the repository received calls — the dropped event should NOT be
        // present. We can't easily check the exact count per saveAll call since flush
        // drains in batches of 500, but at 10_000 events that's exactly 20 batches.
        verify(repository, times(20)).saveAll(anyList());
    }

    @Test
    void flushDrainsMultipleBatchesInOneInvocation() {
        // Enqueue 1200 events — more than one batch (MAX_BATCH_SIZE = 500), so the
        // old single-batch-per-cycle logic would leave 700 behind. The M6a fix drains
        // in a loop until empty.
        for (int i = 0; i < 1200; i++) {
            writer.enqueue(sampleEvent("ch1", i));
        }

        writer.flush();

        // Should have drained in 3 batches: 500 + 500 + 200.
        verify(repository, times(3)).saveAll(anyList());
    }

    @Test
    void flushDoesNothingWhenBufferIsEmpty() {
        writer.flush();
        verify(repository, never()).saveAll(anyList());
    }
}
