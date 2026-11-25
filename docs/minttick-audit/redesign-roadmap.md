# MintTick — Redesign Roadmap

Prioritization: trading risk first, then task completion, then consistency, then aesthetics. Estimates are relative (S ≤ 3 days, M ≤ 2 weeks, L ≤ 6 weeks) for one Flutter engineer, excluding review and device-lab time. These are assumptions to validate.

Governance (AGENTS.md): every Flutter UI item needs web, Android, and iOS build/runtime evidence. Backend-only changes need a client-impact assessment. Do not mark a phase VERIFIED from analyze or a green build alone.

## Phase 0: Safety and honesty fixes

| ID | Item | Issues | Size | Depends on |
|---|---|---|---|---|
| P0-1 | Execution-state banner; remove LIVE CTA as primary; neutral wording for LIVE planning | UX-01, UX-02 | S | Backend readiness endpoint |
| P0-2 | Remove or correct the static Mind status card | UX-09 | S | none |
| P0-3 | Fix "Mantic" naming | UX-14 | S | none |
| P0-4 | Remove third-party favicon fetch | UX-05 (privacy) | S | Logo source decision |
| P0-5 | Fix paper copy vs backend contradiction (trace route first) | UX-17 | S | Route trace |

**Acceptance:** no string or widget claims LIVE execution is active; no static Mind status; no third-party request during market browsing; grep finds no "Mantic".

## Phase 1: Trust-first data presentation

| ID | Item | Issues | Size | Depends on |
|---|---|---|---|---|
| P1-1 | Status chips and freshness on every price surface | UX-07, UX-24 | M | Shared components |
| P1-2 | Connection-required card replacing three "Unavailable" tiles on Home | UX-03 | S | P1-1 |
| P1-3 | Batched quotes for the Markets list (backend endpoint and client) | UX-04 | M | Backend contract |
| P1-4 | Company names for all instruments (instrument master) | UX-06 | M | Backend data |
| P1-5 | Portfolio error states by reason; single primary action; last-verified time | UX-08 | S | Backend error codes |

**Acceptance:** no price without status and timestamp; every LIVE row shows change and source; each error reason maps to one action; goldens pass at 390×844.

## Phase 2: Design-system foundation

| ID | Item | Issues | Size | Depends on |
|---|---|---|---|---|
| P2-1 | Semantic tokens; rename alias getters; one `buildTheme` | UX-18 | M | none |
| P2-2 | Bundle licensed Inter; tabular figures for numbers | UX-22 | S | License |
| P2-3 | Replace hard-coded colors and sizes outside `design/`; lint gate | UX-19 | M | P2-1 |
| P2-4 | 4-pt spacing scale; remove ad-hoc values | UX-20 | S | P2-1 |
| P2-5 | Neutral card surfaces; remove nested and gradient cards | UX-21 | S | P2-1 |
| P2-6 | Logo fidelity: exact approved mark; instrument logo pipeline | UX-05 | M | Approved master asset |

**Acceptance:** contrast tests pass in both themes; no `Color(0x…)` or `fontSize:` literal outside `design/`; goldens updated and reviewed.

## Phase 3: Navigation and information architecture

| ID | Item | Issues | Size | Depends on |
|---|---|---|---|---|
| P3-1 | Split customer, operator, and admin modules; typed routes | UX-12 | L | none |
| P3-2 | Client role guard for admin routes (server remains authoritative) | UX-13 | S | P3-1 |
| P3-3 | Mobile nav: Home, Markets, Portfolio, Mind, More; move Rooms into Home or Portfolio | UX-14, UX-15 | M | P3-1 |
| P3-4 | Implement Notifications or hide the destination; Activity content from backend | UX-16 | M | Backend notification wiring |
| P3-5 | Shared `StateView` for loading, empty, error, disconnected | UX-33 | S | P2-1 |

**Acceptance:** each path maps to one module per role; no placeholder reachable by a customer; route tests pass.

## Phase 4: Trading workflow (largest scope)

| ID | Item | Issues | Size | Depends on |
|---|---|---|---|---|
| P4-1 | Customer P&L (realized/unrealized) with source and timestamp | UX-36 | M | Backend P&L data |
| P4-2 | Order state-machine UI (Draft → Preflight → Approval → Submitted → Filled/Rejected/UNKNOWN) | UX-35 | L | Backend intents and approvals; UNKNOWN never auto-retried |
| P4-3 | Stock detail: chart with timeframes, source footer; market depth only if backend provides it | (new) | L | Chart data source |
| P4-4 | Streaming quotes (SSE/WebSocket) with reconnect and backoff; polling fallback | UX-30 | L | Backend streaming |

**Acceptance:** state-machine tests cover every transition; UNKNOWN shows "Check status" with no retry; reconnect test; load test on quotes.

## Phase 5: MintTick Mind experience

| ID | Item | Issues | Size | Depends on |
|---|---|---|---|---|
| P5-1 | Backend-fed insight card with source, timestamp, limitation | UX-37 | M | Backend insight API |
| P5-2 | Pause-automation control and risk-limits panel | UX-37 | M | Backend automation state |
| P5-3 | Decision history with outcomes; no unbacked performance claims | UX-37, UX-11 | L | Backend audit data |
| P5-4 | Operator research: guided start, uncertainty and evidence panel | UX-11 | M | Research API |

**Acceptance:** every AI item shows source, time, and limitation; no confidence value unless calibrated; pause control works end to end in PAPER.

## Phase 6: Accessibility, performance, and observability

