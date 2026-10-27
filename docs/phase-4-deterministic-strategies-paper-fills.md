# Phase 4 — Deterministic strategies and realistic paper candidates

Phase 4 replaces the synthetic signal list with an explainable, deterministic strategy evaluated against stored historical OHLCV data. Generated signals are candidate records only. A user must explicitly choose to simulate a paper order, and the existing server-side risk policy remains the final authority.

## Strategy

The initial strategy is `SMA_TREND_VOLUME_V1`, a long-only baseline:

- Fast simple moving average: 5 most recent candle closes (configurable).
- Slow simple moving average: 20 most recent candle closes (configurable).
- A BUY candidate requires the fast average to exceed the slow average, the latest close to exceed the slow average, the latest candle volume to meet or exceed the average volume of the preceding slow-period candles, and a candle timestamp within the configured freshness window.
- Otherwise the evaluation is WATCH, with reason codes describing which conditions were not met.
- The stop is placed a configurable percentage below the reference close. The target is derived from a configurable reward-to-risk multiple.
- The evaluator uses saved candles only. It does not fetch live quotes or call an AI model.
- Fewer than `slow-period + 1` stored candles returns an explicit `INSUFFICIENT_HISTORY` evaluation and does not create a signal.

These are experimental rules for software testing, not investment advice or validated alpha.

## Signal storage and API

Migration `V9__deterministic_strategy_signals.sql` creates `strategy_signal` with a uniqueness constraint on instrument, interval, strategy version, and latest candle timestamp. Re-evaluating an unchanged candle updates that record instead of duplicating it.

- `POST /api/strategies/{instrumentId}/evaluate?interval=FIVE_MINUTE` evaluates one active catalogue instrument and the selected stored-candle interval.
- `GET /api/signals?limit=50` returns recent persisted strategy candidates.
- Both endpoints require `X-API-Key`.

The Signals page supports active NSE equity search, interval selection, explicit evaluation, a reason-code/indicator breakdown, and persisted signal history. A BUY candidate exposes a button to open the existing paper-order dialog; it never submits an order automatically.

## Paper fills and risk boundary

Paper fills continue through the existing `POST /api/paper-trades/simulate` service and deterministic risk checks. Fees and adverse slippage remain server-configured by `app.risk.fee-rate` and `app.risk.slippage-rate`; the current defaults are assumptions, not a broker tariff calculation. Risk checks, idempotency, cash/position limits, stale-signal rejection, audit recording, and the emergency stop remain active.

This phase adds no live broker order adapter and does not permit LIVE execution.

## Configuration

All strategy settings have bounded validated constructor checks:

- `app.strategy.fast-period` (default 5)
- `app.strategy.slow-period` (default 20)
- `app.strategy.target-risk-reward` (default 2)
- `app.strategy.stop-buffer-percent` (default 0.5)
- `app.strategy.max-signal-age-minutes` (default 1440)

Corresponding optional environment overrides are shown in `backend/src/main/resources/application.yml`.

## Validation

CI tests cover deterministic strategy conditions, insufficient history, stale candles, protected endpoints, persisted signal history, and idempotent re-evaluation. These automated tests use seeded historical candles; they do not prove a live market-data session is configured, and they do not validate strategy profitability.
