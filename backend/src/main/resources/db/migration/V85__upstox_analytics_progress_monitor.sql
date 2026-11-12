-- Safe operational telemetry only. No candles, broker accounts, OAuth tokens or user positions.
-- A leased task can be resumed after a crashed worker without showing fake completion.
CREATE TABLE minttick_upstox_analytics_progress (
    task_code VARCHAR(48) PRIMARY KEY,
    state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED'
      CHECK (state IN ('NOT_STARTED','RUNNING','VERIFIED','PARTIAL','FAILED','BLOCKED','DISABLED')),
    checked_count BIGINT,
    verified_count BIGINT,
    issue_count BIGINT,
    reason_code VARCHAR(96),
    details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    CONSTRAINT ck_upstox_progress_counts CHECK (
      (checked_count IS NULL OR checked_count >= 0)
      AND (verified_count IS NULL OR verified_count >= 0)
      AND (issue_count IS NULL OR issue_count >= 0)
      AND (verified_count IS NULL OR checked_count IS NULL OR verified_count <= checked_count)
    )
);

INSERT INTO minttick_upstox_analytics_progress (task_code, state, reason_code)
VALUES
 ('GITHUB_DEVELOPMENT', 'NOT_STARTED', 'FIRST_POLL_PENDING'),
 ('SOURCE_VERIFICATION', 'DISABLED', 'OPERATOR_OPT_IN_REQUIRED'),
 ('INSTRUMENT_MAPPING', 'NOT_STARTED', 'NO_VERIFIED_COVERAGE'),
 ('HISTORICAL_INGESTION', 'NOT_STARTED', 'NO_VERIFIED_COVERAGE'),
 ('QUALITY_RIGHTS', 'NOT_STARTED', 'NO_VERIFIED_RIGHTS_RECEIPT'),
 ('FUNDAMENTALS', 'NOT_STARTED', 'NO_VERIFIED_COVERAGE'),
 ('MIND_DATASETS', 'NOT_STARTED', 'NO_VERIFIED_MIND_FEED'),
 ('PAPER_READINESS', 'NOT_STARTED', 'NO_EXECUTION_EVIDENCE')
ON CONFLICT (task_code) DO NOTHING;
