-- Read-only mutual-fund NAV research. NO payment, SIP, bank, mandate or execution data.
CREATE TABLE mf_research_nav (
    scheme_code INTEGER NOT NULL CHECK (scheme_code > 0),
    nav_date DATE NOT NULL,
    nav NUMERIC(16,6) NOT NULL CHECK (nav > 0),
    source VARCHAR(16) NOT NULL DEFAULT 'AMFI' CHECK (source = 'AMFI'),
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (scheme_code, nav_date)
);
CREATE INDEX ix_mf_research_nav_recent ON mf_research_nav(scheme_code, nav_date DESC);
CREATE TABLE mf_research_alert (
    id UUID PRIMARY KEY,
    scheme_code INTEGER NOT NULL CHECK (scheme_code > 0),
    nav_date DATE NOT NULL,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('PULLBACK_REVIEW', 'DEEP_PULLBACK_REVIEW')),
    drawdown_bps INTEGER NOT NULL CHECK (drawdown_bps BETWEEN 0 AND 10000),
    nav NUMERIC(16,6) NOT NULL CHECK (nav > 0),
    peak_nav NUMERIC(16,6) NOT NULL CHECK (peak_nav >= nav),
    illustrative_amount_inr INTEGER NOT NULL CHECK (illustrative_amount_inr BETWEEN 100 AND 5000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_mf_alert_source_day_kind UNIQUE (scheme_code, nav_date, kind)
);
CREATE INDEX ix_mf_research_alert_recent ON mf_research_alert(nav_date DESC, created_at DESC);
CREATE TABLE mf_research_sync (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    status VARCHAR(20) NOT NULL CHECK (status IN ('NEVER_RUN', 'VERIFIED', 'FAILED')),
    last_checked_at TIMESTAMPTZ,
    latest_nav_date DATE,
    rows_accepted INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(60)
);
INSERT INTO mf_research_sync(id,status) VALUES(1,'NEVER_RUN');
COMMENT ON TABLE mf_research_alert IS 'Watchlist research observations only: not investment orders or individualized advice.';
