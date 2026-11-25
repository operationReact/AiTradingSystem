-- V3-02: immutable raw archive for official India market research sources.
-- Raw storage is evidence only; parser/schema acceptance remains a separate research boundary.
CREATE TABLE official_market_source_snapshot (
    snapshot_id UUID PRIMARY KEY,
    source_family VARCHAR(40) NOT NULL CHECK (source_family IN (
        'NIFTY50_CONSTITUENTS',
        'NIFTY50_HISTORY',
        'INDIA_VIX_HISTORY',
        'SECTOR_INDEX_HISTORY'
    )),
    source_variant VARCHAR(80) NOT NULL DEFAULT '',
    source_url VARCHAR(2048) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[a-f0-9]{64}$'),
    content_type VARCHAR(120) NOT NULL,
    payload BYTEA NOT NULL,
    byte_count INTEGER NOT NULL CHECK (byte_count > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_official_source_variant CHECK (
        (source_family = 'SECTOR_INDEX_HISTORY' AND source_variant <> '')
        OR (source_family <> 'SECTOR_INDEX_HISTORY' AND source_variant = '')
    ),
    CONSTRAINT ck_official_source_byte_count CHECK (byte_count = octet_length(payload)),
    CONSTRAINT uq_official_source_payload UNIQUE (source_family, source_variant, payload_sha256)
);

CREATE INDEX ix_official_source_point_in_time
    ON official_market_source_snapshot
       (source_family, source_variant, observed_at DESC, snapshot_id DESC);
