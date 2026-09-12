package dev.realtime.archive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Buffers archived events in memory and flushes them to Postgres in batches on a
 * schedule, rather than one INSERT per ingested event — cuts DB round-trips under load.
 *
 * <p>Best-effort, per spec §9 ("Postgres unavailable does not block the hot path... if
 * it falls behind or errors, log and alert, but ingest and live fan-out keep working
 * off Redis alone") — a failed flush is logged, not retried or escalated.
 */
@Component
public class ArchiveWriter {

    private static final Logger log = LoggerFactory.getLogger(ArchiveWriter.class);
    private static final int MAX_BATCH_SIZE = 500;

    private final ArchivedEventRepository repository;
    private final ConcurrentLinkedQueue<ArchivedEvent> buffer = new ConcurrentLinkedQueue<>();

    public ArchiveWriter(ArchivedEventRepository repository) {
        this.repository = repository;
    }

    /** Enqueues an event for the next scheduled flush. Non-blocking, in-memory only. */
    public void enqueue(ArchivedEvent event) {
        buffer.add(event);
    }

    @Scheduled(fixedDelayString = "${realtime.archive.flush-interval:2s}")
    public void flush() {
        List<ArchivedEvent> batch = drain();
        if (batch.isEmpty()) {
            return;
        }
        try {
            repository.saveAll(batch);
            log.debug("Archived {} event(s)", batch.size());
        } catch (DataIntegrityViolationException e) {
            // A (channelId, redisStreamId) pair already archived — most likely this
            // batch overlapped a previous one after a partial failure. Not retried
            // row-by-row: isolating which specific rows conflicted isn't worth the
            // complexity for a best-effort archive (spec §9). A growing gap between
            // Redis and Postgres is the visible symptom to alert on (M9), not something
            // this method tries to reconcile.
            log.warn("Archive batch had one or more duplicate rows, batch of {} discarded", batch.size(), e);
        } catch (Exception e) {
            log.error("Archive flush failed for a batch of {} event(s) — will not be retried", batch.size(), e);
        }
    }

    private List<ArchivedEvent> drain() {
        List<ArchivedEvent> batch = new ArrayList<>();
        ArchivedEvent event;
        while (batch.size() < MAX_BATCH_SIZE && (event = buffer.poll()) != null) {
            batch.add(event);
        }
        return batch;
    }
}
