# Mintick Mind — Research Engine Lab UI Placeholder Contract (43rd route)

**Decision:** Keep the approved 42 prototype screens unchanged, and add one optional **Research Engines Lab** route (`research-engines`, group RESEARCH) for five assessed open-source research frameworks. Total **43 navigable mock routes**, but 42 core prototype routes remain. This is a planning UI, not an operational market feed, API or trade-enabled module.

**UI PR:** https://github.com/operationReact/AiTradingSystem/pull/660 (status and CI require rechecking).  
**Notion implementation annex:** https://app.notion.com/p/41336a828cb6815dab4eca25bc04f27a?pvs=204  
**Related design references:** [All 42 core screen contracts](SCREEN_CONTRACTS_42.md), [five repository integration runbook](THIRD_PARTY_RESEARCH_ENGINES.md), [market schema](DATA_ARCHITECTURE_SCHEMA.md).

## 1. Why one new screen, not five

VectorBT, Backtesting.py, NautilusTrader, LEAN and Freqtrade are alternative research/validation tools. They are not five new trading apps, five new trading-room types, five independent market-data providers, or five user accounts. One research integration catalogue is adequate; core decisions remain produced by Mintick Mind, not by screen-mounted engines.

## 2. Exact screen contract

| Widget | Current UI source | Later real source | Acceptance |
|---|---|---|---|
| Heading and alert | Flutter static fixture; text `PLANNED · NOT CONNECTED` and `NO BROKER / ORDER AUTHORITY` | Server research-engine read projection | Never show operational status from a local bool |
| Summary | 5 reviewed, 0 connected, VectorBT first pilot, no order authority | Registry health with signed runtime evidence | Cannot infer installed from GitHub stars or fixture |
| Filter chips | All / Pilot / Validator / Advanced / Reference / Patterns | Typed enum | Works in 360dp and desktop, no hidden action |
| Candidate list | 5 vetted, disabled records | Only server-authorized integrations | Select changes details; doesn't launch a job |
| Selected candidate | Role, rationale, OSS-PR reference, licence and zero verified performance | Licence and runtime health; measured job outputs | No fabricated Sharpe, CAGR or claims |
| Prerequisite checklist | Static, unchecked tasks | Evidence-backed checklist/status | No local tapping to mark production ready |
| Upstream files | Literal reviewed repo path examples | Pinned source commit and signed dependency audit | File link is evidence of path only, not security or licence |
| MintTick touchpoints | Existing services plus clearly proposed file paths | Exact PR hashes, OpenAPI and job trace IDs | Proposed paths not displayed as implemented |
| Linked Mind screens | Existing route IDs | Read-only typed model/research products | Navigation only; no broker side effects |

Current fixture fields: `id, name, category, role, decision, license, plannedPrs, prerequisites, sourceFiles, mintTickTouchpoints, targetScreens`; `connected=false`, `orderAuthority=false`. All data are presentation-only, no Java/Python call.

## 3. Screen-specific entry points

- **Research & Backtesting:** "External research engines · roadmap" with PLANNED status and link to Lab; eventually expose engine/fold/cost/discrepancy in actual Experiment Details.
- **Model Laboratory:** "Research tool integrations" link. Distinguish an engine adapter from the 18 model records; VectorBT is not model #19.
- **Strategy Detail:** "Research engine benchmarks" link from Workflows; use strategy version and experiment result fingerprints in future.
- **Experiment Results:** "External validation engines" link; future comparison displays matched runs only (same dataset, strategy, fees, session and splits).
- **Screen & Strategy Coverage:** route count 43 = 42 original + Lab, and entry "5 assessed · 0 connected".
- **Optional future detail overlays, only after OSS-12/13:** Shadow Arena shows Nautilus event-fill fidelity if verified; Culprit Catcher links model-vs-execution discrepancy; Confidence Lab retains calibration; Decision Simulator shows baseline vs alternative assumptions. No additional full-screen routes necessary yet.

## 4. Proposed production DTO (NOT implemented)

