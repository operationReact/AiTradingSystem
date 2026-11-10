-- D20-05E: persistent, NO-FETCH vendor-page planning and lease metadata.
-- Exactly one 2000-calendar-day provider acquisition window is distinct from
-- the D20-03B <=31-day immutable quality/archive chunk.
-- No backfill scheduler, source adapter, rights grant, S3, IAM or data is seeded.

CREATE TABLE golden_vendor_page_plan (
    page_id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES golden_data_provider(provider_id) ON DELETE RESTRICT,
    listing_id UUID NOT NULL REFERENCES golden_listing_identity(listing_id) ON DELETE RESTRICT,
    source_domain VARCHAR(80) NOT NULL
        CHECK (source_domain ~ '^[a-z][a-z0-9_]{2,79}$'),
    interval_name VARCHAR(24) NOT NULL CHECK (interval_name = 'ONE_DAY'),
    from_date DATE NOT NULL,
    through_date DATE NOT NULL,
    dataset_revision INTEGER NOT NULL CHECK (dataset_revision > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','LEASED','RETRY','FAILED','STAGED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01T00:00:00Z',
    native_object_sha256 CHAR(64) CHECK (native_object_sha256 ~ '^[a-f0-9]{64}$'),
    native_object_uri VARCHAR(1024),
    native_object_version VARCHAR(240),
    source_evidence_digest CHAR(64)
        CHECK (source_evidence_digest ~ '^[a-f0-9]{64}$'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_golden_vendor_page_window UNIQUE
        (provider_id, listing_id, source_domain, interval_name,
         from_date, through_date, dataset_revision),
    CONSTRAINT ck_golden_vendor_page_2000_days CHECK
        (through_date >= from_date AND through_date - from_date <= 1999),
    CONSTRAINT ck_golden_vendor_lease CHECK
        ((status = 'LEASED') =
         (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_golden_vendor_staged CHECK
        (status <> 'STAGED' OR
         (native_object_sha256 IS NOT NULL AND native_object_uri IS NOT NULL
          AND native_object_version IS NOT NULL
          AND source_evidence_digest IS NOT NULL
          AND length(trim(native_object_uri)) > 0
          AND length(trim(native_object_version)) > 0))
);
CREATE INDEX ix_golden_vendor_page_lease
    ON golden_vendor_page_plan (provider_id, source_domain, status,
                                next_attempt_at, lease_until, from_date);

-- A single provider-wide pacing row is locked before leasing another page.
-- The 10-second default is deliberately conservative and not an assertion
-- of Angel One's actual approved daily quota or commercial permission.
CREATE TABLE golden_vendor_page_provider_gate (
    provider_id UUID PRIMARY KEY
        REFERENCES golden_data_provider(provider_id) ON DELETE RESTRICT,
    next_allowed_at TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01T00:00:00Z',
    min_spacing_seconds INTEGER NOT NULL DEFAULT 10
        CHECK (min_spacing_seconds BETWEEN 1 AND 3600),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Committed object proof may not be erased or overwritten; a revision requires
-- a new page/dataset revision. Planned/failed metadata stays available for audit.
CREATE FUNCTION golden_reject_staged_vendor_page_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status = 'STAGED' THEN
        RAISE EXCEPTION 'Vendor page custody is immutable; use a new revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tr_golden_vendor_page_custody
BEFORE UPDATE OR DELETE ON golden_vendor_page_plan
FOR EACH ROW EXECUTE FUNCTION golden_reject_staged_vendor_page_mutation();

-- The migration does not create verified rights, instrument aliases or
-- historical exchange session evidence. It never fetches any provider bytes.
