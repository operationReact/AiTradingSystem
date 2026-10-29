ALTER TABLE risk_decision
    ADD COLUMN policy_config_id UUID,
    ADD COLUMN result_class VARCHAR(24),
    ADD COLUMN policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN reward_risk_ratio NUMERIC(18,8),
    ADD COLUMN gross_exposure_after NUMERIC(20,2),
    ADD COLUMN symbol_exposure_after NUMERIC(20,2),
    ADD COLUMN trades_today_after INTEGER,
    ADD COLUMN correlation_id VARCHAR(64);

ALTER TABLE risk_decision
    ADD CONSTRAINT fk_risk_decision_policy_config
        FOREIGN KEY (policy_config_id) REFERENCES risk_policy_config(id);

ALTER TABLE risk_decision
    ADD CONSTRAINT ck_risk_decision_result_class
        CHECK (result_class IS NULL OR result_class IN ('APPROVED','APPROVAL_REQUIRED','REJECTED'));

CREATE INDEX ix_risk_decision_policy_config ON risk_decision (policy_config_id);
CREATE INDEX ix_risk_decision_result_class_evaluated ON risk_decision (result_class, evaluated_at DESC);

CREATE TABLE risk_decision_rule (
    risk_decision_id UUID NOT NULL REFERENCES risk_decision(id) ON DELETE CASCADE,
    rule_sequence INTEGER NOT NULL,
    rule_code VARCHAR(80) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    observed_value NUMERIC(24,8),
    limit_value NUMERIC(24,8),
    unit VARCHAR(24),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (risk_decision_id, rule_code),
    CONSTRAINT ck_risk_rule_sequence CHECK (rule_sequence > 0),
    CONSTRAINT ck_risk_rule_outcome CHECK (outcome IN ('PASS','HARD_REJECT','APPROVAL_REQUIRED'))
);

CREATE INDEX ix_risk_decision_rule_outcome
    ON risk_decision_rule (outcome, rule_code);
