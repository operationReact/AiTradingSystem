# Mintick Mind — All 42 Screen Contracts

**Status:** implementation specification; screen code currently uses synthetic fixtures. **Source of truth:** route IDs from `apps/trading_client/lib/src/features/mintick_mind/presentation/mind_workspace_page.dart` on audited dev. Never mark any screen LIVE or VERIFIED based solely on fixture content.

## Universal screen contract (applies to every screen below)

- Present exact purpose, owner scope (MARKET/RESEARCH/ACCOUNT/SYSTEM), icon, navigation, breadcrumb, accessible back, mobile safe areas, light/dark responsive layout, loading/refreshing/partial/stale/empty/unauthorized/error/disabled states.
- Every price, rank, metric and AI assertion displays source, `asOf`, `availableAt` when relevant, `generatedAt`, quality, model/policy version or an explicit UNVERIFIED/MOCK label.
- Production client must use typed `/api/mind/v1` DTOs or an existing vetted API adapter, not `MindFixtures` or `MindAgentFixtures`. An endpoint named below is a **proposed contract**, not evidence of an implemented endpoint. Use `/api/mind/demo/v1` only when explicitly launched in demo mode.
- Each data-owning widget supports skeleton, empty, stale, disconnected, source-error, permission-denied, retry and no-access; screen switching cancels obsolete requests. Never replace missing real data with fabricated fixture values.
- Every clickable action must navigate or call an audited backend command. It must respect signed-in ownership and never give an AI agent/model direct order authority. Account data is personal, research market signals are shared when permitted.
- Test: widget/unit, typed API contract, auth isolation, browser back and Android back, screen reader labels, reduced animation, 200% font, 360dp/412dp/large tablet, light and near-black themes, slow/error/empty fixtures; golden/regression snapshots.
- **Acceptance gate per screen:** no unconnected action, no inaccurate simulated/live label, end-to-end verified data lineage, correct role checks, UI responsiveness and measured p95. Document source endpoints, model readiness and screenshot evidence in PR.

## Provisional read API mapping

The `GET /api/mind/v1/screens/{routeId}` adapter is an optional uniform snapshot facade for lightweight cards; detailed pages should call domain endpoints, not fan out dozens of source requests. Use the actual existing Java endpoints where they satisfy contracts. New endpoints must be OpenAPI-reviewed, schema versioned, and read-only unless an explicitly authorized command is listed.

### 1. MintTick Home — `app-home`