```json
{
  "contract_version": "research-engine-integration-status.v1",
  "engine_id": "VECTORBT",
  "adoption_decision": "PILOT|VALIDATOR|ADVANCED|REFERENCE|PATTERNS",
  "status": "NOT_CONNECTED|LICENSE_REVIEW|BUILD_READY|RESEARCH_ONLY|UNAVAILABLE",
  "source_commit": "verified-sha-or-null",
  "package_digest": "verified-wheel-or-container-hash-or-null",
  "license_verdict": "pending-or-approved-version",
  "runtime_last_verified_at": null,
  "data_entitlement_status": "PENDING",
  "benchmark_result_id": null,
  "blocking_reasons": [],
  "can_submit_broker_order": false,
  "can_authorize_capital": false
}
```

Future source: existing `ExperimentJobOrchestrationService` and `BacktestComparisonService` plus a new read-model adapter, only after OSS-02/12. No direct third-party SDK imports in Flutter. No new database migration automatically implied; inventory existing V1–V71+ before proposing one.

## 5. Exact source files affected in UI PR

- `apps/trading_client/lib/src/features/mintick_mind/data/mind_research_engine_fixtures.dart` — static five-record registry.
- `apps/trading_client/lib/src/features/mintick_mind/presentation/mind_research_engine_screens.dart` — complete Lab screen; detail selection/filter and links, no network/broker dependencies.
- `mind_workspace_page.dart` — 43rd route, RESEARCH group, dispatch and local selection state.
- `mind_analytics_screens.dart`, `mind_research_screens.dart`, `mind_system_screens.dart`, `mind_agent_screens.dart` — visible entry tiles and coverage count.
- `apps/trading_client/test/mintick_mind_research_engines_ui_test.dart` — fixture, no-trade and UI navigation tests.
- `mintick_mind_workspace_coverage_test.dart`, `mintick_mind_agent_ui_test.dart` — revised 43 route invariant.
- `docs/ui/mintick-mind-ui-parity-tracker.md` — UI traceability and future validation.

## 6. Activation/closure by ordered PR dependency

1. **OSS-01–04:** Review all five repo licences, pinned versions, Python ARM64 and data/pit-cost parity contracts. Enable no external runtime during this phase.
2. **OSS-05–06:** Optional VectorBT pilot and 10–20 representative strategies; only source-verified results, no generic live inference.
3. **OSS-07–08:** Backtesting.py validation if legally suitable and original Freqtrade-inspired checks.
4. **OSS-09–11:** Nautilus and LEAN reference research only after measured need and approved source licences.
5. **OSS-12:** Backend read-only registry status and job result API, audit and immutable fingerprints.
6. **OSS-13:** Replace local fixture status with actual backend status, explicit stale/offline/error states and verified bench comparison UI; never shadow synthetic results.
7. **OSS-14:** Test cost, RAM, latency, order isolation, signed data and broker bans; verify UX with Flutter golden and physical Android captures.

**Safety release gate:** The UI is never a backtest execution authority; it cannot authorize Java risk, model promotion or broker orders. A connected state requires signed backend evidence plus production deployment confirmation, not simply passing Flutter CI.

## 7. Tests and readiness states

- 43 unique route IDs; preserve the first 42 route contracts unchanged.
- Exact five candidate names, nonempty licence/dependencies/PR references and existing linked route IDs.
- All five `connected=false`, `orderAuthority=false` in fixtures; reject misleading available/performance claims.
- Desktop and small Android screens allow scrolling/filter selection without RenderFlex overflow or off-screen untappable cards.
- Verify navigation from Research, Model Laboratory, Strategy Detail, Experiment Results and Coverage.
- Original feature/client tests and security/baseline gates remain green.
- CI green does **not** prove APK installed on device, backend deployed, upstream engine installed, licence verified or any profitability.

## 8. Done / Not done

UI source and Notion linkage were prepared in PR #660. This is **implementation planning visibility**, not fulfillment of OSS-01–14. Update this document with actual CI/merge evidence after checks pass; keep the five integrations disabled until individually approved and benchmarked.
