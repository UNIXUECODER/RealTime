package dev.realtime.archive;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArchivedEventRepository extends JpaRepository<ArchivedEvent, Long> {

    /** Everything from a point onward, no upper bound — used when there's no hot data to hand off to. */
    List<ArchivedEvent> findByChannelIdAndReceivedAtGreaterThanOrderByReceivedAtAsc(
            String channelId, Instant since);

    /** Exactly the trimmed slice between a resume point and where hot data picks back up. */
    List<ArchivedEvent> findByChannelIdAndReceivedAtBetweenOrderByReceivedAtAsc(
            String channelId, Instant since, Instant until);
}
