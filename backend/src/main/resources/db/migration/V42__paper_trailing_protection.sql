-- V2-PAPER-05: persist optional PAPER trailing protection on the existing order/position model.
-- No parallel position store is introduced. A NULL trailing_stop_percent means trailing protection is OFF.

ALTER TABLE paper_order
    ADD COLUMN trailing_stop_percent NUMERIC(8,4);

ALTER TABLE paper_order
    ADD CONSTRAINT ck_paper_order_trailing_stop_percent
        CHECK (trailing_stop_percent IS NULL OR (trailing_stop_percent >= 0.5 AND trailing_stop_percent <= 20.0)),
    ADD CONSTRAINT ck_paper_order_trailing_buy_only
        CHECK (trailing_stop_percent IS NULL OR side = 'BUY');

ALTER TABLE paper_position
    ADD COLUMN trailing_stop_percent NUMERIC(8,4),
    ADD COLUMN high_water_mark NUMERIC(20,6),
    ADD COLUMN high_water_at TIMESTAMPTZ,
    ADD COLUMN effective_protective_stop NUMERIC(20,6);

ALTER TABLE paper_position
    ADD CONSTRAINT ck_paper_position_trailing_stop_percent
        CHECK (trailing_stop_percent IS NULL OR (trailing_stop_percent >= 0.5 AND trailing_stop_percent <= 20.0)),
    ADD CONSTRAINT ck_paper_position_trailing_state
        CHECK (
            (trailing_stop_percent IS NULL
                AND high_water_mark IS NULL
                AND high_water_at IS NULL
                AND effective_protective_stop IS NULL)
            OR
            (trailing_stop_percent IS NOT NULL
                AND high_water_mark IS NOT NULL
                AND high_water_mark > 0
                AND high_water_at IS NOT NULL
                AND effective_protective_stop IS NOT NULL
                AND effective_protective_stop > 0)
        );

CREATE INDEX idx_paper_position_trailing_active
    ON paper_position (updated_at)
    WHERE quantity > 0 AND trailing_stop_percent IS NOT NULL;