- **Group / owning context:** PRIMARY; owner/account-sensitive plus shared research.
- **Required input DTOs and dependencies:** Owner account, rooms, market posture, daily risk, alerts. Data versions and causal availability timestamps required.
- **Visible components / sections:** Owner cash/P&L, room state, priority alerts, navigation. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Open room, select view mode, read account status; mode changes require existing server-authorized policy.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/app-home`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing broker=no invented P&L; stale account flagged; zero broker orders from page.
- **Specific acceptance tests:** Owner isolation, home/room links, 360/412dp, both themes. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 2. Mind Overview — `overview`

- **Group / owning context:** PRIMARY; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** MarketPosture,RegimeSnapshot,SourceQuality,EngineHealth,DecisionSummary. Data versions and causal availability timestamps required.
- **Visible components / sections:** Summary status, regime, model readiness, signal queue, risk and feed health. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Change market horizon; navigate to candidate/evidence/risk.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/overview`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** UNKNOWN regime or stale feed=degraded and WAIT; no fixtures in production.
- **Specific acceptance tests:** Partial source, freshness and p95 response. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 3. Decision Intelligence — `intelligence-hub`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** CandidatePrediction,ModelVote,Uncertainty,QualityGate,AccountRiskView. Data versions and causal availability timestamps required.
- **Visible components / sections:** Source-to-feature-to-model-to-decision trace, candidate comparison, capital veto. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter instrument/horizon; go to council/evidence/decision.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/intelligence-hub`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Decision confidence cannot overrule risk; unusable evidence=ABSTAIN.
- **Specific acceptance tests:** Same decision trace IDs across pages; missing feature gate. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 4. Agent Council & Debate — `agent-council`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** AgentVote,ConflictGroup,EvidenceRef,DecisionProposal. Data versions and causal availability timestamps required.
- **Visible components / sections:** Bull/Bear/Fundamental/Flow/Critic structured votes, sources and challenges. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Choose symbol/vote; drill to evidence.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/agent-council`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** No evidence => agent UNAVAILABLE; council is advisory only.
- **Specific acceptance tests:** Correlation overlap, bounded agent costs, risk veto. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 5. Evidence Ledger — `agent-evidence`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** EvidenceItem,SourceRecord,FeatureSnapshot,SourceQuality. Data versions and causal availability timestamps required.
- **Visible components / sections:** Source identity, original link, available-at, event chronology, validation badge. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter category/status/symbol; follow original document and decision.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/agent-evidence`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Unlicensed/private source access denied; broken provenance=UNVERIFIED.
- **Specific acceptance tests:** Point-in-time causal timestamp and revision tests. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 6. Regime Weight Studio — `regime-weights`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** RegimeProbability,ModelRoutingPolicy,PerformanceByRegime. Data versions and causal availability timestamps required.
- **Visible components / sections:** Weight sliders, regime cards, baseline/challenger and unknown fallback. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Simulate proposed weights; authorized versioned policy change only after backend gate.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/regime-weights`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** UNKNOWN regime keeps safe defaults; no policy override of risk.
- **Specific acceptance tests:** Weights bounded and sum; stale version rejected; rollback. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 7. Signal Scorecard — `signal-scorecard`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** StrategySignal,OutcomeLabel,TransactionCost,ScorecardBucket. Data versions and causal availability timestamps required.
- **Visible components / sections:** Win/loss, net expectancy, sample count, PF, drawdown, horizon selector. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Change horizon/family and open strategy results.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/signal-scorecard`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** No observed outcomes => NOT_MEASURED; partial labels flagged.
- **Specific acceptance tests:** Sample sufficiency, after-cost arithmetic, leakage. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 8. Confidence Calibration — `confidence-lab`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** Prediction,CalibrationBin,OutcomeLabel,ModelEvaluation. Data versions and causal availability timestamps required.
- **Visible components / sections:** Reliability diagram, sample bins, Brier/ECE/log-loss, horizon selectors. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Compare model and horizon; inspect calibration revision.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/confidence-lab`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Immature labels excluded; insufficient bins marked uncertain.
- **Specific acceptance tests:** Reproduced bins and causal label maturity. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 9. Shadow Trading Arena — `shadow-arena`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** ShadowRun,ShadowOrder,Baseline,SimulatedFill,MarketOutcome. Data versions and causal availability timestamps required.
- **Visible components / sections:** Champion/challenger shadow, baseline, simulated fills and cost metrics. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Compare run/strategy, inspect evidence, export test summary.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/shadow-arena`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** No real broker fills or order button from shadow; missing spread conservatism.
- **Specific acceptance tests:** No-broker-call invariant, comparable cohorts, idempotent runs. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 10. FII / DII Flow — `institutional-flow`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** OfficialFlowReport,SourceRevision,FlowAggregate. Data versions and causal availability timestamps required.
- **Visible components / sections:** FII/DII daily aggregate, provisional/final, history and publication. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Choose session, inspect official report, compare trends.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/institutional-flow`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing or provisional never presented as final or stock-specific ownership.
- **Specific acceptance tests:** Market timezone, release cutoff, corrected reports. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 11. Daily Market Verdict — `daily-verdict`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** MarketBreadth,IndexTrend,Regime,FlowReport,NewsEvidence. Data versions and causal availability timestamps required.
- **Visible components / sections:** Session summary, confidence/coverage, major events, watchpoints. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Select session, follow evidence, compare historical verdict.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/daily-verdict`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing sources yield PARTIAL verdict, not a confident fabricated narrative.
- **Specific acceptance tests:** Publish cutoffs, reproducible daily snapshot. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 12. Culprit Catcher — `culprit-catcher`

- **Group / owning context:** AGENTS; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** Incident,DecisionTrace,InputHash,ModelReceipt,RiskEvent,Fill,Outcome. Data versions and causal availability timestamps required.
- **Visible components / sections:** Incident list, severity, causal sequence, probable causes, prevention. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Choose incident, navigate to source/evidence/decision, mark reviewed.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/culprit-catcher`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Unknown cause stays UNKNOWN; do not blame execution for model errors.
- **Specific acceptance tests:** Immutable replay, ordering, source and fill attribution. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 13. Trading Rooms — `trading-rooms`

