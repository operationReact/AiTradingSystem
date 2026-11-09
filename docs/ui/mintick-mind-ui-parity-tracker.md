# Mintick Mind — Native Flutter UI parity tracker

Source of visual truth: user-supplied `MintTick_Mind_Interactive_Prototype(1).html` and the nine original dark near-black MintTick Mind screen mockups. These references are not runtime implementations.

Implementation branch: `feature/minttick-mind-ui-mockups-20261109` (created from `dev`). **Never merge or deploy this work without separate user instruction.**

## Strict constraints

- Flutter-only app changes under `apps/trading_client/`; no Java, Python, exchange feeds, broker APIs, database, Terraform, or production-trading changes.
- All values are generated sample fixtures. Every screen must clearly disclose demonstration data and must never display invented live claims.
- Never make demo controls invoke order submission, broker account mutations, model promotion, or risk-authority endpoints.
- Existing login stays the only login. Route `/mind` is displayed for authenticated customers.
- Visual reference: near-black background, mint-highlight active tabs, consistent cards, readable financial hierarchy, restrained glows, responsive mobile and desktop behavior. No green wash on every element.
- Maintain both light and dark themes in the overall product; the prototype itself is a deliberately dark demonstration design.
- Before claiming pixel parity, capture Flutter golden screenshots at phone widths 360/393/411 and desktop 1440 and compare against the reference at corresponding crop/layout. Source-code compilation or an HTML screenshot alone cannot prove pixel parity.
- Do not modify `frontend/` (legacy React behavior reference).

## UI screen inventory

| Prototype route / surface | Current Flutter stage | Expected next work |
|---|---|---|
| Mind Overview (`overview`) | **Initial native screen committed** | Pixel-level review + golden tests |
| Strategy Universe (`strategies`) | Basic searchable native registry committed | Family views, status/sort/filter, performance, detail |
| Strategy Detail (`strategy-detail`) | Basic details sheet | Full charts, parameters, dependencies |
| Model Laboratory (`models`) | Basic browsable native registry committed | Leaderboard, champion comparisons, health |
| Model Deep Dive (`model-detail`) | Basic details sheet | Provenance, drift, calibration, distributions |
| Decision Explorer (`decisions`) | Basic decision feed committed | Timeline, advanced filters, explanations |
| Decision Anatomy (`decision-detail`) | Basic details sheet | Evidence tree, counterfactuals, outcome tracing |
| Chart Intelligence (`charts`) | Not started | OHLC chart, timeframes, transformed charts, signals |
| Decision Simulator (`decision-lab`) | Not started | Risk/market/quality controls and abstention |
| Dependency Map (`dependencies`) | Not started | Interactive strategy/model/filter/risk graph and matrix |
| Research & Backtesting (`research`) | Not started | Backtest/paper/shadow/walk-forward modes |
| Experiment Results (`experiment-detail`) | Not started | Equity curve, complete evidence, comparisons |
| Performance & Learning (`performance`) | Not started | Model/strategy/portfolio attribution, decay, drift |
| Decision Journal (`journal`) | Not started | Immutable-looking mock decision timeline and exports |
| Multibagger Discovery (`multibagger`) | Initial research cards accessible from Overview | Screening, rankings, scores, deep review |
| Investment Thesis (`thesis-detail`) | Basic scenario detail sheet | Growth/quality/valuation, bull/base/bear, red flags |
| Portfolio Digital Twin (`portfolio`) | Not started | Exposure, allocation, hypothetical shock/holdings |
| Risk Center (`risk`) | Not started | Limits, vetoes, loss states, no-order controls |
| Engine Control (`controls`) | Not started | Mock switches, participation, emergency stop |
| Market Regimes (`regimes`) | Not started | Regime matrix, gating, indicators |
| Events & Knowledge Graph (`events`) | Not started | Corporate/news/macro event connections |
| Trading Rooms (`trading-rooms`) | Not started in Mind mock workspace | Room overview, statuses, mode |
| Trading Room Detail (`room-detail`) | Not started in Mind mock workspace | Room controls and evidence |
| Futures & Options (`derivatives`) | Not started | Mock derivatives catalogue, separate non-executable mode |
| Option Chain (`options-chain`) | Not started | Mock expiry/strike/OI/IV/Greeks |
| Options Payoff Builder (`option-builder`) | Not started | Mock spreads, payoff, max loss/margin |
| Ask Mintick Mind (`assistant`) | Not started | Safe read-only sample questions and explanations |
| MintTick Home (`app-home`) | Existing production page, not modified | Mind entry deep link evaluation |
| Visual Mockup Gallery (`design-gallery`) | Not started | Display nine design-reference images |
| Clickable Design Mode (`design-preview`) | Not started | Original reference hotspots parity on Flutter |
| Coverage Explorer (`coverage`) | Not started | Family/model/route inventory |
| Preferences (`settings`) | Existing production page unchanged | Mind-specific mock settings later |

## Strategy and model completeness

The mock data layer holds **232 uniquely numbered strategy templates across 20 families** copied from the source prototype's named families and templates:
Trend Following; Momentum; Breakout; Mean Reversion; Price Action & Candlesticks; Volume & Liquidity; Volatility; Statistical Arbitrage; Event Driven; Fundamental & Multibagger; Factor & Portfolio; Market Regime & Macro; Market Microstructure; AI & Machine Learning; Futures; Options — Directional; Options — Volatility; Options — Hedging; Short Selling; Execution & Risk.

It also holds **18 named model records**, with research/planned/adapter statuses so mock data never implies actual live capability.

**Important:** Complete catalogue coverage does not imply all 232 strategies are implemented as execution algorithms. Source HTML performance numbers and illustrations are fictional.

## Screen-by-screen release gates

Each screen must have: (1) visual reference and Flutter widget mapping; (2) mock-data completeness; (3) user interactions and drill-down/back navigation; (4) mobile 360/393/411 and desktop responsive checks; (5) no trading/backend calls; (6) widget and golden tests; (7) explicit review/approval recorded before marking completed.

**Current verified scope:** GitHub commits and route/fixture source review only. Flutter analyzer, widget tests, native builds, screenshots and device runtime have **not** been run in this environment; status remains UNVERIFIED for those gates.
