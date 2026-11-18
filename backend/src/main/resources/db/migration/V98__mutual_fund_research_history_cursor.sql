-- Bounded, opt-in historical AMFI NAV imports. No orders or payments.
CREATE TABLE mf_research_history_cursor (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    next_from_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','RUNNING','COMPLETE','FAILED')),
    last_window_end DATE,
    last_imported_at TIMESTAMPTZ,
    accepted_rows INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(60)
);
INSERT INTO mf_research_history_cursor(id,next_from_date)
VALUES (1, CURRENT_DATE - 56);