- **Group / owning context:** PRIMARY; owner/account-sensitive plus shared research.
- **Required input DTOs and dependencies:** TradingRoom,AccountBinding,RoomPolicy,StrategyBinding. Data versions and causal availability timestamps required.
- **Visible components / sections:** Room gallery, allocations, mode, strategy health, stop status. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Open room; authorized paper pause/resume.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/trading-rooms`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Disconnected account cannot appear ready for LIVE.
- **Specific acceptance tests:** Ownership checks, mode migration, no cross-room leakage. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 14. Strategy Universe — `strategies`

- **Group / owning context:** PRIMARY; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** StrategyDefinition,ParameterSchema,EligibilityState,FeatureDependency. Data versions and causal availability timestamps required.
- **Visible components / sections:** 232 template catalogue, 20 family tabs, search, readiness and count. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Search/filter/page, open strategy.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/strategies`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Template never shown as paper/production unless validated.
- **Specific acceptance tests:** Exactly 232 unique IDs, stable status and pagination. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 15. Strategy Detail — `strategy-detail`

- **Group / owning context:** PRIMARY; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** StrategyVersion,RuleSpec,ValidationRun,InputSchema. Data versions and causal availability timestamps required.
- **Visible components / sections:** Rules, parameters, required features, after-cost historical evidence. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Select version, open experiment/dependency, research configuration change.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/strategy-detail`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing input=INELIGIBLE; sample charts marked synthetic.
- **Specific acceptance tests:** Deterministic rules, parameter bounds, version immutability. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 16. Chart Intelligence — `charts`

- **Group / owning context:** PRIMARY; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** Instrument,MarketCandle,Quote,TechnicalFeature,CorporateAction. Data versions and causal availability timestamps required.
- **Visible components / sections:** Price candles, volume, indicator overlays, timeframe and annotations. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Search canonical symbol, timeframe, zoom and toggle overlays.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/charts`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing history=>empty; stale quote not current; no phantom candle.
- **Specific acceptance tests:** Gap handling, adjusted/raw, session timeline, draw speed. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 17. Decision Explorer — `decisions`

- **Group / owning context:** PRIMARY; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** DecisionLedger,AbstainReason,RiskDecision,Outcome. Data versions and causal availability timestamps required.
- **Visible components / sections:** Chronological proposed/wait/blocked/approved records with evidence. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter symbol/horizon/status; open anatomy.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/decisions`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** No records means true empty; no synthetic cross-contamination.
- **Specific acceptance tests:** Stable pagination, owner-private risk fields. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 18. Decision Anatomy — `decision-detail`

- **Group / owning context:** PRIMARY; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** DecisionSnapshot,FeatureHash,ModelVersion,SourceEvidence,RiskCheck,Outcome. Data versions and causal availability timestamps required.
- **Visible components / sections:** Why-now, counterfactual, uncertainty, model votes and final risk verdict. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Open original source/journal/culprit, compare baseline.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/decision-detail`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing source or hash=>INCOMPLETE; never regenerate with future facts.
- **Specific acceptance tests:** Replayed trace and final risk precedence. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 19. Decision Simulator — `decision-lab`

