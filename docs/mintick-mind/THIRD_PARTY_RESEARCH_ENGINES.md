# Mintick Mind — Five Open-Source Trading Engines: Low-Level Integration & 14-PR Runbook

**Change request:** Add five externally discovered GitHub research/trading engines to the existing Mintick Mind roadmap without compromising the single-owner-first, multi-user-ready independent intelligence architecture. **Status:** engineering evaluation and PR plan only; no repo dependency added to production, no AWS deployment, no live orders, no model promotion.

**Baseline:** Existing [Mintick Mind — Complete Implementation Master Plan (42 Screens · 18 Models · 232 Strategies)](https://app.notion.com/p/41336a828cb6814f953bded0901576b3) and [AiTradingSystem — V4 Multi-Model Intelligence & Admin Control Master Implementation Plan](https://app.notion.com/p/40d36a828cb6812594c0e657fe5db1b3), repository `operationReact/AiTradingSystem` on dev (inspect latest SHA at implementation). Prior [PR #656](https://github.com/operationReact/AiTradingSystem/pull/656) added market snapshot migrations V70/V71. Preserve existing market-data source status and prevent stored quote snapshots from becoming actionable. GitHub branch documentation is separate from production completion.

## 1. Architectural decision and scope

- **Mintick Mind Core:** source-licensed NSE/BSE/India data, point-in-time features, deterministic strategy registry, validated predictive models, prediction outcomes, calibrated voting/ensemble, evidence/incident logs. No user account is necessary for generic research scoring.
- **Private account layer:** Cognito owner account, broker credentials and sessions, holdings, capital, room allocation, risk, order intent, fills, positions and execution learning. The market engine must not query private data to issue generic stock scores.
- **Five external repositories:** candidate **research adapters, benchmark oracles and architectural references**, not replacement market-data vendors and not broker-authorized execution engines.
- **Rule:** Java continues owning funds/risk/approval/orders. External processes get strictly immutable, verified, bounded, **research-only** datasets and return evidence artefacts without trading authority.
- **Default:** third-party runtime disabled until licensing, source provenance, exact version/commit pin, SCA vulnerability review, Python/OS architecture compatibility, performance proof and regression suite pass.
- **Commercial future:** a multi-user paid service requires reviewing AGPL, GPL, LGPL, VectorBT Commons Clause and any Indian exchange/broker-data terms. A personal trading account does not eliminate the need to follow licence conditions. These notes are engineering flags, not legal opinions.
- **No promised profits:** a backtesting framework does not supply a profitable trading model or gratis Indian exchange data. All results must be reproducible after costs and realistic corporate-action/session rules.

## 2. Evaluation order and decision matrix

| ID | Repository | Decision | Exact upstream snapshot | Licence |
| --- | --- | --- | --- | --- |
| R1 | [VectorBT](https://github.com/polakowo/vectorbt) | PILOT FIRST — conditional adapter; not live execution | `07f5e85e83a5` | Apache-2.0 with Commons Clause |
| R2 | [Backtesting.py](https://github.com/kernc/backtesting.py) | EVALUATE AS SECONDARY ORACLE — optional, short-lived research | `ca2e2611621e` | AGPL-3.0 |
| R3 | [NautilusTrader](https://github.com/nautechsystems/nautilus_trader) | R&D P2 — high-fidelity simulation benchmark; never replace Java order service | `772f35e5b5a0` | LGPL-3.0 |
| R4 | [QuantConnect LEAN](https://github.com/QuantConnect/Lean) | REFERENCE / OPTIONAL CROSS-ENGINE BENCHMARK — no main backend replacement | `cc98c42f5543` | Apache-2.0 |
| R5 | [Freqtrade](https://github.com/freqtrade/freqtrade) | STUDY DESIGN PATTERNS — no production runtime import | `227997806986` | GPL-3.0 |

These pinned SHAs are *audit references*, not automatically approved Python dependency versions. At each adapter implementation PR, verify the upstream commit's dependency lock, Python support, known issues, checksum and reproducible package build. Do not install from a moving `master/develop` branch.

## 3. Existing MintTick components to reuse (verified source paths)

| Existing file | Responsibility / integration rule |
| --- | --- |
| `backend/src/main/java/in/aitradingsystem/governance/ExperimentJobOrchestrationService.java` | Existing immutable spec fingerprint, durable job queue, `JAVA_BACKTEST` and `PYTHON_INTELLIGENCE` runner categories, PostgreSQL claim lease. Add compatible adapter identity via runner version/config, avoid a second job database. |
| `backend/src/main/java/in/aitradingsystem/governance/BacktestComparisonService.java` | Existing comparison fingerprints for dataset, target, folds and economic costs; attach normalized third-party metrics as research-only evidence. |
| `backend/src/main/java/in/aitradingsystem/governance/ExperimentSpecService.java` | Immutable research spec input and governance; no arbitrary raw command payload. |
| `backend/src/main/java/in/aitradingsystem/backtest/PaperBacktestService.java` | Java deterministic baseline and existing strategy history. Never replace before parity. |
| `backend/src/main/java/in/aitradingsystem/backtest/WalkForwardValidationService.java` | Existing walk-forward logic and temporal validity contract. |
| `backend/src/main/java/in/aitradingsystem/marketdata/MarketDataResearchExportService.java` | Versioned canonical historical market export; preserve source/available-at and 20,000-row cap currently present. If pilot needs more, design paginated/chunked export rather than removing bounds. |
| `backend/src/main/java/in/aitradingsystem/marketdata/MarketOverviewSnapshotStore.java` and `MarketDetailSnapshotStore.java` | PR #656 V70/V71 latest observation snapshots; never treat as long-range history or current trade eligibility when stored. |
| `intelligence/src/aitrading_intelligence/research_dataset.py` | Source-bound research dataset builder; add projection to canonical OHLCV/data-frame formats with fingerprint checks. |
| `intelligence/src/aitrading_intelligence/fill_simulator.py` | Existing Pydantic simulation request/config/fill/result contract; use as a comparison reference, not an unverified shortcut for real fills. |
| `intelligence/src/aitrading_intelligence/combinatorial_purged_cv.py` | Existing CPCV/purged split primitives; preserve split fingerprint parity. |
| `intelligence/src/aitrading_intelligence/v4_model_pareto_tournament.py` | Existing research-only Pareto tournament, no capital authority. Add performance/compute/realism dimensions with immutable evaluation receipts. |
| `intelligence/src/aitrading_intelligence/research_artifact_inference.py` | Signed/allowlisted CatBoost/LightGBM research inference; third-party research engine cannot add an inference/execution backdoor. |
| `intelligence/src/aitrading_intelligence/fingerprints.py` | Shared deterministic canonical fingerprint semantics for experiment inputs and outputs. |
| `intelligence/pyproject.toml` | Current Python constraint `>=3.11,<3.12`; dependency pins MUST resolve on this baseline and AWS ARM64 or use separate CPU container. |
| `apps/trading_client/lib/src/features/mintick_mind/presentation/mind_research_screens.dart` | Connect Research/Experiment/Performance/Shadow cards through typed read-only DTOs after real experiment output exists. |
| `apps/trading_client/lib/src/features/mintick_mind/presentation/mind_analytics_screens.dart` | Strategy Universe, Strategy Detail, Model Laboratory, Decision simulator and dependencies consume normalized results, no fake performance. |
| `apps/trading_client/lib/src/features/mintick_mind/presentation/mind_agent_screens.dart` | Shadow Arena, Scorecard, Confidence, Evidence Ledger, Culprit Catcher consume traced result/cost/incident IDs. |

Avoid creating duplicate `experiment_job`, `backtest_result`, `model_registry`, `market_overview_snapshot`, or `market_detail_snapshot` tables. Any necessary migrations must inspect latest Flyway head (V71 at the PR #656 audit, possibly later). New integrations should be additive, optional, and reversible.

## 4. Detailed external repository dossiers

### R1. VectorBT — PILOT FIRST — conditional adapter; not live execution

**Upstream:** [repository](https://github.com/polakowo/vectorbt); audited revision `07f5e85e83a5286c396f4ecfd55dfaa05f25442d`.

**Value:** NumPy/Numba vectorized parameter sweeps and reusable portfolio simulations.

**Known limitations:** Fast vectorized fills may simplify order timing, spread, partial fills and liquidity; memory may grow with parameter grid.

**Licence gate:** Apache-2.0 with Commons Clause (non-standard; third-party sale/service restrictions). Record legal review before any future public or commercial deployment.

**Specific verified upstream files to inspect:**

- `vectorbt/portfolio/base.py`: Portfolio.from\_signals/from\_orders/from\_order\_func.
- `vectorbt/indicators/factory.py`: IndicatorFactory and param combinations.
- `vectorbt/signals/generators.py`: signal generation primitives.
- `vectorbt/generic/splitters.py`: train/test split primitives.
- `vectorbt/returns/metrics.py`: returns metrics.

**Proposed integration boundary:** Use bounded Python research subprocess/optional dependency behind experiment-runner interface; no feed/broker API access; compare to the existing Java baseline on identical data.

**Tasks:** (1) Review upstream tests, release/commit, version/pins and licence. (2) Define minimal canonical sample input. (3) Build an isolated, non-networked research-only proof of concept with no AWS keys or broker sessions. (4) Convert native engine results to MintTick immutable `ResearchComparisonResultV1`. (5) Run matched Java baselines and compare order times, fees and labels. (6) Benchmark speed, max RSS, disk, build time and maintenance burden. (7) Choose ADOPT / KEEP OPTIONAL / REJECT with justification; keep disabled on failure.

**Focused acceptance tests:** parameter-grid cap, reproducibility/seed, no lookahead, fee/slippage parity, split alignment, output hashes, memory peak and wall-clock, overfitting/deflated return check.

**Decision:** ADOPT ONLY IF measured after-cost value and acceptable license; never make it mandatory for the engine.

### R2. [Backtesting.py](http://Backtesting.py) — EVALUATE AS SECONDARY ORACLE — optional, short-lived research

**Upstream:** [repository](https://github.com/kernc/backtesting.py); audited revision `ca2e2611621e472542ba90f7243a1fa06a7d7108`.

**Value:** Simple Python event-by-bar strategy lifecycle, order/stop/take-profit simulation and transparent trade logs.

**Known limitations:** Not a broker data source; assumptions around bar boundaries, intra-bar fills, portfolio scope and competition need explicit parity checks.

**Licence gate:** AGPL-3.0 (distribution/network-service obligations require legal review before a multi-user service). Record legal review before any future public or commercial deployment.

**Specific verified upstream files to inspect:**

- `backtesting/backtesting.py`: Backtest, Strategy, broker simulation.
- `backtesting/lib.py`: helper studies.
- `backtesting/_stats.py`: metric outputs.
- `backtesting/test/_test.py`: upstream reference scenarios.
- `pyproject.toml`: dependency metadata.

**Proposed integration boundary:** Recompute a restricted set of strategies with same bars and costs; persist discrepancy reports, never allow its results to authorize capital.

**Tasks:** (1) Review upstream tests, release/commit, version/pins and licence. (2) Define minimal canonical sample input. (3) Build an isolated, non-networked research-only proof of concept with no AWS keys or broker sessions. (4) Convert native engine results to MintTick immutable `ResearchComparisonResultV1`. (5) Run matched Java baselines and compare order times, fees and labels. (6) Benchmark speed, max RSS, disk, build time and maintenance burden. (7) Choose ADOPT / KEEP OPTIONAL / REJECT with justification; keep disabled on failure.

**Focused acceptance tests:** bar-open vs bar-close entry, stop/take-profit when same candle touches both, fractional/lot rounding, gaps, fees, no-trade, out-of-market fills.

**Decision:** OPTIONAL benchmark. Disable/remove if it duplicates Java or its licensing is incompatible.

### R3. NautilusTrader — R&D P2 — high-fidelity simulation benchmark; never replace Java order service

**Upstream:** [repository](https://github.com/nautechsystems/nautilus_trader); audited revision `772f35e5b5a0ad9204962503873a458692680540`.

**Value:** Rust-native event replay, simulated exchanges, latency and matching/fill models.

**Known limitations:** Large runtime/dependency surface, Python/Rust ABI and deployment complexity, changing upstream APIs; no verified drop-in Angel One/Upstox adapter.

**Licence gate:** LGPL-3.0 (separate link/distribution obligations; review platform plan). Record legal review before any future public or commercial deployment.

**Specific verified upstream files to inspect:**

- `crates/backtest/README.md`: crate capabilities.
- `crates/backtest/src/engine.rs`: event-driven BacktestEngine.
- `crates/backtest/src/exchange.rs`: simulated exchange behavior.
- `crates/backtest/src/execution_client.rs`: simulated execution events.
- `crates/backtest/tests/integration/fee_model.rs`: fee reference tests.
- `crates/backtest/tests/integration/spread_execution.rs`: spread tests.
- `crates/backtest/tests/integration/canonical_backtest_workloads.rs`: canonical deterministic cases.

**Proposed integration boundary:** Offline separate research container with canonical instrument/order/quote event conversion; publish signed normalized results to existing experiment ledger.

**Tasks:** (1) Review upstream tests, release/commit, version/pins and licence. (2) Define minimal canonical sample input. (3) Build an isolated, non-networked research-only proof of concept with no AWS keys or broker sessions. (4) Convert native engine results to MintTick immutable `ResearchComparisonResultV1`. (5) Run matched Java baselines and compare order times, fees and labels. (6) Benchmark speed, max RSS, disk, build time and maintenance burden. (7) Choose ADOPT / KEEP OPTIONAL / REJECT with justification; keep disabled on failure.

**Focused acceptance tests:** event ordering, trade/quote timestamp alignment, market calendars, order book/depth licensing, latency, partial fills, order lifecycle, resource budget, no outbound broker permissions.

**Decision:** PILOT ONLY if coarse OHLCV fill simulator demonstrably misses important Indian execution behavior; otherwise defer.

### R4. QuantConnect LEAN — REFERENCE / OPTIONAL CROSS-ENGINE BENCHMARK — no main backend replacement

**Upstream:** [repository](https://github.com/QuantConnect/Lean); audited revision `cc98c42f5543256cea0a33e4521401e42303b7d7`.

**Value:** Broad engine with calendars, security models, price adjustments, brokerage fee/slippage models and realistic order lifecycles.

**Known limitations:** C#/.NET stack overlaps Java and Python; heavy data-format conversion; broker integrations do not equal our Angel One/Upstox connectivity.

**Licence gate:** Apache-2.0 (engine license; data/CLI/cloud services have separate terms). Record legal review before any future public or commercial deployment.

**Specific verified upstream files to inspect:**

- `Algorithm.CSharp/BasicTemplateIndiaAlgorithm.cs`: India regression example.
- `Algorithm.Python/BasicTemplateIndiaAlgorithm.py`: Python India regression example.
- `Common/Brokerages/ZerodhaBrokerageModel.cs`: Indian brokerage rule reference.
- `Common/Data/Auxiliary/CorporateFactorProvider.cs`: adjustment factor architecture.
- `Common/Data/Consolidators/MarketHourAwareConsolidator.cs`: session-aware bars.
- `Common/Orders/`: order lifecycle types.

**Proposed integration boundary:** Document engine design patterns; optionally compare 2–3 reference portfolios using a sandboxed C# runner with converted, licensed Indian candles. No source copying without attribution/review.

**Tasks:** (1) Review upstream tests, release/commit, version/pins and licence. (2) Define minimal canonical sample input. (3) Build an isolated, non-networked research-only proof of concept with no AWS keys or broker sessions. (4) Convert native engine results to MintTick immutable `ResearchComparisonResultV1`. (5) Run matched Java baselines and compare order times, fees and labels. (6) Benchmark speed, max RSS, disk, build time and maintenance burden. (7) Choose ADOPT / KEEP OPTIONAL / REJECT with justification; keep disabled on failure.

**Focused acceptance tests:** timezone/session, ticker remapping/corporate actions, fee/tax parity, model of Indian product eligibility, same datasets and trade times.

**Decision:** NO core dependency unless it outperforms existing platform in independently measured correctness sufficiently to justify .NET operations.

### R5. Freqtrade — STUDY DESIGN PATTERNS — no production runtime import

**Upstream:** [repository](https://github.com/freqtrade/freqtrade); audited revision `227997806986013fb6544d9253429855c603ca97`.

**Value:** Lookahead-analysis, recursive-analysis, hyperparameter search, dry-run, lifecycle recovery and FreqAI training concepts.

**Known limitations:** Crypto/exchange-oriented data and trading flows; Indian market sessions, NSE/BSE instruments, corporate actions and broker policy mismatch.

**Licence gate:** GPL-3.0 (strong copyleft when copying/linking/distributing covered code). Record legal review before any future public or commercial deployment.

**Specific verified upstream files to inspect:**

- `freqtrade/optimize/analysis/lookahead.py`: lookahead signal tests.
- `freqtrade/optimize/analysis/recursive.py`: indicator recursion diagnostics.
- `freqtrade/optimize/hyperopt/hyperopt.py`: bounded optimization workflow.
- `freqtrade/optimize/backtesting.py`: backtest orchestration.
- `freqtrade/freqai/data_kitchen.py`: data/feature workflow.
- `freqtrade/freqai/prediction_models/LightGBMClassifier.py`: model interface reference.

**Proposed integration boundary:** Write original MintTick implementations of leak-detection and experiment scheduling concepts; do not transplant GPL files or import crypto execution engine.

**Tasks:** (1) Review upstream tests, release/commit, version/pins and licence. (2) Define minimal canonical sample input. (3) Build an isolated, non-networked research-only proof of concept with no AWS keys or broker sessions. (4) Convert native engine results to MintTick immutable `ResearchComparisonResultV1`. (5) Run matched Java baselines and compare order times, fees and labels. (6) Benchmark speed, max RSS, disk, build time and maintenance burden. (7) Choose ADOPT / KEEP OPTIONAL / REJECT with justification; keep disabled on failure.

**Focused acceptance tests:** causal indicator parity, future-mutation differential, recomputation consistency, cache invalidation, bounded optimizer, no crypto exchange dependency.

**Decision:** DOCUMENT ONLY; selectively independently implement concepts when audit demonstrates a gap.

## 5. Proposed new MintTick files (none exists merely because it is listed)

**All paths below are proposed. Before coding, inspect latest dev naming and tests to avoid duplicating equivalents.**

| Proposed file | Concrete content | Dependency |
| --- | --- | --- |
| `intelligence/src/aitrading_intelligence/research_engines/contracts.py` | Pydantic `ResearchEngineSpecV1`, `MarketSeriesV1`, `ResearchEngineResultV1`, `EngineDiscrepancyV1`; strict model config, immutable fingerprints | Existing `fingerprints.py`, dataset exports |
| `intelligence/src/aitrading_intelligence/research_engines/registry.py` | Optional engine enumeration, allowlist, installed version, legal status, activation flags, no arbitrary Python imports | Existing model/experiment governance |
| `intelligence/src/aitrading_intelligence/research_engines/market_adapter.py` | Canonical candle/CSV/Parquet → pandas OHLCV, UTC index, exchange calendar, corporate-action revision, volume and no-lookahead mask | Market research export |
| `intelligence/src/aitrading_intelligence/research_engines/costs.py` | Indian brokerage, STT, exchange fees, GST and stamp duty by product/date, spread and pessimistic slippage; versions and assumptions | Existing `india_fee_tax.py`, Java fees |
| `intelligence/src/aitrading_intelligence/research_engines/vectorbt_adapter.py` | Conditional import `vectorbt`; bounded parameter grid; `Portfolio.from_signals` for screening, normalize fills and metrics | R1 licence, resource policy |
| `intelligence/src/aitrading_intelligence/research_engines/backtesting_py_adapter.py` | Conditional `Backtest`  • `Strategy`; constrained per-bar reference strategies, normalize trade logs | R2 licence |
| `intelligence/src/aitrading_intelligence/research_engines/nautilus_adapter.py` | Offline event/contract mapping to NautilusTrader subprocess; return matched trades and fill realism | R3 independent environment |
| `intelligence/src/aitrading_intelligence/research_engines/lean_reference.py` | Optional file-manifest bridge to standalone sandbox C# LEAN benchmark; not a direct .NET dependency in Java | R4 explicit approved benchmark |
| `intelligence/src/aitrading_intelligence/research_engines/leakage_checks.py` | Original in-house differential future-mutation and recursive warm-up tests inspired by Freqtrade concepts, no GPL code copy | R5 research |
| `intelligence/src/aitrading_intelligence/research_engines/parity.py` | Normalize fills/trades, compare nearest eligible decision timestamps, metric tolerance, discrepancy categories | Java baseline + all adapters |
| `intelligence/src/aitrading_intelligence/research_engines/runner.py` | Dispatcher from signed experiment spec, hard time/memory/parameter and file limits; offline-only, unprivileged process | Experiment job ledger |
| `intelligence/src/aitrading_intelligence/research_engines/report.py` | Signed JSON research receipt, source SHA, licence version, runtime dependency lock digest, costs, no-capital flags | Existing research signed outputs |
| `intelligence/tests/test_research_engines_*.py` | Units for schema, market mapping, fees, gaps, backend parity, no-broker calls, metered timeout | All adapter phases |
| `intelligence/tests/fixtures/research_engines/*.json` | Synthetic deterministic India trading-session cases, splits, gaps, zero-volume, ambiguous intrabar stop, options example | Test-only |
| `docs/mintick-mind/THIRD_PARTY_RESEARCH_ENGINES.md` | Versioned repo evaluation, commit SHAs, legal decision, adoption scoring, PR evidence | This Notion annex |
| `docs/mintick-mind/RESEARCH_ENGINE_PR_RUNBOOK.md` | Sequence, risks, acceptance checklist, failure drill and revert paths | PR orchestration |
| `backend/src/test/java/in/aitradingsystem/governance/ResearchEngineParityIT.java` | Java/Postgres research result fingerprint and failed/duplicate job controls; adapt naming as discovered | Existing orchestration |
| `apps/trading_client/test/mintick_mind_research_engine_results_test.dart` | Loading, true metric vs synthetic, no-data, licence-disabled, comparison drilldowns | Read-only result APIs |

**Do not add a separate backend for each upstream project.** Engines operate only behind an optional `ResearchEngineAdapter` boundary; each run uses the immutable Python/Java experiment job and result registry already present. No broker credentials, owner PIN/TOTP, SSM parameter values, raw account positions, trading auth tokens or AWS resource management permissions reach the adapters.

## 6. Canonical input and normalized output contracts

### Proposed research input

```json
{
  "contract_version": "minttick-research-engine-input.v1",
  "engine_id": "VECTORBT",
  "engine_revision": "pinned-commit-or-wheel-digest",
  "experiment_id": "server-issued-id",
  "dataset_fingerprint": "sha256-hex",
  "instrument_universe": ["canonical-instrument-id"],
  "interval": "5m",
  "from_at": "timestamp-inclusive-utc",
  "to_at": "timestamp-exclusive-utc",
  "feature_cutoff_rule": "CLOSED_BAR_ONLY",
  "strategy_version": "approved-strategy-version",
  "parameter_grid": {"fast_ema": [9, 12], "slow_ema": [21, 26]},
  "economic_cost_model_fingerprint": "sha256-hex",
  "fold_plan_fingerprint": "sha256-hex",
  "seed": 42,
  "max_candidates": 32,
  "max_runtime_seconds": 120,
  "max_memory_mib": 1024,
  "can_authorize_capital": false,
  "can_submit_broker_order": false,
  "can_promote_model": false
}
```

### Proposed output

```json
{
  "contract_version": "minttick-research-engine-result.v1",
  "experiment_id": "server-issued-id",
  "engine_id": "VECTORBT",
  "engine_revision": "pinned-revision",
  "dataset_fingerprint": "sha256-hex",
  "strategy_version": "approved-version",
  "fold_plan_fingerprint": "sha256-hex",
  "economic_cost_model_fingerprint": "sha256-hex",
  "input_fingerprint": "sha256-hex",
  "status": "RESEARCH_EVIDENCE_ONLY",
  "trades": [],
  "summary_metrics": {
    "net_return": null,
    "max_drawdown": null,
    "turnover": null,
    "sample_count": 0,
    "fees_and_taxes": null
  },
  "performance": {"wall_ms": null, "max_rss_mib": null, "cpu_seconds": null},
  "discrepancies": [],
  "can_authorize_capital": false,
  "can_submit_broker_order": false,
  "can_promote_model": false,
  "result_fingerprint": "sha256-hex"
}
```

Schema examples are **templates only**, not verified output or actual profitable results. All IDs, hashes, timestamps, costs and metrics must be generated from an experiment, never fabricated in production. Validate engine/ref against an operator-pinned allowlist, require canonical hash and reject extra fields. Do not allow model caller to supply artifact path, custom code, import module, HTTP URL or arbitrary shell command.

## 7. Deterministic India-specific fixtures and parity methodology

| Fixture ID | Scenario | Exact expected assertion |
| --- | --- | --- |
| TC-01 | NSE cash normal 09:15–15:30 session | No out-of-session bar creation or fills; IST conversion deterministic |
| TC-02 | Holiday / special exchange session | Proper session plan; no forward-filled phantom data |
| TC-03 | Candle close timestamp vs next-bar entry | No same-bar close decision executed at unavailable earlier price |
| TC-04 | Overnight gap across stop | Open-gap fill model applied, not guaranteed stop price |
| TC-05 | Stop and target touched same candle | Deterministic conservative ambiguity handling or explicit UNKNOWN |
| TC-06 | Split / bonus / dividend corporate action | Raw/adjusted series correct; no spurious sudden alpha |
| TC-07 | Delisted and IPO/new entrant | Universe membership point-in-time, avoid survivorship |
| TC-08 | Broker lot/tick size and trading product | Correct rounding and instrument validity |
| TC-09 | Fee and tax date changes | Versioned Indian cost schedules, same net P&L baseline |
| TC-10 | Zero liquidity, large spread, suspended/circuit-limit stock | Abstain/fill rejection or adverse slippage, no unrealistic execution |
| TC-11 | Missing, duplicated, future or corrected bar | Dataset rejected/quarantined and reason logged |
| TC-12 | Short-sale eligibility and delivery restriction | Never simulate unpermitted product as executable |
| TC-13 | F&O contract expiry and rollover | No wrong active contract or post-expiry fill |
| TC-14 | Multi-strategy simultaneous signals | Position/cash accounting conserved; no duplicated broker side effects |
| TC-15 | In-process timeout, OOM or restart | Existing job lease/retry rules honored; uncertain results not published |
| TC-16 | Copy of one input with future market data mutated | Frozen historical decisions identical; future leakage test |
| TC-17 | Cross-user synthetic account scopes | Reusable market research, private account data never leaks |
| TC-18 | PR #656 stored market overview after outage | No stored historical market ideas treated as live research signals |

Match identical input instruments, closed bars, available-at, initial cash, position sizing, slippage, fees/taxes, split adjustments, seed, time-based exits and execution policy across engines. Compare **individual order decisions/fill timestamps** before comparing headline Sharpe or cumulative return. Categorize every mismatch as data, strategy, fill, fee, calendar, numeric tolerance or unknown. An unexplained mismatch is a blocked promotion.

## 8. Phase-by-phase and PR-by-PR implementation plan

Implementation is strictly sequential for **dependent changes**. PR numbers here are *planning IDs*, not actual GitHub PR numbers. Every implementation PR must be based on latest merged `dev`, reviewed and merged only after relevant CI and measured evidence.

| Planning PR | Phase | Deliverable | Dependencies | Merge gate |
| --- | --- | --- | --- | --- |
| OSS-01 | A — discovery | Document exact five upstream pinned commits, legal classification, Python ABI/ARM64 dependency matrix, CVEs, licence notices; reject default live/production installation | Current dev | Proven licences, no source copying, reproducible locks |
| OSS-02 | A — canonical | Shared immutable input/result schemas, enum, source provenance, canonical hash and research-only authority flags; registry default disabled | OSS-01; existing research dataset + governance | Schema unit/golden, malformed input, arbitrary path/network rejection |
| OSS-03 | A — India data | One India market-data-to-adapter transform with timestamps, as-of universes, splits, fees, missing candle rules and fold IDs | OSS-02; PR #656 V70/V71 preserved | 18 deterministic fixture cases, no lookahead, no stored-live crossing |
| OSS-04 | B — baseline | Java/Python baseline output normalization, matching transaction costs and normalized trade-event receipts, discrepancy taxonomy | OSS-03; BacktestComparisonService | Golden parity for representative SMA, EMA, RSI and breakout |
| OSS-05 | B — vectorbt | VectorBT optional adapter, pinned dependency in research-only extra or isolated container, bounded batch and recorded resource consumption | OSS-04; R1 licence sign-off | Same-case result consistency, no capital authority, speed+RSS baseline |
| OSS-06 | B — vectorbt eval | Parameter sweep on 10–20 representative strategy templates from the 232; robust walk-forward/purged CV with holdouts | OSS-05 | After-cost metrics, no leakage, memory/runtime cap, statistical stability |
| OSS-07 | C — independent validator | [Backtesting.py](http://Backtesting.py) isolated oracle benchmark and legal decision | OSS-04; R2 AGPL review | Stop/target/gap discrepancy report with independent baseline |
| OSS-08 | C — engineering patterns | Original, independent future-mutation/lookahead/recursive warm-up guard inspired by Freqtrade; do NOT vendor GPL code | OSS-03; R5 architectural study | In-house code and tests pass; no Freqtrade runtime |
| OSS-09 | D — advanced fill gap analysis | Compare existing fill simulator to real paper/live fill evidence where available; qualify whether Nautilus needed | OSS-04 and after enough evidence | Documented unmodelled error/cost opportunity and threshold |
| OSS-10 | D — Nautilus pilot | Separate Rust/Python/offline replay sandbox, Indian instrument/market event mapping and licensed feed data | OSS-09 justified; R3 license and ABI gate | Deterministic replay; no broker permissions; cost benefit proof |
| OSS-11 | D — LEAN reference | Compare LEAN India sample, brokerage fee/security and corporate-adjustment semantics; optionally standalone benchmark only | OSS-04; R4 license/data review | Reference report and adopt/reject; no .NET production dependency |
| OSS-12 | E — Java integration | Store signed normalized engine result in existing ExperimentJobOrchestrationService/BacktestComparisonService, preserve immutable fingerprints and disabled live flags | OSS-05/07 eligible adapters | Real Postgres integration, lease/retry/duplicate and tamper tests |
| OSS-13 | E — Flutter results | Wire Research, Experiment Results, Strategy Detail, Signal Scorecard, Shadow Arena, Confidence Lab, Culprit Catcher to authorized read models | OSS-12 | Real vs synthetic separation, source/cost evidence, golden widget and APK test |
| OSS-14 | F — cost/operational release | Optional worker job scheduling, metric dashboards, CPU/RSS budget, provider entitlement review, rollback drills and documented engine choices | OSS-12/13 | Cost compared to baseline; disabled fallback and runtime health validated |

### Detailed execution checklist for **every** PR

1. Fetch latest `dev`, CI state, Flyway migration head, exact source library revision. Compare with previous phase and update dependency graph.
2. Add one narrowly scoped set of files and tests; explicit acceptance criteria, no unrelated UX or production broker changes.
3. Define exact canonical inputs, source/available-at timestamps, output, cost model, feature flags, owner boundary and forbidden side effects.
4. Add fail-closed tests: market open/closed, stale/duplicate/future tick, missing corporate adjustment, instrument token mismatch, corrupted/outdated source.
5. Implement deterministic code with no untrusted dynamic imports/commands; resource caps, timeouts, no network for offline runs.
6. Compare against existing Java results with explicit differences; store frozen input hashes, metrics, environment/package digests and reproducibility manifest.
7. Run Python unit/integration, Java/Maven/Postgres, Flutter widget/golden for affected screen, security/dependency scans and appropriate GitHub workflow.
8. Measure wall latency, CPU seconds, peak RSS, storage/artefact bytes and total run cost. Compare with baseline and specify intended workload/retention.
9. Record PASS/FAIL/BLOCKED with links to actual PR, commit, CI, benchmark dataset and logs. No claim of production data correctness based on synthetic tests only.
10. Merge to dev only after gates; verify dev head contains commit, then start **next** PR from current dev. On failure roll back flag/package rather than compromising Java risk policy.

### Suggested future cost/value acceptance policy (not commitments)

- Reject an engine with no demonstrable incremental correctness, out-of-sample utility or experiment-throughput benefit.
- For vectorized sweeps, require benchmark on *the same data and machine* showing wall-time and memory relative to existing code, with a configurable engineering target (e.g., at least 2× faster for meaningful fixed workloads or materially broader reproducible exploration without budget overrun). A speed target is NOT a measured result.
- For fill-replay, require meaningful improvement in order-timing/slippage fidelity without weakening conservative execution assumptions; if only OHLCV is available, high-fidelity tick/order-book backtests may not be possible.
- Keep reusable research plans as precomputed batch results; never run per-screen expensive backtests or LLM inference.
- ARM64 wheels/Numba/Rust/dotnet compatibility must be demonstrated before selecting deployed architecture. Avoid permanently running all five frameworks.
- Training on user fills is separate from the independent global market prediction outcome process; do not leak account data into research.

## 9. Coverage across all 42 existing Mintick Mind screens

**Primary direct consumers:** Strategy Universe, Strategy Detail, Research & Backtesting, Experiment Results, Model Laboratory, Model Deep Dive, Performance & Learning, Decision Simulator, Shadow Trading Arena, Signal Scorecard, Confidence Calibration, Decision Anatomy, Evidence Ledger, Culprit Catcher.

**Indirect consumers through the same typed normalized research views:** Mind Overview, Decision Intelligence, Agent Council & Debate, Regime Weight Studio, Trading Rooms, Room Detail, Decision Explorer, Dependency Map, Chart Intelligence, Market Regimes, Daily Market Verdict, Events & Knowledge Graph, Multibagger Discovery, Investment Thesis, Portfolio Digital Twin, Risk Center, Decision Journal and Ask Mintick Mind (read-only explanations only).

**No direct upstream framework dependency:** MintTick Home, FII/DII Flow (official reports), Futures & Options, Option Chain, Strategy Payoff Builder (they require actual derivatives data/contract rules instead), Engine Control (only job health/authorization), Visual Mockup Gallery, Clickable Design Mode, Screen & Strategy Coverage, Preferences.

**Important:** this annex does not replace the existing per-screen contract table. It adds external engine result contract dependencies for affected screens. All 42 routes still need their independently verified source and UI readiness gates.

## 10. Explicit stop conditions and unresolved decisions

- **STOP:** unreviewed software/data rights, arbitrary user-supplied strategy code, missing historical source provenance, point-in-time leakage, unexplained cross-engine disagreement, resource budget breach, real-account data reaching an external benchmarking service, an adapter attempting broker calls, any bypass of emergency stop, wrong F&O/cash short product eligibility, or missing rollback plan.
- **REVIEW:** Should VectorBT be used in research-only personal environment under Commons Clause today? What licence or replacement is necessary before commercial multi-user use? AGPL/GPL can impose obligations depending on linking, distribution and network operations; determine legal posture for each use.
- **MEASURE:** current canonical Java backtest correctness, p95 experiment duration and throughput, cost per 10/100/1000 strategy candidates, RAM/CPU on target ARM64, precision of market/source timestamp joins, live feed entitlement and archive retention.
- **PREFER:** lightweight custom in-house research and existing validated libraries when equal in correctness and cheaper operationally. Adoption is based on measured return on complexity, never social-media popularity.

## 11. Reversible completion record

At the end of OSS-14, record for each of R1–R5: `adopted`, `optional`, `reference only`, or `rejected`; source revision, licence, packaging, champion use case, benchmark evidence, support/upgrade plan and total-cost effect. Update the master architecture decision and screen/model readiness matrix. No third-party package has been installed merely by approving this Notion plan.

**Baseline source pointers:** [MintTick main repository](https://github.com/operationReact/AiTradingSystem), [PR #656](https://github.com/operationReact/AiTradingSystem/pull/656), [VectorBT licence](https://github.com/polakowo/vectorbt/blob/master/LICENSE.md), [Backtesting.py licence](https://github.com/kernc/backtesting.py/blob/master/LICENSE.md), [NautilusTrader licence](https://github.com/nautechsystems/nautilus_trader/blob/develop/LICENSE), [LEAN licence](https://github.com/QuantConnect/Lean/blob/master/LICENSE), [Freqtrade licence](https://github.com/freqtrade/freqtrade/blob/develop/LICENSE).