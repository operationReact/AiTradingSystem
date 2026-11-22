# MintTick F&O Trading Desk — mobile-first Flutter product contract (2026-11-22)

## Product decision and truthfulness

MintTick must have a compact professional trading desk, not a visual clone of Zerodha Kite, Dhan or Sensibull. Keep the approved **near-black flat** palette, restrained green, small dense tiles, no gradients, no decorative glow, no duplicate navigation bars, and the existing MintTick identity. Android, iOS, and Flutter web share the same domain/API contract. Strategy choice remains **Mintick Mind automatic by room mandate**; never add a required manual 43-strategy picker.

**Research implemented is not execution-ready.** Actual owner PAPER and LIVE Mind flows currently support only STR-181 (long CE) / STR-182 (long PE). The existing broker LIVE coordinator is a manually confirmed, limited NIFTY long-option pilot. Other strategy groups require independent futures, multi-leg, margin, portfolio exposure, expiry and fill reconciliation work. Historical independent after-cost market E2E, PAPER lifecycle E2E, and LIVE broker E2E remain 0/43 certified; no UI claim may change these facts. Do not enable orders or rights by showing a control.

This ADR governs UI states/options, not provider account entitlements or a release decision. Current backend endpoints may support a **subset** of the target functionality; a missing endpoint must render explicit disabled or unavailable state, not mock market data.

## Navigation, one mobile bottom bar

1. **Home** — compact account/net exposure, rooms, market status, recent verified Mind decisions; do not duplicate Markets.
2. **Markets** — Equity and F&O segmented. F&O opens the source-led *Discover Desk*; room selection is optional for market viewing.
3. **Rooms** — user-created Equity-Intraday / Equity-Swing / F&O-Intraday / F&O-Positional, renamed any time. Room risk preference is set once per room. No per-trade strategy/risk sliders; no F&O orders from Equity room or vice versa.
4. **Mintick Mind** — Opportunities, Decision History, Risk, Evidence, PAPER lifecycle; the model chooses strategy within room mandate. Status must be separate for evaluator, source rights, historical proof, PAPER and LIVE readiness.
5. **Portfolio** — PAPER/LIVE portfolios are visually distinct, with reconciliation states, open exposures, broker-vs-local holdings and orders/alerts.

Do not add a second nested bottom bar. Use a compact sub-tab strip inside F&O: **Chain | Insights | Movers | Trade** (first code slice completed in this PR). Advanced mode adds underlying-specific **Chart**, **Positions**, **Risk**, **Orders** within the selected room, not one screen with seven oversized cards.

## Markets → F&O Discover Desk

| Surface | Behavior | Evidence gate |
| --- | --- | --- |
| Source ribbon | Provider name; stream connection; exchange timestamp and age; market OPEN/CLOSED/STALE; contract-discovery/display-rights state | Never infer LIVE from WebSocket connection alone |
| Underlying | NIFTY first. Add BANKNIFTY, FINNIFTY, MIDCPNIFTY and stock F&O **only** when actual backend catalog + rights + listing support them | No fake index/contract |
| Expiry selector | Exchange/provider verified listed dates; filter expired; explicit empty/error and last-known informational state | No synthetic weekly/monthly expiry |
| Chain | CE metrics \\| strike \\| PE metrics, ATM estimate clearly labelled *estimate* when true spot unavailable, sticky column headings on wider screens, ITM/ATM tint, strike search/near-ATM and all strikes | Real bid/ask/price/OI and same-leg timing |
| Strike details | Tap any leg: identity, lot/tick, bid/ask, LTP, exchange/receive time, market depth, OI and Greeks only when genuinely sourced; explicit unavailable rows | No zeros as placeholders for missing Greeks |
| Insights | Call OI, put OI and returned-strikes PCR only if *all* returned CE/PE OI rows are fresh; never label sampled data market-wide. When incomplete, show missing-data explanation | No pseudo-signal derived from one fresh row |
| Movers | Previous-close % move from fresh timestamped LTP; no invented momentum/volume rankings | Explicit source and calculation period |
| Trade | Route to selected F&O PAPER/LIVE room with server readiness and explicit intent; discovery does not place orders | Broker order path separate from research |

## Room F&O terminal (priority after discover)