- **Group / owning context:** PRIMARY; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** FrozenHistoricalCase,ScenarioOverride,RulePolicy,SimulationOutcome. Data versions and causal availability timestamps required.
- **Visible components / sections:** Risk/freshness/model-assumption sliders and hypothetical decision path. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Run bounded research-only scenario; reset to immutable base.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/decision-lab`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Simulated approval not live order approval.
- **Specific acceptance tests:** No side-effects, invariant fuzz, attribution. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 20. Dependency Map — `dependencies`

- **Group / owning context:** PRIMARY; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** DependencyGraph,SourceAvailability,ModelInputs,StrategyFeatureRefs. Data versions and causal availability timestamps required.
- **Visible components / sections:** Graph/matrix, node status, missing providers, conflict/correlation. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Graph toggle, click node, filter by target.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/dependencies`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Broken source propagates unavailable descendants.
- **Specific acceptance tests:** Cycle detection, no infinite graph and node lineage. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 21. Model Laboratory — `models`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** ModelDefinition,ModelVersion,Licence,Evaluation,WorkerReadiness. Data versions and causal availability timestamps required.
- **Visible components / sections:** 18-model registry, family/status filter, challenger leaderboard, cost. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter, open deep dive and run manifest.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/models`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Research/planned adapter not running; fixture metric never verified.
- **Specific acceptance tests:** Exact 18 records, maturity, unpromoted challenger. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 22. Model Deep Dive — `model-detail`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** ModelArtifact,InputSchema,TrainingCutoff,Metrics,Calibration,Drift. Data versions and causal availability timestamps required.
- **Visible components / sections:** Version, digest, training lineage, evaluation, latency, contributions. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Compare benchmark and model/feature history.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/model-detail`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Dormant worker != executing model; no fake metrics.
- **Specific acceptance tests:** Signature, schema compatibility, out-of-sample and licence. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 23. Research & Backtesting — `research`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** ExperimentSpec,DatasetManifest,BacktestRun,JobBudget,WalkForward. Data versions and causal availability timestamps required.
- **Visible components / sections:** Backtests, paper/shadow tabs, queue, experiments, cost and duration. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Create bounded experiment, poll status, cancel authorized job.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/research`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Quota exceeded or source unlicensed => reject.
- **Specific acceptance tests:** Job idempotency, reproducible seed, walk-forward no leakage. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 24. Experiment Results — `experiment-detail`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** ExperimentResult,EquityCurve,TradeLedger,MetricDef,CostAssumptions. Data versions and causal availability timestamps required.
- **Visible components / sections:** Equity/drawdown, win/PF, fees, fold splits, baseline comparisons. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Select comparison, export permissions, inspect methods.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/experiment-detail`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Failed run never appears as profit, missing fold marked.
- **Specific acceptance tests:** Reproduce metrics with input hash, fee fixtures. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 25. Performance & Learning — `performance`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** MarketOutcome,AccountFill,PaperFill,Attribution,Drift. Data versions and causal availability timestamps required.
- **Visible components / sections:** Net performance, separate alpha vs execution quality, drawdown, drift. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter account/room/model and horizon, inspect losing cases.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/performance`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Insufficient samples unavailable; avoid confusing paper and live.
- **Specific acceptance tests:** Fees and cashflow conservation, late labels, snapshot consistency. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 26. Decision Journal — `journal`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** DecisionAudit,RiskAudit,OrderLifecycle,UserAction,SourceRef. Data versions and causal availability timestamps required.
- **Visible components / sections:** Chronological user/engine decisions, rejected orders, incident links. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter/export/redact, inspect decision and original evidence.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/journal`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Do not omit abstentions; no writeable history.
- **Specific acceptance tests:** Append-only, user scope, export controls, trace IDs. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 27. Multibagger Discovery — `multibagger`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** Company,FinancialStatement,Valuation,Governance,PointInTimeUniverse. Data versions and causal availability timestamps required.
- **Visible components / sections:** Company ranking, financial quality, governance flags, valuation, stage. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter sector/stage, open thesis, add private watch.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/multibagger`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** No financial history => insufficient evidence, not score 0.
- **Specific acceptance tests:** Restatements and survivorship, point-in-time valuation. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 28. Investment Thesis — `thesis-detail`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** FinancialFacts,CorporateEvent,ValuationScenario,ThesisRevision. Data versions and causal availability timestamps required.
- **Visible components / sections:** Base/bull/bear, capex, ROCE/FCF, catalysts, falsification and evidence. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Change assumptions, compare versions, annotate privately.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/thesis-detail`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Unverified financial claims labeled; no promise of multibaggers.
- **Specific acceptance tests:** Scenario math, source version, edit owner audit. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 29. Market Regimes — `regimes`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** MarketIndex,IndiaVix,Breadth,RegimePrediction,Quality. Data versions and causal availability timestamps required.
- **Visible components / sections:** Trend/range/risk-off, probabilities, sector performance, strategy eligibility. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Change index/horizon; inspect regime weights.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/regimes`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** UNKNOWN triggers conservative routing and abstention.
- **Specific acceptance tests:** No future info, confidence thresholds, stability. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 30. Events & Knowledge Graph — `events`

- **Group / owning context:** RESEARCH; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** CorporateDisclosure,NewsEvidence,MacroRelease,KnowledgeLink. Data versions and causal availability timestamps required.
- **Visible components / sections:** Events timeline, source-linked graph, impact hypotheses, revisions. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter company/sector, drill to primary source.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/events`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Hypothesis edge must be labeled, original source required.
- **Specific acceptance tests:** Publication cutoff, duplicates and revisions. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 31. Portfolio Digital Twin — `portfolio`

