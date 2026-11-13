-- Official BSE equity-security proof, separate from the Angel One broker instrument master.
-- A source download NEVER becomes an eligible cash equity unless its EQ/A/E flags pass validation.
-- Preserve historical reference versions and source custody. Do not retrofit old research snapshots.
CREATE TABLE bse_security_master_import (
    id UUID PRIMARY KEY,
    source_url VARCHAR(300) NOT NULL,
    source_file VARCHAR(90) NOT NULL,
    source_as_of DATE NOT NULL,
    archive_sha256 CHAR(64) NOT NULL CHECK (archive_sha256 ~ '^[0-9a-f]{64}$'),
    total_records INTEGER NOT NULL CHECK (total_records > 0),
    verified_equities INTEGER NOT NULL CHECK (verified_equities > 0 AND verified_equities <= total_records),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_bse_security_master_sha
    ON bse_security_master_import (archive_sha256);

CREATE TABLE bse_verified_cash_equity (
    import_id UUID NOT NULL REFERENCES bse_security_master_import(id),
    scrip_code VARCHAR(15) NOT NULL CHECK (scrip_code ~ '^[0-9]{1,15}$'),
    security_id VARCHAR(20) NOT NULL,
    isin CHAR(12) NOT NULL CHECK (isin ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$'),
    PRIMARY KEY (import_id, scrip_code)
);
CREATE INDEX ix_bse_verified_cash_equity_code ON bse_verified_cash_equity(scrip_code);

CREATE TABLE bse_security_master_active (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    active_import UUID REFERENCES bse_security_master_import(id),
    activated_at TIMESTAMPTZ
);
INSERT INTO bse_security_master_active(singleton_id,active_import) VALUES (1,NULL);
