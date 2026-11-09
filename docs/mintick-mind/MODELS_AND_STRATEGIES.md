# Mintick Mind — 18 Model and 232 Strategy Implementation Contracts

**Audit source:** Flutter `MindFixtures` on `dev`. Its displayed metrics/status are mock, not verified training, profitability or active execution. No strategy is automatically eligible just because a card exists.

## Shared model protocol

Every model must declare `model_id`, `version`, model family/role, licence/commercial rights, training/validation/test periods, point-in-time dataset fingerprint, feature schema/cutoff, target and horizon, evaluation splits, calibration version, artefact SHA-256/signature, dependencies, training compute budget, inference runtime/timeout, promotion history and tested rollback. Java sends a frozen feature payload, not untrusted model feature values from the phone. Python may return advisory scores/evidence but no order or risk override.

Shared output includes `case_key`, `instrument_id`, `as_of`, `available_at`, `model_version`, `feature_fingerprint`, `score`, `calibrated_probability` (nullable), `uncertainty`, `abstention_reason`, `latency_ms`, `source_quality`, `response_hash`. A numerical score is NOT a calibrated probability unless evaluated. Metrics are computed only on labelled held-out outcomes. Cost and latency count toward champion promotion.

Lifecycle: DISCOVERED → LICENSE_REVIEWED → RESEARCH_ENABLED → BACKTEST_ELIGIBLE → BACKTEST_PASSED → PAPER_VALIDATED → SHADOW_VALIDATED → GOVERNANCE_CANDIDATE → CHAMPION. Failures move to DISABLED/QUARANTINED/ROLLED_BACK. No automatic promotion to LIVE.

## Exact 18 model registry contracts

### M01 LightGBM

- UI family/role/status: **Structured Alpha / Champion / Research**; displayed illustrative metric `AUC 0.81` must be removed or marked synthetic until verified.
- **Input:** Tabular OHLCV, cross-sectional relative strength, sector, regime, liquidity, calendar, point-in-time labels.
- **Output:** Calibrated probability/rank for specified instrument/horizon with full feature/source/model provenance.
- **Inference cadence:** Daily/selected closed bars; **cost and latency:** CPU batch + cached result.
- **Acceptance:** Purged walk-forward; after-cost return@K, Brier, calibration, baseline gain; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M02 CatBoost

- UI family/role/status: **Structured Alpha / Challenger / Research**; displayed illustrative metric `AUC 0.79` must be removed or marked synthetic until verified.
- **Input:** Same shared tabular inputs plus category encodings and missingness.
- **Output:** Independent calibrated alpha benchmark with full feature/source/model provenance.
- **Inference cadence:** Daily/selected bars; **cost and latency:** CPU batches, frozen category schema.
- **Acceptance:** Disjoint OOS and incremental value versus LightGBM; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M03 XGBoost

- UI family/role/status: **Structured Alpha / Benchmark / Research**; displayed illustrative metric `AUC 0.77` must be removed or marked synthetic until verified.
- **Input:** Exactly aligned features/labels as other tree competitors.
- **Output:** Redundancy and feature-ablation benchmark with full feature/source/model provenance.
- **Inference cadence:** Offline/nightly research; **cost and latency:** CPU research only if no incremental gain.
- **Acceptance:** Cross-validated model and ensemble diversity, net value; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M04 PyTorch MLP

- UI family/role/status: **Neural Baseline / Benchmark / Research**; displayed illustrative metric `AUC 0.73` must be removed or marked synthetic until verified.
- **Input:** Past-only standardized tabular features; frozen imputer/scaler.
- **Output:** Neural baseline score with full feature/source/model provenance.
- **Inference cadence:** Offline/selected case; **cost and latency:** CPU; no permanent GPU.
- **Acceptance:** Reproducible preprocessing, outperform trivial base; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M05 Original Patch Transformer

- UI family/role/status: **Time-Series / Challenger / Research**; displayed illustrative metric `MAE 0.031` must be removed or marked synthetic until verified.
- **Input:** Past-only patch windows, session masks, adjusted candles.
- **Output:** Multi-horizon return/volatility estimate with full feature/source/model provenance.
- **Inference cadence:** Selected bounded research jobs; **cost and latency:** CPU/GPU optional, time budgets.
- **Acceptance:** Chronological OOS vs naive and AR baselines; no future tokens; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M06 TFT-inspired Model

