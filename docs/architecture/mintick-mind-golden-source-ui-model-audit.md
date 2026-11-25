# Mintick Mind Golden Source — exhaustive UI/model/strategy dependency audit

**Source of truth:** `dev` Flutter registry, `MintickMindScreens.all`, `MindFixtures.models` and `_catalog`, plus live Java/Python backend inspection on 2026-11-10.
**Machine-readable contract:** [mintick-mind-golden-source-requirements.v1.json](mintick-mind-golden-source-requirements.v1.json). Source-path drift is checked in `scripts/tests/test_mind_golden_source_requirements.py`.
**Audit outcome:** 43 registered routes, 18 *UI model catalogue records*, 20 strategy families, 232 strategy templates and 24 distinct data-source families.

> A UI fixture, mock AUC, mock Sharpe or a research module is **not** a verified, trained, licensed, running production model. This matrix specifies what needs to be available, not what is already populated.

## Critical assessment

1. **Raw cash OHLCV is necessary but insufficient.** Temporal security identity, adjustments, dated fundamentals, ownership, news, index/sector memberships, FII/DII flow, market depth, futures/options, macro data, broker costs, portfolio ledger, and model evidence all have separate authoritative origins.
2. The currently checked private account has a cash catalogue with 2,778 eligible equities but reported **zero 64-bar historical coverage**. The Angel One private one-session historical probe passed; full-vendor 20-year retention and redistribution rights remain unverified. Avoid claiming completed history.
3. **43 routed screens are mostly explicit mock workspace UI.** The Overview page has injected read-only readiness/strategy/prospective panels, and the agent Evidence screen can be server-backed; elsewhere prototype values should not be promoted to customer-visible facts.
4. **232 templates are a strategy taxonomy, not 232 running strategies.** Model family pipelines need independent data contracts, time horizons, backtest/fair-comparison models, and licensing gates.
5. **20 years targets daily equity history per actual listing date**, plus necessary source histories where reliable/licensed. It does not require 20 years of full L2 depth, option chains or every fundamental disclosure if no such source exists. Record `NO_DATA` and degrade readiness rather than invent values.

## All 43 registered screen dependencies