| ID | Item | Issues | Size | Depends on |
|---|---|---|---|---|
| P6-1 | Touch-target audit (48 dp); semantics for prices and statuses | UX-26, UX-27 | M | P2 |
| P6-2 | Text-scaling and width goldens (320/360/390/411/600; 1.0/1.3/1.5) | UX-28 | M | P2 |
| P6-3 | Shared API client and repositories per session; cache instruments | UX-29 | S | none |
| P6-4 | Split `app.dart`; scope rebuilds | UX-31, UX-32 | M | P3-1 |
| P6-5 | Privacy-safe client telemetry | UX-34 | M | Privacy review |
| P6-6 | Mask user UUID on account screen | S-01 | S | none |

**Acceptance:** TalkBack and VoiceOver pass on core flows; frame timings measured on a mid-range device; telemetry schema test.

## Dependencies

```
P0 ─► P1 ─► P2 ─► P3 ─► P4 ─► P5
            └──────► P6 (parallel with P3–P5)
Backend: batched quotes (P1-3), instrument names (P1-4), readiness (P0-1), notifications (P3-4), P&L (P4-1), intents/approvals (P4-2), streaming (P4-4), insight API (P5-1)
```

## Risks

- LIVE framing (UX-01) must be resolved before any marketing or screenshots.
- Logo licensing (P2-6) may gate P0-4 timing.
- The style decision (`design-system-proposal.md` §0) changes P2 scope.
- Backend endpoints for P4 are not verified as complete; treat estimates as provisional.

## Decisions required

1. Confirm whether both `neonFuturisticAi` and `cleanModern` must ship (AGENTS.md), or only one design (2026-11-06 audit).
2. Confirm LIVE customer framing: hide Live entirely, or show it disabled with a reason.
3. Confirm logo source and licensing.

## Progress: Markets revamp (implemented)

Evidence from this change set; verified by tests, not by device runs.

- UX-01 (500 on quotes): typed 429/503 problem responses for provider and broker-session failures. Backend `CustomerMarketDataControllerTest` 10/10.
- Live data: one shared Angel One stream with multi-symbol subscription and per-row LIVE/STALE/MARKET_CLOSED/UNAVAILABLE states. Backend `CustomerAngelOneLiveQuoteServiceTest` 7/7.
- Batch snapshot: `GET /api/customer/market-data/snapshot?instrumentIds=` (max 30). Flutter `CustomerSnapshotMarketRepository`.
- Markets page: per-row status text plus icon, session banner, no prices unless LIVE, refresh on resume, no hardcoded exchange date.
- Overview preview: reads the same snapshot; the hardcoded IST date guard and "instrument detail" copy were removed.
- Flutter: `flutter analyze` clean; `flutter test` 267 passing. iOS build deferred per user instruction.
- Not done: per-stock detail view, favicon privacy replacement (UX-05), design-system token consolidation.

### Markets design M1 (tabbed shell)

- Four tabs in minttick_customer_market_page.dart: Overview, Movers, Sectors, Explore. Real data only in Overview (shared preview) and Explore (search, NSE/BSE chips, batched snapshot rows, load more).
- Movers, Sectors and the Overview index, breadth and top-movers blocks show an explicit "Not available yet" reason. No values are invented.
- Backend gap closed in this pass: AccountApiOnlyFilter now allows GET /api/customer/market-data/snapshot (test added in AccountApiOnlyFilterTest).
- Verified: lutter analyze clean; lutter test 268 passing; AccountApiOnlyFilterTest passing.
- Not yet done (M2 onward): stock detail, index/breadth/movers/sector data, watchlists, MintTick Mind card.

### Markets design M2 (stock detail)

- Backend: new read-only GET /api/customer/market-data/candles (CustomerMarketCandleController) returns stored candles without broker tokens. Allowlisted in AccountApiOnlyFilter. Tests: CustomerMarketCandleControllerTest (2), AccountApiOnlyFilterTest (2), CustomerMarketDataControllerTest (10) passing.
- Flutter: MintTickStockDetailPage with a LIVE-and-fresh price only, a stored-candle line chart (5m, 1H, 1D), honest empty and error states, and "not available yet" stats. Explore rows open it.
- Verified: flutter analyze clean; flutter test 272 passing.
- Not yet done: Analyze-in-Trade-Room navigation (onAnalyze not wired; needs a product decision). Overview mover rows now open stock detail (see section 17). Watchlist (M4) is in section 16. Candle endpoint licensing comment in MarketDataController needs a product decision before release.

### Markets design M3 (first provider slice: day OHLC, previous close, volume)

- Backend: GET /api/customer/market-data/quotes/detail?instrumentId= reads the app-session FULL quote only (no broker fallback). Parser AngelOneQuoteDetailParser validates identity, feed time, positive prices, high >= low and volume >= 0. Allowlisted in AccountApiOnlyFilter. Tests: AngelOneQuoteDetailParserTest (5), CustomerMarketDataControllerTest (12), AccountApiOnlyFilterTest (2) passing.
- Flutter: stock detail loads the day detail once on open (manual Refresh, no polling). It shows Open, High, Low, Previous close, Volume and Exchange time with a source label. Change vs previous close shows only with a LIVE price. VWAP and 52-week range stay "not available yet".
- Not verified against a live provider sample: FULL response field names and whether `close` means the previous session close. Confirm before production use.
- Not yet done (deferred, needs approval): index instruments, full-universe screener for movers, breadth and gap, and the provider bulk-quote rate budget. Lambda fallback does not return OHLC.
