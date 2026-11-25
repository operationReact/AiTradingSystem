# D20-01B — typed Golden Source readiness contracts

This is an **isolated Python research-validation component**, not an activated data lake, ingestion worker, training pipeline, verified licence, or capital permission.

## Inputs and ownership

- An immutable, reviewed Golden Source catalogue (`docs/architecture/mintick-mind-golden-source-requirements.v1.json`) specifies mandatory source IDs for all 18 UI model entries. A privileged deployment path must pin and authenticate its digest before loading it; user-supplied JSON is **not** trusted configuration.
- A fitted model artifact declares an exact ordered feature schema. The model adapter and Golden Source readiness contract must both reject missing mandatory features rather than silently substituting zeros.
- Each `SourceSliceEvidence` describes one **point-in-time** source slice with its own dataset fingerprint, stable listing or explicitly permitted market-wide scope, quality/coverage denominator, original event/first-available timestamp, retrieval time, independently attested licensed uses, and row count.
- `ModelFeatureSlice` includes its decision-time cutoff, feature schema/fingerprint and sorted feature values, plus dataset fingerprints for **every required input source**.
- The licence rights digest is an **upstream attestation reference**; this pure validator cannot independently verify the commercial licence, authorize market-data retention or permit end-user redistribution. A production verifier must check signed source/rights manifests separately.

## Readiness states

`evaluate_model_data_readiness` emits only `RESEARCH_READY` or `BLOCKED`, with deterministic sanitized blockers. It rejects unknown or unavailable source, synthetic sources, missing features, unverified model artifacts, missing permitted use, missing rights attestation, insufficient historical rows, stale or quarantined slices, future-published observations, source/feature hash mismatch, and wrong listing scopes.

A successful `RESEARCH_READY` is **never** `LIVE_READY` and cannot authorize orders, trading, model promotion, or other irreversible actions. This module performs no network access, AWS operations or database writes.

## Verified in this PR

- Standalone Pydantic contracts and executable unit tests, including first-available-at ordering and historical point-in-time rejection.
- 18 model input names resolve from exact audited catalogue requirements. This does **not** assert that these UI fixture models are trained or deployed.
- A single model's feature snapshot can be reused by its inference workflow; actual S3/Parquet feature materialization and production model-roster integration remain separate PRs.

## Follow-on engineering

1. D20-01C: Apply strict feature-missingness semantics to offline training helpers, with explicit imputation policies and reproducibility tests. Do not change historical model scoring without audited retraining.
2. D20-02: Stable `listing_id`, temporal provider token aliases, delisting, and corporate-action history in forward-only Flyway migrations.
3. D20-03: Rights-registry verification, provider adapters and source-quality coverage from actual **licensed** 20-year daily data; source rights must be externally verified.
4. D20-04 onward: S3 Parquet immutable source/feature manifests, offline vs hot projections, cost-bounded backfill and model-specific readiness APIs.
5. Replace the 43 screen fixtures gradually with authenticated data and explicit unavailable/stale indicators; never represent source readiness as profitable predictions.

**Safety:** No collector activation, user authorization changes, broker-order capability, public endpoint, bulk history calls, licence acceptance, or AWS resource provision.
