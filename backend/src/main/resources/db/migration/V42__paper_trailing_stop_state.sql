-- V2-PAPER-05: explicit trailing-stop persistence on the existing PAPER order/position model.
-- A NULL trailing_percent means trailing protection is disabled. No hidden/default percentage exists.

ALTER TABLE paper_order
    ADD COLUMN trailing_percent NUMERIC(7,4);

ALTER TABLE paper_order
    ADD CONSTRAINT ck_paper_order_trailing_percent
        CHECK (trailing_percent IS NULL OR (trailing_percent > 0 AND trailing_percent < 100));

ALTER TABLE paper_position
    ADD COLUMN trailing_percent NUMERIC(7,4),
    ADD COLUMN high_water_mark NUMERIC(20,6),
    ADD COLUMN high_water_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN effective_protective_stop NUMERIC(20,6),
    ADD COLUMN trailing_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE paper_position
    ADD CONSTRAINT ck_paper_position_trailing_percent
        CHECK (trailing_percent IS NULL OR (trailing_percent > 0 AND trailing_percent < 100)),
    ADD CONSTRAINT ck_paper_position_trailing_state
        CHECK (
            (trailing_percent IS NULL
                AND high_water_mark IS NULL
                AND high_water_at IS NULL
                AND effective_protective_stop IS NULL)
            OR
            (trailing_percent IS NOT NULL
                AND high_water_mark IS NOT NULL
                AND high_water_at IS NOT NULL
                AND effective_protective_stop IS NOT NULL)
        ),
    ADD CONSTRAINT ck_paper_position_high_water_positive
        CHECK (high_water_mark IS NULL OR high_water_mark > 0),
    ADD CONSTRAINT ck_paper_position_effective_stop_positive
        CHECK (effective_protective_stop IS NULL OR effective_protective_stop > 0),
    ADD CONSTRAINT ck_paper_position_trailing_version
        CHECK (trailing_version >= 0);
