# AiTradingSystem Intelligence Service

Python 3.11 foundation for point-in-time research, feature engineering, quantitative models and PyTorch challenger inference.

## Safety boundary

This service produces **evidence only**. It cannot authorize capital, place an order, bypass the Java Risk Engine, or activate LIVE trading. Java/Spring remains authoritative for account state, risk, orders, fills and execution.

`/v1/contracts/validate` establishes the versioned Java↔Python evidence contract, point-in-time guards and deterministic input fingerprinting.

## Feature and dataset foundation

V2-04 introduces versioned point-in-time data contracts for market bars, events and fundamental snapshots, plus deterministic feature-row assembly and Parquet research exports.

Key guarantees:

- feature rows only use records whose `observed_at <= as_of`;
- future market/event/fundamental records are excluded rather than silently joined;
- feature rows carry a stable schema version, data cutoff, provenance and SHA-256 fingerprint;
- research exports include Parquet schema metadata plus a JSON manifest with dataset version, code SHA, source versions, row count and dataset fingerprint;
- dataset generation remains an offline/research concern and cannot authorize trades.

## Authoritative Java market-history bridge

The Spring backend exposes a bounded, versioned research export at `GET /api/market-data/research/candles`. It reads the existing authoritative `market_candle` history instead of giving Python direct database access.

The `market-history.v1` contract carries instrument catalogue lineage, provider sources, candle event time, first safe feature-availability time and ingestion time. Intraday OHLCV is not considered observable until the candle interval has closed; daily OHLCV is not considered observable until the Indian cash-market close. This prevents the training pipeline from treating a candle's opening timestamp as if its final high/low/close were already known.

Build an offline Parquet dataset from a saved Java export:

```bash
python -m aitrading_intelligence.market_source market-history.json ./dataset \
  --dataset-version reliance-5m-v1 \
  --code-sha <40-char-git-sha>
```

The resulting manifest records the Java export contract, instrument catalogue version, provider lineage, interval, code SHA and effective data cutoff.

## V2-06 quantitative baseline and signal fusion

V2-06 uses an interpretable scikit-learn logistic baseline trained from point-in-time `FeatureRow` inputs and a fixed tree challenger for chronological out-of-sample comparison. The persisted logistic research artifact contains explicit feature names, coefficients, intercept, training window, class counts and a deterministic SHA-256 artifact fingerprint.

`POST /v1/fusion/rank` combines three evidence families:

- deterministic Java strategy evidence;
- Python quantitative baseline evidence;
- optional Gemini structured evidence.

Fusion is freshness-aware and confidence-weighted. Deterministic strategy and quantitative evidence are mandatory and must be fresh; Gemini can be omitted or excluded when stale. Model disagreement remains `WATCH` rather than being hidden by a high aggregate score.

Every fused result remains `CANDIDATE_NOT_EXECUTABLE` and `can_authorize_capital=false`. A ranked `BUY` candidate is still only evidence for the Java Risk Engine; it is not an order approval.

## V2-07 PyTorch challenger foundation

The first PyTorch target is `forward-return-after-costs.v1`: at decision time `t`, predict whether the close after a configurable number of bars exceeds the current close by more than the configured round-trip cost plus minimum edge. The future close is used only to generate the offline label; it is never copied into the model input feature snapshot.

Training is intentionally conservative:

- chronological train/validation split;
- training examples whose future target would not yet be known at validation start are purged;
- feature mean/scale are learned only from the training partition;
- small CPU MLP with fixed seed and deterministic PyTorch algorithms;
- immutable target/config/data/model fingerprints;
- validation ROC AUC, Brier score and log loss;
- gradient × standardized-input attribution for inference evidence;
- every artifact and inference result remains `promotion_allowed=false` and `can_authorize_capital=false`.

PyTorch is isolated behind a dedicated CPU-only dependency file and CI workflow so ordinary API/backend changes do not download the large ML runtime.

Install the research runtime:

```bash
pip install -r requirements-pytorch-cpu.txt
pip install -e '.[dev]'
```

A trained PyTorch candidate remains a **challenger**. It cannot enter signal fusion as an accepted model until it beats the V2-06 baselines under later out-of-sample, replay, regime, cost/slippage and governance gates.

## Run locally

```bash
python -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
uvicorn aitrading_intelligence.app:app --reload --port 8090
```

## Test

```bash
ruff check src tests
pytest -q
```

For the PyTorch-enabled suite use the dedicated CPU runtime above; CI runs it separately.

## Contract invariants

- all timestamps are timezone-aware;
- `data_cutoff <= as_of` so later data cannot leak into an earlier decision;
- candle event time and safe feature-availability time remain distinct;
- feature values must be finite numbers;
- every intelligence response says `can_authorize_capital=false`;
- canonical SHA-256 fingerprints make validated inputs, model artifacts and fused candidates reproducible;
- model evidence carries version/schema/provenance rather than hidden state;
- PyTorch remains a non-promoted challenger until objective baseline/replay/governance gates pass;
- signal fusion ranks evidence only; Java Risk Engine remains the only capital authority.
