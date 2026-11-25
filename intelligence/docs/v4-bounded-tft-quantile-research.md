# V4-04 — Bounded original TFT-style quantile forecaster

**Contract:** `india-bounded-tft-quantile-cpu.v1`.

This research candidate is a small, independently written model that uses a
subset of TFT-like concepts: variable gating, causal self-attention, gated
residuals, and 10th/50th/90th percentile next-return forecasting. It does
**not** implement the complete published Temporal Fusion Transformer
architecture, nor use upstream pretrained weights. No commercial or
production-model approval is implied.

## Fixed experiment interface

The input and cost gates are inherited from the existing V4-03 independent
NSE/BSE cash-equity benchmark and PR #361 causal baseline. The model uses
only contiguous five-minute returns observable at the decision bar, computed
from the **same instrument, provider, catalogue and time windows** as every
previous V4-04 research challenger.

The model must not see later bars: its attention mask blocks attention to
future positions within the observed window, and every input feature
(return, absolute return, running trailing average) is calculated solely
from that observed window. This first model supports next-five-minute
forecast horizons only.

The training process uses:

- Earliest training-export examples for fitting, with any sample whose
  label overlaps calibration start **purged**.
- A later, untouched training-export calibration slice for finite-sample
  absolute-residual intervals.
- The independent held-out export for a single benchmark evaluation.
- No fitting, parameter selection, interval calibration, or retries based
  on any holdout label.

The original gated causal attention network trains on CPU with bounded
hidden dimensions, attention heads, epochs, fixed seed and pinball quantile
loss. Its native 10%/90% interval coverage is reported as a descriptive
statistic, **not** a guaranteed market confidence interval. Separate
train-only absolute-residual calibration determines conservative
long-only, uncertainty-abstaining hypothetical trades.

The adapter calculates baseline and candidate MAE, RMSE, empirically
measured interval coverage, and paired after-assumed-India-cost decisions,
including zeros for abstentions. It evaluates incremental economic value
with the existing paired circular-block bootstrap. Outputs are
fingerprinted to the immutable benchmark, exact training/calibration/
holdout cohorts, fees, target, model weights and quantile predictions.

## Run for research, not trading

```bash
cd intelligence
pip install -r requirements-pytorch-cpu.txt
python -m aitrading_intelligence.india_bounded_tft \
  train-market-history.json holdout-market-history.json india-fee-book.json \
  --cost-bps 25
```

The full CI Python suite checks validation and failure modes even without
PyTorch. The separate pinned CPU PyTorch workflow **actually trains**
and tests the model, compares two runs for determinism, and checks tamper
detection, future-label isolation, market-source mismatch and full
cost/safety gating. Synthetic fixtures are not Indian-market evidence.

Even positive research bootstrap outcomes are **not** trading-alpha
proof. The output always sets false for source verification,
independent preregistration, verified Indian-market economic uplift,
upstream TFT, model promotion and capital authorization. V4-03 real
market-data source/cost audits and operational Java acceptance remain
outstanding. V4-04 still requires model-family tournament and verified
source/licensing before any model can advance toward PAPER/SHADOW.
No orders, broker credentials, cloud, or LIVE controls are added.
