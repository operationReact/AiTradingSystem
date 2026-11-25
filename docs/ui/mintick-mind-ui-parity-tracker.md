# Mintick Mind — Flutter UI implementation and parity tracker

**Branch:** \`feature/minttick-mind-ui-mockups-20261109\`  
**Base:** forked from `dev` at `af9417aba64ede259397a527b4b2c0d5b9b6e063`; PR #657 targets current `dev`.
**UX source:** original `MintTick_Mind_Interactive_Prototype(1).html`, enhanced `MintTick_Mind_Agent_Enhanced_Clickable_Prototype.html`, and nine visual reference mockups.
**Technology:** Flutter under \`apps/trading_client\`; never modify the historical React product reference.  
**Merge:** user authorized merge into `dev` after CI on 2026-11-09. **Deployment:** not requested. **Execution:** demo UI and mock backend have no trading authority.

## Implemented mock UI scope

All **42 named routes** from the enhanced prototype (32 original + 10 agent additions), plus **one new research-engine planning screen** (43 total), have Flutter screen definitions with a navigable shell. This is **functionally mapped and source-committed**, not yet pixel-certified. The screens use shared near-black/mint cards, text, diagrams, compact charts, metric grids, menus, navigation, dialogs, tabs, filters, buttons and hypothetical data.

| Route | Current Flutter screen coverage | UI parity gate |
| --- | --- | --- |
| app-home | Personal Mind landing + original MintTick home link | Golden pending |
| overview | Approved first-pass Mind overview embedded in native shell | Golden pending |
| trading-rooms | Mock room registry, statuses | Golden pending |
| room-detail | Room allocations, strategies, decisions | Golden pending |
| strategies | Search, 20-family filter, horizons, all 232 catalogue entries | Golden pending |
| strategy-detail | Research metrics, dependencies, lifecycle, drilldowns | Golden pending |
| models | 18-model registry, role filters, challenger comparison | Golden pending |
| model-detail | Metrics, calibration, drift, history, contribution | Golden pending |
| charts | Instruments, timeframes, nine chart-type selectors, signal stack | Golden pending |
| decisions | Decision category filters and timeline | Golden pending |
| decision-detail | Reasoning, evidence, risk, counterfactuals, outcomes | Golden pending |
| decision-lab | Adjustable agreement/freshness/risk simulation | Golden pending |
| dependencies | Graph/matrix, conflicts, correlated evidence, portfolio links | Golden pending |
| research | Backtest, paper, shadow, walk-forward tabs and experiments; planned-engine entry point | Golden pending |
| research-engines | 5 external research candidates with NOT CONNECTED badges, readiness checklist, source-file references, proposed PRs, and navigation; no installed backtester or broker execution | New 43rd screen; golden pending |
| experiment-detail | Hypothetical performance and methodology | Golden pending |
| performance | Attribution, drawdowns, drift and learning links | Golden pending |
| journal | Filterable decision audit timeline and mock export control | Golden pending |
| multibagger | Screening, stages, company research scores and discovery queue | Golden pending |
| thesis-detail | Thesis, hypothetical return cases, risks and watch state | Golden pending |
| portfolio | Exposure, capital allocation and scenario slider | Golden pending |
| risk | Risk limits, simulated emergency-stop and eligibility checks | Golden pending |
| regimes | Market posture, regime weights and strategy activation guidance | Golden pending |
| events | Event timeline and cross-sector effect hypothesis graph | Golden pending |
| derivatives | Futures, options and short-selling research inventory | Golden pending |
| options-chain | Strike/underlying selector, chain, IV/OI/Greeks samples | Golden pending |
| option-builder | Structure switch, strike slider and payoff diagram | Golden pending |
| controls | Local simulation toggles, kill switch, no live order capability | Golden pending |
| assistant | Scripted read-only research question/answer interface | Golden pending |
| design-gallery | Nine clickable **Flutter recreation thumbnails** | Original bitmaps not embedded |
| design-preview | Native responsive phone preview mapped to a target screen | Bitmap/pixel parity pending |
| coverage | Cross-linked 43-route (42 prototype + 1 planning), 232-strategy, 18-model coverage inventory | Golden pending |
| settings | Mind-only display and mock preference controls | Golden pending |
| intelligence-hub | Source-to-decision pipeline, horizon policy, capital veto | Golden pending |
| agent-council | Bull/Bear/Fundamental/Flow/Critic debate with symbol and vote filter | Golden pending |
| agent-evidence | Evidence ledger, timestamps, status and category filters | Golden pending |
| regime-weights | Five market regimes, weighting changes, unknown-data abstention | Golden pending |
| signal-scorecard | Horizon-specific mock performance metrics and restrictions | Golden pending |
| confidence-lab | Reliability bins, sample labels, missing calibration warning | Golden pending |
| shadow-arena | Shadow vs baseline comparisons, counterfactual tabs, no fills | Golden pending |
| institutional-flow | Mock FII/DII flow with official-source-unavailable warning | Golden pending |
| daily-verdict | Date-selectable market verdict and non-executable watchpoints | Golden pending |
| culprit-catcher | Four example incidents, timeline, causes and preventive study links | Golden pending |

## Strategy and model coverage

- The source prototype's **232 named strategy templates across 20 families** are encoded in \`data/mind_fixtures.dart\`, each with a unique ID. Their catalogue presence is not the implementation of live trading algorithms.
- The **18 model records** retain research/planned/advisory roles. No model result is represented as a verified live inference.
- The app explicitly distinguishes research, paper, shadow, and disabled derivative execution. No new remote calls are imported into this feature.
- Nine chart presentation options are selectable. The early mock chart painter is **illustrative only**; it is not a real OHLCV-to-Renko/Heikin-Ashi/Point-and-Figure conversion engine.
- F&O payoff and chain visuals are simplified fictional approximations, not a broker risk calculator. Margin/fees/assignment/exercise are not production simulated.
- The original source's nine PNG reference images are **not embedded** in this branch. Gallery previews are recreated with Flutter widgets, not actual screenshot assets. Therefore the claim of an exact pixel match would be incorrect.

## Files and ownership

\`lib/src/app.dart\`: single authenticated \`/mind\` entry and route-specific navigation isolation.  
\`lib/src/features/mintick_mind/data/mind_fixtures.dart\`: mock registry, names and statuses.  
\`lib/src/features/mintick_mind/presentation/mind_widgets.dart\`: scoped visual tokens and widgets.  
\`lib/src/features/mintick_mind/presentation/mintick_mind_page.dart\`: original first-pass Overview and sub-tabs.  
`lib/src/features/mintick_mind/presentation/mind_workspace_page.dart`: responsive navigation and 43-route dispatch (42 approved prototype routes + Research Engines Lab).
\`lib/src/features/mintick_mind/presentation/mind_analytics_screens.dart\`: strategy, model, chart, decision and dependency surfaces.  
\`lib/src/features/mintick_mind/presentation/mind_investment_screens.dart\`: discovery, thesis, portfolio, risk, regimes, events.  
\`lib/src/features/mintick_mind/presentation/mind_research_screens.dart\`: experiments, journal, performance, F&O.  
\`lib/src/features/mintick_mind/presentation/mind_system_screens.dart\`: rooms, controls, assistant, gallery, preferences, coverage.  
\`test/mintick_mind_mock_ui_test.dart\` and \`test/mintick_mind_workspace_coverage_test.dart\`: UI/data tests, **not executed here**.

### Enhanced agent UI and mock backend

- `lib/src/features/mintick_mind/data/mind_agent_fixtures.dart`: deterministic votes, evidence, incidents, regimes, calibration and institutional flow samples.
- `lib/src/features/mintick_mind/presentation/mind_agent_screens.dart`: ten Flutter screen implementations.
- `backend/src/main/java/in/aitradingsystem/api/MindDemoController.java`: read-only authenticated `/api/mind/demo/v1/*` contract; inactive unless Spring profile `mind-demo` is enabled.
- `backend/src/test/java/in/aitradingsystem/api/MindDemoControllerTest.java`: demo, verification and no-execution assertions.
- Flutter UI continues to use local fixtures. The demo REST contract is not called automatically; future service integration will be a separately validated change.

## Strict guardrails

1. Only same-session authenticated access. No additional admin account.
2. All numbers, signals, scores, returns and business scenarios are mock data.
3. Java risk authority, broker credential handling, real market APIs, Python research, authentication and actual execution are unchanged. Only an opt-in read-only demonstration controller/test was added.
4. UI toggles never grant live capital or change backend settings. No order methods, no offline order replay.
5. Derivative execution disabled regardless of displayed option choices.
6. Preserve existing MintTick app pages and navigation; the new workspace is an isolated authenticated `/mind` route.
7. Do not claim Pixel PERFECT, VERIFIED runtime or finished 3-platform acceptance until evidence exists.

## Remaining acceptance gates

- Run \`flutter analyze\`, \`flutter test\`, then Flutter web/Android/iOS builds and platform smoke tests.
- Render 360/393/411 logical-width mobile and desktop 1440 screens, check overflowing rows, navigation, accessibility, text scaling and touch target sizes.
- Review visual screenshots against the nine original references; adjust fonts, cards, chart density, layout, spacing, stroke widths and motion until approved.
- For the design-gallery screen, add approved source PNG assets as real Flutter assets in a separate UI-only change if requested.
- Run route-by-route interaction verification and certify explicit mock-only enforcement.
- Verify the diff includes only Flutter/client mock UI, the explicit profile-gated Java mock controller/tests, and documentation. Check the branch against current `dev` and merge only when CI confirms no conflicts.

**Current evidence:** GitHub source commits, 43-route static inventory (42 prototype + 1 planning), backend DTO fixtures and tests added. **Flutter runtime evidence: UNVERIFIED** until CI/platform builds; no device visual parity is certified.


## Five external research repository placeholder change (2026-11-09)

- New `research-engines` screen: single integration planning cockpit; no five separate apps/screens. All statuses explicitly **PLANNED / NOT CONNECTED** and **NO BROKER / ORDER AUTHORITY**.
- `mind_research_engine_fixtures.dart`: five static records for VectorBT (pilot), Backtesting.py (validator), NautilusTrader (advanced simulation), QuantConnect LEAN (architecture benchmark), Freqtrade (research patterns); each includes licence, tasks/PR plan, blockers, upstream files, proposed MintTick touchpoints, affected screen IDs. `connected` and `orderAuthority` always false.
- `mind_research_engine_screens.dart`: interactive filtering, candidate details, uncompleted prerequisite list and clickable in-app navigation, with zero network/broker/model dependencies.
- Entry links placed on Research & Backtesting, Model Laboratory, Strategy Detail, Experiment Results and Coverage. Mock-only status is visible; no fabricated performance or success claims added.
- `mintick_mind_research_engines_ui_test.dart`: validates five records, disabled authority, route/links and candidate selection; existing 42-route assertions updated to 43. Future CI/device verification evidence needed before marking release-ready.
- Linked Notion implementation annex: https://app.notion.com/p/41336a828cb6815dab4eca25bc04f27a?pvs=204, planning IDs OSS-01 through OSS-14.
- The existing 42 screens' original identities and user flows are preserved. This enhancement is a planning placeholder only; implementation work is still governed by external library licensing, source data, parity, resource budget and Java execution safety gates.