- UI family/role/status: **Time-Series / Challenger / Research**; displayed illustrative metric `MAE 0.034` must be removed or marked synthetic until verified.
- **Input:** Historical sequence, market calendar known-future covariates, static tags.
- **Output:** Multi-horizon probabilistic forecast with full feature/source/model provenance.
- **Inference cadence:** Batch research; **cost and latency:** Optional worker; memory ceiling.
- **Acceptance:** Known-future covariate audit, interval coverage; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M07 TimeMixer-style Model

- UI family/role/status: **Time-Series / Challenger / Research**; displayed illustrative metric `MAE 0.032` must be removed or marked synthetic until verified.
- **Input:** 1m/5m/daily aligned multiresolution observations.
- **Output:** Temporal forecast with uncertainty with full feature/source/model provenance.
- **Inference cadence:** Off-peak bounded jobs; **cost and latency:** Shared time-series cache, optional worker.
- **Acceptance:** Compare multiscale incremental benefit vs simple models; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M08 Chronos-2 Adapter

- UI family/role/status: **Time-Series / Candidate / Adapter**; displayed illustrative metric `Not verified` must be removed or marked synthetic until verified.
- **Input:** Licensed verified checkpoint, tokenizer, causal historical window.
- **Output:** Pretrained time-series challenger forecast with full feature/source/model provenance.
- **Inference cadence:** DISABLED pending checkpoint/license; **cost and latency:** No runtime untrusted download; hash-pinned.
- **Acceptance:** Legal clearance, output validation, India OOS tests; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M09 Regime HMM

- UI family/role/status: **Regime / Advisory / Research**; displayed illustrative metric `F1 0.76` must be removed or marked synthetic until verified.
- **Input:** Index/sector returns, breadth, VIX, realized vol, macro.
- **Output:** Regime posterior probabilities + UNKNOWN with full feature/source/model provenance.
- **Inference cadence:** On completed relevant market bars; **cost and latency:** Cheap CPU, cached per universe/horizon.
- **Acceptance:** Label instability, calibrated regime confidence, transitions; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M10 Isolation Forest

- UI family/role/status: **Anomaly / Advisory / Research**; displayed illustrative metric `F1 0.68` must be removed or marked synthetic until verified.
- **Input:** Price/volume anomalies, stale feed, spread, feature outliers.
- **Output:** Anomaly and risk abstention evidence with full feature/source/model provenance.
- **Inference cadence:** Event/closed-bar selective; **cost and latency:** Cheap CPU vectorized.
- **Acceptance:** False-positive load, drift and actionable quality; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M11 Gemini Context

- UI family/role/status: **News NLP / Advisory / Integration**; displayed illustrative metric `Not verified` must be removed or marked synthetic until verified.
- **Input:** Licensed news/filing text, original source, publication/ingest timestamps, prompt version.
- **Output:** Advisory structured event/context evidence with citations with full feature/source/model provenance.
- **Inference cadence:** Novel document only; **cost and latency:** LLM request dedupe, token caps, source-first rule.
- **Acceptance:** Verifiable facts and prompt-injection tests; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M12 Financial NLP Challenger

- UI family/role/status: **News NLP / Candidate / License review**; displayed illustrative metric `Not verified` must be removed or marked synthetic until verified.
- **Input:** Rights-cleared labelled India financial-news corpus and disclosures.
- **Output:** Event and sentiment classification score with full feature/source/model provenance.
- **Inference cadence:** Batched new documents; **cost and latency:** Disabled pending license and quality.
- **Acceptance:** Indian corporate benchmark, ablation and uncertainty; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M13 Static Ensemble

- UI family/role/status: **Decision Fusion / Baseline / Research**; displayed illustrative metric `Sharpe 1.19` must be removed or marked synthetic until verified.
- **Input:** Aligned model votes, calibrators, fixed weights and versions.
- **Output:** Research fusion score and disagreement with full feature/source/model provenance.
- **Inference cadence:** After participating primary models; **cost and latency:** Deterministic CPU aggregation.
- **Acceptance:** Static champion benchmark, correlated-evidence caps; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M14 Adaptive Ensemble

- UI family/role/status: **Decision Fusion / Challenger / Research**; displayed illustrative metric `Sharpe 1.23` must be removed or marked synthetic until verified.
- **Input:** Matched model votes, verified regime, bounded rolling OOS metrics.
- **Output:** Regime-conditional research fusion score with full feature/source/model provenance.
- **Inference cadence:** After verified regime and votes; **cost and latency:** Preapproved config, deterministic CPU.
- **Acceptance:** Beat static ensemble after cost, rollback; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M15 Meta Decision Learner