| Route / screen | Mandatory source families | Additional inputs | Current UI state |
|---|---|---|---|
| `app-home` — MintTick Home | `live_quotes`, `model_registry_receipts`, `trading_room_strategy` | `portfolio_ledger`, `account_preferences` | synthetic or demo until wired |
| `overview` — Mind Overview | `live_quotes`, `model_registry_receipts`, `trading_room_strategy`, `backtest_outcomes` | `portfolio_ledger`, `risk_execution_controls` | fixture plus optional authenticated evidence |
| `intelligence-hub` — Decision Intelligence | `model_registry_receipts`, `model_features`, `index_breadth_vix` | `risk_execution_controls` | synthetic or demo until wired |
| `agent-council` — Agent Council & Debate | `model_registry_receipts`, `model_features`, `risk_execution_controls` | `news_events` | synthetic or demo until wired |
| `agent-evidence` — Evidence Ledger | `model_registry_receipts`, `model_features`, `intraday_bars` | `news_events`, `operations_health` | fixture plus optional authenticated evidence |
| `regime-weights` — Regime Weight Studio | `index_breadth_vix`, `model_registry_receipts` | `macro_fx_rates` | synthetic or demo until wired |
| `signal-scorecard` — Signal Scorecard | `backtest_outcomes`, `model_registry_receipts`, `costs_liquidity` | `market_depth_delivery` | synthetic or demo until wired |
| `confidence-lab` — Confidence Calibration | `model_registry_receipts`, `backtest_outcomes` | `model_features` | synthetic or demo until wired |
| `shadow-arena` — Shadow Trading Arena | `backtest_outcomes`, `model_registry_receipts`, `costs_liquidity` | `portfolio_ledger` | synthetic or demo until wired |
| `institutional-flow` — FII / DII Flow | `flows_fii_dii`, `index_breadth_vix` | `market_depth_delivery` | synthetic or demo until wired |
| `daily-verdict` — Daily Market Verdict | `live_quotes`, `index_breadth_vix`, `model_registry_receipts` | `news_events`, `flows_fii_dii` | synthetic or demo until wired |
| `culprit-catcher` — Culprit Catcher | `model_registry_receipts`, `operations_health`, `backtest_outcomes` | `risk_execution_controls` | synthetic or demo until wired |
| `trading-rooms` — Trading Rooms | `trading_room_strategy`, `risk_execution_controls` | `portfolio_ledger`, `model_registry_receipts` | synthetic or demo until wired |
| `strategies` — Strategy Universe | `trading_room_strategy`, `backtest_outcomes`, `costs_liquidity` | `model_registry_receipts` | synthetic or demo until wired |
| `strategy-detail` — Strategy Detail | `trading_room_strategy`, `backtest_outcomes`, `model_features` | `risk_execution_controls` | synthetic or demo until wired |
| `charts` — Chart Intelligence | `daily_bars`, `intraday_bars`, `corporate_actions`, `live_quotes` | `news_events`, `model_features` | synthetic or demo until wired |
| `decisions` — Decision Explorer | `model_registry_receipts`, `model_features`, `risk_execution_controls` | `trading_room_strategy`, `portfolio_ledger` | synthetic or demo until wired |
| `decision-detail` — Decision Anatomy | `model_registry_receipts`, `model_features`, `risk_execution_controls` | `news_events`, `costs_liquidity` | synthetic or demo until wired |
| `decision-lab` — Decision Simulator | `model_features`, `model_registry_receipts`, `risk_execution_controls` | `backtest_outcomes` | synthetic or demo until wired |
| `dependencies` — Dependency Map | `model_registry_receipts`, `model_features`, `trading_room_strategy` | `backtest_outcomes` | synthetic or demo until wired |
| `models` — Model Laboratory | `model_registry_receipts`, `backtest_outcomes`, `operations_health` | `model_features` | synthetic or demo until wired |
| `model-detail` — Model Deep Dive | `model_registry_receipts`, `model_features`, `backtest_outcomes` | `operations_health` | synthetic or demo until wired |
| `research` — Research & Backtesting | `daily_bars`, `intraday_bars`, `backtest_outcomes`, `costs_liquidity` | `corporate_actions`, `index_breadth_vix` | synthetic or demo until wired |
| `research-engines` — Research Engines Lab | `model_registry_receipts`, `operations_health` | `backtest_outcomes` | synthetic or demo until wired |
| `experiment-detail` — Experiment Results | `backtest_outcomes`, `model_registry_receipts`, `costs_liquidity` | `security_identity` | synthetic or demo until wired |
| `performance` — Performance & Learning | `portfolio_ledger`, `backtest_outcomes`, `model_registry_receipts` | `costs_liquidity` | synthetic or demo until wired |
| `journal` — Decision Journal | `model_registry_receipts`, `risk_execution_controls`, `trading_room_strategy` | `portfolio_ledger` | synthetic or demo until wired |
| `multibagger` — Multibagger Discovery | `daily_bars`, `fundamental_filings`, `security_identity`, `corporate_actions` | `ownership_governance`, `index_breadth_vix` | synthetic or demo until wired |
| `thesis-detail` — Investment Thesis | `fundamental_filings`, `ownership_governance`, `corporate_actions`, `daily_bars` | `news_events`, `macro_fx_rates` | synthetic or demo until wired |
| `regimes` — Market Regimes | `index_breadth_vix`, `macro_fx_rates`, `model_registry_receipts` | `flows_fii_dii` | synthetic or demo until wired |
| `events` — Events & Knowledge Graph | `news_events`, `security_identity` | `fundamental_filings`, `corporate_actions` | synthetic or demo until wired |
| `portfolio` — Portfolio Digital Twin | `portfolio_ledger`, `risk_execution_controls`, `live_quotes` | `model_registry_receipts`, `costs_liquidity` | synthetic or demo until wired |
| `risk` — Risk Center | `risk_execution_controls`, `portfolio_ledger`, `live_quotes`, `costs_liquidity` | `market_depth_delivery` | synthetic or demo until wired |
| `derivatives` — Futures & Options | `derivatives_chain`, `derivatives_reference_margin`, `live_quotes` | `costs_liquidity` | synthetic or demo until wired |
| `options-chain` — Option Chain | `derivatives_chain`, `derivatives_reference_margin` | `live_quotes`, `market_depth_delivery` | synthetic or demo until wired |
| `option-builder` — Strategy Payoff Builder | `derivatives_chain`, `derivatives_reference_margin`, `costs_liquidity` | `portfolio_ledger` | synthetic or demo until wired |
| `controls` — Engine Control | `risk_execution_controls`, `model_registry_receipts`, `operations_health` | `account_preferences` | synthetic or demo until wired |
| `assistant` — Ask MintTick Mind | `model_registry_receipts`, `operations_health` | `trading_room_strategy`, `portfolio_ledger` | synthetic or demo until wired |
| `room-detail` — Trading Room Detail | `trading_room_strategy`, `risk_execution_controls`, `portfolio_ledger` | `model_registry_receipts` | synthetic or demo until wired |
| `design-gallery` — Visual Mockup Gallery | Visual design only | — | design only |
| `design-preview` — Clickable Design Mode | Visual design only | — | design only |
| `coverage` — Screen & Strategy Coverage | `operations_health`, `model_registry_receipts` | `trading_room_strategy` | synthetic or demo until wired |
| `settings` — Preferences | `account_preferences` | `risk_execution_controls` | synthetic or demo until wired |

