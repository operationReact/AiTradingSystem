# V4-09 — Frozen trained-model research inference endpoint

## Delivered model execution

This change exposes **actual CPU predictions** from existing *trained* CatBoost and LightGBM challenger checkpoints, not predictions fabricated from benchmark fixtures. The FastAPI endpoint is:

`POST /v1/research/model-inference`

It returns `v4-frozen-research-model-inference.v1` evidence with model metadata, feature and target fingerprints, genuine `predict_proba`/booster prediction, bounded probability, research-only ENTER/WAIT/ABSTAIN, model bytes SHA-256, prediction time and an immutable output fingerprint.

### Security, source and time boundaries

- **Disabled by default:** the server must be configured with both `APP_RESEARCH_ARTIFACT_MANIFEST` (absolute local manifest path) and `APP_RESEARCH_INFERENCE_TOKEN` (minimum 24 characters). Each request requires matching `X-Research-Service-Token`. Do not expose the Python intelligence service directly to the public internet; isolate it behind trusted internal network boundaries. Never log or commit tokens.
- Only a **preloaded registry** of 1–16 local JSON artifacts can be scored. HTTP callers cannot supply arbitrary checkpoint bytes or filesystem paths. The manifest fixes each model ID, family, absolute non-symlink path, SHA-256 bytes digest and expected existing training artifact fingerprint. Each artifact is at most 32 MiB. Checkpoint types and installed CatBoost/LightGBM versions must match.
- The model is **loaded once** and remains immutable until the service restarts. Do not silently replace a production checkout; use a controlled model registry update, restart and rollout. The operator-supplied manifest is not an independent code-signing authority.
- Inputs must be a valid `FeatureRow` with an independently recomputed builder fingerprint, point-in-time `data_cutoff <= as_of`, *exactly named nonmissing model features*, finite numbers and no obvious forward-label/target/outcome fields. Training's `training_as_of` must strictly precede decision time; input target-spec fingerprint must equal the frozen model's target spec.
- The server actually invokes the stored CatBoost or LightGBM CPU predictor. A fixed, unapproved research-only threshold maps `p >= 0.6` to ENTER, `p <= 0.4` to WAIT, and the uncertain interval to ABSTAIN. This is **not** a broker BUY instruction, calibrated decision policy, strategy approval, measured economic uplift, or recommendation.
- Flags `source_provider_verified=false`, `external_timestamp_witness_verified=false`, `model_promotion_allowed=false`, `actual_paper_fill_verified=false`, `live_order_execution_enabled=false`, `can_authorize_capital=false` are schema-locked.

### Manifest example (illustrative placeholders only)

Store this file **outside GitHub** and secure it with system-level read permissions:

```json
{
  "contract_version": "v4-frozen-research-model-inference.v1",
  "artifacts": [
    {
      "model_id": "light-alpha-001",
      "family": "LIGHTGBM",
      "artifact_path": "/private/approved-research/light-alpha-001.json",
      "artifact_sha256": "<64-character actual SHA-256 of artifact file bytes>",
      "expected_model_fingerprint": "<64-character fingerprint from original LightGBMModelArtifact>"
    }
  ]
}
```

The sample uses placeholders and will not load until replaced with actual, matching values from a trained checkpoint and operator-reviewed manifest. CatBoost entries use `CATBOOST` and `CatBoostModelArtifact` JSON.

```bash
# Configuration is deliberately deferred until a real secured research environment.
export APP_RESEARCH_ARTIFACT_MANIFEST=/private/research-manifest.json
export APP_RESEARCH_INFERENCE_TOKEN='<private internal token 24+ characters>'
```

The request body has `model_id`, `target_spec_fingerprint`, and a complete `row` matching `FeatureRow` (including causal timestamps and original feature fingerprint). The response includes `probability_up`, `action`, `inference_fingerprint` and permanently false capital/trading flags. HTTP 401 means wrong/missing internal token, 503 means unconfigured/missing registry, and 422 means unsupported model, target/feature mismatch or invalid lookahead.

### Verification

```bash
cd intelligence
python -m pytest -q tests/test_frozen_research_inference.py
ruff check src tests
```

Integration tests train actual small **synthetic** CatBoost and LightGBM checkpoints using existing deterministic test fixtures, serialize them, load from a SHA-256-frozen local registry, and call the real FastAPI endpoint. They verify nonempty real probability predictions, immutable evidence, disabled-by-default private access, denial for bad token, unauthorized model override, old training cutoff, missing/future/leaky features, bad input hash, malformed manifest/duplicate model, model byte tampering and forbidden LIVE claims.

### Remaining requirements (explicitly NOT delivered)

- A Java in-process orchestration adapter must produce exact FeatureRows from **V59 server-frozen market/news captures**, dispatch the two matching model versions through the authenticated internal inference plane, record timeout/abstention/failure states, and write only post-source eligible decisions to **V56** matched source cases. This endpoint does not invent that integration or claim that a model happened to be run on real Indian market data.
- Real NSE/BSE licensed source verification, news rights, externally witnessed timestamps, economic acceptance, PAPER order/fill accounting, drift monitoring and broker reconciliation remain separate acceptance gates.
- No route in this PR changes Java risk authority, submits PAPER or broker orders, promotes a model, or enables LIVE. User's configured static IP and deferred paid subscriptions are not altered.
- V4-03 model benchmark completion, V4-06 decision policy tournament, and the V4-11 Flutter research controls are tracked independently.

This increment is a complete, code-tested **trained-checkpoint serving capability**, not V4-09's entire multi-model runtime.