- UI family/role/status: **Decision Fusion / Candidate / Planned**; displayed illustrative metric `Not verified` must be removed or marked synthetic until verified.
- **Input:** Strictly out-of-fold model predictions, costs, regimes, matured labels.
- **Output:** Meta trade/WAIT/ABSTAIN signal, advisory with full feature/source/model provenance.
- **Inference cadence:** After training label maturity; **cost and latency:** Research CPU only, avoid leakage.
- **Acceptance:** Purged stacked evaluation, improved net utility and abstention; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M16 Decision-1 Research

- UI family/role/status: **Decision Fusion / Candidate / Planned**; displayed illustrative metric `Not verified` must be removed or marked synthetic until verified.
- **Input:** Frozen source/evidence, model outputs, conflicts, policy.
- **Output:** Experimental structured decision arbitration with full feature/source/model provenance.
- **Inference cadence:** Explicitly selected research cases; **cost and latency:** Bounded worker/LLM; no capital authority.
- **Acceptance:** Truthful evidence, deterministic comparator, no model-generated execution; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M17 Quantile Forecaster

- UI family/role/status: **Time-Series / Candidate / Research**; displayed illustrative metric `Coverage 0.78` must be removed or marked synthetic until verified.
- **Input:** Past-only returns, vol, calendar, censored forward labels.
- **Output:** Quantile intervals with coverage with full feature/source/model provenance.
- **Inference cadence:** Daily/selected research; **cost and latency:** CPU batches; shared cached input.
- **Acceptance:** Quantile monotonicity, empirical coverage, tail calibration; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

### M18 PPO / FinRL-X

- UI family/role/status: **Reinforcement Learning / Candidate / Advanced R&D**; displayed illustrative metric `Not verified` must be removed or marked synthetic until verified.
- **Input:** Historical simulated state/action/reward, realistic cost and risk.
- **Output:** Offline action policy for research only with full feature/source/model provenance.
- **Inference cadence:** Manual bounded research jobs; **cost and latency:** No always-on RL worker/GPU.
- **Acceptance:** Reject simulator exploits; delayed OOS and shadow; no live authority; source entitlement; schema parity; no lookahead; held-out cohorts; latency/memory budget; failure->ABSTAIN; no broker authority.
- **Task-level implementation:** inventory existing Java/Python artefact → define versioned feature/cutoff contract → create reproducible training manifest + labels → build/verify artefact → run causal backtests against deterministic baseline → calibrate → add signed model receipt in existing governance ledger → run contract/failure/security tests → paper/shadow comparison → explicit governance review.

## Strategy lifecycle and per-template engineering checklist

TEMPLATE → DEPENDENCIES_MAPPED → RULES_IMPLEMENTED → BACKTEST_ELIGIBLE → CAUSAL_BACKTEST_PASSED → PAPER_ELIGIBLE → PAPER_VALIDATED → SHADOW_ELIGIBLE → SHADOW_VALIDATED → GOVERNANCE_REVIEW. LIVE authorisation always a **separate** decision gated by Java risk, account broker permissions and operator approval.

Every template must gain: immutable rule/version, allowed markets/horizons, trading calendar and contract requirements, required source feature IDs/versions, canonical instrument universe, exact entry/exit/abstention pseudocode, parameters with ranges, minimum history/samples, order type/product restrictions, fee/tax/spread/slippage assumptions, exposure and stop policy, missing-data behavior, unit tests, walk-forward baseline comparisons, paper/shadow evidence and rollback. Never infer actual readiness from the fixture `status` field.

## All 20 families and every named strategy (232 exact source IDs)

### Trend Following (12 strategies)

- Shared minimum input contract: **D03 D04 D11 / moving-average trend, ATR, benchmark and multi-timeframe alignment**.
- Proto horizons: Intraday / Swing / Delivery; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-001 | SMA Cross | EMA/SMA | DATA + CAUSAL VALIDATION |
| STR-002 | EMA Cross | EMA/SMA | DATA + CAUSAL VALIDATION |
| STR-003 | Supertrend | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-004 | ADX Trend Strength | ADX | DATA + CAUSAL VALIDATION |
| STR-005 | MACD Trend | MACD | DATA + CAUSAL VALIDATION |
| STR-006 | Ichimoku Cloud | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-007 | Parabolic SAR | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-008 | Donchian Trend | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-009 | Multi-Timeframe Trend Alignment | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-010 | 20 EMA Pullback | EMA/SMA | DATA + CAUSAL VALIDATION |
| STR-011 | Anchored VWAP Trend | VWAP | DATA + CAUSAL VALIDATION |
| STR-012 | Higher High Higher Low | validated family-default features | DATA + CAUSAL VALIDATION |