## All 18 UI model entries and non-interchangeable input requirements

| Model | Required source families | Distinguishing feature/label contract | Verification state |
|---|---|---|---|
| LightGBM | `model_features`, `daily_bars`, `intraday_bars` | Tabular returns/range/volume, optional causally observed event/fundamental features; label after costs | trained synthetic research |
| CatBoost | `model_features`, `daily_bars`, `intraday_bars` | Tabular causal feature rows, categorical metadata only if valid as-of, calibrated forward-return label | trained synthetic research |
| XGBoost | `model_features`, `daily_bars`, `intraday_bars`, `costs_liquidity` | Fixed fair-comparison cohort, numeric market/finance features, costs and label | research benchmark |
| PyTorch MLP | `model_features`, `daily_bars`, `intraday_bars` | Normalized fixed-order causal feature tensor, training-only normalization and labels | research benchmark |
| Original Patch Transformer | `intraday_bars`, `market_calendar`, `costs_liquidity` | Closed-bar lagged return patches, session-local masks and future after-cost labels | bounded cpu research |
| TFT-inspired Model | `intraday_bars`, `market_calendar`, `costs_liquidity` | Ordered same-session returns, optional known-in-advance covariates, causal quantiles | bounded cpu research |
| TimeMixer-style Model | `intraday_bars`, `market_calendar`, `costs_liquidity` | Multi-scale observed return windows; no future context | bounded cpu research |
| Chronos-2 Adapter | `intraday_bars`, `market_calendar`, `costs_liquidity` | Univariate observed return sequence; licensed model weights and independent benchmarks required | licensing and checkpoint unverified |
| Regime HMM | `index_breadth_vix`, `market_calendar` | benchmark.NIFTY50.return_1d; breadth.NSE.net_breadth; breadth.NSE.return_dispersion; volatility.INDIA_VIX.close | research advisory |
| Isolation Forest | `index_breadth_vix`, `market_calendar` | Same four exact ordered India context features as Regime HMM; time-valid anomaly baseline | research advisory |
| Gemini Context | `news_events`, `security_identity` | Licensed and available-at news text/events, cited entity mappings, model version, timestamp and output confidence | integration not independently validated |
| Financial NLP Challenger | `news_events`, `security_identity`, `costs_liquidity` | Licensed original text, published/captured timestamps, issuer resolution, event labels after horizon | licensing review |
| Static Ensemble | `model_registry_receipts`, `model_features`, `costs_liquidity` | Aligned model snapshots, fixed weights, forecast uncertainty, approved decision-time fees | research only |
| Adaptive Ensemble | `model_registry_receipts`, `model_features`, `index_breadth_vix` | Time-valid regime, model quality multiplier, disagreements and source fingerprints | research only |
| Meta Decision Learner | `model_registry_receipts`, `model_features`, `backtest_outcomes`, `costs_liquidity` | Out-of-fold model scores, realized outcome labels available after horizon and cost estimates | planned no independent production worker |
| Decision-1 Research | `model_registry_receipts`, `model_features`, `backtest_outcomes`, `risk_execution_controls` | Frozen multi-model votes, missingness, risk veto reasons, independent labels for research only | planned no independent production worker |
| Quantile Forecaster | `intraday_bars`, `market_calendar`, `costs_liquidity` | Ordered returns, quantile targets, coverage/reliability and calibrated uncertainty | research candidate |
| PPO / FinRL-X | `daily_bars`, `intraday_bars`, `portfolio_ledger`, `costs_liquidity`, `risk_execution_controls` | Historical causal state/action/reward, fees, position/cash simulator, episodic halts and eligibility | advanced rd no execution authority |

