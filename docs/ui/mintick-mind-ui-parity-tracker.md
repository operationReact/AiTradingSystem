# Mintick Mind — Flutter UI implementation and parity tracker

**Branch:** \`feature/minttick-mind-ui-mockups-20261109\`  
**Parent:** \`dev\` commit \`af9417aba64ede259397a527b4b2c0d5b9b6e063\`  
**Source of product UX:** user-uploaded \`MintTick_Mind_Interactive_Prototype(1).html\` and nine Mintick Mind mockup screenshots.  
**Technology:** Flutter under \`apps/trading_client\`; never modify the historical React product reference.  
**Merge / deployment permission:** **NOT GRANTED**. Do not merge into \`dev\`, alter backend resources, deploy AWS, or submit orders.

## Implemented mock UI scope

All **32 named routes** from the prototype have Flutter screen definitions with a navigable shell. This is **functionally mapped and source-committed**, not yet pixel-certified. The screens use shared near-black/mint cards, text, diagrams, compact charts, metric grids, menus, navigation, dialogs, tabs, filters, buttons and hypothetical data.

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
| research | Backtest, paper, shadow, walk-forward tabs and experiments | Golden pending |
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
| coverage | Cross-linked 32-screen, 232-strategy, 18-model coverage inventory | Golden pending |
| settings | Mind-only display and mock preference controls | Golden pending |

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
\`lib/src/features/mintick_mind/presentation/mind_workspace_page.dart\`: responsive navigation and 32-route dispatch.  
\`lib/src/features/mintick_mind/presentation/mind_analytics_screens.dart\`: strategy, model, chart, decision and dependency surfaces.  
\`lib/src/features/mintick_mind/presentation/mind_investment_screens.dart\`: discovery, thesis, portfolio, risk, regimes, events.  
\`lib/src/features/mintick_mind/presentation/mind_research_screens.dart\`: experiments, journal, performance, F&O.  
\`lib/src/features/mintick_mind/presentation/mind_system_screens.dart\`: rooms, controls, assistant, gallery, preferences, coverage.  
\`test/mintick_mind_mock_ui_test.dart\` and \`test/mintick_mind_workspace_coverage_test.dart\`: UI/data tests, **not executed here**.

## Strict guardrails

1. Only same-session authenticated access. No additional admin account.
2. All numbers, signals, scores, returns and business scenarios are mock data.
3. Java risk authority, broker credential handling, real market APIs, Python research, authentication and actual execution are unchanged.
4. UI toggles never grant live capital or change backend settings. No order methods, no offline order replay.
5. Derivative execution disabled regardless of displayed option choices.
6. Preserve existing MintTick app pages and navigation; the new workspace is an isolated route.
7. Do not claim Pixel PERFECT, VERIFIED runtime or finished 3-platform acceptance until evidence exists.

## Remaining acceptance gates

- Run \`flutter analyze\`, \`flutter test\`, then Flutter web/Android/iOS builds and platform smoke tests.
- Render 360/393/411 logical-width mobile and desktop 1440 screens, check overflowing rows, navigation, accessibility, text scaling and touch target sizes.
- Review visual screenshots against the nine original references; adjust fonts, cards, chart density, layout, spacing, stroke widths and motion until approved.
- For the design-gallery screen, add approved source PNG assets as real Flutter assets in a separate UI-only change if requested.
- Run route-by-route interaction verification and certify explicit mock-only enforcement.
- Verify the commit diff continues to include **only Flutter/client UI, tests and documentation**, and remains unmerged.

**Current evidence:** GitHub source commits, static route inventory and delimiter checks. **Flutter runtime evidence: UNVERIFIED** (SDK unavailable in current session).
