-- Mintick Mind in-app progress evidence (issue #998).
-- The audited capability inventory is a versioned classpath manifest; this table
-- holds append-only runtime evidence and owner notes so verification history
-- survives restarts. Rows are never updated or deleted by application code.
CREATE TABLE mind_capability_event (
    id UUID PRIMARY KEY,
    capability_id VARCHAR(96) NOT NULL,
    event_type VARCHAR(32) NOT NULL
      CHECK (event_type IN ('RUNTIME_VERIFIED','RUNTIME_FAILED','DATA_BLOCKED',
                            'NOTE','RECHECK_REQUESTED')),
    backend_revision VARCHAR(64) NOT NULL,
    dataset_version VARCHAR(128),
    evidence_ref VARCHAR(256),
    detail VARCHAR(1000),
    actor VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    observed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_mind_capability_runtime_actor CHECK (
      event_type IN ('NOTE','RECHECK_REQUESTED') OR actor = 'SYSTEM')
);

CREATE INDEX idx_mind_capability_event_lookup
    ON mind_capability_event (capability_id, recorded_at DESC);

CREATE OR REPLACE FUNCTION mind_capability_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'mind_capability_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_mind_capability_event_append_only
    BEFORE UPDATE OR DELETE ON mind_capability_event
    FOR EACH ROW EXECUTE FUNCTION mind_capability_event_append_only();
