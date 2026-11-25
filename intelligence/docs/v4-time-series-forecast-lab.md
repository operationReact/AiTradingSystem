# V4-04 — Causal NSE/BSE forecasting lab baseline (research only)

This first V4-04 increment establishes a common, executable
`india-causal-forecast-baseline.v1` for later Chronos-2, TFT, PatchTST,
iTransformer, and TimeMixer challengers. It is deliberately **not** any of
those model families. It uses a bounded, deterministic session-local
moving-average forecast over recent five-minute returns.

## Run

```bash
cd intelligence
python -m aitrading_intelligence.india_causal_forecast_baseline \
  train-market-history.json holdout-market-history.json india-fee-book.json \
  --horizon-bars 1 --cost-bps 25 --minimum-edge-bps 15 --lookback 8
```

The two `market-history.v1` exports must cover non-overlapping
observations for the *same* NSE/BSE cash-equity instrument, provider and
catalogue. The runner calls the existing V4-03 `india-independent-holdout.v1`
validator/benchmark to enforce label timing, distinct market windows,
minimum independent sessions, time-consistent India brokerage/transaction
taxes/GST and strictly positive assumed spread/slippage.

The baseline constructs features using only observed bars in the **same
India market session**. Every five-minute return input is available no later
than its prediction time; the forward-return label follows that time but
never crosses the end of the session.

Uncertainty is a two-sided split-conformal-style **absolute-residual
interval** using *training history only*, with a conservative `n+1`
finite-sample quantile. The baseline does not learn parameters from its
training labels; this permits residual calibration over the earlier
training-only market export. It never calibrates intervals on the holdout.
Empirical coverage is *measured*, not guaranteed under Indian-market
nonstationarity or temporal dependence. The interval is not a calibrated
confidence interval for real P&L.

Forecast-to-decision conversion is conservative: enter a simulated
**long-only** research trade only if the predicted return **minus**
training-calibrated absolute-error quantile exceeds both the configured
India round-trip assumed costs and minimum positive-edge thresholds.
Logically abstaining rows do not authorize broker orders. Reported
trade count, net-after-assumed-cost return and compounded drawdown are
offline measurements on heldout hypothetical decisions, not executed trades.

The immutable report binds its V4-03 parent, both export hashes, fee book,
target, configuration and exact training/holdout row memberships. Missing
training calibration, too few sessions, insufficient holdout forecasts,
parent failure, incompatible instrument, impossible label timing or
understated fees fail closed.

## Next candidates and licensing

- **Chronos-2:** model weights, API/hosting terms, permitted trading use and
  attribution must be independently checked **before** downloading or
  implementing a production adapter. No license is approved by this PR.
- **TFT/PatchTST/iTransformer/TimeMixer:** implement bounded, removable
  research adapters, CPU-first tests and predictions on **exactly the
  same causal cohort**, then compare MAE/RMSE, calibrated coverage, real
  after-cost economic value, latency and drawdown against this baseline.
- Any new trained model must use a chronological *training/calibration*
  split. It may **not** fit on the same examples used for residual
  calibration, nor on the independent holdout.
- Model registration, actual external Indian-market source validation,
  liquidity/spread estimation, pre-registration and repeated-trial
  evidence remain separately gated under V4-03.

All models are `RESEARCH ONLY`. The baseline always marks
`external_market_source_verified=false`,
`holdout_pre_registration_verified=false`,
`competing_model_improvement_verified=false`,
`promotion_allowed=false` and `can_authorize_capital=false`.
No PAPER/SHADOW, LIVE, cloud or broker functionality is added.
