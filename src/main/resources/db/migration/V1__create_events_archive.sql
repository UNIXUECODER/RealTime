CREATE TABLE events_archive (
    id              BIGSERIAL PRIMARY KEY,
    channel_id      VARCHAR(255) NOT NULL,
    redis_stream_id VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_events_archive_channel_stream_id UNIQUE (channel_id, redis_stream_id)
);

-- Supports the replay query pattern: filter by channel, range on received_at, ordered by it.
CREATE INDEX idx_events_archive_channel_received_at ON events_archive (channel_id, received_at);
