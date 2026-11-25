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

## P08-PR02 — Analytics readers

All readers accept a bounded inclusive date range of 1–366 days and use the same half-open Asia/Kolkata event interval. They run in read-only repeatable-read transactions and never place orders or recompute source accounting.

`PortfolioAnalyticsService` exposes cached period totals, dated equity points, fee/slippage attribution, cash-flow-adjusted compounded daily returns, daily-close drawdown and the latest persisted exposure snapshot with its own as-of timestamp. A missing cache day or unavailable daily denominator suppresses compounded returns/drawdown; existing totals remain explicitly partial. These returns use net P&L on opening equity and do not claim a time-weighted intraday deposit model.

Closed-exit win rate and realized expectancy are computed from SELL fills grouped by order ID. Breakeven exits remain in the denominator; partial fills do not multiply exits. Daily-close drawdown is distinct from sampled intraday drawdown. Current exposure snapshots and historical period totals have separate provenance/time fields.

`StrategyAnalyticsService` supplies cached trade/P&L/cost totals and conversion for the cohort of candidates created in the requested period. Multiple evaluations of one candidate count once toward conversion, and only evaluations before the range end count. Executed-order-days are named explicitly because an order partially filled across two days can appear on both daily rows. Strategy groups are bounded at 1000; excessive cardinality requires a narrower period rather than silent truncation.

`ResearchAnalyticsService` reports scan throughput, reported candidate yield per scanned instrument, evaluation event distribution and unweighted backtest/scenario summaries. Candidate yield may exceed one when several strategies emit candidates for an instrument; it is not a probability. Empty sample averages/ratios remain NULL. Research summaries are advisory and are explicitly not forecasts of future profitability.