## All 20 strategy families

| Family | Templates | Source families |
|---|---:|---|
| Trend Following | 12 | `daily_bars`, `intraday_bars`, `model_features` |
| Momentum | 11 | `daily_bars`, `intraday_bars`, `index_breadth_vix` |
| Breakout | 12 | `intraday_bars`, `market_calendar`, `market_depth_delivery` |
| Mean Reversion | 11 | `intraday_bars`, `model_features`, `costs_liquidity` |
| Price Action & Candlesticks | 16 | `daily_bars`, `intraday_bars`, `corporate_actions` |
| Volume & Liquidity | 11 | `intraday_bars`, `market_depth_delivery` |
| Volatility | 10 | `daily_bars`, `intraday_bars`, `index_breadth_vix` |
| Statistical Arbitrage | 9 | `daily_bars`, `security_identity`, `costs_liquidity` |
| Event Driven | 12 | `news_events`, `fundamental_filings`, `corporate_actions`, `daily_bars` |
| Fundamental & Multibagger | 17 | `fundamental_filings`, `ownership_governance`, `daily_bars`, `corporate_actions` |
| Factor & Portfolio | 13 | `daily_bars`, `fundamental_filings`, `security_identity`, `portfolio_ledger` |
| Market Regime & Macro | 11 | `index_breadth_vix`, `macro_fx_rates` |
| Market Microstructure | 9 | `market_depth_delivery`, `live_quotes`, `intraday_bars` |
| AI & Machine Learning | 15 | `model_features`, `model_registry_receipts`, `backtest_outcomes` |
| Futures | 11 | `derivatives_chain`, `derivatives_reference_margin`, `costs_liquidity` |
| Options — Directional | 11 | `derivatives_chain`, `derivatives_reference_margin`, `costs_liquidity` |
| Options — Volatility | 12 | `derivatives_chain`, `derivatives_reference_margin`, `index_breadth_vix` |
| Options — Hedging | 9 | `derivatives_chain`, `derivatives_reference_margin`, `portfolio_ledger` |
| Short Selling | 8 | `intraday_bars`, `market_depth_delivery`, `derivatives_reference_margin` |
| Execution & Risk | 12 | `risk_execution_controls`, `portfolio_ledger`, `costs_liquidity` |

## Source inventory and acquisition blockers

