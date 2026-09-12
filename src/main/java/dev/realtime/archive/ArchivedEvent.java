package dev.realtime.archive;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Cold-storage row for one archived event (spec §7). The unique constraint is on
 * {@code (channel_id, redis_stream_id)}, not {@code redis_stream_id} alone — Redis
 * stream IDs are only unique within a single stream, so two different channels can
 * coincidentally produce the same ID.
 */
@Entity
@Table(
        name = "events_archive",
        uniqueConstraints = @UniqueConstraint(columnNames = {"channel_id", "redis_stream_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchivedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Column(name = "redis_stream_id", nullable = false)
    private String redisStreamId;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
