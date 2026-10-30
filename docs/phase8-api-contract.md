# P08-PR06 — Supported operator APIs

All routes require the existing operator API key. Read DTOs expose missing coverage and observation times; they do not grant execution authority. Full `/portfolio`, `/operations`, `/audit` presentation belongs to Phase 9 and must reuse the same server data in both experiences.

| Route | Contract |
| --- | --- |
| GET `/api/analytics/portfolio?from=&to=` | Period totals, coverage, equity, daily-close drawdown, exit expectancy and exposure snapshot |
| GET `/api/analytics/equity?from=&to=` | Period, coverage, dated equity and daily-close drawdown |
| GET `/api/analytics/strategies?from=&to=` | Period and bounded strategy performance/research-cohort items |
| GET `/api/analytics/research?from=&to=` | Advisory scan/evaluation/backtest/scenario summaries |
| POST `/api/analytics/portfolio/recalculate` | Body `{ "tradeDate": "YYYY-MM-DD" }`; deterministic audited rebuild of one completed IST day |
| GET `/api/notifications?activeOnly=true&severity=&page=0&size=20` | Bounded page plus projection progress/backlog flag |
| GET `/api/notifications/{id}` | Persisted incident detail |
| POST `/api/notifications/{id}/acknowledge` | Idempotent acknowledgement; actor comes from authenticated principal, not body claims |
| GET `/api/operations/health` | Network-free diagnostic component evidence |
| GET `/api/audit?eventType=&entityType=&entityId=&correlationId=&actor=&from=&to=&page=&size=` | Bounded, redacted, stable audit page |
| GET `/api/audit/export` | Same audit filters; bounded UTF-8 CSV attachment with formula protection |

Analytics dates are inclusive `YYYY-MM-DD`, defaulting to the last 30 **completed** Asia/Kolkata dates. Ranges are at most 366 days. Audit from/to are ISO instants with a half-open interval, default seven days and maximum 31 days. Readers never fill missing cache days with fictional returns. Source/coverage limits are in the performance, notifications, operations and audit contracts.

Notification reads coordinate a bounded drain of unprojected durable job failures and stable provider/staleness observations. The progress flag remains true while more durable failures await projection; later reads continue. Acknowledged historical failures do not reopen. Projection and acknowledgement do not change risk, halt, execution policy, source accounting or broker session.

Performance rebuilds are audited in the same transaction as their derived cache update. Missing mark, ledger mismatch or unsupported attribution returns a correlated normalized HTTP 409 with a bounded known error code; unfinished dates and invalid query bounds return 400. An operator must repair/source the evidence rather than accepting invented performance.

The HTTP/PostgreSQL contract test exercises every supported route's authentication, data-state/advisory responses, materialization and audit, accounting-conflict correlation, principal-owned acknowledgement, CSV download and failed-checkpoint projection/replay without provider-error leakage.