| Domain | Repository state (not runtime coverage) | Proposed minimum fields | History target |
|---|---|---|---|
| `security_identity` | partial repository | security_id, listing_id, isin_when_available, exchange, series, provider_token, symbol, valid_from | 20+y / listing lifecycle |
| `market_calendar` | partial repository | exchange, session_date, market_open, market_close, holiday, special_session, listing_suspension, available_at | 20+y / venue and session |
| `daily_bars` | provider sample only | listing_id, exchange, session_date, open, high, low, close, volume | 20-25y from each listing date where licensed/available |
| `intraday_bars` | provider sample only | listing_id, interval, bar_open_at, bar_close_at, open, high, low, close | 5m 1-2y; 1m 3-6m subject to rights |
| `live_quotes` | partial repository unverified live | listing_id, event_at, exchange_time, last_trade_price, day_ohlc, day_volume, best_bid, best_ask | current + bounded retained snapshots |
| `index_breadth_vix` | partial repository | index_id, constituents_as_of, weights_as_of, sector_map_as_of, index_ohlcv, advance_decline, return_dispersion, vix_close | 20+y daily where available; live intraday |
| `market_depth_delivery` | missing verified source | listing_id, bid_depth, ask_depth, bid_ask_spread, trade_imbalance, delivery_quantity, delivery_percent, orderbook_event_at | intraday where licensed |
| `corporate_actions` | missing complete history | listing_id, event_type, announcement_at, ex_date, record_date, factor, dividend_cash, rights_ratio | 20+y event and revision history |
| `fundamental_filings` | not populated | security_id, report_period, filing_published_at, consolidated_standalone, revenue, ebitda, net_income, eps | 20+y as available from filings |
| `ownership_governance` | not populated | promoter_holding, promoter_pledge, fii_dii_holdings, institutional_ownership, insider_transactions, auditor_flags, dilution, filing_at | quarterly/event archives where available |
| `news_events` | partial research contract unlicensed corpus | event_id, source_id, rights_id, headline_metadata, source_published_at, captured_at, first_available_at, linked_security_ids | licensed event availability history |
| `flows_fii_dii` | mock ui no verified pipeline | trade_date, category, gross_buy, gross_sell, net_flow, segment, report_release_at, revision | EOD/intraday from permitted exchange reports |
| `macro_fx_rates` | partial research contract | indicator_id, observation_date, published_at, value, revision_at, usd_inr, crude, gsec_yield | 20+y where available |
| `derivatives_chain` | mock ui no verified pipeline | contract_id, underlying_listing_id, symbol, expiry, strike, call_put, lot_size, option_ltp | contract lifecycle plus licensed short-window OI/IV |
| `derivatives_reference_margin` | mock ui no verified pipeline | contract_spec_version, underlying_id, expiry_cycle, tick_size, lot_size, margin_rule, span_exposure, borrow_eligible | effective-dated exchange/broker series |
| `costs_liquidity` | partial research contract | brokerage, stt, stamp_duty, exchange_fee, sebi_fee, gst, slippage_model, spread_model | effective-dated full horizon |
| `model_features` | research schema exists hot store missing | listing_id, feature_schema_version, decision_as_of, input_available_at, market_return_1, market_range_pct, market_volume_change_1, rsi | versioned immutable dataset snapshots |
| `model_registry_receipts` | backend existing partial | model_id, artifact_digest, features_schema_digest, training_dataset_digest, model_version, inference_at, prediction_probability, uncertainty | append-only model lifecycle |
| `backtest_outcomes` | backend existing partial | experiment_id, dataset_version, label_horizon, outcome_available_at, training_fold, holdout_fold, after_cost_return, drawdown | immutable experiment windows and labels |
| `trading_room_strategy` | backend existing partial | user_id, room_id, strategy_id, horizon, mode, strategy_params, effective_from, strategy_version | versioned user/room settings |
| `portfolio_ledger` | backend existing partial | account_id, portfolio_id, positions, cash, fills, corporate_action_application, mark_price_at, realized_pnl | private per-user ledger |
| `risk_execution_controls` | backend existing partial | user_id, mode, risk_limits, position_caps, exposure, sector_concentration, correlation, drawdown | audit/event sourced |
| `account_preferences` | backend existing partial | user_id, theme, notifications, watchlist, report_preferences, locale, updated_at | per-user current/preferences |
| `operations_health` | partial repository | provider, last_success_at, feed_lag, quote_coverage, dataset_coverage, model_drift, worker_p95, data_license_state | bounded operational monitoring |

## Mandatory golden-source architecture