### Momentum (11 strategies)

- Shared minimum input contract: **D03 D04 D11 / RSI, ROC, relative strength, sector rank**.
- Proto horizons: Intraday / Swing / Delivery; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-013 | RSI Momentum | RSI | DATA + CAUSAL VALIDATION |
| STR-014 | Relative Strength Rotation | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-015 | ROC Acceleration | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-016 | MACD Signal Crossover | MACD | DATA + CAUSAL VALIDATION |
| STR-017 | 52-Week High Momentum | 52-week adjusted highs | DATA + CAUSAL VALIDATION |
| STR-018 | Stochastic Momentum | stochastic oscillator | DATA + CAUSAL VALIDATION |
| STR-019 | Momentum Divergence | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-020 | Momentum Ignition | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-021 | Trend Continuation | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-022 | TSI Momentum | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-023 | Cross-Sectional Momentum | validated family-default features | DATA + CAUSAL VALIDATION |

### Breakout (12 strategies)

- Shared minimum input contract: **D02 D03 D11 / prior high/low, closed-bar volume, spread, gap handling**.
- Proto horizons: Intraday / Swing / Delivery; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-024 | Opening Range Breakout | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-025 | Donchian Channel Breakout | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-026 | 52-Week High Breakout | 52-week adjusted highs | DATA + CAUSAL VALIDATION |
| STR-027 | Volatility Breakout | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-028 | Consolidation Breakout | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-029 | Darvas Box | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-030 | Flag Breakout | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-031 | Triangle Breakout | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-032 | Cup and Handle | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-033 | Pivot Breakout | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-034 | Range Expansion Breakout | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-035 | Multi-Day High Breakout | validated family-default features | DATA + CAUSAL VALIDATION |

### Mean Reversion (11 strategies)

- Shared minimum input contract: **D02 D03 D11 / VWAP, Z-score, bands, ATR and liquidity**.
- Proto horizons: Intraday / Swing; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-036 | RSI Oversold Rebound | RSI | DATA + CAUSAL VALIDATION |
| STR-037 | RSI Overbought Fade | RSI | DATA + CAUSAL VALIDATION |
| STR-038 | Bollinger Band Reversion | RSI, Bollinger bands | DATA + CAUSAL VALIDATION |
| STR-039 | VWAP Reclaim | VWAP | DATA + CAUSAL VALIDATION |
| STR-040 | Z-Score Reversion | RSI | DATA + CAUSAL VALIDATION |
| STR-041 | Keltner Mean Reversion | RSI, Keltner channels | DATA + CAUSAL VALIDATION |
| STR-042 | Gap Fill Reversion | RSI | DATA + CAUSAL VALIDATION |
| STR-043 | Overnight Reversal | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-044 | Short-Term Oversold | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-045 | Distance-from-MA Reversion | RSI, EMA/SMA | DATA + CAUSAL VALIDATION |
| STR-046 | Reversion after Panic | RSI | DATA + CAUSAL VALIDATION |

### Price Action & Candlesticks (16 strategies)

- Shared minimum input contract: **D03 D11 / causal candle patterns, swing structure, support/resistance**.
- Proto horizons: Intraday / Swing / Delivery; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-047 | Support Resistance Bounce | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-048 | Support Resistance Break | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-049 | Inside Bar | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-050 | Engulfing Pattern | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-051 | Hammer Reversal | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-052 | Shooting Star | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-053 | Morning Star | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-054 | Evening Star | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-055 | Doji Context | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-056 | Double Top Bottom | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-057 | Head and Shoulders | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-058 | Ascending Triangle | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-059 | Descending Triangle | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-060 | Market Structure Break | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-061 | Fair Value Gap Research | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-062 | Fibonacci Pullback | validated family-default features | DATA + CAUSAL VALIDATION |

### Volume & Liquidity (11 strategies)