Top bar: `PAPER` or `LIVE` prominent and persistent, broker account/source status, underlying, expiry, refresh age and kill-switch state. Below: compact ticket next to chart/chain in tablet/web; mobile enters bottom-sheet ticket from a leg tap. **PAPER/LIVE must never be inferred from color alone.**

- **Order preview**: exact listed instrument, CE/PE, expiry, side, lots, units, bid/ask, limit, estimated gross premium, separately itemized fees/taxes/slippage estimate, current margin, position/exposure impact, room-level risk verdict, idempotency key and venue. Confirmation uses explicit `Submit PAPER` or `Confirm REAL order` wording. Do not support one-tap LIVE entry by accident.
- **Multi-leg basket** (target, NOT implemented): each leg's direction, price and OI/depth, individual fill state, target payoff, max loss when *bounded and proven*, cost, margin, atomicity caveat, partial fills and safe rollback. Never claim exchange-atomic basket semantics.
- **Payoff** (target, NOT implemented): expiration P&L curve, break-even(s), max loss/profit only if mathematically bounded, Greeks by underlying change, fees and separate pre-expiry scenario stress. Research view does not submit orders.
- **Futures** (target, NOT implemented as live OMS): separate FUT instrument catalogue, near/far expiry, basis, OI, cost of carry, cash vs futures clocks, margin and rollover; no automatic substitution with option contracts.
- **Positions/Orders**: PAPER/LIVE provenance, group by room and strategy/candidate, P&L marked source/time, broker order ID, pending/partial/rejected/UNKNOWN, reconciliation and manual operator recovery. Never retry UNKNOWN automatically. Exit, hedge, roll available only where backend supports audited flows.

## Mintick Mind interaction

A research signal is a proposal only. Show one compact opportunity row per **eligible** room candidate: underlying, strategy name (read-only), reason, evidence freshness, estimated cost, risk verdict, confidence *only if calibrated from verified data*, and `Review`. Never display 43 invented profitable signals.

Stage labels are independent: `Evaluator wired` → `Inputs verified` → `Historical accepted` → `PAPER passed` → `LIVE broker eligible`. The global counter can remain 43/43 evaluator, 0/43 certified in other tiers. Each blocked state must carry a real backend reason and next action.

Room risk defaults are engine-owned; user can choose room-level **Conservative/Moderate/High** but cannot override hard exposure, margin, market-hours, stale-source, expiry, drawdown, account or compliance limits. Naked/unbounded-risk structures must never silently gain LIVE permissions just because the strategy evaluator exists.

## Chart specification

Candle chart uses historical source-confirmed OHLCV only, with timeframes 5m/15m/1H/1D where the server has genuine candles. Show price pane with last-price line and independent movable crosshair; below, separate volume, RSI and MACD panels; zero-centred MACD histogram plus signal/line legends and non-overlapping axis labels. Data gaps appear as gaps, not drawn flat lines or placeholders. Keep chart/timeframe selection after navigation; redraw safely on orientation and app resume, and do not refresh the live marker from historical candles. Validate red/orange study lines and horizontal crosshair behavior through Flutter screenshots + gesture regression, not React screenshots.

## Quality gates

- Compact 360/375/411 dp mobile, 768 tablet and 1024/1440 web layouts; text scaling, TalkBack, keyboard focus, safe-area, contrast and touch targets.
- Flutter unit/widget tests for source freshness, missing OI/IV/Greeks, chain completeness, market closed, error/UNKNOWN, source/permission change, PAPER/LIVE modality and real data bounds. Same API semantics on Android, iOS and web.
- Real backend/PAPER host E2E, signed Android device, iOS simulator/device, Flutter web runtime and visual regression against approved Figma and latest screens are all separate acceptance evidence.
- No UI-only PR claims to complete network, broker rights, profitability, real-market E2E or LIVE activation.

## External patterns considered

Official Zerodha Kite option-chain and terminal-mode docs (option chain, market depth, basket order workflow), Dhan Options Trader advanced chain/strategy builder/payoff documentation, Sensibull position/strategy payoff documentation, and TradingView Supercharts indicator/drawing tools. These are capability inspiration, not copied designs or permission to source market data.

Current docs: https://support.zerodha.com/category/trading-and-markets/trading-faqs/f-otrading/articles/option-chain-on-kite , https://dhan.co/options-trader/ , https://sensibull.com/ , https://www.tradingview.com/support/solutions/43000746464-getting-started-with-supercharts/
