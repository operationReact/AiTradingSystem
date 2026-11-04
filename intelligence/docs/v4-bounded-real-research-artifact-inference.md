# V4-03 / V4-09 — Actual bounded CatBoost and LightGBM research inference

## Implemented and intentionally excluded

The Python intelligence FastAPI app now supports **actual trained model computation**:

`POST /v1/research/artifact-inference`

The endpoint reloads an existing `CatBoostModelArtifact` or `LightGBMModelArtifact` and calls that model's real `predict_proba` / `Booster.predict`, respectively. It **does not retrain** the model, fake a probability, claim real trading alpha, submit PAPER or broker orders, enroll a champion, or enable LIVE capital. The response marks `ACTUAL_MODEL_COMPUTATION_RESEARCH_ONLY`, `independent_input_custody_verified=false`, `model_promotion_allowed=false`, and `can_authorize_capital=false`.

The existing research model-training programs already produce serialized weights and immutable metadata. This increment supplies the previously missing **artifact-to-prediction** capability without crossing the Java broker/risk boundary.

## Opt-in secrets and local allowlist

With no environment configuration the endpoint returns HTTP 503. It becomes available only when BOTH variables are set:

- `AI_RESEARCH_WORKER_TOKEN`: a randomly generated private secret of 32+ characters; supply it through `X-Research-Worker-Token`, and restrict the internal API's network access to trusted Java workers.
- `AI_RESEARCH_MODEL_MANIFEST`: a secured, operator-controlled local JSON file path. The HTTP request cannot choose a file or edit the model allowlist.

A manifest must have `contract_version` `bounded-artifact-research-inference.v1` and `models` containing 1–16 entries with:

`model_version_id` (the Java registered UUID), `family` (`CATBOOST` or `LIGHTGBM`), `artifact_filename` (one .json filename, no directories), `artifact_sha256` (raw bytes digest), `artifact_fingerprint` (the research model's metadata fingerprint), and `wait_below` / `enter_at_or_above` thresholds.

The artifact sits next to the manifest and cannot be a symlink or escape via path traversal. Manifest and files have bounded sizes; bytes must match the explicit SHA-256 allowlist; the model family and research artifact fingerprint must match. This records *operator-pinned* integrity, not independently authenticated model provenance or licensed input custody. Rotate the token via the secure configuration workflow and never commit the token, model weights, signed agreements or datasets into the repository.

Sample structure using clearly **fictional** placeholders (not runnable or authorized):

```json
{
  "contract_version": "bounded-artifact-research-inference.v1",
  "models": [
    {
      "model_version_id": "00000000-0000-4000-8000-000000000001",
      "family": "CATBOOST",
      "artifact_filename": "approved-catboost.json",
      "artifact_sha256": "<SHA-256 of the complete file>",
      "artifact_fingerprint": "<artifact model_fingerprint>",
      "wait_below": 0.4,
      "enter_at_or_above": 0.6
    }
  ]
}
```

The `artifact_filename` is a *local file with trained weights*. This PR does NOT create a real approved model inventory or configure runtime credentials.

## Request / result contract

Caller must send exact feature names learned by the trained model (no absent, extra, nonfinite, boolean, oversized or future feature values), case ID, exchange symbol, registered model UUID, previously frozen Java case input fingerprint, feature decision timestamp and latest availability timestamp. The trained model must already have existed before the requested feature time.

Example payload:

```json
{
  "model_version_id": "00000000-0000-4000-8000-000000000001",
  "case_key": "paper-case-001",
  "symbol": "RELIANCE-EQ",
  "source_input_fingerprint": "<frozen V59 source hash>",
  "feature_as_of": "2026-11-04T09:20:00Z",
  "latest_feature_available_at": "2026-11-04T09:19:55Z",
  "features": {
    "market.close": 100.0,
    "market.return_1": 0.002
  }
}
```

The feature object above is **illustrative only**. A real request must include *every* feature declared in the trained artifact, which can include news evidence. No market future returns, targets or outcome labels are allowed in the request schema.

Response includes the actual model probability, the configured deterministic `ENTER`/`WAIT`/`ABSTAIN` classification, artifact/feature/source fingerprints, actual server evaluation time and a fingerprint over the entire response. A request-supplied source hash is **not proof** that the worker sourced those features from licensed Java market/news input. This output is not a brokerage instruction.

## CI regression and follow-up integrations

```bash
cd intelligence
python -m pytest -q tests/test_research_artifact_inference.py
ruff check src tests
```

Regression tests train small synthetic model artifacts through the existing real CatBoost/LightGBM trainers, serialize them, verify their SHA-256, reload and score their actual weights, and exercise the worker token/manifest path, post-training timestamp restrictions, exact feature schema, forged responses, and capital lock. **These are synthetic software tests, not a real-world model performance benchmark.**

Remaining separate engineering work:

1. Generate the exact model features from Java's frozen V59 market/news capture, rather than trusting operator-supplied feature payloads.
2. Build a bounded Java-to-Python worker invocation with timeout/circuit breaker, registered model identity, authenticated request and receipt validation. Persist all success/failure/late outcomes and model disagreement durably under V56/V58, and keep Java as the capital authority.
3. Test multiple sessions, source fidelity, lease/timeout failure injection and PAPER order/fill/reconciliation integration.
4. Obtain independent licensed India data and approve real outcomes only during the later acceptance/release phase.

No production deployment or LIVE enablement is performed.