- Shared minimum input contract: **D02 D03 D11 / turnover, delivery volume, VWAP, optionally licensed depth**.
- Proto horizons: Intraday / Swing; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-063 | Volume Spike Confirmation | spread, liquidity and/or book availability | DATA + CAUSAL VALIDATION |
| STR-064 | On-Balance Volume Trend | spread, liquidity and/or book availability | DATA + CAUSAL VALIDATION |
| STR-065 | Accumulation Distribution | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-066 | Chaikin Money Flow | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-067 | Money Flow Index | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-068 | Volume Profile Reversion | RSI, spread, liquidity and/or book availability | DATA + CAUSAL VALIDATION |
| STR-069 | VWAP Band Breakout | VWAP | DATA + CAUSAL VALIDATION |
| STR-070 | Anchored VWAP Reclaim | VWAP | DATA + CAUSAL VALIDATION |
| STR-071 | Unusual Volume Detection | spread, liquidity and/or book availability | DATA + CAUSAL VALIDATION |
| STR-072 | Liquidity Sweep Research | spread, liquidity and/or book availability | DATA + CAUSAL VALIDATION |
| STR-073 | Delivery Volume Breakout | spread, liquidity and/or book availability | DATA + CAUSAL VALIDATION |

### Volatility (10 strategies)

- Shared minimum input contract: **D03 D04 D11 / realized volatility, ATR, VIX, compression**.
- Proto horizons: Intraday / Swing; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-074 | ATR Breakout | ATR | DATA + CAUSAL VALIDATION |
| STR-075 | ATR Trailing Stop | ATR, account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-076 | Bollinger Squeeze | Bollinger bands, volatility/regime | DATA + CAUSAL VALIDATION |
| STR-077 | Keltner Squeeze | Keltner channels, volatility/regime | DATA + CAUSAL VALIDATION |
| STR-078 | Volatility Contraction Pattern | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-079 | Historical Volatility Regime | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-080 | Volatility Expansion | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-081 | Range Compression | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-082 | Volatility Targeting | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-083 | Intraday Range Forecast | volatility/regime | DATA + CAUSAL VALIDATION |

### Statistical Arbitrage (9 strategies)

- Shared minimum input contract: **D03 D04 D11 / aligned cross-asset history, correlations, covariance and costs**.
- Proto horizons: Swing / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-084 | Pairs Mean Reversion | RSI, aligned pair/basket history | DATA + CAUSAL VALIDATION |
| STR-085 | Cointegration Spread | aligned pair/basket history, contract/OI/basis | DATA + CAUSAL VALIDATION |
| STR-086 | Sector Relative Value | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-087 | Beta-Neutral Spread | aligned pair/basket history | DATA + CAUSAL VALIDATION |
| STR-088 | Correlation Dislocation | aligned pair/basket history | DATA + CAUSAL VALIDATION |
| STR-089 | Cross-Sectional Z-Score | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-090 | Residual Momentum | aligned pair/basket history | DATA + CAUSAL VALIDATION |
| STR-091 | Statistical Basket Rebalance | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-092 | Factor Neutral Reversion | RSI | DATA + CAUSAL VALIDATION |

### Event Driven (12 strategies)

- Shared minimum input contract: **D05 D06 D09 D11 / primary filings, results, announcements, public timestamps**.
- Proto horizons: Intraday / Swing / Delivery; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-093 | Earnings Surprise | timestamped corporate facts | DATA + CAUSAL VALIDATION |
| STR-094 | Post Earnings Drift | timestamped corporate facts | DATA + CAUSAL VALIDATION |
| STR-095 | Results Gap Continuation | timestamped corporate facts | DATA + CAUSAL VALIDATION |
| STR-096 | Guidance Revision | timestamped corporate facts | DATA + CAUSAL VALIDATION |
| STR-097 | Corporate Action Adjustment | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-098 | Buyback Announcement | timestamped corporate facts | DATA + CAUSAL VALIDATION |
| STR-099 | Dividend Event | timestamped corporate facts | DATA + CAUSAL VALIDATION |
| STR-100 | Index Rebalance | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-101 | Sector Policy Event | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-102 | Management Change Event | timestamped corporate facts | DATA + CAUSAL VALIDATION |
| STR-103 | Order Win Inflection | timestamped corporate facts, account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-104 | Pledge Disclosure Watch | timestamped corporate facts | DATA + CAUSAL VALIDATION |

### Fundamental & Multibagger (17 strategies)

