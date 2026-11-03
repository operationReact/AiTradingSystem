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

## V4-03 prospectively frozen India holdout (self-declared research record)

`india-holdout-freeze-plan.v1` records a **prospective** NSE/BSE cash-equity
five-minute holdout window *before* its first candle is observed. The freeze
fingerprints the already captured training export, registered model families,
model/target configuration and point-in-time India fee/tax schedule. It rejects
backdated windows relative to its supplied timestamp and source-generation
metadata, changed inputs, unregistered model families, and a shifted evaluation
window. Its generated JSON is immutable by fingerprint and written once
without overwriting an existing file (mode 0600).

Use an **actually future** holdout; dates below are illustrative and should
be replaced by the next eligible market sessions:

```bash
python -m aitrading_intelligence.india_holdout_freeze freeze \
  train-market-history.json india-fee-book.json locked-plan.json \
  --plan-id india-five-minute-nov26 --from-at 2026-11-05T09:15:00+05:30 \
  --to-at 2026-11-06T15:30:00+05:30 --cost-bps 25

# After the specified holdout is collected and evaluated:
python -m aitrading_intelligence.india_holdout_freeze bind \
  locked-plan.json train-market-history.json holdout-market-history.json \
  india-fee-book.json --cost-bps 25 --report result-holdout.json
```

The `bind` command verifies the frozen training export, exact declared future
window and exchange/instrument/catalogue/provider fields; fee book, target,
model-config fingerprints; and (when supplied) model-result lineage.
Any discrepancy is a hard error. Generate and externally timestamp the plan
**before collecting/evaluating holdout data**. Save the plan digest in an
independent, access-controlled timestamped system to establish stronger
preregistration evidence. This CLI alone cannot prove that its local timestamps
were not backdated or that the data provider, licensing or broker fees are
genuine.

The resulting `india-holdout-plan-binding.v1` *always* states
`SELF_DECLARED_PLAN_CONSISTENT`, never independently preregistered or source
verified. It cannot promote models, validate market profitability, or authorize
PAPER/SHADOW/LIVE trading. Real data-sourcing audits and market-evaluation
results are still required.

## V4-03 independent Indian-market holdout runner (research only)

This runner consumes **two distinct** Java `market-history.v1` exports of the same
NSE/BSE cash-equity instrument (5-minute bars), plus a versioned and fingerprinted
`india-fee-tax-config.v1` book. Training labels must be observable before the later
holdout begins. CatBoost and LightGBM are trained only on the earlier export and
scored on the untouched later export. PyTorch is optional in the CPU model-lab runtime.

```bash
python -m aitrading_intelligence.india_independent_holdout \
  train-market-history.json holdout-market-history.json india-fee-book.json \
  --horizon-bars 1 --cost-bps 25 --minimum-edge-bps 15
# Add --include-pytorch only after installing requirements-pytorch-cpu.txt.
```

The configured total round-trip cost must cover the effective NSE/BSE intraday
brokerage, statutory charges, GST on applicable charges, and a declared
spread/slippage allowance. Rates are **provided** by the operator, not fetched or
certified as statutory truth; independently verify the broker fee book and source.
Holdout sample/session or class insufficiency produces `INCONCLUSIVE`, never
fictitious validation metrics.

Even successful output is `RESEARCH_HOLDOUT_METRICS_ONLY`. Source authenticity,
pre-registration, repeat-trial bias, liquidity/volume capacity, realistic fills,
independent external data audits and PAPER/SHADOW success remain **unverified**.
No model receives production champion, risk, broker or LIVE authority.

## V4-03 conditional XGBoost benchmark (optional CPU research)

The research-only `conditional-xgboost-benchmark.v1` compares bounded CPU XGBoost
with the existing CatBoost baseline on exactly the same independently held-out
NSE/BSE intraday market-history cohort. Install the optional pinned dependency
using `pip install -r requirements-xgboost-cpu.txt`. The dedicated
`python-xgboost.yml` CI workflow validates the real XGBoost implementation,
without adding its runtime to the backend or regular Python intelligence image.

Run offline with separate, point-in-time, previously frozen market exports:

```bash
python -m aitrading_intelligence.xgboost_benchmark \
  train-market-history.json holdout-market-history.json india-fee-book.json \
  --horizon-bars 1 --cost-bps 25 --minimum-edge-bps 15
```

The evaluation reuses the existing India holdout's source identity, causal
train/holdout split, same-session targets, fee-book and spread/slippage checks.
It records aligned after-cost returns and probabilities, paired block-bootstrap
economic uplift, probability redundancy, calibration and drawdown regression,
and XGBoost CPU fit/inference latency. Configuration bounds model size and
maximum inference time. A candidate can only receive
`RESEARCH_CANDIDATE_ONLY`; insufficient or non-additive evidence returns
`INCONCLUSIVE` or `NOT_ADDITIVE` with explicit reasons.

**Limitations:** Synthetic test fixtures do not establish Indian-market
profitability. Trading fees and slippage require independently verified inputs;
holdout pre-registration and source authenticity are unverified; this screen
does not correct for repeated unregistered experiments. It does not enroll a
candidate in PAPER/SHADOW or authorize production/LIVE capital.

## V4-03 Python -> Java research evidence bridge (research-only)

