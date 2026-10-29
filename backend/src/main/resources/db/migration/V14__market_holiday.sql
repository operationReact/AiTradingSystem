CREATE TABLE market_holiday (
    trade_date DATE PRIMARY KEY,
    market VARCHAR(16) NOT NULL DEFAULT 'NSE_CASH',
    holiday_name VARCHAR(120) NOT NULL,
    source VARCHAR(80) NOT NULL,
    source_as_of DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
