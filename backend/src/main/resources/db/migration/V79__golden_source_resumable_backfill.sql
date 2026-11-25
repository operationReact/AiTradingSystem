-- D20-03B: dormant, provider-independent historical data ingestion control plane.
-- No source, licence, broker, AWS object store, scheduler or credential is provisioned.
-- Jobs and chunks use permanent listing IDs from V77, never current broker tokens.

CREATE TABLE golden_dataset_backfill_job (
    job_id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES golden_data_provider(provider_id) ON DELETE RESTRICT,
    listing_id UUID NOT NULL REFERENCES golden_listing_identity(listing_id) ON DELETE RESTRICT,
    source_domain VARCHAR(80) NOT NULL CHECK (source_domain ~ '^[a-z][a-z0-9_]{2,79}$'),
    interval_name VARCHAR(24) NOT NULL CHECK (interval_name ~ '^[A-Z][A-Z0-9_]{1,23}$'),
    from_date DATE NOT NULL,
    through_date DATE NOT NULL,
    dataset_revision INTEGER NOT NULL CHECK (dataset_revision > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETE', 'BLOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_date <= through_date),
    CONSTRAINT uq_golden_dataset_job UNIQUE
        (provider_id, listing_id, source_domain, interval_name,
         from_date, through_date, dataset_revision)
);
CREATE INDEX ix_golden_backfill_job_status
    ON golden_dataset_backfill_job (status, created_at);

-- Chunk-level immutable output references are set only after independent
-- validation AND durable archiving. A successful HTTP response is insufficient.
-- Attempts and lease fencing survive restarts. Partial/invalid data never
-- creates a VERIFIED checkpoint or implies model readiness.
CREATE TABLE golden_dataset_backfill_chunk (
    chunk_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES golden_dataset_backfill_job(job_id) ON DELETE RESTRICT,
    from_date DATE NOT NULL,
    through_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'LEASED', 'RETRY', 'VERIFIED',
                         'INCOMPLETE', 'QUARANTINED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01T00:00:00Z',
    expected_records BIGINT,
    valid_records BIGINT,
    archive_uri VARCHAR(1024),
    dataset_digest CHAR(64),
    source_evidence_digest CHAR(64),
    first_available_at TIMESTAMPTZ,
    last_available_at TIMESTAMPTZ,
    error_code VARCHAR(48),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_date <= through_date),
    CHECK (expected_records IS NULL OR expected_records >= 0),
    CHECK (valid_records IS NULL OR (valid_records >= 0 AND
        expected_records IS NOT NULL AND valid_records <= expected_records)),
    CHECK (dataset_digest IS NULL OR dataset_digest ~ '^[a-f0-9]{64}$'),
    CHECK (source_evidence_digest IS NULL OR source_evidence_digest ~ '^[a-f0-9]{64}$'),
    CHECK ((status = 'LEASED') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status <> 'VERIFIED' OR (expected_records > 0 AND valid_records = expected_records)),
    CHECK (status NOT IN ('VERIFIED', 'INCOMPLETE') OR
        (archive_uri IS NOT NULL AND length(trim(archive_uri)) > 0
         AND dataset_digest IS NOT NULL AND source_evidence_digest IS NOT NULL)),
    CONSTRAINT uq_golden_backfill_chunk_range UNIQUE (job_id, from_date, through_date)
);
CREATE INDEX ix_golden_backfill_chunk_claim
    ON golden_dataset_backfill_chunk (status, next_attempt_at, lease_until, job_id);

-- Provider-level throttling prevents independent listing chunks causing a
-- retry storm during one vendor outage. A single failed job cannot bypass it.
CREATE TABLE golden_dataset_provider_cooldown (
    provider_id UUID PRIMARY KEY REFERENCES golden_data_provider(provider_id) ON DELETE RESTRICT,
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures BETWEEN 0 AND 8),
    retry_after TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deliberately no grants, sample provider records, job seeds, scheduled worker,
-- external historical queries, or cloud resources.
