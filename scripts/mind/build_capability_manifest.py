#!/usr/bin/env python3
"""Build the audited Mintick Mind capability manifest (issue #998, PR A).

The 43 screen descriptors and the 232-entry strategy catalog are parsed from
the approved Flutter source so identifiers can never drift from the UI. Only
implementation links verified by file + test existence in this repository are
recorded. Declared statuses never exceed TESTED: DEPLOYED / RUNTIME_VERIFIED /
FAILED are produced only by server-side runtime evidence events.

Usage: python3 scripts/mind/build_capability_manifest.py [--check]
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIND = ROOT / "apps/trading_client/lib/src/features/mintick_mind"
OUT = ROOT / "backend/src/main/resources/mind/capability-manifest.v1.json"
MANIFEST_VERSION = "mind.capability-manifest.v1+2026-11-14.13"
AUDITED_AT = "2026-11-13"

DECLARABLE = {"NOT_STARTED", "MOCK", "IMPLEMENTING", "CODE_COMPLETE", "TESTED",
              "DATA_BLOCKED", "RIGHTS_BLOCKED"}

# --------------------------------------------------------------------------
# Screen audit: every route renders. Status describes the data behind it.
# --------------------------------------------------------------------------
SCREEN_AUDIT: dict[str, dict] = {
    "overview": {
        "status": "IMPLEMENTING",
        "note": "Readiness + 6 research evidence panels call real /api/customer/mind/v1 endpoints; "
                "decision cards, top strategies and tabs remain hardcoded fixtures behind a MOCK UI banner.",
        "endpoints": ["/api/customer/mind/v1/overview", "/api/customer/mind/v1/strategy-replay",
                      "/api/customer/mind/v1/ma-walkforward", "/api/customer/mind/v1/prospective-cohort",
                      "/api/customer/mind/v1/prospective-signals"],
        "missing": ["Replace hardcoded decision cards with server candidates",
                    "Remove MOCK UI banner only after every overview value is server-backed"],
    },
    "agent-evidence": {
        "status": "IMPLEMENTING",
        "note": "Evidence Ledger panel calls /api/customer/mind/v1/evidence; surrounding scope chips are fixtures.",
        "endpoints": ["/api/customer/mind/v1/evidence"],
        "missing": ["Wire scope filters to server query parameters"],
    },
    "coverage": {
        "status": "IMPLEMENTING",
        "note": "Being replaced by the server progress registry (/api/customer/mind/v1/progress).",
        "endpoints": ["/api/customer/mind/v1/progress"],
        "missing": ["Flutter web/Android/iOS runtime evidence of the progress screen"],
    },
    "strategies": {
        "status": "IMPLEMENTING",
        "note": "Status, implementation code and counts come from the server registry; fixture 'Paper/Watch/Paused' "
                "states were removed. Relationship and ranking side panels remain labelled mock.",
        "endpoints": ["/api/customer/mind/v1/progress"],
        "missing": ["Real relationship graph", "Measured risk-adjusted ranking", "Flutter web/Android/iOS runtime evidence"],
    },
    "strategy-detail": {
        "status": "IMPLEMENTING",
        "note": "Identity, blocker, evidence and before-DONE checklist from the registry; backtest curve is a labelled mock.",
        "endpoints": ["/api/customer/mind/v1/progress"],
        "missing": ["Per-strategy backtest/walk-forward results", "Flutter web/Android/iOS runtime evidence"],
    },
    "models": {
        "status": "IMPLEMENTING",
        "note": "Model status/blockers from the registry; fixture AUC/Sharpe metrics replaced with 'Not measured'.",
        "endpoints": ["/api/customer/mind/v1/progress"],
        "missing": ["Model training lifecycle", "Measured out-of-sample metrics", "Flutter web/Android/iOS runtime evidence"],
    },
    "model-detail": {
        "status": "IMPLEMENTING",
        "note": "Status, blocker and code evidence from the registry; fake health percentages and latency removed.",
        "endpoints": ["/api/customer/mind/v1/progress"],
        "missing": ["Serving path and monitored health", "Flutter web/Android/iOS runtime evidence"],
    },
    "portfolio": {
        "status": "IMPLEMENTING",
        "note": "Virtual PAPER equity, realized/unrealized P&L, exposure, rooms and positions with mark freshness from "
                "/api/customer/mind/v1/paper-portfolio; what-if shock applies to real positions.",
        "endpoints": ["/api/customer/mind/v1/paper-portfolio"],
        "missing": ["Sector / correlation exposure", "Flutter web/Android/iOS runtime evidence"],
    },
    "risk": {
        "status": "IMPLEMENTING",
        "note": "Shows the enforced automatic PAPER risk policy and real per-room PAPER pause/resume; fake sliders removed.",
        "endpoints": ["/api/customer/mind/v1/paper-portfolio", "/api/customer/trading-rooms/{roomId}/paper/pause"],
        "missing": ["Daily loss circuit breaker for Mind cycles", "Flutter web/Android/iOS runtime evidence"],
    },
    "decisions": {
        "status": "IMPLEMENTING",
        "note": "Lists every recorded Mind PAPER cycle with outcome filters from /paper-journal; fixture timeline removed.",
        "endpoints": ["/api/customer/mind/v1/paper-journal"],
        "missing": ["Decisions from strategies other than SMA_TREND_VOLUME_V1", "Flutter web/Android/iOS runtime evidence"],
    },
    "decision-detail": {
        "status": "IMPLEMENTING",
        "note": "Server-recorded gate-by-gate trail, quote, sizing and reason codes for one cycle.",
        "endpoints": ["/api/customer/mind/v1/paper-journal"],
        "missing": ["Counterfactual / alternative evaluation", "Flutter web/Android/iOS runtime evidence"],
    },
    "journal": {
        "status": "IMPLEMENTING",
        "note": "Recorded cycles with virtual order ids and audit values from the server; mock export removed.",
        "endpoints": ["/api/customer/mind/v1/paper-journal"],
        "missing": ["Signed export", "Flutter web/Android/iOS runtime evidence"],
    },
    "performance": {
        "status": "IMPLEMENTING",
        "note": "Closed Mind PAPER round trips only: count, win rate, realized P&L, max drawdown, explicit small-sample "
                "warning (<30); invented returns removed.",
        "endpoints": ["/api/customer/mind/v1/paper-journal"],
        "missing": ["Equity curve once sample is meaningful", "Backtest vs PAPER comparison",
                    "Flutter web/Android/iOS runtime evidence"],
    },
    "controls": {
        "status": "IMPLEMENTING",
        "note": "Engine status, pipeline, PAPER/LIVE and data-dependency states from the registry; local fake toggles "
                "and simulated emergency stop removed (real PAPER pause is in Risk Center).",
        "endpoints": ["/api/customer/mind/v1/progress"],
        "missing": ["Run coordinator health (heartbeat, queue lag, errors)", "Flutter web/Android/iOS runtime evidence"],
    },
    "dependencies": {
        "status": "IMPLEMENTING",
        "note": "Real dependsOn chains from the registry with the status of each prerequisite; fixture graph removed.",
        "endpoints": ["/api/customer/mind/v1/progress"],
        "missing": ["Visual graph layout", "Flutter web/Android/iOS runtime evidence"],
    },
    "trading-rooms": {
        "status": "IMPLEMENTING",
        "note": "Funded PAPER rooms with virtual cash, positions, realized P&L and paused state; fixture rooms removed.",
        "endpoints": ["/api/customer/mind/v1/paper-portfolio"],
        "missing": ["Per-room strategy stack", "Flutter web/Android/iOS runtime evidence"],
    },
    "culprit-catcher": {
        "status": "IMPLEMENTING",
        "note": "Non-filled recorded cycles grouped by decisive gate reason; unknown outcomes listed first.",
        "endpoints": ["/api/customer/mind/v1/paper-journal"],
        "missing": ["Regime-mistake analysis once regimes are verified", "Flutter web/Android/iOS runtime evidence"],
    },
    "signal-scorecard": {
        "status": "IMPLEMENTING",
        "note": "Real SMA_TREND_VOLUME_V1 funnel: evaluated -> BUY -> entries -> closed trades, with sample warning.",
        "endpoints": ["/api/customer/mind/v1/paper-journal"],
        "missing": ["Scorecards for further strategies", "Flutter web/Android/iOS runtime evidence"],
    },
    "charts": {
        "status": "IMPLEMENTING",
        "note": "Watchlist instruments charted from stored completed OHLCV candles (/api/customer/market-data/candles) "
                "with SMA5/SMA20, range and source; simulated chart, fictional price and invented signal stack removed.",
        "endpoints": ["/api/customer/market-data/candles", "/api/customer/watchlist"],
        "missing": ["Additional chart styles from real data", "Flutter web/Android/iOS runtime evidence"],
    },
    "research": {
        "status": "IMPLEMENTING",
        "note": "Real MA walk-forward and strategy-replay research panels (/api/customer/mind/v1/ma-walkforward, "
                "/strategy-replay) above labelled mock experiment cards.",
        "endpoints": ["/api/customer/mind/v1/ma-walkforward", "/api/customer/mind/v1/strategy-replay"],
        "missing": ["Per-strategy backtests for every registry evaluator", "Flutter web/Android/iOS runtime evidence"],
    },
    "shadow-arena": {
        "status": "IMPLEMENTING",
        "note": "Real forward-only prospective cohort, signals and outcomes panels; comparison cards below are mocks.",
        "endpoints": ["/api/customer/mind/v1/prospective-cohort", "/api/customer/mind/v1/prospective-signals"],
        "missing": ["Model-vs-model shadow comparisons", "Flutter web/Android/iOS runtime evidence"],
    },
    "derivatives": {
        "status": "DATA_BLOCKED",
        "note": "Banner shows the real data.fno registry blocker; contracts, OI, chain and prices below are labelled mocks.",
        "missing": ["Permitted F&O contracts/OI/option-chain source (#924)", "Real chain and greeks", "Flutter runtime evidence"],
    },
    "options-chain": {
        "status": "DATA_BLOCKED",
        "note": "Banner shows the real data.fno registry blocker; contracts, OI, chain and prices below are labelled mocks.",
        "missing": ["Permitted F&O contracts/OI/option-chain source (#924)", "Real chain and greeks", "Flutter runtime evidence"],
    },
    "option-builder": {
        "status": "DATA_BLOCKED",
        "note": "Banner shows the real data.fno registry blocker; contracts, OI, chain and prices below are labelled mocks.",
        "missing": ["Permitted F&O contracts/OI/option-chain source (#924)", "Real chain and greeks", "Flutter runtime evidence"],
    },
    "app-home": {
        "status": "IMPLEMENTING",
        "note": "Customer Home summary from /api/customer/home/v1/summary: PAPER rooms, broker connection state, Mind evidence status and next action; live execution shown as not authorized.",
        "endpoints": ["/api/customer/home/v1/summary"],
        "missing": ["Flutter web/Android/iOS runtime evidence"],
    },
    "intelligence-hub": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "agent-council": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "regime-weights": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "confidence-lab": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "institutional-flow": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "daily-verdict": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "multibagger": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "thesis-detail": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "events": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "regimes": {
        "status": "DATA_BLOCKED",
        "blockerCode": "NO_BACKEND_SOURCE",
        "note": "No backend data source for this screen yet; the screen is a labelled preview and is never presented as a working feature.",
        "endpoints": [],
        "missing": ["Backend data source and deterministic evaluator for this screen", "Flutter web/Android/iOS runtime evidence"],
    },
    "design-gallery": {
        "status": "MOCK",
        "blockerCode": "DESIGN_REFERENCE_ONLY",
        "note": "Design reference only: layout and visual mockups, not a product feature. Labelled on screen.",
        "endpoints": [],
        "missing": [],
    },
    "design-preview": {
        "status": "MOCK",
        "blockerCode": "DESIGN_REFERENCE_ONLY",
        "note": "Design reference only: clickable layout preview, not a product feature. Labelled on screen.",
        "endpoints": [],
        "missing": [],
    },
    "assistant": {
        "status": "IMPLEMENTING",
        "note": "Read-only grounded assistant (/api/customer/mind/v1/assistant/query): answers from the progress registry, PAPER portfolio and journal with sources and timestamps; refuses trade requests; no language model.",
        "endpoints": ["/api/customer/mind/v1/assistant/query"],
        "missing": ["Broader question coverage", "Flutter web/Android/iOS runtime evidence"],
    },
    "research-engines": {
        "status": "IMPLEMENTING",
        "note": "Server registry of named research engines (/api/customer/mind/v1/research-engines). All five are reported NOT_CONNECTED or NOT_APPLICABLE; none is available until an adapter and its own runtime evidence exist.",
        "endpoints": ["/api/customer/mind/v1/research-engines"],
        "missing": ["Adapters for VectorBT, Backtesting.py, Nautilus and LEAN", "Flutter web/Android/iOS runtime evidence"],
    },
    "settings": {
        "status": "IMPLEMENTING",
        "note": "Real controls only: per-room PAPER pause/resume (existing wallet service), execution boundary (PAPER only, LIVE not authorized) and the deployed backend revision from the progress registry. Fake monitoring switches removed.",
        "endpoints": ["/api/customer/mind/v1/paper-portfolio", "/api/customer/trading-rooms/{roomId}/paper/pause", "/api/customer/mind/v1/progress"],
        "missing": ["Flutter web/Android/iOS runtime evidence"],
    },
    "room-detail": {
        "status": "IMPLEMENTING",
        "note": "Hosts the real Mind PAPER strategy cycle panel (funded room, watchlist instrument, server outcome, "
                "orders/positions/P&L). Remaining room cards are fixtures.",
        "endpoints": ["/api/customer/trading-rooms/{roomId}/mind/paper-cycles"],
        "missing": ["Replace remaining fixture room cards", "Flutter web/Android/iOS runtime evidence"],
    },
}
DEFAULT_SCREEN_MISSING = ["Audit each control and value", "Server API for every displayed value",
                          "Replace fixture data or label unavailable", "Flutter web/Android/iOS runtime evidence"]
NAV_SCREENS = {"app-home", "design-gallery", "design-preview", "settings"}

# --------------------------------------------------------------------------
# Strategy catalog -> verified implementations (name match is exact catalog name).
# --------------------------------------------------------------------------
JAVA_TEST = "backend/src/test/java/in/aitradingsystem/"
JAVA_MAIN = "backend/src/main/java/in/aitradingsystem/"
PY_MAIN = "intelligence/src/aitrading_intelligence/"
PY_TEST = "intelligence/tests/"

STRATEGY_IMPL: dict[str, dict] = {
    "SMA Cross": {
        "code": "SMA_TREND_VOLUME_V1", "version": "1", "match": "VARIANT", "runtime": "JAVA_DETERMINISTIC",
        "note": "5/20 SMA trend with volume confirmation; also the MA walk-forward research subject.",
        "paths": [JAVA_MAIN + "strategy/StrategyService.java", JAVA_MAIN + "mind/MindMaWalkForwardEvaluator.java"],
        "tests": [JAVA_TEST + "strategy/StrategyServiceTest.java", JAVA_TEST + "strategy/StrategyQualityGateTest.java"],
        "intervals": ["ONE_MINUTE", "FIVE_MINUTE", "FIFTEEN_MINUTE", "ONE_HOUR", "ONE_DAY"],
    },
    "Opening Range Breakout": {
        "code": "OPENING_RANGE_BREAKOUT_V1", "version": "1", "match": "EXACT", "runtime": "JAVA_DETERMINISTIC",
        "note": "09:15-09:30 IST opening range breakout with volume confirmation (scanner registry).",
        "paths": [JAVA_MAIN + "scanner/OpeningRangeBreakoutStrategy.java"],
        "tests": [JAVA_TEST + "scanner/ExtendedScannerStrategiesTest.java", JAVA_TEST + "scanner/StrategyRegistryTest.java"],
    },
    "RSI Oversold Rebound": {
        "code": "RSI_REVERSAL_V1", "version": "1", "match": "EXACT", "runtime": "JAVA_DETERMINISTIC",
        "note": "Prior RSI < 30 recovering above 30 with price confirmation (scanner registry).",
        "paths": [JAVA_MAIN + "scanner/RsiReversalStrategy.java"],
        "tests": [JAVA_TEST + "scanner/ExtendedScannerStrategiesTest.java"],
    },
    "Donchian Channel Breakout": {
        "code": "VOLUME_BREAKOUT_V1", "version": "1", "match": "VARIANT", "runtime": "JAVA_DETERMINISTIC",
        "note": "Close above prior 20-bar high with >=1.5x average volume (Donchian high + volume filter).",
        "paths": [JAVA_MAIN + "scanner/VolumeBreakoutStrategy.java"],
        "tests": [JAVA_TEST + "scanner/ExtendedScannerStrategiesTest.java"],
    },
    "Anchored VWAP Trend": {
        "code": "VWAP_MOMENTUM_V1", "version": "1", "match": "VARIANT", "runtime": "JAVA_DETERMINISTIC",
        "note": "Rolling (not anchored) VWAP momentum; anchoring not implemented.",
        "paths": [JAVA_MAIN + "scanner/VwapMomentumStrategy.java"],
        "tests": [JAVA_TEST + "scanner/ExtendedScannerStrategiesTest.java"],
    },
    "20 EMA Pullback": {
        "code": "MA_RECLAIM_V1", "version": "1", "match": "VARIANT", "runtime": "JAVA_DETERMINISTIC",
        "note": "Reclaim of 20-bar SMA (not EMA) while 5 SMA > 20 SMA.",
        "paths": [JAVA_MAIN + "scanner/MovingAverageReclaimStrategy.java"],
        "tests": [JAVA_TEST + "scanner/ExtendedScannerStrategiesTest.java"],
    },
    # Execution & risk gates (deterministic Java risk engine; legacy automated-paper path).
    "Exposure Cap": {
        "code": "RISK_GROSS_EXPOSURE+MAX_ORDER_NOTIONAL", "version": "1", "match": "EXACT", "runtime": "JAVA_RISK_GATE",
        "paths": [JAVA_MAIN + "risk/rules/GrossPortfolioExposureRule.java", JAVA_MAIN + "risk/rules/MaxOrderNotionalRule.java"],
        "tests": [JAVA_TEST + "risk/ProductionRiskRulesTest.java"],
    },
    "Drawdown Kill Switch": {
        "code": "RISK_DAILY_LOSS+EMERGENCY_STOP", "version": "1", "match": "VARIANT", "runtime": "JAVA_RISK_GATE",
        "note": "Daily-loss limit and emergency stop; no multi-day drawdown curve gate.",
        "paths": [JAVA_MAIN + "risk/rules/DailyLossRule.java", JAVA_MAIN + "risk/rules/EmergencyStopRule.java"],
        "tests": [JAVA_TEST + "risk/ProductionRiskRulesTest.java"],
    },
    "Stop Loss Discipline": {
        "code": "RISK_PRICE_LEVELS+MIN_REWARD_RISK", "version": "1", "match": "VARIANT", "runtime": "JAVA_RISK_GATE",
        "paths": [JAVA_MAIN + "risk/rules/PriceLevelsRule.java", JAVA_MAIN + "risk/rules/MinimumRewardRiskRule.java"],
        "tests": [JAVA_TEST + "risk/ProductionRiskRulesTest.java"],
    },
    "Trailing Exit": {
        "code": "TRAILING_PROTECTION_POLICY", "version": "1", "match": "EXACT", "runtime": "JAVA_RISK_GATE",
        "paths": [JAVA_MAIN + "paper/TrailingProtectionPolicy.java"],
        "tests": [JAVA_TEST + "paper/AutomatedPaperPositionMonitorServiceTest.java"],
    },
    "Broker Reconciliation Gate": {
        "code": "PAPER_RECONCILIATION", "version": "1", "match": "VARIANT", "runtime": "JAVA_RISK_GATE",
        "note": "PAPER ledger reconciliation only; no LIVE broker reconciliation.",
        "paths": [JAVA_MAIN + "paper/PaperReconciliationService.java"],
        "tests": [JAVA_TEST + "paper/PaperReconciliationServiceIT.java"],
    },
    "Quote Staleness Gate": {
        "code": "RISK_MARKET_STATE_FRESHNESS+PAPER_TICK_15S", "version": "1", "match": "EXACT", "runtime": "JAVA_RISK_GATE",
        "paths": [JAVA_MAIN + "risk/rules/MarketStateFreshnessRule.java", JAVA_MAIN + "tradingrooms/CustomerPaperOrderService.java"],
        "tests": [JAVA_TEST + "tradingrooms/CustomerPaperOrderServiceIT.java"],
    },
    # Python research implementations: tested in isolation, not wired to Mind runtime.
    "Tabular Alpha Classification": {"code": "PY_LIGHTGBM", "match": "VARIANT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "lightgbm_model.py"], "tests": [PY_TEST + "test_lightgbm_model.py"]},
    "Market Regime HMM": {"code": "PY_REGIME_HMM", "match": "EXACT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "regime_hmm.py"], "tests": [PY_TEST + "test_regime_hmm.py"]},
    "Isolation Forest Anomaly Gate": {"code": "PY_REGIME_ANOMALY", "match": "VARIANT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "regime_anomaly.py"], "tests": [PY_TEST + "test_regime_anomaly.py"]},
    "Static Ensemble": {"code": "PY_FUSION", "match": "EXACT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "fusion.py"], "tests": [PY_TEST + "test_fusion.py"]},
    "Adaptive Ensemble": {"code": "PY_ADAPTIVE_FUSION", "match": "EXACT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "adaptive_fusion.py"], "tests": [PY_TEST + "test_adaptive_fusion.py"]},
    "Cross-Sectional ML Ranker": {"code": "PY_CROSS_SECTIONAL_RANKING", "match": "EXACT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "cross_sectional_ranking.py"], "tests": [PY_TEST + "test_cross_sectional_ranking.py"]},
    "Uncertainty Abstention": {"code": "PY_ENSEMBLE_UNCERTAINTY", "match": "EXACT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "ensemble_uncertainty.py"], "tests": [PY_TEST + "test_ensemble_uncertainty.py"]},
    "Model Disagreement Gate": {"code": "PY_FUSION_DISAGREEMENT", "match": "EXACT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "fusion_disagreement_control.py"], "tests": [PY_TEST + "test_fusion_disagreement_control.py"]},
    "Time-Series Forecast": {"code": "PY_PATCH_TRANSFORMER", "match": "VARIANT", "runtime": "PYTHON_RESEARCH",
        "paths": [PY_MAIN + "india_patch_transformer.py"], "tests": [PY_TEST + "test_india_patch_transformer.py"]},
    "Financial NLP Context": {"code": "PY_INDIA_FIN_NLP", "match": "VARIANT", "runtime": "PYTHON_RESEARCH",
        "status": "RIGHTS_BLOCKED", "blocker": "NEWS_LICENCE_REVIEW",
        "paths": [PY_MAIN + "india_financial_nlp_corpus.py"], "tests": [PY_TEST + "test_india_financial_nlp_corpus.py"]},
}

# Families that need data the canonical feed (#924) does not yet provide.
FAMILY_DATA_DEPENDENCY = {
    "Event Driven": "corporate-events feed", "Fundamental & Multibagger": "fundamentals feed",
    "Factor & Portfolio": "fundamentals + cross-sectional universe", "Market Microstructure": "order-book depth feed",
    "Futures": "F&O contracts + OI feed", "Options — Directional": "option chain feed",
    "Options — Volatility": "option chain + IV feed", "Options — Hedging": "option chain + greeks feed",
    "Statistical Arbitrage": "cross-sectional universe + adjusted history",
    "Market Regime & Macro": "breadth / macro / FX feeds",
}
FNO_FAMILIES = {"Futures", "Options — Directional", "Options — Volatility", "Options — Hedging"}

MODEL_IMPL: dict[str, dict] = {
    "LightGBM": {"paths": [PY_MAIN + "lightgbm_model.py"], "tests": [PY_TEST + "test_lightgbm_model.py"]},
    "CatBoost": {"paths": [PY_MAIN + "catboost_model.py"], "tests": [PY_TEST + "test_catboost_model.py"]},
    "XGBoost": {"paths": [PY_MAIN + "xgboost_benchmark.py"], "tests": [PY_TEST + "test_xgboost_benchmark.py"]},
    "PyTorch MLP": {"paths": [PY_MAIN + "pytorch_model.py"], "tests": [PY_TEST + "test_pytorch_model.py"]},
    "Original Patch Transformer": {"paths": [PY_MAIN + "india_patch_transformer.py"], "tests": [PY_TEST + "test_india_patch_transformer.py"]},
    "TFT-inspired Model": {"paths": [PY_MAIN + "india_bounded_tft.py"], "tests": [PY_TEST + "test_india_bounded_tft.py"]},
    "TimeMixer-style Model": {"paths": [PY_MAIN + "india_time_mixer.py"], "tests": [PY_TEST + "test_india_time_mixer.py"]},
    "Chronos-2 Adapter": {"paths": [PY_MAIN + "india_chronos2_zero_shot.py"], "tests": [PY_TEST + "test_india_chronos2_zero_shot.py"],
                          "status": "RIGHTS_BLOCKED", "blocker": "CHECKPOINT_LICENCE_GATE"},
    "Regime HMM": {"paths": [PY_MAIN + "regime_hmm.py"], "tests": [PY_TEST + "test_regime_hmm.py"]},
    "Isolation Forest": {"paths": [PY_MAIN + "regime_anomaly.py"], "tests": [PY_TEST + "test_regime_anomaly.py"]},
    "Gemini Context": {"paths": [PY_MAIN + "india_gemini_sentiment_benchmark.py"], "tests": [PY_TEST + "test_india_gemini_sentiment_benchmark.py"],
                       "status": "RIGHTS_BLOCKED", "blocker": "NEWS_LICENCE_AND_API_REVIEW"},
    "Financial NLP Challenger": {"paths": [PY_MAIN + "india_financial_nlp_corpus.py"], "tests": [PY_TEST + "test_india_financial_nlp_corpus.py"],
                                 "status": "RIGHTS_BLOCKED", "blocker": "COMMERCIAL_LICENCE_PENDING"},
    "Static Ensemble": {"paths": [PY_MAIN + "fusion.py"], "tests": [PY_TEST + "test_fusion.py"]},
    "Adaptive Ensemble": {"paths": [PY_MAIN + "adaptive_fusion.py"], "tests": [PY_TEST + "test_adaptive_fusion.py"]},
    "Meta Decision Learner": {"paths": [PY_MAIN + "learned_meta_model_readiness.py"], "tests": [PY_TEST + "test_learned_meta_model_readiness.py"],
                              "status": "IMPLEMENTING", "blocker": "READINESS_GATE_ONLY_NO_LEARNER"},
    "Quantile Forecaster": {"paths": [PY_MAIN + "india_bounded_tft.py"], "tests": [PY_TEST + "test_india_bounded_tft.py"]},
}

ENGINE_ITEMS = [
    ("engine.registry", "Strategy registry", "TESTED", "Scanner StrategyRegistry (5 evaluators) + SMA_TREND_VOLUME_V1; not yet unified with Mind catalog.",
     [JAVA_MAIN + "scanner/StrategyRegistry.java"], [JAVA_TEST + "scanner/StrategyRegistryTest.java"], []),
    ("engine.data-gate", "Data availability & quality gate", "TESTED", "Candle validation + MarketDataQualityService eligibility before evaluation.",
     [JAVA_MAIN + "marketdata/CandleSeriesValidator.java"], [JAVA_TEST + "strategy/StrategyQualityGateTest.java"], ["data.historical-candles"]),
    ("engine.strategy-eval", "Deterministic strategy evaluation", "TESTED", "Stored completed candles only; candidate is never executable by itself.",
     [JAVA_MAIN + "strategy/StrategyService.java"], [JAVA_TEST + "strategy/StrategyServiceTest.java"], ["engine.data-gate"]),
    ("engine.risk-gates", "Session / fresh quote / exposure gates", "TESTED", "CustomerPaperOrderService enforces cash session, 15s verified tick, marketable limit, 20% exposure cap.",
     [JAVA_MAIN + "tradingrooms/CustomerPaperOrderService.java"], [JAVA_TEST + "tradingrooms/CustomerPaperOrderServiceIT.java"], ["data.fresh-ticks"]),
    ("engine.paper-submit", "PAPER order via existing virtual wallet", "TESTED", "Idempotent fills, wallet lock, owner isolation.",
     [JAVA_MAIN + "tradingrooms/CustomerPaperOrderService.java"], [JAVA_TEST + "tradingrooms/CustomerPaperOrderServiceIT.java"], ["engine.risk-gates"]),
    ("engine.accounting", "Positions, cash & realized P&L", "TESTED", "Average-cost positions and realized P&L in paise.",
     [JAVA_MAIN + "tradingrooms/CustomerPaperOrderService.java"], [JAVA_TEST + "tradingrooms/CustomerPaperOrderServiceIT.java"], ["engine.paper-submit"]),
    ("engine.strategy-to-paper", "Strategy candidate -> PAPER orchestration", "TESTED",
     "MindPaperCycleService: permitted-source + freshness gates, verified LTP, automatic 1% risk sizing, "
     "deterministic order key, existing CustomerPaperOrderService; owner-initiated, no auto-retry.",
     [JAVA_MAIN + "mind/MindPaperCycleService.java"],
     [JAVA_TEST + "mind/MindPaperCycleServiceIT.java", JAVA_TEST + "mind/MindPaperCycleServiceTest.java"],
     ["engine.strategy-eval", "engine.paper-submit"]),
    ("engine.backtest", "Backtest engine", "TESTED", "SMA backtest with fees; not yet generalized across registry.",
     [JAVA_MAIN + "backtest/SmaBacktestEngine.java"],
     [JAVA_TEST + "backtest/SmaBacktestEngineTest.java", JAVA_TEST + "backtest/LookAheadBiasRegressionTest.java"], []),
    ("engine.walkforward", "Walk-forward after-cost validation", "TESTED", "MA walk-forward research report with bias gates.",
     [JAVA_MAIN + "mind/MindMaWalkForwardService.java"],
     [JAVA_TEST + "mind/MindMaWalkForwardServiceTest.java", JAVA_TEST + "mind/MindMaWalkForwardEvaluatorTest.java"], ["engine.backtest"]),
    ("engine.scheduler", "Run coordination, checkpoints & backpressure", "NOT_STARTED", "No Mind run coordinator / poison-job visibility yet.", [], [], []),
    ("engine.model-lifecycle", "Model training / version-pinned artifacts", "NOT_STARTED", "Python research modules exist; no Mind training lifecycle or deployed artifact registry.", [], [], []),
    ("engine.ensemble-selection", "Ensemble / automatic selection & conflict resolution", "NOT_STARTED", "Python fusion research exists; not connected to Mind runtime.", [], [], []),
]

DATA_ITEMS = [
    ("data.canonical-contract", "Canonical cross-broker research feed (#924)", "DATA_BLOCKED", "DataTeam",
     "AWAITING_CANONICAL_CONTRACT", "Versioned candle/feature-input contract + fixtures owned by data team (#924)."),
    ("data.historical-candles", "Stored historical candles", "TESTED", "DataTeam", None,
     "market_candle store with quality gate; source rights must be verified per dataset."),
    ("data.fresh-ticks", "Verified live LTP ticks for PAPER fills", "TESTED", "DataTeam", None,
     "market_last_tick must be within 15s and inside cash session."),
    ("data.fundamentals", "Fundamentals feed", "DATA_BLOCKED", "DataTeam", "NO_PERMITTED_SOURCE", "Required by Fundamental/Factor families."),
    ("data.fno", "F&O contracts, OI and option chain", "DATA_BLOCKED", "DataTeam", "NO_PERMITTED_SOURCE", "Required by Futures/Options families."),
    ("data.order-book", "Order-book depth", "DATA_BLOCKED", "DataTeam", "NO_PERMITTED_SOURCE", "Required by Microstructure family."),
]

PAPER_ITEMS = [
    ("paper.vertical-slice", "PAPER vertical slice: strategy -> gates -> virtual fill -> P&L", "TESTED",
     ["engine.strategy-to-paper", "engine.accounting"]),
    ("live.broker-gated", "LIVE broker-gated workflow", "NOT_STARTED", []),
]


def parse_screens() -> list[tuple[str, str, str]]:
    src = (MIND / "presentation/mind_workspace_page.dart").read_text()
    found = re.findall(r"MindScreenDescriptor\('([^']+)', '([^']+)', Icons\.\w+, '([A-Z]+)'\)", src)
    if len(found) != 43:
        raise SystemExit(f"expected 43 screens, found {len(found)}")
    return found


def parse_catalog() -> list[tuple[str, str, str, str]]:
    src = (MIND / "data/mind_fixtures.dart").read_text()
    rows = re.findall(r"\('([^']+)', '([^']+)', '([^']+)'\),", src.split("_catalog")[1].split("];")[0])
    out = []
    for family, modes, names in rows:
        for name in names.split("|"):
            out.append((f"STR-{len(out) + 1:03d}", name, family, modes))
    if len(out) != 232 or len({r[2] for r in out}) != 20:
        raise SystemExit(f"expected 232 strategies / 20 families, found {len(out)}")
    return out


def parse_models() -> list[tuple[str, str]]:
    src = (MIND / "data/mind_fixtures.dart").read_text()
    found = re.findall(r"MindModel\('([^']+)', '([^']+)'", src)
    if len(found) != 18:
        raise SystemExit(f"expected 18 models, found {len(found)}")
    return found


def require_paths(paths: list[str]) -> None:
    for p in paths:
        if not (ROOT / p).exists():
            raise SystemExit(f"declared evidence path does not exist: {p}")


def item(**kw) -> dict:
    base = {"parentId": None, "group": None, "owner": "Claude", "blockerCode": None, "requiredInputs": [],
            "dependsOn": [], "implementation": None, "evidence": [], "missing": [], "note": None}
    base.update(kw)
    if base["status"] not in DECLARABLE:
        raise SystemExit(f"{base['id']}: undeclarable status {base['status']}")
    require_paths([e["path"] for e in base["evidence"] if e.get("path")])
    return base


def build() -> dict:
    items = []
    for sid, label, group in parse_screens():
        audit = SCREEN_AUDIT.get(sid, {})
        status = audit.get("status", "MOCK")
        evidence = [{"kind": "SOURCE", "path": "apps/trading_client/lib/src/features/mintick_mind/presentation/mind_workspace_page.dart"}]
        evidence += [{"kind": "API", "ref": e} for e in audit.get("endpoints", [])]
        items.append(item(id=f"screen.{sid}", kind="SCREEN", name=label, group=group, status=status,
                          blockerCode=audit.get("blockerCode"),
                          note=audit.get("note") or ("Navigation / design reference screen; values are fixtures."
                                                     if sid in NAV_SCREENS else "Renders fixture data only; no API calls."),
                          missing=audit.get("missing", DEFAULT_SCREEN_MISSING), evidence=evidence))
    used = set()
    for sid, name, family, modes in parse_catalog():
        impl = STRATEGY_IMPL.get(name)
        dep = FAMILY_DATA_DEPENDENCY.get(family)
        if impl:
            used.add(name)
            require_paths(impl["paths"] + impl["tests"])
            status = impl.get("status", "TESTED")
            blocker = impl.get("blocker") or ("NOT_WIRED_TO_MIND_RUNTIME" if impl["runtime"] != "JAVA_DETERMINISTIC"
                                              else "NOT_WIRED_TO_PAPER")
            implementation = {k: impl.get(k) for k in ("code", "version", "match", "runtime")}
            implementation["version"] = implementation["version"] or "research"
            evidence = [{"kind": "SOURCE", "path": p} for p in impl["paths"]] + [{"kind": "TEST", "path": p} for p in impl["tests"]]
            missing = ["Connect to Mind strategy-to-PAPER orchestration", "Backtest + walk-forward evidence per strategy",
                       "Runtime evaluation evidence on deployed revision"]
            note = impl.get("note")
        else:
            status = "DATA_BLOCKED" if dep else "NOT_STARTED"
            blocker = "MISSING_DATA_" + re.sub(r"[^A-Z]+", "_", dep.upper()).strip("_") if dep else "CATALOG_NAME_ONLY"
            implementation, evidence = None, []
            missing = (["Permitted data source: " + dep] if dep else []) + ["Deterministic evaluator", "Unit tests", "Backtest evidence"]
            note = "Catalog name only; no evaluator in repository."
        items.append(item(id=f"strategy.{sid}", kind="STRATEGY", name=name, group=family, parentId=f"family.{family}",
                          status=status, blockerCode=blocker, implementation=implementation, evidence=evidence,
                          missing=missing, note=note, requiredInputs=(["OHLCV candles"] + ([dep] if dep else [])),
                          dependsOn=(["data.fno"] if family in FNO_FAMILIES else [])))
    unmapped = set(STRATEGY_IMPL) - used
    if unmapped:
        raise SystemExit(f"implementation mapped to unknown catalog names: {sorted(unmapped)}")
    for name, family in parse_models():
        impl = MODEL_IMPL.get(name)
        if impl:
            require_paths(impl["paths"] + impl["tests"])
            status = impl.get("status", "TESTED")
            blocker = impl.get("blocker", "NO_DEPLOYED_TRAINED_ARTIFACT")
            evidence = [{"kind": "SOURCE", "path": p} for p in impl["paths"]] + [{"kind": "TEST", "path": p} for p in impl["tests"]]
            note = "Python research code with tests. Fixture metric shown in the UI is not a measured result."
        else:
            status, blocker, evidence = "NOT_STARTED", "NO_IMPLEMENTATION", []
            note = "No implementation in repository; fixture name only."
        items.append(item(id="model." + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-"), kind="MODEL", name=name,
                          group=family, status=status, blockerCode=blocker, evidence=evidence, note=note,
                          missing=["Trained, version-pinned artifact", "Out-of-sample evaluation on permitted data",
                                   "Serving path wired to Mind runtime"]))
    for iid, name, status, note, paths, tests, deps in ENGINE_ITEMS:
        items.append(item(id=iid, kind="ENGINE", name=name, group="Engine pipeline", status=status, note=note,
                          dependsOn=deps, evidence=[{"kind": "SOURCE", "path": p} for p in paths]
                          + [{"kind": "TEST", "path": p} for p in tests]))
    for iid, name, status, owner, blocker, note in DATA_ITEMS:
        items.append(item(id=iid, kind="DATA", name=name, group="Data dependencies", status=status, owner=owner,
                          blockerCode=blocker, note=note, evidence=[{"kind": "ISSUE", "ref": "#924"}]))
    for iid, name, status, deps in PAPER_ITEMS:
        live = iid.startswith("live.")
        items.append(item(id=iid, kind="PAPER" if not live else "LIVE", name=name, group="PAPER readiness" if not live else "LIVE",
                          status=status, dependsOn=deps, blockerCode="LIVE_NOT_AUTHORIZED" if live else None,
                          evidence=[] if live else [{"kind": "SOURCE", "path": JAVA_MAIN + "mind/MindPaperCycleService.java"},
                                                    {"kind": "TEST", "path": JAVA_TEST + "mind/MindPaperCycleServiceIT.java"},
                                                    {"kind": "API", "ref": "/api/customer/trading-rooms/{roomId}/mind/paper-cycles"}],
                          note="LIVE activation is separately gated and not authorized by #998." if live else
                          "DONE only after a real fill on the deployed backend is recorded as runtime evidence."))
    ids = [i["id"] for i in items]
    if len(ids) != len(set(ids)):
        raise SystemExit("duplicate capability ids")
    return {"manifestVersion": MANIFEST_VERSION, "auditedAt": AUDITED_AT,
            "sourceIssues": ["#997", "#998", "#924"],
            "denominators": {"SCREEN": 43, "STRATEGY": 232, "MODEL": 18}, "items": items}


def main() -> None:
    text = json.dumps(build(), indent=1, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != text:
            raise SystemExit("capability manifest is out of date; run scripts/mind/build_capability_manifest.py")
        print("capability manifest up to date")
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
