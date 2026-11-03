# V4-04 — Bounded CPU patch-transformer challenger (research only)

`india-bounded-patch-transformer.v1` is an **original, minimal patch-based
TransformerEncoder forecaster** intended to benchmark the PatchTST family
idea. It is *not* an upstream pretrained PatchTST implementation and does
not claim feature parity, accuracy, licensed weights, market profitability,
or eligibility for investment decisions.

## Reproducible command

```bash
cd intelligence
# Pinned CPU-only PyTorch is optional for the normal Python production runtime.
pip install -r requirements-pytorch-cpu.txt
python -m aitrading_intelligence.india_patch_transformer \
  train-market-history.json holdout-market-history.json india-fee-book.json \
  --cost-bps 25 --horizon-bars 1 --minimum-edge-bps 15
```

The parent `india-causal-forecast-baseline.v1` (PR #361) first validates the
same disjoint NSE/BSE five-minute instrument, catalogue, provider/fee-book
and held-out data constraints inherited from V4-03. Missing market data,
understated broker costs, insufficient days, or overlapping label windows
fail closed. Source authenticity and independent preregistration remain
unverified, even when the evaluation finishes.

All inputs use eight **contiguous, session-local, available-at-decision**
five-minute returns (by default). A patch linear projection, single
one-layer self-attention encoder and regression head predict the specified
forward return. CPU-only training is bounded by fixed configurations:
PyTorch 3.11 CPU, no pretrained downloads, no GPU, one attention block,
8–32 hidden features and 2–10 training epochs; fixed random seed and
zero dropout provide repeatable numeric results in pinned CI.

Training is partitioned chronologically:

1. **Fit**: first 70% of pre-holdout causal rows, with samples whose labels
   are not observable before the calibration start purged.
2. **Calibrate**: later untouched training-export rows supply a
   finite-sample-corrected absolute-residual quantile. No model fits on these
   calibration labels.
3. **Compare**: identical later disjoint holdout rows and India fee-book for
   the moving-average baseline and patch-transformer, with separate
   training-only calibrated uncertainty intervals. Simulated long-only
   trades require a forecast lower bound greater than the full assumed
   round-trip cost plus minimum edge; uncertain forecasts abstain.
4. **Economics**: matched observations, one value per decision including
   abstentions, and paired circular-block bootstrap via existing
   `incremental-economic-value.v1`. Inconclusive or non-additive outcomes
   never become an eligible trading signal.

The immutable `india-bounded-patch-transformer.v1` evidence fingerprints
the parent baseline, separate training/holdout exports, target, India fee
book, model configuration, purged training/calibration/holdout memberships,
model weights, holdout forecasts, and paired after-cost evidence.

Even if paired research labels the challenger `RESEARCH_CANDIDATE_ONLY`,
the report **always** marks external source authenticity, holdout
pre-registration, production model-license approval, promotion and capital
authorization FALSE. It cannot issue broker orders or enter PAPER/SHADOW/LIVE.

This is one bounded candidate, not a complete V4-04 model tournament. True
upstream PatchTST, TFT, iTransformer, TimeMixer, and Chronos-2 remain
unimplemented, and Chronos-2 licensing/usage terms must be verified before
weights or hosted APIs are introduced. Repeated-trial registration and real
market-source verification remain separate V4-03 gates.
