-- Last verified Upstox V3 LTP for the authenticated customer F&O Markets screen.
-- Strictly non-executable. No simulated/depth-based prices, no OAuth token, no REST prices.
-- Requires an independently verified right to retain/display snapshots.
CREATE TABLE customer_fno_verified_display_snapshot (
    instrument_key VARCHAR(80) PRIMARY KEY,
    exchange_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    ltp NUMERIC(18,4) NOT NULL,
    previous_close NUMERIC(18,4),
    open_interest BIGINT,
    retain_until DATE NOT NULL,
    CONSTRAINT customer_fno_snapshot_key_chk CHECK (
        instrument_key ~ '^(NSE_FO|BSE_FO)\\|[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT customer_fno_snapshot_price_chk CHECK (ltp > 0),
    CONSTRAINT customer_fno_snapshot_oi_chk CHECK (
        open_interest IS NULL OR open_interest >= 0),
    CONSTRAINT customer_fno_snapshot_clock_chk CHECK (observed_at >= exchange_at),
    CONSTRAINT customer_fno_snapshot_retention_chk CHECK (
        retain_until <= (exchange_at AT TIME ZONE 'Asia/Kolkata')::date + 7)
);
CREATE INDEX customer_fno_verified_display_snapshot_purge_idx
    ON customer_fno_verified_display_snapshot(retain_until);
