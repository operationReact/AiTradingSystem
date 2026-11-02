# AiTradingSystem Intelligence Service

Python 3.11 foundation for point-in-time research, feature engineering, quantitative models and future PyTorch inference.

## Safety boundary

This service produces **evidence only**. It cannot authorize capital, place an order, bypass the Java Risk Engine, or activate LIVE trading. Java/Spring remains authoritative for account state, risk, orders, fills and execution.

The first version intentionally contains no learned model. `/v1/contracts/validate` establishes the versioned Java↔Python evidence contract, point-in-time guards and deterministic input fingerprinting without fabricating a recommendation.

## Feature and dataset foundation

V2-04 introduces versioned point-in-time data contracts for market bars, events and fundamental snapshots, plus deterministic feature-row assembly and Parquet research exports.

Key guarantees:

- feature rows only use records whose `observed_at <= as_of`;
- future market/event/fundamental records are excluded rather than silently joined;
- feature rows carry a stable schema version, data cutoff, provenance and SHA-256 fingerprint;
- research exports include Parquet schema metadata plus a JSON manifest with dataset version, code SHA, source versions, row count and dataset fingerprint;
- dataset generation remains an offline/research concern and cannot authorize trades.

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

## Contract invariants

- all timestamps are timezone-aware;
- `data_cutoff <= as_of` so later data cannot leak into an earlier decision;
- feature values must be finite numbers;
- every response says `can_authorize_capital=false`;
- canonical SHA-256 fingerprints make validated inputs and dataset rows reproducible;
- future model evidence must carry model/version/schema/provenance rather than hidden state.
