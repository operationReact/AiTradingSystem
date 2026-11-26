-- Keep event classification and only source-evidenced numeric disclosures with each immutable
-- analysis version. These are research observations, not order or price-prediction inputs.
ALTER TABLE news_analyses
    ADD COLUMN event_type VARCHAR(40),
    ADD COLUMN financial_metrics JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE news_analyses
    ADD CONSTRAINT ck_news_analysis_event_type CHECK (event_type IS NULL OR event_type IN (
        'QUARTERLY_RESULTS', 'ANNUAL_RESULTS', 'RESULT_GUIDANCE', 'DIVIDEND',
        'SPLIT_BONUS_RIGHTS', 'BUYBACK', 'ORDER_WIN', 'MERGER_ACQUISITION',
        'OWNERSHIP_CHANGE', 'MANAGEMENT_CHANGE', 'REGULATORY', 'MACRO', 'SECTOR', 'OTHER', 'UNKNOWN')),
    ADD CONSTRAINT ck_news_analysis_financial_metrics_array CHECK (jsonb_typeof(financial_metrics) = 'array');
