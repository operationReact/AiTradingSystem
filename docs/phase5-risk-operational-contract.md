# Phase 5 deterministic risk operational contract

This document records the final Phase 5 operational constraints for the deterministic PAPER risk path.

## Metrics

The Micrometer contract is intentionally low-cardinality:

- `risk_evaluations_total{result}` — one increment after a completed deterministic policy evaluation.
- `risk_rule_results_total{rule,outcome}` — one increment for each ordered rule result.
- `risk_evaluation_duration_seconds` — timer around in-memory `RiskPolicyEngine.evaluate` only.
- `risk_policy_version_active` — current policy version observed by the active-policy/evaluation path.
- `risk_emergency_stop_active` — `1` while new PAPER orders are halted, otherwise `0`.
- `risk_position_size_requests_total{result}` — position-size request success/rejection counter.

No metric tag contains order IDs, symbols, correlation IDs, exception messages, or other unbounded values.

## Performance and locking boundary

`RiskContextFactory` owns server-state loading before the engine runs. Its state acquisition is a fixed sequence of current-state/indexed queries: paper account, active instrument identity, current symbol position, aggregate current exposure, open-position count, today's realized P&L, today's fill count, duplicate pending exposure, emergency-stop state, and active policy. The number of queries does not grow with the number of risk rules, positions, fills, or historical candles.

`RiskPolicyEngine` receives a fully materialized `RiskContext`. Every `RiskRule` is a pure in-memory computation. Rules must not inject JDBC/DataSource, repositories, HTTP clients, broker/provider clients, or perform history scans. Adding a rule must not add a DB or network round trip to evaluation.

The PAPER order transaction continues to serialize fresh order evaluation with the existing paper-account lock. Idempotent replay returns the original persisted risk decision/policy snapshot and does not re-run risk evaluation.

## API hardening evidence

Phase 5 API integration coverage collectively verifies:

- active persisted policy retrieval and bounded policy history;
- invalid policy validation and immutable create-and-activate versioning;
- decision detail including ordered rule evidence;
- position sizing with server-owned policy/portfolio/instrument state;
- emergency-stop validation and idempotent state semantics;
- original decision and policy snapshot on idempotent replay.

The Phase 5 exit gate must run this evidence together with baseline CI and previous P3/P4 regression gates before Phase 5 can be marked VERIFIED.
