# V4-03 — Prospective trial budgets and selection-bias research gate

The registered-trial gate complements the **prospective, self-declared**
`india-holdout-freeze-plan.v1` (PR #359) and the declared-family Bonferroni
screen `structured-alpha-tournament-screen.v1` (PR #349). It does not replace
the independent NSE/BSE custody preflight or the real Java registration worker.

## How to use

1. Freeze an **upcoming** NSE/BSE holdout period before its first market candle.
   Independently timestamp and archive the holdout plan fingerprint.
2. Before the same holdout opens, register a **fixed set** of baseline and
   challenger model versions/parameter configuration fingerprints. The baseline
   is declared by ID and version, **not** by looking at future performance.
   All intended tuning attempts, including experiments likely to fail, count
   against the budget; do not drop a bad run from the declarations.
3. After the holdout closes, supply **every** declared trial disposition
   (EVALUATED, TRAINING_FAILED or CANCELLED_BEFORE_EVALUATION), all evaluated
   prediction/net-return evidence and the baseline evidence. Missing and
   unregistered attempts fail closed.
4. The screen increases the paired-bootstrap confidence threshold for the
   **total declared budget**, not merely successful experiments. For example,
   three registered trials and a 95% base confidence require 98.333% adjusted
   confidence even if only two trials completed. With the existing 99% limit,
   six or more registered trials at 95% confidence are INCONCLUSIVE rather
   than silently relaxing the test. There is no capital or model promotion.

Save a trial manifest JSON such as (example hashes are placeholders; replace
them with actual SHA-256 parameter configuration fingerprints):

```json
[
  {
    "candidate_id": "lightgbm-run-01",
    "model_version_ref": "lightgbm-v1",
    "family": "LIGHTGBM",
    "parameter_spec_fingerprint": "<actual 64-character SHA-256>"
  }
]
```

Then run:

```bash
python -m aitrading_intelligence.registered_trial_cli register \
  locked-holdout-plan.json trials.json locked-trial-budget.json \
  --baseline-id catboost --baseline-version catboost-v1

# Independently timestamp/archive BOTH frozen-plan and trial-budget digests
# before collecting or opening the actual holdout.

python -m aitrading_intelligence.registered_trial_cli audit \
  locked-holdout-plan.json locked-trial-budget.json baseline-evidence.json \
  all-trial-dispositions.json evaluated-candidates.json
```

This scope does **not** determine whether a parameter specification actually
produced a given model binary. That requires a separate immutable model-build
and training-artifact attestation. A locally generated budget cannot detect
hidden research attempts, certify pre-registration time, prove the absence of
data snooping, or independently authenticate Indian cash-equity market data,
liquidity, trading costs or out-of-sample profitability. The output explicitly
sets `externally_timestamped=false`,
`undeclared_trials_independently_excluded=false`, and
`can_authorize_capital=false`. Even the most favorable result is only
`RESEARCH_SHORTLIST_ONLY`, not PAPER/SHADOW/LIVE acceptance.

**Critical:** Reusing the same holdout for unregistered adaptive tuning violates
the intended test family and invalidates the corrected confidence bound; freeze
a new future market cohort and an independent trial budget instead.
