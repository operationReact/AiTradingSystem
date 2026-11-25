# V4-04 — Train-only directional-probability bridge for research tournament

## Why this bridge is required

The existing V4-03 structured-alpha tournament accepts an aligned
`ModelPredictionSeries` containing **direction probabilities** in [0,1]
and a `NetReturnSeries` with the exact same ordered after-cost decisions.
PRs #361–#365 instead produce signed five-minute **forecast returns** and
sometimes 0.1/0.5/0.9 return *quantiles*. A signed return such as 0.004
is **not** an estimated 0.4% probability and must never be silently
inserted into the tournament as one.

`india-v4-forecast-directional-bridge.v1` converts such forecasts into
bounded directional-probability research evidence by training a small
L2-regularized logistic calibration model on *earlier calibration-only*
observations. The underlying forecasting model must already have been
fitted on a **separate, earlier and label-purged** training subset.
No holdout label or outcome enters probability fitting.

## Operational sequence

1. Freeze and **externally timestamp** a future NSE/BSE holdout and all
   model trials before any holdout data is collected (PRs #359/#360).
2. Obtain the canonical cohort file from PR #366; all challengers must
   share the identical target/fee/source/cohort identity.
3. For each already fitted forecasting model, export **calibration**
   signed-return predictions and realized returns with label-available
   timestamps (all strictly earlier than the first holdout decision).
   These labels train the logistic probability mapping only.
4. Separately export the **independent holdout** signed-return forecasts
   and decision-level hypothetical after-cost returns, including
   **zeros for abstentions**. No holdout realized label is allowed as a
   logistic-calibration input.
5. Build a self-declared `ForecastTournamentSubmission` containing
   immutable `DirectionalCalibrationRecord`, `ModelPredictionSeries`,
   `NetReturnSeries` and `CrossModelCausalCohort`. The model identity,
   version, source result, target, fold, fees, observation hashes and
   decision timestamps must match.
6. Only after independent model-run attestation and external data
   provenance checks should the resulting candidates be considered for
   PR #360's registered-trial budget audit. No automatic promotion.

Example structure (with real values, complete arrays and a real hash):

```json
{
  "forecasts": [0.001, -0.002],
  "realized_returns": [0.002, -0.001],
  "label_available_times": ["2026-11-02T09:25:00+05:30", "2026-11-02T09:30:00+05:30"]
}
```

The actual calibration file must contain **at least 20** independent
earlier rows, including enough positive and negative labels; the example
is intentionally too short to execute. The separate holdout file uses
`{"forecasts":[...], "net_returns_after_costs":[...]}`.

```bash
cd intelligence
python -m aitrading_intelligence.forecast_directional_cli \
  frozen-cohort.json earlier-calibration.json candidate-holdout.json \
  immutable-candidate-submission.json \
  --candidate-id bounded-time-mixer-research \
  --model-version india-bounded-time-mixer-cpu.v1 \
  --source-result-fingerprint '<exact immutable model-run result fingerprint>'
```

The CLI refuses to overwrite an existing file and writes mode 0600.
The local self-declared record includes training-only in-sample Brier
score, fitted logistic coefficients, earlier calibration membership,
full heldout forecast fingerprint, candidate and net-return-series
fingerprints, and *explicit* zero authority.

## What is NOT proven

This bridge is **not** a fitted model's source-code/model-binary
attestation. It accepts operator-supplied forecast and net-return files;
the same source result fingerprint does **not** prove that the model
actually produced them. It also does not prove real Indian-market data,
independent timestamping, paid broker fees, liquidity/slippage,
out-of-sample probability calibration, hidden-trial exclusion,
market profitability or any PAPER/SHADOW/LIVE readiness.

A favorable synthetic fixture or a valid logistic conversion cannot
enter a trade. `real_market_probability_calibration_verified=false`,
`independent_model_artifact_attestation_verified=false`,
`independently_verified_india_market_history=false`,
`external_trial_preregistration_verified=false`,
`promotion_allowed=false` and `can_authorize_capital=false`
remain immutable.

**Next:** Have every existing research model emit immutable
per-decision calibration/holdout artifacts **as part of** its run and
bind their digests into the original model result. Then consume these
submissions via PR #360 registered-trial budget audit on authentic
prospective NSE/BSE data with independent custody and fee evidence.