- **S3 bronze:** contract-entitled, immutable originals + evidence of collection and permitted use.
- **S3 silver:** normalized, bitemporal bars, issuer/listing identities, historical index constituents, corporate actions, dated fundamentals, macro/derivatives data, source revisions and quality flags.
- **S3 gold:** multi-resolution, feature-family datasets keyed by `(listing_id, decision_as_of, schema_version, source_manifest_hash)`; train/validation/holdout splits remain immutable.
- **PostgreSQL hot layer:** latest verified market snapshots, last completed candles, model feature vectors/materialized signals, audit/receipts, user portfolios and deterministic risk controls. No 20-year training load on PAPER EC2.
- **Isolated research workers:** fetch the same frozen feature manifest across selected registered models. Each model selects required feature columns and must fail `INCOMPLETE` for missing mandatory inputs. Separate model-weight, data-rights and governance gates.
- **Decision fanout:** build one causal snapshot and share the fingerprint across eligible model calls. Aggregate bounded model votes and explicit confidence/abstentions; never recompute all history on a user request or allow research output to bypass the Java risk boundary.

## Required model-readiness states, never filled from mocks

For each `(model_version, listing_id, decision_as_of)`, publish `READY`, `NOT_LICENSED`, `SOURCE_UNAVAILABLE`, `INSUFFICIENT_HISTORY`, `STALE`, `INCOMPATIBLE_SCHEMA`, `UNTRAINED`, or `RESEARCH_ONLY` with source-specific blocker codes and dataset/feature hashes. `READY` only when all mandatory licensed inputs meet their calendar-based lookback, temporal availability, freshness and revisions. A missing event can mean `NO_EVENT` only if the licensed event source was queried and coverage was verified; it is never silently the number zero.

## Prioritized implementation with explicit acceptance

1. **D20-01A — This audit**: source requirements and machine-checkable crosswalk for all 43/18/232, independent from AWS and orders.
2. **D20-01B — Typed contracts and fail-closed readiness**: implement the per-source and model manifest/version/available-at validators with synthetic/time-travel tests; no provider calls.
3. **D20-02 — Stable issuer/listing identity + corporate actions**: forward-only schema, alias and point-in-time delisted/merger fixtures, separate raw/split-adjusted/total-return view.
4. **D20-03 — Licensed provider registry**: rights by retain/train/derive/display/redistribute, verified historical coverage, provider pricing/rate limits and immutable source evidence.
5. **D20-04 — Daily history lake + resumable import**: 20 years per actual listing age and exchange session, S3 Parquet compact partitions; stage 1 / 50 / 500 / full eligible with gap reports and no fiction.
6. **D20-05 — Non-price datasets**: dated filings, ownership/governance, index breadth and VIX, news/events, macro, institutional flows, borrow eligibility, derivatives L1/L2 as separately licensed products.
7. **D20-06 — Reusable batch feature tables**: deterministic shared technical, fundamental, momentum, regime, liquidity, event, Greeks and after-cost features; versioned as-of availability and missingness indicators.
8. **D20-07 — Model readiness and performance**: per-model typed requirements/feature columns, model version and fitted artifact, calibrated outcome evidence, latency p50/p95/p99 and strict abstention on missing inputs.
9. **D20-08 — Wire UI from real services**: replace individual fixture cards only with verified, authenticated backend contracts; otherwise show precise empty/stale/unavailable state; preserve mock-only design gallery.
10. **D20-09 — Shadow/live rollout gates**: genuine independent licensed holdout, post-cost economics, 20-year survivorship-safety, deployed worker reliability, broker execution and deterministic risk-authority review. No automatic orders.

## Acceptance metrics

- Per source **calendar-and-listing-aware coverage**: expected sessions, received and valid records, gap reason, earliest/latest available, provenance and permit state by year, security, market and interval.
- Per model **input completeness**: mandatory/optional feature counts, usable labelled samples, training/calibration/holdout windows, leakage failures, sample size and feature schema/version.
- Per screen **truthful rendering**: live/closed/cached/unavailable/fixture-only status and correct timestamp, no fixture model performance or holdings represented as actual.
- Multi-model **feature reuse**: cache-hit rate, single shared feature-snapshot construction per case, per-model read p95, queue wait, circuit-breaker and no orders under missing data.
- Source rights verified individually for raw retention, private training, derived outputs, end-user visual display and external redistribution; technical API access is never substituted for a commercial license.

**No AWS resources, database migrations, live orders or full-universe historical downloads are authorized by this audit.**