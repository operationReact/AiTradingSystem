-- GitHub developer report observations, explicitly separate from source ingestion
-- and paper-readiness acceptance. No credentials or broker data are stored.
INSERT INTO minttick_upstox_analytics_progress (task_code, state, reason_code)
VALUES ('WORKER_REPORTS', 'NOT_STARTED', 'FIRST_WORKER_REPORT_POLL_PENDING')
ON CONFLICT (task_code) DO NOTHING;
