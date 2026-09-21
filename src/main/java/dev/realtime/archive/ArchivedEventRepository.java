package dev.realtime.archive;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArchivedEventRepository extends JpaRepository<ArchivedEvent, Long> {

    /**
     * Everything from a point onward (inclusive), no upper bound — used when there's no
     * hot data to hand off to. Inclusive since M6c (F-07): {@code receivedAt} is an
     * {@link Instant}, so a strict exclusive bound previously dropped same-millisecond
     * rows with a higher sequence number before {@link ReplayService#coldRange}'s exact,
     * sequence-aware in-memory filter ever got a chance to keep them correctly. Capped via
     * {@code pageable} (M6c, F-02) so a request spanning a huge cold-only range can't load
     * the whole thing into heap.
     */
    List<ArchivedEvent> findByChannelIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(
            String channelId, Instant since, Pageable pageable);

    /**
     * Exactly the trimmed slice between a resume point and where hot data picks back up —
     * already inclusive on both ends (Spring Data's {@code Between} compiles to SQL's
     * inclusive {@code BETWEEN}), so F-07 never applied here. Capped via {@code pageable}
     * (M6c, F-02) for the same reason as above.
     */
    List<ArchivedEvent> findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(
            String channelId, Instant since, Instant until, Pageable pageable);
}