- Shared minimum input contract: **D05 D06 D11 / standardized financials, valuation, governance, as-of universe**.
- Proto horizons: Delivery / Long-Term / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-105 | Quality Compounders | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-106 | Earnings Acceleration | timestamped corporate facts | DATA + CAUSAL VALIDATION |
| STR-107 | ROCE Improvement | financial statements | DATA + CAUSAL VALIDATION |
| STR-108 | Free Cash Flow Growth | financial statements | DATA + CAUSAL VALIDATION |
| STR-109 | Debt Deleveraging | financial statements | DATA + CAUSAL VALIDATION |
| STR-110 | GARP Growth at Reasonable Price | financial statements | DATA + CAUSAL VALIDATION |
| STR-111 | Undervalued Cash Flows | financial statements | DATA + CAUSAL VALIDATION |
| STR-112 | Operating Leverage Inflection | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-113 | Capacity Expansion Inflection | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-114 | Margin Expansion | financial statements | DATA + CAUSAL VALIDATION |
| STR-115 | Smallcap Business Inflection | EMA/SMA | DATA + CAUSAL VALIDATION |
| STR-116 | Promoter Governance Screening | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-117 | Capital Allocation Quality | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-118 | Industry Consolidation | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-119 | Fundamental Momentum | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-120 | Financial Turnaround | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-121 | High ROIC Reinvestment | contract/OI/basis, financial statements | DATA + CAUSAL VALIDATION |

### Factor & Portfolio (13 strategies)

- Shared minimum input contract: **D03 D04 D05 D14 / factor exposures, corporate actions, correlation and cost**.
- Proto horizons: Delivery / Long-Term / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-122 | Quality Factor | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-123 | Value Factor | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-124 | Low Volatility Factor | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-125 | Momentum Factor | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-126 | Size Factor | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-127 | Multi-Factor Rank | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-128 | Sector Rotation | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-129 | Equal Weight Rebalance | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-130 | Risk Parity | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-131 | Volatility Target Portfolio | volatility/regime, account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-132 | Minimum Variance | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-133 | Cash Buffer Rotation | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-134 | Core Satellite Allocation | validated family-default features | DATA + CAUSAL VALIDATION |

### Market Regime & Macro (11 strategies)

- Shared minimum input contract: **D03 D04 D08 D11 / breadth, sector, vol and macro event release times**.
- Proto horizons: Intraday / Swing / Delivery; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-135 | Trend Range Classifier | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-136 | High Volatility Regime | volatility/regime | DATA + CAUSAL VALIDATION |
| STR-137 | Low Liquidity Avoidance | contract/OI/basis, spread, liquidity and/or book availability | DATA + CAUSAL VALIDATION |
| STR-138 | Market Breadth Shift | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-139 | Sector Relative Strength | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-140 | Risk-On Risk-Off | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-141 | Global Market Spillover | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-142 | Rate Sensitivity | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-143 | Oil Price Sensitivity | contract/OI/basis | DATA + CAUSAL VALIDATION |
| STR-144 | FX Sensitivity | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-145 | Event Volatility Gate | volatility/regime | DATA + CAUSAL VALIDATION |

### Market Microstructure (9 strategies)

- Shared minimum input contract: **D02 D11 / bid-ask, spread, book state, sequence timestamps, quote staleness**.
- Proto horizons: Intraday / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-146 | Bid Ask Spread Filter | aligned pair/basket history, spread, liquidity and/or book availability | LICENSED DEPTH/QUOTE STREAM |
| STR-147 | Order Book Imbalance | spread, liquidity and/or book availability, account, risk and/or execution states | LICENSED DEPTH/QUOTE STREAM |
| STR-148 | Liquidity Depth Gate | spread, liquidity and/or book availability | LICENSED DEPTH/QUOTE STREAM |
| STR-149 | Microprice Signal Research | validated family-default features | LICENSED DEPTH/QUOTE STREAM |
| STR-150 | Trade Flow Imbalance | spread, liquidity and/or book availability | LICENSED DEPTH/QUOTE STREAM |
| STR-151 | Market Impact Estimator | validated family-default features | LICENSED DEPTH/QUOTE STREAM |
| STR-152 | Session Opening Liquidity | spread, liquidity and/or book availability | LICENSED DEPTH/QUOTE STREAM |
| STR-153 | Intraday Seasonality | validated family-default features | LICENSED DEPTH/QUOTE STREAM |
| STR-154 | Quote Staleness Gate | validated family-default features | LICENSED DEPTH/QUOTE STREAM |

### AI & Machine Learning (15 strategies)

- Shared minimum input contract: **D11 D12 D13 D15 / frozen features, trained model version, evaluation, labels**.
- Proto horizons: Research / Swing / Delivery; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-155 | Tabular Alpha Classification | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-156 | Time-Series Forecast | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-157 | Cross-Sectional ML Ranker | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-158 | Static Ensemble | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-159 | Adaptive Ensemble | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-160 | Market Regime HMM | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-161 | Isolation Forest Anomaly Gate | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-162 | Financial NLP Context | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-163 | Event Intelligence Ranker | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-164 | Calibrated Meta Decision | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-165 | Uncertainty Abstention | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-166 | Contextual Bandit Research | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-167 | Reinforcement Learning Research | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-168 | Feature Drift Gate | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-169 | Model Disagreement Gate | validated family-default features | DATA + CAUSAL VALIDATION |

