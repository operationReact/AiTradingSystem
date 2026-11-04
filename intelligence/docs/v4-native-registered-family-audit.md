# V4-04 — Native four-family prospectively declared research-trial audit

**Contract:** `india-v4-native-registered-family-audit.v1`

This bounded, research-only integration joins the native, in-process
forecast evidence from V4-04 with the existing V4-03 all-attempts research
trial correction. It is not a model promotion decision and is **not**
proof of independently authenticated Indian-stock-market profits.

## Source of truth and immutable predeclared choices

1. Before the first holdout candle, create `india-holdout-freeze-plan.v1`
   with the **earlier, actually captured** market-history export. The freeze
   must explicitly include `PYTORCH` alongside the required CatBoost and
   LightGBM families. Externally timestamp/commit the plan fingerprint.
2. Before the same first holdout candle, create
   `structured-alpha-trial-budget.v1` with the **causal moving-average
   baseline ID and version** plus every intended bounded neural trial,
   not just the future winners. Each slot must declare
   `family=PYTORCH`, its exact candidate ID/model version and the exact
   model's **original configuration fingerprint**: patch-transformer,
   TimeMixer-style, TFT-style or a subsequently reviewed candidate.
   Store an independently verifiable pre-holdout witness. Do not backdate
   a plan after seeing any test data.
3. After the independent holdout closes, obtain the original immutable
   PR #370 native causal baseline tournament submission. Obtain PR #369
   complete native submissions for the actual fitted candidate model
   reports (each containing its signed model-weight hash, earlier
   calibration prediction records, heldout forecast records, and full
   hypothetical after-India-cost return trace).
4. Supply a `TrialOutcome` for **every** registered slot:
   `EVALUATED` must bind its **exact original model report fingerprint**;
   failed and cancelled attempts must remain represented as
   `TRAINING_FAILED` or `CANCELLED_BEFORE_EVALUATION`, without a
   fabricated source hash.
5. `audit_native_registered_family` checks holdout-export consistency
   with the **frozen source, instrument, provider, fee book, target and
   planned clock window**, every neural parameter configuration and
   model version against the declared slots, common immutable decision
   membership, and all disposition/report fingerprints.
6. It delegates to V4-03 `audit_registered_trials`, preserving the
   **full declared trial count** in the Bonferroni familywise correction,
   including failed/cancelled attempts. The nested research shortlist
   is still just a hypothesis, not authorization of model selection or
   trading capital.

## Fail-closed regression coverage

The pinned CPU PyTorch CI workflow will actually train all three original
bounded models and test the complete synthetic path, a full three-slot
audit, failed/cancelled trial accounting, wrong predeclared parameter
configuration, missing outcomes, tampered original model/economic
reports and attempted pre-holdout evaluation. Non-PyTorch checks validate
freeze/budget creation and absence of capital authority.

Every immutable output includes the freeze plan, registered budget,
consistent export/freeze binding, original native baseline and candidate
reports, original per-trial dispositions, the PR #360 audit result and
an overall result fingerprint.

**Important:** A self-declared freeze with a local timestamp is not an
independent preregistration witness. GitHub CI synthetic tests do not
independently certify Indian NSE/BSE provider provenance, broker data
rights, real India brokerage/stamp duty/STT/GST costs, market impact,
execution fills, out-of-sample performance or that unreported researcher
attempts never occurred. Even a `RESEARCH_SHORTLIST_ONLY` result
continues to set all source, timestamp, fees, executed-model-binary,
hidden-attempt-exclusion, production and capital authorization flags
to `false`.

This PR supplies a **wired program contract and tests**, not a real
prospectively collected run. Authentic market capture, independent
timestamping and source/cost attestation remain external prerequisites
before any PAPER/SHADOW observation, let alone LIVE.
