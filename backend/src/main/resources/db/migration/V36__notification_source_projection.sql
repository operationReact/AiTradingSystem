-- Durable per-event projection receipt prevents acknowledged historical failures from being raised on every poll.
CREATE TABLE notification_source_projection (
    source_type VARCHAR(60) NOT NULL,
    source_id VARCHAR(120) NOT NULL,
    notification_id UUID REFERENCES system_notification(id),
    projected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_type, source_id)
);
