# V4-04 — Original bounded TimeMixer-style CPU research (not official TimeMixer)

`india-bounded-time-mixer-cpu.v1` is a small, original multi-scale
time-series regressor. It is **not** the full upstream TimeMixer implementation
and does not use downloaded or pretrained TimeMixer model weights. It has no
separate checkpoint/model-license grant to rely on beyond project dependencies.

## Research-only architecture

- Only causal, contiguous five-minute NSE/BSE cash-equity bar returns are
  permitted; model inputs never include future labels.
- Decompose the available-at-decision return window into a left-padded
  three-step trend and a seasonal residual; downsample the seasonal residual
  by a bounded factor (2 or 4).
- Mix the seasonal and coarse-scale features through a small gated MLP and
  blend against the trend projection. Hidden width 8–32, at most ten epochs,
  pinned CPU PyTorch, deterministic seed and no pretrained downloads.
- Chronological early fit, purge late training labels that overlap calibration
  start, train-free untouched calibration, then independent later holdout.
  The trained model and calibration data must be strictly from the older
  `market-history.v1` export.
- Evaluate exactly the same after-assumed-India-cost, abstaining long-only
  hypothetical actions for the TimeMixer-style challenger and PR #361's causal
  moving-average baseline. Reuse PR #362's training-only residual interval
  calculations and circular-block paired net-economic bootstrap; record
  baseline and challenger MAE/RMSE, empirical uncertainty coverage, drawdown
  and paired evidence fingerprints.
- Reuse the V4-03/V4-04 parent for source/exchange/instrument catalogue,
  session timing, India fee/tax/GST schedules and assumed slippage limits;
  a missing bar in either model input OR future target fails closed.
- Never authorize trading capital, model promotion, broker API access,
  PAPER/SHADOW enrollment or LIVE trading.

```bash
cd intelligence
pip install -r requirements-pytorch-cpu.txt
python -m aitrading_intelligence.india_time_mixer \
  train-market-history.json holdout-market-history.json india-fee-book.json \
  --cost-bps 25 --horizon-bars 1
```

The report is `RESEARCH_COMPARISON_ONLY` or `INCONCLUSIVE` and stores
model-weight, configuration, data/fee/target, purged fit/calibration/holdout
membership and paired after-cost evidence fingerprints. Any promising
**synthetic** fixture outcome is not real Indian-market alpha.
The output always sets
`authentic_market_source_verified=false`,
`preregistered_holdout_verified=false`,
`genuine_market_economic_uplift_verified=false`,
`upstream_timemixer_weights_used=false`,
`production_model_approved=false`,
`promotion_allowed=false`,
`can_authorize_capital=false`.

Limitations: no authentic market-data source was obtained; no genuine
out-of-sample performance, external timestamp, custody/fee audit, repeated
trial governance, or real trading execution was verified. Chronos-2 authentic
checkpoint, TFT, iTransformer and upstream TimeMixer remain additional
distinct V4-04 acceptance work, not completed by this bounded candidate.
