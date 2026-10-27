# Phase 5 — Offline paper backtesting

This slice adds an offline historical simulator over candles already stored in PostgreSQL. It is not a live or broker execution path.

## API

All routes below use the existing operator API-key security for `/api/**`.

- `POST /api/backtests` runs a backtest and returns the immutable run summary plus its simulated trades.
- `GET /api/backtests/{id}` loads a saved run and its trades.
- `GET /api/backtests?limit=20` lists recent run summaries.

Example request:

```json
{
  "instrumentId": 123,
  "interval": "FIVE_MINUTE",
  "fromDate": "2026-08-01",
  "toDate": "2026-08-31",
  "initialCapital": 80000
}
```

`initialCapital` is optional and defaults to the configured paper risk capital.

## Simulation assumptions

- Uses the configured SMA fast/slow periods, volume confirmation, stop buffer, and risk/reward ratio.
- Uses completed candle data only. A signal on candle N may enter no earlier than candle N+1 open.
- Long positions only; no short selling, broker calls, or actual orders.
- A candle that touches both stop and target is treated conservatively as a stop-loss exit.
- Stop/target gaps use the candle open, then configured slippage and flat per-side fee assumptions are applied.
- At most 20,000 candles and a 366-calendar-day request window are accepted.
- Requires at least `slowPeriod + 2` stored candles.

Results are historical simulations, not forecasts or investment advice. They may be biased by missing data, market microstructure, corporate actions, and the simplified flat fee/slippage model. Run out-of-sample validation before interpreting performance. Each saved run records the strategy version and parameter snapshot so results can be compared later.