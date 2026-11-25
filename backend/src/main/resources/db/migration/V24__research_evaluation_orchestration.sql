ALTER TABLE research_scenario_run
    ADD COLUMN request_fingerprint CHAR(64);

ALTER TABLE research_scenario_run
    ADD CONSTRAINT ck_research_scenario_request_fingerprint
        CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX ux_research_scenario_candidate_fingerprint
    ON research_scenario_run(candidate_id, request_fingerprint)
    WHERE candidate_id IS NOT NULL AND request_fingerprint IS NOT NULL;

CREATE TABLE research_evaluation (
    id UUID PRIMARY KEY,
    candidate_id BIGINT NOT NULL REFERENCES research_scan_candidate(id),
    backtest_run_id UUID NULL REFERENCES paper_backtest_run(id),
    scenario_run_id UUID NOT NULL REFERENCES research_scenario_run(id),
    decision_label VARCHAR(24) NOT NULL,
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_research_evaluation_decision_label CHECK (
        decision_label IN ('FAVORABLE','MIXED','UNFAVORABLE','INSUFFICIENT_DATA')
    )
);

CREATE INDEX idx_research_evaluation_candidate_created
    ON research_evaluation(candidate_id, created_at DESC);

CREATE INDEX idx_research_evaluation_scenario
    ON research_evaluation(scenario_run_id);