`python-research-result-bridge.v1` prepares the existing ADMIN-only Java
`POST /api/admin/model-governance/experiment-jobs/{jobId}/result` request
from an immutable NSE/BSE holdout report (optionally paired with an immutable
XGBoost benchmark report). **It does not send HTTP requests or claim a job.**

The worker must already have registered an experiment spec, enqueued a
`PYTHON_INTELLIGENCE` job with runner version exactly
`india-independent-holdout.v1` or `conditional-xgboost-benchmark.v1`,
obtained an active lease through Java `/claim`, and started that job.
The runner computes `derive_holdout_dataset_fingerprint(holdout_report)` and
pre-registers that digest as `experiment.dataset.datasetFingerprint`.
The target label fingerprint must match
`experiment.target.labelDefinitionFingerprint`, and the Java target name
must be `forward-return-after-costs.v1`. Java's experiment spec, not this
bridge, remains the authority for job identity and model-governance policy.

```bash
python -m aitrading_intelligence.java_research_registration \
  java-experiment.json java-running-job.json india-holdout-report.json \
  private-result-registration.json
# Optional: --xgboost-report xgboost-research-report.json
```

The output is exactly the JSON body for Java's result endpoint. It includes
the short-lived **claimToken**; the CLI writes it with restrictive file
creation permissions (0600) and never prints the token. Use the authenticated
ADMIN worker's established session to submit it before the Java lease expires.
Do not commit, upload, or log this token-bearing request. A fresh Java lease
and a new output path are required after expiry; the tool refuses overwrite.

The bridge validates the exact Java experiment ID, spec fingerprint, dataset
binding, target label, running Python runner version, and unexpired lease.
It validates the source report fingerprint and, for XGBoost, its exact parent
holdout report fingerprint. The Java server revalidates ADMIN authorization,
job state, lease token, model governance, and immutable result uniqueness.

**All these reports use assessment `INCONCLUSIVE`**, even if a model
looks promising. Source authenticity, holdout preregistration, the Java
multi-fold protocol and real trading economics have not been independently
verified. A successfully registered result means the research computation
finished, NOT that the model is approved for PAPER, SHADOW or LIVE trading.
No broker order, deployment, or capital authorization is added.

## V4-03 trusted Java result submission and read-back (opt-in, no trading)

The one-shot `java_research_result_worker` consumes a **precomputed**
`india-independent-holdout.v1` report, optionally with a
`conditional-xgboost-benchmark.v1` child report. It uses the existing
Spring ADMIN-only experiment-job API to:

1. GET the explicit, already **RUNNING** Python experiment job
2. GET the immutable experiment specification
3. Confirm job/spec/dataset/target, research report fingerprints, runner
   version, and unexpired Java claim lease
4. POST **one** `INCONCLUSIVE` immutable result with its active claim token
5. GET the Java result and completed job to verify fingerprints, lineage and
   zero capital authority

The tool **does not** register an experiment, claim/start a queue job, run
model training, manage Java credentials, enable LIVE, or make broker calls.
Only invoke it for an experiment previously created and started by an
authorized Java control-plane operator/worker. The Java server rechecks RBAC,
governance, worker claim and transaction uniqueness.

**Authentication contract:** Java's current ADMIN API authenticates via the
HttpOnly `ATS_SESSION` cookie plus `X-CSRF-Token` on POST. A Bearer header is
not supported, and legacy API-key authentication yields only ROLE_OPERATOR,
which cannot access model-governance endpoints. The session must already belong
to an ADMIN account. The local/dev session-bootstrap endpoint is not a means of
obtaining ADMIN authority; never elevate its role for this worker.
A deployment lacking an authorized ADMIN session is a genuine operational blocker.

```bash
# Supply an EXISTING ADMIN session and its session-bound CSRF token securely.
# Obtain these from an authorized authentication flow; never use the legacy
# X-API-Key (ROLE_OPERATOR) or an unsupported Bearer header.
# Avoid typing real secrets into terminal history or committing them to Git.
export AITS_JAVA_ADMIN_SESSION='<securely-injected-ATS_SESSION-cookie-value>'
export AITS_JAVA_ADMIN_CSRF_TOKEN='<securely-injected-X-CSRF-Token-value>'
python -m aitrading_intelligence.java_research_result_worker \
  https://your-governed-backend.example \
  00000000-0000-4000-8000-000000000001 \
  india-holdout-report.json
# Optional: --xgboost-report xgboost-research-report.json
unset AITS_JAVA_ADMIN_SESSION AITS_JAVA_ADMIN_CSRF_TOKEN
```

HTTPS is required except HTTP to loopback for isolated local testing;
redirects, embedded URL credentials, and automatic proxy environment access
are disabled. Requests use a bounded timeout and never retry the non-idempotent
POST. On a timeout or conflict the worker checks for an existing immutable
Java result instead of posting again. Any discrepancy, authorization failure,
expired lease, or ambiguous outcome is fail-closed; the worker never prints
ADMIN session cookies, CSRF tokens or claim tokens.

A success receipt means only that Java **stored and read back**
`INCONCLUSIVE` research evidence and marked its job `SUCCEEDED`.
It does **not** prove genuine NSE/BSE inputs, pre-registered holdout, actual
strategy profit, PAPER/SHADOW acceptance, deployment, or LIVE readiness.
Tests use a simulated Java HTTP control-plane response; no real endpoint is
contacted by CI. Runtime deployment and authoritative market-data
verification remain separate acceptance requirements.

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