- **Group / owning context:** CAPITAL; owner/account-sensitive plus shared research.
- **Required input DTOs and dependencies:** AccountPortfolio,Position,Cash,Quote,Correlation,ScenarioRisk. Data versions and causal availability timestamps required.
- **Visible components / sections:** Actual holdings, sector weights, cash, stress and risk forecasts. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Choose account, shock simulation, explore correlations.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/portfolio`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Broker stale/absent shows last verified timestamp, no invented NAV.
- **Specific acceptance tests:** Account authorization, P&L reconciliation, stale-quote conservatism. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 32. Risk Center — `risk`

- **Group / owning context:** CAPITAL; owner/account-sensitive plus shared research.
- **Required input DTOs and dependencies:** RiskPolicy,Exposure,BuyingPower,Margin,KillSwitch,Reconciliation. Data versions and causal availability timestamps required.
- **Visible components / sections:** Daily/room budgets, stops, limits, rejection reasons and health. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Propose bounded versioned policy, authorized stop command.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/risk`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing account or reconciliation means BLOCK; local switch not authority.
- **Specific acceptance tests:** Risk outranks intelligence; emergency stop and audit. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 33. Futures & Options — `derivatives`

- **Group / owning context:** CAPITAL; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** DerivativeContract,Eligibility,MarginRequirement,DerivativeCoverage. Data versions and causal availability timestamps required.
- **Visible components / sections:** Futures/options families, product prerequisites, expiry and funding. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Navigate option chain/builder; filter research status.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/derivatives`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing live chain means research unavailable, no executable recommendation.
- **Specific acceptance tests:** Expiry/settlement, product risk, data entitlement. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 34. Option Chain — `options-chain`

- **Group / owning context:** CAPITAL; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** OptionContract,Quote,OpenInterest,IV,Greeks,MarketQuality. Data versions and causal availability timestamps required.
- **Visible components / sections:** Bid/ask, call/put rows, OI, IV, Greeks, strike/expiry selector. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Switch underlying, strike and expiry, open builder.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/options-chain`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Stale/missing leg => no synthetic premium or IV.
- **Specific acceptance tests:** Greeks conventions, lot, spread, time alignment. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 35. Strategy Payoff Builder — `option-builder`

- **Group / owning context:** CAPITAL; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** OptionLeg,ContractMaster,OptionQuote,CostModel,PayoffScenario. Data versions and causal availability timestamps required.
- **Visible components / sections:** Multi-leg structures, payoff max loss/gain, breakeven, costs/margin. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Adjust strikes/qty, add/remove leg, simulate (never order).
- **Suggested initial view contract:** `GET /api/mind/v1/screens/option-builder`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Unknown margin or stale premium => theoretical only.
- **Specific acceptance tests:** Payoff math, fee/expiry checks, max-loss honesty. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 36. Engine Control — `controls`

- **Group / owning context:** SYSTEM; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** EngineHealth,JobState,EmergencyState,GovernancePolicy,Audit. Data versions and causal availability timestamps required.
- **Visible components / sections:** Job/worker health, model controls, readiness, emergency stop, rollback. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Authorized pause research, inspect logs, invoke backend-stop only.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/controls`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Local visual toggle cannot change capital authority.
- **Specific acceptance tests:** RBAC, audit, optimistic versions, stop replay test. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 37. Ask Mintick Mind — `assistant`

- **Group / owning context:** SYSTEM; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** EvidenceIndex,AuthorizedReadAPI,Conversation,AnswerTrace. Data versions and causal availability timestamps required.
- **Visible components / sections:** Ask research questions, sourced answer cards, clickable evidence. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Ask limited queries, inspect linked fact, clear session.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/assistant`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** No verifiable source=>cannot answer; no order or secrets tools.
- **Specific acceptance tests:** Prompt injection/ACL/LLM cost cap/citation truth. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 38. Trading Room Detail — `room-detail`

- **Group / owning context:** SYSTEM; owner/account-sensitive plus shared research.
- **Required input DTOs and dependencies:** RoomConfig,StrategyBinding,CapitalAllocation,DecisionFeed,RoomRisk. Data versions and causal availability timestamps required.
- **Visible components / sections:** Room stack, decisions, room capital, monitor, interlocks and lifecycle. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Open strategy, propose room config and pause/resume with audit.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/room-detail`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** No account/broker=>account capability disabled.
- **Specific acceptance tests:** Room ownership, versioned config and reconciliation. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 39. Visual Mockup Gallery — `design-gallery`

- **Group / owning context:** SYSTEM; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** ApprovedDesignAssets,ThemeTokens,AssetManifest. Data versions and causal availability timestamps required.
- **Visible components / sections:** Nine design references, comparison and status of visual parity. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Select reference, preview, theme change.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/design-gallery`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Missing approved assets shown as missing, not random replacement.
- **Specific acceptance tests:** Asset integrity, accessibility, licensing. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 40. Clickable Design Mode — `design-preview`

