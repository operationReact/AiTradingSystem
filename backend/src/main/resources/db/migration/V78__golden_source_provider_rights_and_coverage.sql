-- D20-03A: source-by-source entitlement and coverage custody for Indian market research.
-- Empty on deployment. A working Angel One API is NOT evidence of permission
-- to retain, train upon, display or redistribute historic NSE/BSE market data.
-- No default allow or automatic registration of vendor rights.

CREATE TABLE golden_data_provider (
    provider_id UUID PRIMARY KEY,
    provider_code VARCHAR(80) NOT NULL UNIQUE,
    legal_name VARCHAR(240) NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK (
        source_kind IN ('EXTERNAL', 'SYNTHETIC_TEST')
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (provider_code ~ '^[A-Z0-9_]{3,80}$'),
    CHECK (length(trim(legal_name)) > 0)
);

-- One attested permission for one specific use of one source domain.
-- Receipts are evidence references, not an independent cryptographic licence
-- verifier. Only a future reviewed verification workflow may populate these.
CREATE TABLE golden_source_rights_receipt (
    receipt_id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES golden_data_provider(provider_id) ON DELETE RESTRICT,
    source_domain VARCHAR(80) NOT NULL,
    permitted_use VARCHAR(24) NOT NULL CHECK (
        permitted_use IN (
            'RAW_RETENTION', 'MODEL_TRAINING', 'MODEL_INFERENCE',
            'END_USER_DISPLAY', 'REDISTRIBUTION'
        )
    ),
    receipt_status VARCHAR(20) NOT NULL CHECK (
        receipt_status IN ('PENDING', 'VERIFIED', 'REVOKED', 'EXPIRED', 'REJECTED')
    ),
    terms_digest CHAR(64) NOT NULL CHECK (terms_digest ~ '^[a-f0-9]{64}$'),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    verified_by VARCHAR(160),
    revoked_at TIMESTAMPTZ,
    source_reference VARCHAR(240) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_domain ~ '^[a-z][a-z0-9_]{2,79}$'),
    CHECK (length(trim(source_reference)) > 0),
    CONSTRAINT ck_golden_rights_valid_range CHECK (
        valid_to IS NULL OR valid_to > valid_from
    ),
    CONSTRAINT ck_golden_rights_verification CHECK (
        receipt_status <> 'VERIFIED'
        OR (verified_at IS NOT NULL AND verified_by IS NOT NULL
            AND length(trim(verified_by)) > 0
            AND verified_at <= captured_at AND revoked_at IS NULL)
    ),
    CONSTRAINT ck_golden_rights_revocation CHECK (
        (receipt_status = 'REVOKED' AND revoked_at IS NOT NULL
            AND revoked_at <= captured_at)
        OR (receipt_status <> 'REVOKED' AND revoked_at IS NULL)
    )
);
CREATE INDEX ix_golden_rights_lookup ON golden_source_rights_receipt
    (provider_id, source_domain, permitted_use, receipt_status, valid_from, valid_to);
CREATE INDEX ix_golden_rights_revision ON golden_source_rights_receipt
    (provider_id, source_domain, captured_at DESC);

-- Actual data inventory, not merely a claim that an HTTP request succeeded.
-- One immutable coverage assertion is scoped to a dataset/revision and a
-- listing or global benchmark; no fabricated bars or mocked readiness flags.
CREATE TABLE golden_source_coverage_evidence (
    coverage_id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES golden_data_provider(provider_id) ON DELETE RESTRICT,
    source_domain VARCHAR(80) NOT NULL,
    scope_id VARCHAR(160) NOT NULL,
    dataset_digest CHAR(64) NOT NULL CHECK (dataset_digest ~ '^[a-f0-9]{64}$'),
    interval_name VARCHAR(24) NOT NULL,
    from_date DATE NOT NULL,
    through_date DATE NOT NULL,
    expected_records BIGINT NOT NULL CHECK (expected_records >= 0),
    valid_records BIGINT NOT NULL CHECK (valid_records >= 0),
    first_available_at TIMESTAMPTZ NOT NULL,
    last_available_at TIMESTAMPTZ NOT NULL,
    quality_status VARCHAR(24) NOT NULL CHECK (
        quality_status IN ('VERIFIED', 'INCOMPLETE', 'QUARANTINED', 'UNKNOWN')
    ),
    source_evidence_digest CHAR(64) NOT NULL CHECK (
        source_evidence_digest ~ '^[a-f0-9]{64}$'
    ),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_date <= through_date),
    CHECK (valid_records <= expected_records),
    CHECK (quality_status <> 'VERIFIED'
        OR (expected_records > 0 AND valid_records = expected_records)),
    CHECK (first_available_at <= last_available_at),
    CHECK (last_available_at <= recorded_at),
    CHECK (source_domain ~ '^[a-z][a-z0-9_]{2,79}$'),
    CHECK (length(trim(scope_id)) > 0),
    CHECK (length(trim(interval_name)) > 0),
    CONSTRAINT uq_golden_source_coverage_revision UNIQUE (
        provider_id, source_domain, scope_id, dataset_digest,
        interval_name, from_date, through_date
    )
);
CREATE INDEX ix_golden_coverage_by_scope ON golden_source_coverage_evidence
    (source_domain, scope_id, interval_name, through_date DESC);
CREATE INDEX ix_golden_coverage_provider ON golden_source_coverage_evidence
    (provider_id, recorded_at DESC);

-- Intentionally no INSERTs, synthetic rights, release-time backfill, broker
-- login, AWS IAM permissions, or payment/contract acceptance in this migration.
