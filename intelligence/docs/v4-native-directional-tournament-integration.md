# V4-04 — Native-model evidence to train-only directional tournament

**Contract:** `india-native-forecast-tournament-submission.v1`

This is an end-to-end **research program link** between three existing
CPU-trained V4-04 models and the V4-03 alpha tournament. It joins:

- PR #368 immutable native per-decision model reports (bounded patch
  transformer, TimeMixer-style, TFT-style)
- PR #366 canonical Indian cash-equity observation/fee/target bindings
- PR #367 train-only logistic calibration from signed forecast returns
  to meaningful directional probabilities
- PR #349 familywise-adjusted economic model shortlist contract

## Why this is safer than hand-entering candidate arrays

`build_native_forecast_tournament_submission(report)` takes exactly the
**entire original model-run report**, not a hand-written experiment ID,
prediction list or cost/return vector. It independently revalidates the
model report and embedded per-row trace and computes:

1. The original immutable `report_fingerprint` as the **experiment
   source-result** identity; no invented or user-supplied run digest
2. Real per-decision calibration forecasts and realized earlier
   calibration labels with actual label-available timestamps
3. Each model's heldout forecast vector and recomputable hypothetical
   after-cost return vector, including abstention zeros
4. Train-only logistic directional probabilities for model-diversity
   and registered-tournament compatibility, aligned to identical cohort
5. The complete original model report and `ForecastTournamentSubmission`
   in one immutable `NativeForecastTournamentSubmission` record

The wrapper validator **recomputes the directional calibration and
prediction/net-return series directly from the embedded original model
report**. It rejects rewritten calibration coefficients, candidate
probabilities, model/version/source-result ID, observation times, cost
identity, holdout returns, model-state or the wrapper fingerprint.

The pinned CPU PyTorch CI job now trains all three bounded forecasters,
links each into the actual directional/tournament contract, and exercises
a synthetic three-family model comparison. This is a *research integration
test*, not independently verified profitable forecasting.

## Program use

```python
from aitrading_intelligence.india_patch_transformer import (
    evaluate_bounded_patch_forecast,
)
from aitrading_intelligence.native_forecast_tournament import (
    build_native_forecast_tournament_submission,
)

# The export/fee/target inputs must be independently validated elsewhere.
research_report = evaluate_bounded_patch_forecast(
    training_export, holdout_export, india_fee_book, target_spec
)
native_submission = build_native_forecast_tournament_submission(research_report)
print(native_submission.submission_fingerprint)
```

Normal model CLI result JSON already embeds a `native_trace`. This
integration function produces a second immutable research submission,
but currently has no production API or broker side effect.

## Boundaries

No independent model-binary attestation, real NSE/BSE market custody,
verified fee/slippage source, external prospective registration or
independent out-of-sample economic improvement is implied. The model
reports use synthetic regression data in CI; a valid local model and
calibration record can be fabricated in a research environment and
cannot certify genuine results.

The original causal moving-average baseline and authentic Chronos-2
checkpoint are not yet included as native tournament submissions.
PR #360's frozen trial-budget audit and hidden-attempt verification
remain separately gated and have NOT been run on authenticated real
markets.

`independently_attested_binary_execution=false`,
`independently_authenticated_indian_market_source=false`,
`externally_preregistered_trial_verified=false`,
`promotion_allowed=false` and `can_authorize_capital=false`
remain mandatory. No PAPER/SHADOW, LIVE, broker order, cloud deployment
or model promotion is authorized.
