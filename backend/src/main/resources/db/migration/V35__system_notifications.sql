CREATE TABLE system_notification (
    id UUID PRIMARY KEY,
    type VARCHAR(60) NOT NULL,
    severity VARCHAR(12) NOT NULL CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    title VARCHAR(160) NOT NULL,
    message VARCHAR(500) NOT NULL,
    entity_type VARCHAR(60),
    entity_id VARCHAR(120),
    dedupe_key VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by VARCHAR(120),
    CONSTRAINT ck_notification_entity_pair CHECK ((entity_type IS NULL) = (entity_id IS NULL)),
    CONSTRAINT ck_notification_ack_pair CHECK ((acknowledged_at IS NULL) = (acknowledged_by IS NULL))
);
-- Service policy: one unacknowledged incident per explicit global dedupe key. Database closes the race.
CREATE UNIQUE INDEX ux_system_notification_active_dedupe ON system_notification(dedupe_key)
    WHERE acknowledged_at IS NULL AND dedupe_key IS NOT NULL;
CREATE INDEX idx_system_notification_created ON system_notification(created_at DESC,id DESC);
CREATE INDEX idx_system_notification_active ON system_notification(severity,created_at DESC)
    WHERE acknowledged_at IS NULL;
