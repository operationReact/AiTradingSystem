# P08-PR04 — Operational health and incident projection

Authenticated `GET /api/operations/health` returns diagnostic states for database, Angel One session/read-only readiness, catalogue activation age, the latest aggregate active-catalogue one-minute candle, news ingestion age, scanner run, reconciliation, execution mode and emergency stop. Every component carries its reason and available as-of/age evidence. Missing observations are UNKNOWN, not healthy by default. A clean reconciliation older than the latest fill is behind execution.

The read uses `AngelOneHealthService.status()` only. It never invokes login, a connection test, a quote request or an order submission. Broker readiness is in-process evidence and may become unverified after restart. Global latest-bar health does not assert freshness of every instrument. Market freshness is IDLE outside the cash session; exact trade readiness still belongs to the deterministic execution checks.

Diagnostic thresholds are configurable: catalogue 86400s, market 300s, news/scanner 3600s by default. They are health-display limits, not changes to active hard-risk policy. Future timestamps are degraded evidence. A database read failure returns unavailable/unknown controls without exception text, SQL, connection strings or secrets. `liveAvailable` remains false. Emergency-stop state is nullable when database truth is unavailable.

## Durable incidents

`notification_source_projection` records per-source event receipts in the same transaction as notification creation. Concurrent projection claims one receipt; the notification reference is filled before commit. A crash/error rolls back the claim. Acknowledged historical failures remain projected and are not reopened by another status poll.

`OperationalIncidentRefreshService` drains up to 20 unprojected failed backfill checkpoints and 20 failed scenario runs per call, exposing `backlogRemaining` instead of pretending the backlog is complete. It uses anti-joins on durable receipts rather than a timestamp cursor that could skip a late commit. Provider degradation and stale-market observations use stable evidence identities. No raw stored provider error text enters notification messages.

Unexpected HTTP backtest/validation/walk-forward execution failures produce a fixed diagnostic incident after their service transaction returns/rolls back. Expected validation/data-eligibility rejections retain existing responses without creating outage alerts. If diagnostic persistence is also unavailable, the original API error is preserved. The notifications API allocation P08-PR06 coordinates refresh/list access; health reads themselves remain observational.

Health is not execution authorization. Acknowledgement/projection never resets a halt, repairs accounting or changes policy. Deployment-health incident sources remain allocated to later deployment work.
