# Phase 6 PAPER execution and accounting completion contract

Phase 6 is complete only when persisted PAPER state reconciles across orders, fills, positions, cash, realized/unrealized P&L and portfolio snapshots.

## Canonical invariants

- An idempotent replay returns the original persisted order/result and cannot create a second fill, cash entry, risk decision or audit outcome.
- A rejected order cannot mutate cash, positions, fills or settlement ledger state.
- Concurrent duplicate submissions serialize on the PAPER account/idempotency boundary and produce exactly one settlement.
- Concurrent different orders for the same protected exposure cannot both bypass deterministic risk/account state.
- Fill sequences are unique per order and accounting applies only the newly accepted fill delta.
- Position quantity and cost basis are derived from persisted fills/settlements and remain product-aware.
- PAPER cash is reconstructed and reconciled against the immutable cash-entry ledger; reconciliation records differences but never silently repairs source evidence.
- Portfolio realized P&L is ledger/fill-derived; unrealized P&L uses persisted open positions plus the canonical server-side mark source.
- Portfolio snapshots are durable database evidence, not process-memory state.
- Restart safety means a fresh service process can reconstruct orders, fills, positions, cash, P&L, snapshots and reconciliation state solely from PostgreSQL; no correctness-critical PAPER accounting state may exist only in an in-memory cache.
- Intraday end-of-session behavior is idempotent and must not alter DELIVERY positions.
- PAPER execution never implies LIVE broker submission.

## Exit evidence

The Phase 6 completion workflow must run PostgreSQL/Testcontainers evidence covering order replay/rejection/concurrency, partial-fill persistence, position accounting, portfolio valuation/snapshots, reconciliation and intraday close behavior. It must also run E2E-P6 browser evidence for the persisted flow:

`fills -> positions -> cash ledger -> reconciliation`

The browser evidence must cover representative Pro Terminal desktop and Clear Invest mobile layouts, reject serious/critical accessibility violations, and reject horizontal viewport overflow.

Canonical Figma traceability: Phase 6 `Portfolio, Positions & Reconciliation` section `23:2949`, representative variants `23:2952 / 23:3017 / 23:3078 / 23:3143 / 23:3204 / 23:3265 / 23:3322 / 23:3383`.
