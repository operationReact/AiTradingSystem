# P08-PR01 — Daily performance materialization

`PerformanceAggregationService.recalculate(tradeDate)` rebuilds a **closed Asia/Kolkata day**. The two daily tables are derived caches, never execution or accounting authority. APIs and scheduling are later allocations; this service does not start paid resources or place orders.

## Accounting definitions

- Opening and closing cash are ledger sums strictly before their respective midnight boundaries. Capital seeds during the day are external flows, excluded from investment P&L.
- Historical holdings and their remaining cost basis are replayed from persisted fills. Current mutable positions and account balance are not used for a historical result.
- Held instruments use the last persisted candle **strictly before** the relevant boundary. Valuation evidence records instrument, candle ID, exact price/time and boundary. This is a last-known persisted mark, not an assertion that a fresh exchange close is available. Missing instrument/mark evidence fails without publishing a fabricated value.
- Net P&L = closing equity − opening equity − external capital flow. It must equal daily realized fill P&L plus the change in unrealized P&L; mismatch aborts the transaction.
- Gross P&L adds the day's persisted fees and slippage back to net P&L. Returns use opening equity; a zero opening denominator produces NULL.
- Trade count is distinct executed order IDs in the day, so partial fills do not inflate it. Wins/losses count SELL orders by their combined realized result for that day; breakeven exits are neither.
- Drawdown is explicitly based on persisted equity samples, not continuous market observation. Insufficient samples or an external capital flow produce NULL/UNAVAILABLE, never an invented zero.

## Strategy attribution and limitations

Strategy rows use order-owned strategy identity; missing identity is `UNATTRIBUTED`. They provide realized/unrealized P&L, fees/slippage, gross/net daily P&L and executed-order/exit counts. They do not invent an allocated capital denominator or strategy return percentage.

V1 execution currently uses `PAPER_MANUAL`. If different strategies share a position and an exit would require guessing another strategy's inventory, attribution fails explicitly and the complete materialization transaction rolls back. Lot-level cross-strategy attribution is a separate model change, not a silent heuristic.

## Consistency and validation

Repeatable-read source snapshots, a per-day PostgreSQL advisory lock, bounded serialization retry, an upserted portfolio row and transactional replacement of strategy rows make recalculation repeatable and concurrency-safe. The source ledger, fills and marks are never changed by aggregation.

PostgreSQL evidence covers a closed round trip; retained holdings; no next-day price leakage; deposit-adjusted P&L; missing mark and ledger mismatch rejection; unsupported cross-strategy attribution rollback; repeated and concurrent rebuilds; and rejection of unfinished dates. A pure drawdown test verifies peak-to-trough arithmetic and missing-sample semantics.
