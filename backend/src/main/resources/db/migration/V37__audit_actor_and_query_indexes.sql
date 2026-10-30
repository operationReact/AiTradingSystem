-- Do not invent authenticated identities for historical records that never captured one.
ALTER TABLE audit_event ADD COLUMN actor VARCHAR(120);
ALTER TABLE audit_event ADD COLUMN actor_source VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNKNOWN'
    CHECK (actor_source IN ('LEGACY_UNKNOWN','AUTHENTICATED','SYSTEM'));
CREATE INDEX idx_audit_event_actor_created ON audit_event(actor,created_at DESC,id DESC);
CREATE INDEX idx_audit_event_type_created ON audit_event(event_type,created_at DESC,id DESC);
CREATE INDEX idx_audit_event_correlation_created ON audit_event(correlation_id,created_at DESC,id DESC);
