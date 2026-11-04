# V4-04 — Native forecasting research registered-trial budget audit

**Contract:** `india-native-registered-trial-audit.v1`.

This opt-in evidence adapter combines PR #359's prospective NSE/BSE cash-equity
holdout freeze, PR #360's registered-trial budget, PR #370's deterministic
baseline, and PRs #368/#369's original bounded CPU patch-transformer,
TimeMixer-style and TFT-style research model reports.

## Strict local admission checks

The adapter calls the existing `bind_india_holdout_to_plan` on complete
raw training and later holdout market exports, target, India fee-book and
holdout configuration. It refuses:

- Changed freeze/training/holdout source fingerprints, exchange, provider,
  instrument identity, catalogue, five-minute interval or recorded holdout
  boundaries
- A target or India fee/tax/assumed slippage reference that changed after
  the prospectively recorded research plan
- A missing `PYTORCH` model family in the frozen trial-family registry
- Candidate IDs/versions/configuration hashes that do not match a declared
  `RegisteredTrial`; the configuration is the **exact model run's**
  immutable config fingerprint, not a newly invented after-holdout ID
- A model submission with different PR #366 holdout decision membership,
  fee/target, source, version, model result hash or INCONCLUSIVE native trace
- Any missing, duplicate or unregistered trial disposition. Every
  registered attempt has exactly one `EVALUATED`, `TRAINING_FAILED` or
  `CANCELLED_BEFORE_EVALUATION` entry. Failed/cancelled outcomes remain
  in the multiple-comparison denominator instead of silently disappearing
- Screening before the original holdout close, or a budget registered after
  the original holdout opened

After these checks, it calls the **existing**
`audit_registered_trials` for the complete declared trial family and
its familywise-adjusted paired economics. The returned immutable envelope
includes the frozen plan, market binding, signed-offline registration
budget, full native baseline, evaluated native model submissions,
complete trial outcome ledger, economic policy and actual audit result.
Validation reruns the audit and verifies the evidence hash, source,
parameter configuration, baseline and outcomes.

## Research-only example

```python
from datetime import timedelta
from aitrading_intelligence.native_registered_trial_audit import (
    audit_native_registered_trials,
)

evidence = audit_native_registered_trials(
    original_pre_collection_freeze,
    original_pre_collection_trial_budget,
    training_export, holdout_export, india_fee_book, target_spec,
    independent_holdout_config,
    native_baseline_tournament_submission,
    native_model_tournament_submissions,
    all_registered_trial_dispositions,
    evaluation_started_at=original_pre_collection_freeze.planned_holdout_to
        + timedelta(minutes=5),
)
print(evidence.audit.status)
print(evidence.evidence_fingerprint)
```

Real runs must present *prospectively* prepared source and trial registration
artifacts; never backdate a plan for a previously observed market dataset.
The integration test uses **explicitly simulated historical fixtures**
to exercise contract mechanics, not to assert historical preregistration.

## Important limitations

The freeze and trial-budget times remain **self-declared**. This adapter
cannot establish that no undisclosed trials were run, that an externally
trusted timestamp/custodian saw the registration before collection,
or that the market/fee information originated from an authorized NSE/BSE
provider. The upstream freeze requires CatBoost and LightGBM among the
available families but this registered sub-budget can contain only the
three declared PYTORCH challenger attempts: this is not an independently
verified inventory of all possible model searches.

A synthetic familywise-adjusted research shortlist is NOT validated
financial alpha, investment advice, deployed PAPER profitability, an
approved model, SHADOW or LIVE authority. Both original source/multiple-
trial evidence and genuine Indian market after-cost trading economics are
pending. The envelope always sets the following to FALSE:

`independently_verified_market_custody`,
`independently_excluded_hidden_trials`,
`externally_attested_preregistration`,
`promoted_to_paper_or_live`,
`can_authorize_capital`.

No network, market API call, trading order, broker credential, production
deployment or model promotion is performed by this module.
