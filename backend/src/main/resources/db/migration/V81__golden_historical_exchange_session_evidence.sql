-- D20-05B: append-only provider-scoped historical exchange session evidence.
-- No records are seeded. A calendar must come from a separately approved,
-- licensed and verified external historical source, never a weekday heuristic.
-- Prior vendor revisions remain visible for forensic point-in-time inspection.

CREATE TABLE golden_historical_exchange_session_evidence (
    evidence_id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES golden_data_provider(provider_id) ON DELETE RESTRICT,
    source_domain VARCHAR(80) NOT NULL CHECK (source_domain ~ '^[a-z][a-z0-9_]{2,79}$'),
    exchange VARCHAR(8) NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
    session_date DATE NOT NULL,
    source_revision INTEGER NOT NULL CHECK (source_revision > 0),
    session_status VARCHAR(16) NOT NULL CHECK (session_status IN ('TRADING', 'CLOSED')),
    session_close_at TIMESTAMPTZ,
    source_published_at TIMESTAMPTZ NOT NULL,
    first_available_at TIMESTAMPTZ NOT NULL,
    calendar_evidence_digest CHAR(64) NOT NULL
        CHECK (calendar_evidence_digest ~ '^[a-f0-9]{64}$'),
    verified_at TIMESTAMPTZ NOT NULL,
    verified_by VARCHAR(160) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_golden_historical_session_source_revision UNIQUE
        (provider_id, source_domain, exchange, session_date, source_revision),
    CHECK ((session_status = 'TRADING' AND session_close_at IS NOT NULL)
        OR (session_status = 'CLOSED' AND session_close_at IS NULL)),
    CHECK (source_published_at <= first_available_at
        AND first_available_at <= verified_at
        AND verified_at <= captured_at),
    CHECK (length(trim(verified_by)) > 0),
    CHECK (length(trim(source_reference)) > 0)
);
CREATE INDEX ix_golden_historical_session_lookup
    ON golden_historical_exchange_session_evidence
    (exchange, session_date, provider_id, source_revision DESC);

CREATE FUNCTION golden_reject_historical_session_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Historical exchange session evidence is append-only; publish a new revision'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_golden_historical_session_immutable
BEFORE UPDATE OR DELETE ON golden_historical_exchange_session_evidence
FOR EACH ROW EXECUTE FUNCTION golden_reject_historical_session_mutation();

-- Deliberately NO automatic inserts from the operational market_holiday table,
-- no broker token mapping, no source-rights records, no licensed data download,
-- no scheduler, no AWS resources and no public/admin mutation endpoints.
