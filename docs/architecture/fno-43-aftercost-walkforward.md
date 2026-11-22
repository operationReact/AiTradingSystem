# MintTick F&O — original round-trip after-cost walk-forward accounting

**Scope:** This is one reusable, deterministic, pure-Java accounting boundary for STR-170 through STR-212. It is not a market data collector, fill simulator, strategy evaluator, paper engine, or broker order service.

## Why needed

The 43 typed evaluator paths and original-source historical adapters do not produce independent real-market backtest evidence merely by compiling. A separate chronological accounting boundary must subtract brokerage/exchange fees, statutory taxes, spread/slippage, impact, and financing from gross outcomes. It must reject future-known data and enforce a frozen training/out-of-sample split.

## Implementation

`FnoHistoricalAfterCostWalkForwardV1.evaluate` takes a single registered F&O strategy ID, historical decision as-of, fixed train/OOS cutoff, an externally attested research-source-rights precondition and a bounded set of **already completed** round trips. A round trip supplies original-time entry and exit decisions, source-first-known timestamps, execution timestamps, syntactically valid source SHA-256 digest references, gross cash P&L, separately enumerated costs and risk capital.

The function rejects negative costs, impossible ordering, source first-known after decisions, future outcomes, overlapping single-position trades, mixed strategy IDs, invalid digests, split-crossing trades, missing training/OOS, unlicensed source and arithmetic overflow. It calculates per-split net P&L, OOS gross/cost/net P&L, OOS realized drawdown and losing trade counts. A positive reported return does not constitute statistical evidence of an edge or any entitlement to trade.

**Non-authority:** Java fixtures and caller-supplied timestamps/digests do **not independently prove** original custody, valid licenses, historical broker fills, NSE truth, actual transaction prices, margin or order execution. Accordingly the report always returns `independentHistoricalMarketE2E=false`, `paperLedgerE2E=false`, `liveBrokerE2E=false`, `ordersPlaced=false`. An accepted market backtest requires separate audited rights, exact original source/first-known receipts, a complete event-to-order-to-fills replay, walk-forward embargo and stress, and independently verified after-cost outcomes. The full 43 strategy certification stays 0/43 until independently satisfied.

## CI and follow-on work

`.github/workflows/minttick-fno-aftercost-oos-gate.yml` checks actual JUnit P&L/cost arithmetic, deterministic output, no-lookahead rejects, train/OOS isolation, duplicate/overlapping trades, invalid digests, missing rights and fail-closed overflow. The same job runs existing 43 typed dispatcher and source-audit tests.

The next independent work item is a rights-attested historical adapter that produces real, verified completed trade receipts for this boundary, including multi-leg partial fills, contract-expiry exit/settlement, statutory costs, lot size and margin provenance. This utility intentionally rejects overlapping trades because it does not substitute for a multi-position risk/ledger engine.

Tracking: https://github.com/operationReact/AiTradingSystem/issues/1658.