### Futures (11 strategies)

- Shared minimum input contract: **D10 D11 / contract master, roll, basis, expiry, OI, margin**.
- Proto horizons: F&O / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-170 | Futures Basis Carry | contract/OI/basis | FUTURES CONTRACT + MARGIN |
| STR-171 | Futures Calendar Spread | aligned pair/basket history, contract/OI/basis | FUTURES CONTRACT + MARGIN |
| STR-172 | Rollover Signal | contract/OI/basis | FUTURES CONTRACT + MARGIN |
| STR-173 | Open Interest Trend | contract/OI/basis | FUTURES CONTRACT + MARGIN |
| STR-174 | Price OI Divergence | contract/OI/basis | FUTURES CONTRACT + MARGIN |
| STR-175 | Cash Futures Arbitrage Research | contract/OI/basis | FUTURES CONTRACT + MARGIN |
| STR-176 | Futures Momentum | contract/OI/basis | FUTURES CONTRACT + MARGIN |
| STR-177 | Futures Mean Reversion | RSI, contract/OI/basis | FUTURES CONTRACT + MARGIN |
| STR-178 | Futures Hedge Overlay | contract/OI/basis, Greeks/margin | FUTURES CONTRACT + MARGIN |
| STR-179 | Contract Liquidity Gate | spread, liquidity and/or book availability | FUTURES CONTRACT + MARGIN |
| STR-180 | Expiry Basis Reversion | RSI, contract/OI/basis | FUTURES CONTRACT + MARGIN |

### Options — Directional (11 strategies)

- Shared minimum input contract: **D10 D11 D14 / call/put quote, IV/Greeks, expiries, margin, risk**.
- Proto horizons: F&O / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-181 | Long Call | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-182 | Long Put | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-183 | Bull Call Debit Spread | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |
| STR-184 | Bear Put Debit Spread | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |
| STR-185 | Bull Put Credit Spread | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |
| STR-186 | Bear Call Credit Spread | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |
| STR-187 | Protective Put | Greeks/margin | OPTION QUOTES + BROKER/MARGIN |
| STR-188 | Covered Call | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-189 | Collar | Greeks/margin | OPTION QUOTES + BROKER/MARGIN |
| STR-190 | Call Ratio Spread Research | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |
| STR-191 | Put Ratio Spread Research | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |

### Options — Volatility (12 strategies)

- Shared minimum input contract: **D10 D11 D14 / IV/realized volatility, Greeks, skew, surface, costs**.
- Proto horizons: F&O / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-192 | Long Straddle | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-193 | Long Strangle | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-194 | Short Straddle Research | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-195 | Short Strangle Research | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-196 | Iron Condor | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-197 | Iron Butterfly | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-198 | Long Butterfly | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-199 | Calendar Spread | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |
| STR-200 | Diagonal Spread | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |
| STR-201 | IV Rank Mean Reversion | RSI | OPTION QUOTES + BROKER/MARGIN |
| STR-202 | Volatility Crush Research | volatility/regime | OPTION QUOTES + BROKER/MARGIN |
| STR-203 | Gamma Scalping Research | Greeks/margin | OPTION QUOTES + BROKER/MARGIN |

### Options — Hedging (9 strategies)

- Shared minimum input contract: **D10 D11 D14 / position risk, contract/Greeks, hedge costs and margin**.
- Proto horizons: F&O / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-204 | Delta Hedge | Greeks/margin | OPTION QUOTES + BROKER/MARGIN |
| STR-205 | Beta Hedge with Options | Greeks/margin | OPTION QUOTES + BROKER/MARGIN |
| STR-206 | Tail Risk Put Hedge | Greeks/margin | OPTION QUOTES + BROKER/MARGIN |
| STR-207 | Put Spread Collar | aligned pair/basket history, Greeks/margin | OPTION QUOTES + BROKER/MARGIN |
| STR-208 | Defined Risk Spread Roll | aligned pair/basket history | OPTION QUOTES + BROKER/MARGIN |
| STR-209 | Expiry Risk Neutralization | validated family-default features | OPTION QUOTES + BROKER/MARGIN |
| STR-210 | Portfolio Protective Overlay | Greeks/margin, account, risk and/or execution states | OPTION QUOTES + BROKER/MARGIN |
| STR-211 | Greeks Exposure Rebalance | Greeks/margin, account, risk and/or execution states | OPTION QUOTES + BROKER/MARGIN |
| STR-212 | Skew-Based Hedge | Greeks/margin | OPTION QUOTES + BROKER/MARGIN |

