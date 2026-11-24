-- MI-PR02 (#1826): per-source rights policy. Default-deny: every seeded source starts REVIEW_PENDING with no
-- permitted use. An APPROVED row must carry a reviewer reference and an effective start, so an approval is never
-- implicit. Additive migration; existing news tables are not altered.
CREATE TABLE news_source_policy (
    source_id VARCHAR(80) PRIMARY KEY,
    publisher VARCHAR(160) NOT NULL,
    license_state VARCHAR(20) NOT NULL
        CONSTRAINT ck_news_source_policy_state CHECK (license_state IN ('APPROVED', 'REVIEW_PENDING', 'REJECTED', 'REVOKED')),
    permitted_uses TEXT[] NOT NULL DEFAULT '{}'
        CONSTRAINT ck_news_source_policy_uses CHECK (permitted_uses <@ ARRAY['INGEST', 'DISPLAY', 'NLP_TRANSFER', 'RETAIN']::text[]),
    reviewer_ref VARCHAR(200),
    terms_ref VARCHAR(500),
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    content_ttl_days INTEGER NOT NULL DEFAULT 30
        CONSTRAINT ck_news_source_policy_ttl CHECK (content_ttl_days BETWEEN 1 AND 365),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_news_source_policy_window CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_news_source_policy_approved CHECK (
        license_state <> 'APPROVED' OR (reviewer_ref IS NOT NULL AND effective_from IS NOT NULL))
);

INSERT INTO news_source_policy (source_id, publisher, license_state, permitted_uses) VALUES
    ('moneycontrol', 'Moneycontrol', 'REVIEW_PENDING', '{}'),
    ('economic-times', 'The Economic Times', 'REVIEW_PENDING', '{}'),
    ('sebi', 'Securities and Exchange Board of India', 'REVIEW_PENDING', '{}');