- **Group / owning context:** SYSTEM; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** PreviewFixture,ViewportPreset,ThemeTokens. Data versions and causal availability timestamps required.
- **Visible components / sections:** Interactive native preview, mobile layout, navigation and visual tokens. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Choose layout/screen/theme and back gesture.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/design-preview`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** MOCK badge permanent; no real-data or order side effects.
- **Specific acceptance tests:** S23 Ultra and small phone golden, gestures. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 41. Screen & Strategy Coverage — `coverage`

- **Group / owning context:** SYSTEM; shared market/research or system context, with account-scoped overlays only where needed.
- **Required input DTOs and dependencies:** RouteManifest,StrategyRegistry,ModelRegistry,ReadinessMatrix. Data versions and causal availability timestamps required.
- **Visible components / sections:** 42 route readiness, 232 strategy IDs, 18 model records, links. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Filter incomplete, open screen or contract.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/coverage`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Prototype implementation is not backend readiness.
- **Specific acceptance tests:** Inventory counts, missing contract detection. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

### 42. Preferences — `settings`

- **Group / owning context:** SYSTEM; owner/account-sensitive plus shared research.
- **Required input DTOs and dependencies:** OwnerPreference,NotificationPolicy,AccessibilityConfig,ThemeSettings. Data versions and causal availability timestamps required.
- **Visible components / sections:** Theme, notification rules, market timezone, help and settings. Shared shell and states from universal contract apply.
- **Interactions and side effects:** Save preferences via owner-scoped API; theme immediate.
- **Suggested initial view contract:** `GET /api/mind/v1/screens/settings`. This is an aggregation proposal to reconcile against existing controllers; not an implemented endpoint.
- **Fail-closed / edge states:** Offline local pref marked pending; no tokens or secrets stored.
- **Specific acceptance tests:** Cross-user privacy, 200% type, sync and sign-out. Also enforce all common accessibility/security/freshness conditions.
- **Integration sequence:** typed fixture adapter -> server DTO contract -> backend service join -> source/quality integration -> golden/widget/e2e test -> account/market provenance proof -> remove fixture for this widget -> guarded rollout. Never migrate only top-level card while leaving hidden mock metrics behind.

## Cross-screen components and responsibilities

| Component | Required behavior | Primary consumers |
|---|---|---|
| `MindShell` and `MindRouter` | Signed-in navigation, route restoration, breadcrumbs, responsive sidebar, no unintended exit | All 42 |
| `DataFreshnessStrip` | as-of/source/quality; stale override and entitlement | Quote, dashboard, charts, derivatives, account |
| `InstrumentPicker` | Canonical instrument ID; search aliases; de-listed instruments; provider token mapping server-side | Charts, models, evidence, council, chain |
| `TechnicalChart` | Adaptive intervals, load on viewport, gap/split/holiday marks, typed series | Charts, strategy detail, performance |
| `EvidenceLink` | Source document/reference, publication, verified data origin, allowed access | Decision, agent, events, thesis, incident |
| `ModelBadge` | Maturity, artifactual version, health, calibrated/unverified state | Model registry, hub, council |
| `RiskGateBadge` | Java policy decision, reason, ownership and capital authorization; never local-derived | Risk, account, rooms, decisions |
| `PortfolioSummary` | Account-specific valuation with reconciliation age, fees and delayed quote flags | Home, portfolio, rooms |
| `DecisionLineage` | Stable trace id from source to final outcome, no fabricated causal chain | Decision anatomy, journal, Culprit Catcher |
| `RunStatusCard` | Idempotency, pending/unknown/fail/success and bounded queue | Research, shadow, controls |
| `NoDataPanel` | Clear exact absent dependency, retry eligibility, never invent data | All 42 |
| `MarketSourceLegend` | licensed/official/broker, original/adjusted, provisional/final, timestamp conventions | Markets, flow, financials |

## Coverage verification

- Exactly 42 unique route IDs listed and matched against the Flutter route registry at specification authoring.
- There are two different home surfaces: app-home and overview. Home should summarize personal capital, whereas Mind Overview should summarize engine/research health—do not duplicate the Markets page wholesale.
- Static design-gallery/design-preview screens intentionally use local mock assets; their status must not be conflated with live market-data readiness.
- A real API response cannot be considered complete until its data has been traced through its processor into a visible UI component under success, stale and failure conditions.