### Short Selling (8 strategies)

- Shared minimum input contract: **D02 D03 D14 / broker product/borrow eligibility, market rules, borrow/short risk**.
- Proto horizons: Intraday / Research; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-213 | Intraday Trend Short | validated family-default features | SHORT PRODUCT ELIGIBILITY |
| STR-214 | Bearish Breakdown | validated family-default features | SHORT PRODUCT ELIGIBILITY |
| STR-215 | Failed Breakout Short | validated family-default features | SHORT PRODUCT ELIGIBILITY |
| STR-216 | VWAP Rejection Short | VWAP | SHORT PRODUCT ELIGIBILITY |
| STR-217 | Bearish Momentum Short | validated family-default features | SHORT PRODUCT ELIGIBILITY |
| STR-218 | Gap Down Continuation | validated family-default features | SHORT PRODUCT ELIGIBILITY |
| STR-219 | Overbought Short Reversion | RSI | SHORT PRODUCT ELIGIBILITY |
| STR-220 | Borrow-Eligible Positional Short Research | validated family-default features | SHORT PRODUCT ELIGIBILITY |

### Execution & Risk (12 strategies)

- Shared minimum input contract: **D02 D11 D14 D15 / funds, spreads, order receipts, fees, drawdown and reconciliation**.
- Proto horizons: Intraday / Swing / Delivery; engineering must validate actual product/market eligibility.
- Family-specific acceptance: bound entry/exit conditions to causal inputs; verify provider entitlements, source quality, session/expiry rules, liquidity, slippage, taxes/fees and rule baseline against actual trades. Derivatives and intraday shorting are research-only until validated.

| ID | Strategy template | Additional feature contract | Gate |
|---|---|---|---|
| STR-221 | Stop Loss Discipline | account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-222 | Trailing Exit | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-223 | Time Based Exit | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-224 | Exposure Cap | account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-225 | Portfolio Correlation Gate | aligned pair/basket history, account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-226 | Position Sizing Volatility | volatility/regime, account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-227 | Drawdown Kill Switch | account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-228 | Gap Risk Filter | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-229 | Pretrade Liquidity Check | spread, liquidity and/or book availability | DATA + CAUSAL VALIDATION |
| STR-230 | Dynamic Participation Limit | validated family-default features | DATA + CAUSAL VALIDATION |
| STR-231 | Order Slicing Research | account, risk and/or execution states | DATA + CAUSAL VALIDATION |
| STR-232 | Broker Reconciliation Gate | account, risk and/or execution states | DATA + CAUSAL VALIDATION |

## Cross-strategy arbitration and dependency rules

- Every signal references immutable `strategy_version`, `feature_snapshot_hash`, source times, costs, model/policy version, horizon and `decision_id`; eligibility is computed server-side and cached by data snapshot, not by Flutter screen.
- Rank candidates using an after-cost expected utility, account risk budget and portfolio overlap; never naively add 232 correlated votes. When source overlaps, cap family/exposure weights and record conflict.
- Missing indicator, incomplete candle, stale quote, unverified corporate action, absent option bid/ask, expired contract, insufficient margin or broker rejection ⇒ WAIT/ABSTAIN/BLOCK as appropriate; do not fill with synthetic values.
- Family representatives should be built and tested incrementally: EMA trend, volume-confirmed breakout, RSI mean reversion, simple point-in-time quality factor and defined-risk options payoff research. This list does not imply automatic execution eligibility.
- Per-template test matrix: feature-ready/not-ready; session open/closed/holiday; split/dividend; data revision; stale quote; suspension/circuit; zero turnover; high spread; transaction costs; cancelled/partial fill; no account; duplicate signal; future-label leakage; negative scenario.

## Inventory invariants

- Exact model entries: **18**, 18 distinct names. Exact template entries: **232**, IDs STR-001 through STR-232, across **20** families.
- A missing active algorithm implementation never suppresses the template in the catalogue; it is displayed as TEMPLATE/RESEARCH with disabled activation and dependency details.
- Release metrics on actual models/strategies must link experiment receipts and source fingerprint. The UI scores currently in the fixtures may not be cited as live performance.
