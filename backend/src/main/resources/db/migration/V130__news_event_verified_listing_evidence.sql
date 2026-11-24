-- MI-PR08: strictly evidentiary, append-only mapping from a news event version
-- to an independently attested point-in-time exchange listing.
-- No automatic ticker/issuer guesses and no effect on broker/order services.

CREATE TABLE news_event_verified_listing_evidence (
    event_id UUID NOT NULL,
    version INTEGER NOT NULL,
    receipt_id UUID NOT NULL,
    listing_id UUID NOT NULL REFERENCES golden_listing_identity(listing_id),
    relationship VARCHAR(24) NOT NULL CHECK (
        relationship IN ('AFFECTED_ISSUER', 'MENTION_ONLY', 'UNDERLYING_CONTEXT')
    ),
    listing_identity_digest CHAR(64) NOT NULL CHECK (
        listing_identity_digest ~ '^[0-9a-f]{64}$'
    ),
    mapping_first_known_at TIMESTAMPTZ NOT NULL,
    evidence_method VARCHAR(80) NOT NULL CHECK (length(trim(evidence_method)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, version, receipt_id, listing_id, relationship),
    FOREIGN KEY (event_id, version, receipt_id)
        REFERENCES news_event_evidence(event_id, version, receipt_id)
);

CREATE INDEX ix_news_event_verified_listing_asof
    ON news_event_verified_listing_evidence(listing_id, mapping_first_known_at DESC);
CREATE INDEX ix_news_event_verified_listing_event
    ON news_event_verified_listing_evidence(event_id, version, mapping_first_known_at);

-- A historical event cannot acquire today's issuer identity evidence in the
-- past. mapping_first_known_at is bounded by the event version, original
-- source observation, and when the dated official listing evidence entered
-- the local identity ledger. Recheck at DB insert time, not only in Java.
CREATE FUNCTION news_verified_listing_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    event_known TIMESTAMPTZ;
    source_known TIMESTAMPTZ;
    listing_created TIMESTAMPTZ;
    official_digest CHAR(64);
BEGIN
    SELECT v.effective_known_at, r.first_observed_at,
           listing.created_at, listing.identity_evidence_digest
      INTO event_known, source_known, listing_created, official_digest
      FROM news_event_version v
      JOIN news_event_evidence proof
        ON proof.event_id = v.event_id AND proof.version = v.version
      JOIN news_ingest_receipt r ON r.receipt_id = proof.receipt_id
      JOIN golden_listing_identity listing ON listing.listing_id = NEW.listing_id
     WHERE v.event_id = NEW.event_id
       AND v.version = NEW.version
       AND proof.receipt_id = NEW.receipt_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'missing verified news/listing evidence'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.listing_identity_digest <> official_digest THEN
        RAISE EXCEPTION 'listing evidence digest mismatch'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.mapping_first_known_at < GREATEST(event_known, source_known, listing_created) THEN
        RAISE EXCEPTION 'listing mapping precedes available source evidence'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_news_verified_listing_guard
    BEFORE INSERT ON news_event_verified_listing_evidence
    FOR EACH ROW EXECUTE FUNCTION news_verified_listing_guard();

CREATE TRIGGER trg_news_verified_listing_append_only
    BEFORE UPDATE OR DELETE ON news_event_verified_listing_evidence
    FOR EACH ROW EXECUTE FUNCTION news_ledger_reject_mutation();
