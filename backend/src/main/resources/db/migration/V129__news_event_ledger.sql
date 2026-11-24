-- MI-PR03 (#1826): canonical event ledger and provenance. Additive only. Existing news tables are untouched.
-- Invariants enforced by the database, not only by application code:
--   * a receipt's first_observed_at is immutable; a receipt is never updated after insert;
--   * event versions and evidence are append-only; a correction or retraction is a new version;
--   * every receipt's source must exist in the rights policy table (default-deny reference).

CREATE TABLE news_ingest_receipt (
    receipt_id UUID PRIMARY KEY,
    source_id VARCHAR(80) NOT NULL REFERENCES news_source_policy (source_id),
    external_id VARCHAR(200),
    source_url TEXT NOT NULL CONSTRAINT ck_news_ingest_receipt_https CHECK (source_url LIKE 'https://%' OR source_url LIKE 'http://%'),
    title VARCHAR(500) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ,
    first_observed_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_news_ingest_receipt_source_hash UNIQUE (source_id, content_hash),
    CONSTRAINT ck_news_ingest_receipt_observed_after_fetch CHECK (first_observed_at <= fetched_at)
);

CREATE TABLE news_canonical_event (
    event_id UUID PRIMARY KEY,
    primary_issuer_key VARCHAR(120),
    event_class VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_news_canonical_event_status CHECK (status IN ('ACTIVE', 'RETRACTED')),
    first_seen_at TIMESTAMPTZ NOT NULL,
    current_version INTEGER NOT NULL DEFAULT 1 CONSTRAINT ck_news_canonical_event_version CHECK (current_version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE news_event_version (
    event_id UUID NOT NULL REFERENCES news_canonical_event (event_id),
    version INTEGER NOT NULL CONSTRAINT ck_news_event_version_positive CHECK (version >= 1),
    effective_known_at TIMESTAMPTZ NOT NULL,
    category VARCHAR(40) NOT NULL,
    summary VARCHAR(600),
    severity NUMERIC(4,3) CONSTRAINT ck_news_event_version_severity CHECK (severity IS NULL OR (severity >= 0 AND severity <= 1)),
    confidence NUMERIC(4,3) CONSTRAINT ck_news_event_version_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    analysis_provider VARCHAR(20),
    analysis_model VARCHAR(80),
    correction_of_version INTEGER,
    retraction BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, version),
    CONSTRAINT ck_news_event_version_correction CHECK (correction_of_version IS NULL OR correction_of_version < version)
);

CREATE TABLE news_event_evidence (
    event_id UUID NOT NULL,
    version INTEGER NOT NULL,
    receipt_id UUID NOT NULL REFERENCES news_ingest_receipt (receipt_id),
    evidence_kind VARCHAR(20) NOT NULL CONSTRAINT ck_news_event_evidence_kind CHECK (evidence_kind IN ('PRIMARY_DISCLOSURE', 'REPUBLICATION', 'REGULATOR', 'OTHER')),
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, version, receipt_id),
    FOREIGN KEY (event_id, version) REFERENCES news_event_version (event_id, version)
);

-- Append-only enforcement. Any UPDATE or DELETE on these tables is rejected by the database.
CREATE FUNCTION news_ledger_reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'news ledger rows are append-only (%)', TG_TABLE_NAME USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_news_ingest_receipt_append_only BEFORE UPDATE OR DELETE ON news_ingest_receipt
    FOR EACH ROW EXECUTE FUNCTION news_ledger_reject_mutation();
CREATE TRIGGER trg_news_event_version_append_only BEFORE UPDATE OR DELETE ON news_event_version
    FOR EACH ROW EXECUTE FUNCTION news_ledger_reject_mutation();
CREATE TRIGGER trg_news_event_evidence_append_only BEFORE UPDATE OR DELETE ON news_event_evidence
    FOR EACH ROW EXECUTE FUNCTION news_ledger_reject_mutation();

CREATE INDEX ix_news_event_evidence_receipt ON news_event_evidence (receipt_id);
CREATE INDEX ix_news_event_version_known ON news_event_version (effective_known_at);
CREATE INDEX ix_news_ingest_receipt_published ON news_ingest_receipt (published_at DESC);
