# V4-04 — Native per-decision forecast and after-cost research traces

**Implemented:** `india-native-forecast-decision-trace.v1` for three CPU
research candidates:

- PR #362 original bounded patch-transformer
- PR #364 original bounded TimeMixer-style forecaster
- PR #365 original bounded TFT-style quantile forecaster (uses native 0.5
  median as the signed directional-return forecast for this trace)

Unlike an operator-authored sidecar spreadsheet, these artifacts are
constructed **in memory during the trained model's evaluation** from the
actual calibration model outputs, actual heldout forecasts and hypothetical
after-cost decisions, and are embedded in the final immutable report hash.

## What the trace verifies

Each candidate report includes a full `native_trace` object containing:

- The model ID/version and trained model state fingerprint
- The PR #361 independent causal baseline report fingerprint
- The full common `india-v4-cross-model-causal-cohort.v1` binding from PR #366
- The actual target specification and Indian round-trip cost assumption
- Pre-holdout calibration observations, label availability times, outcomes
  and *model-produced* calibration return forecasts
- Every independent holdout decision, its source row fingerprint and
  observed forward return, model-produced signed return forecast, and
  **after-assumed-cost hypothetical net return** (zero on abstention)
- Training-only finite-sample corrected absolute-residual quantile, count
  of opportunities that passed the positive edge/uncertainty threshold
- An immutable trace fingerprint, included directly inside the containing
  model-result/report fingerprint

Validation recomputes the residual quantile using **calibration only**,
checks that the latest calibration label predates the earliest heldout
decision, requires exact source/target/fee/order cohort membership,
recomputes each ENTER/ABSTAIN decision using the frozen cost-plus-edge rule,
counts decisions and validates every after-cost return value.

Report validation additionally checks that the trace's model state,
parent report, feed/fee/target, holdout/calibration membership, predicted
forecast vector, trade count and sum of net returns agree with the
corresponding model summary. Missing native traces are invalid on
otherwise completed model runs. INCONCLUSIVE/insufficient-evidence
models cannot claim observed trade evidence.

## Reproduce

```bash
cd intelligence
pip install -r requirements-pytorch-cpu.txt
pytest -q tests/test_native_forecast_trace.py

# Example native research JSON (no real broker/market authorization)
python -m aitrading_intelligence.india_patch_transformer \
  older-train-market-export.json later-holdout-export.json india-fee-book.json \
  --cost-bps 25
```

The CLI emits the full result JSON, including the native trace. The user
may externally archive and SHA-256 hash the resulting report file for
later research, but the locally created record is **not** an external
trusted timestamp or independently authenticated market-data source.

## Explicit limitations

- This is a more inspectable internal *program path*, not remote
  attestation of a model binary or cryptographic proof that unknown
  alternate training attempts were not performed.
- Observed forward returns and net after-assumed-cost decisions come
  from self-declared offline NSE/BSE-format exports; the market custody,
  fee accuracy, transaction tax, liquidity and simulated slippage remain
  independently UNVERIFIED.
- An embedded trace is not itself a probability; PR #367's train-only
  logistic directional bridge is the next explicit conversion step
  toward PR #360's registered-trial audit. The current model traces do
  not assert the legitimate pre-holdout registration of those trials.
- Chronos-2's PR #363 opt-in API adapter is **not included** because its
  authentic 120M checkpoint has never been executed/attested and the CI
  adapter is simulated. Baseline native-trace parity and the runner-native
  connection to PR #367 remain separate tasks.
- `actual_model_checkpoint_independently_verified`,
  `source_market_data_independently_verified`,
  `preregistration_independently_verified`,
  `eligible_for_model_promotion`, and
  `can_authorize_capital` are always false. No PAPER, SHADOW, LIVE
  trading, backend deployment, or broker API calls are initiated.
