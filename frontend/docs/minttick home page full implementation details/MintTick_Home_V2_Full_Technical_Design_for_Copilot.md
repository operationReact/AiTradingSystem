# MintTick Home V2 — Full Product, UX, Frontend, Backend, Data, Security & Delivery Specification

**Status:** IMPLEMENTATION SPECIFICATION — approved visual direction, not proof that pictured features or financial values already exist  
**Product:** MintTick (India-first, customer-facing Flutter Web + Android/iOS, backend Spring Boot Java / PostgreSQL / Flyway, AWS)  
**Target:** Authenticated MintTick **Home** (`/`) in `PAPER` and `LIVE` workspaces; **not** the Markets page  
**Repository:** `operationReact/AiTradingSystem`  
**Source branch audited:** `dev`, commit `af9417aba64ede259397a527b4b2c0d5b9b6e063` (2026-11-08). Always re-check latest `dev` before coding.  
**Approved visual reference:** `MintTick_Home_Approved_Visual_Reference.png` included alongside this file. It is a *visual concept*, with illustrative numbers/statuses—not an approved set of fabricated runtime data.  
**Owner's overriding requirements:** Preserve existing MintTick brand and near-black background; Home must be a **user's trading-workspace command center**, **not** a second Markets dashboard; market instrument data and feed depend on the app's AWS-managed market credentials, while a user's personal broker connection is required **only** for user-specific brokerage capabilities and authorized order execution. Provide working frontend **and** backend. 

---

## 0. Non-negotiable Copilot contract

1. Inspect the existing repo and its current tests, dependencies, routes, API authorization and database migrations before editing. **Do not blindly implement from this document against a changed codebase.** Identify the current last Flyway migration and latest merged changes.
2. Implement end to end in **small feature branches and PRs targeting `dev`**. Keep CI green; do not rewrite existing backend execution/risk boundaries, or force deployment on a stopped EC2 instance.
3. Preserve `PAPER` and `LIVE` data isolation across frontend, backend, database, caches, and UI state. Merely selecting `LIVE` never enables real orders.
4. Build UI components from *actual verified API state*. No synthetic prices, P&L, day returns, room statuses, health percentages, insights, watchlist content, course progress, or notification counts. Use `null` / unavailable, appropriate skeletons or empty states.
5. Treat all values shown in the screenshot—e.g. 2 active rooms, ₹40,000 capital, +₹1,320 P&L, 5 ideas, 88% health—as **illustrative**. Today, the repo has room drafts and isolated Paper wallets, but not a customer-ready P&L ledger/valuation across rooms and not an approved room-health percentage.
6. Market data remains **app-managed**; personal Angel One/Upstox connection is not a precondition for market browsing. Broker setup appears on Home only when a verified user-specific need exists, primarily in `LIVE` mode.
7. Use Cognito-verified application identity server-side; each API only returns data owned by the authenticated customer. No broker secrets, keys, PINs, TOTP seed, JWTs, or SSM secrets in Flutter, analytics, logs, URLs or screenshots.
8. Existing customer order engine currently fails closed. Home must not claim that a funded Paper room is actively placing trades or that broker connectivity authorizes LIVE execution. Do not add a bypass or new trading action without separate approved scope and integration tests.
9. The Home design must be visually and functionally consistent with Markets, Trading Rooms, Portfolio, navigation and the existing theme system; no alternate brand or visually harsh neon takeover.
10. Treat unavailable backend dependencies as scoped failures: render the rest of Home where possible, show a clear retry action for the failed section, and retain strict auth failures for the whole workspace.
11. Every interactive control in the visual reference must have explicit navigation, state behavior, analytics semantics, accessibility labels, and tests. No dummy buttons or fake links.
12. Include documentation, migrations, automated tests, feature flags, safe deployment/rollback, and a verification report after every PR.

## 1. Product objective and information architecture

### 1.1 Home's job

The authenticated Home must answer **five questions within five seconds**:

1. Which workspace am I in—Paper or Live—and is it safe/read-only?
2. How many *real* rooms exist, what is their *verified* status, and what capital or performance data is **actually** known?
3. What is the single most useful action I can take next?
4. What is MintTick Mind genuinely able to explain from evidence about *my workspace*, if anything?
5. Where can I continue my rooms, portfolio, watchlists, broker setup and learning journey?

The Markets page owns indices, tickers, movers, heatmaps, sector trends, market breadth, real-time quote feeds, charts and stock-search discovery. **Do not duplicate these on Home.** At most show one non-numeric contextual link such as `Explore Markets`, with session status from the existing calendar *if already available*. Do not run three separate stock quote requests from Home.

### 1.2 Screen hierarchy, top to bottom

1. **Global header:** official MintTick brand, notifications bell, customer avatar/profile.
2. **Personal greeting:** time-appropriate greeting + verified display name, concise subtitle.
3. **Persistent workspace switcher:** Paper Trading | Live Trading; selected state and risk semantics.
4. **Today in your workspace:** authenticated, mode-scoped KPI overview, possibly partial data.
5. **Quick actions:** Create Room, Resume Room, Portfolio, Watchlist, conditional Broker Setup.
6. **Your Trading Rooms:** up to 2–3 relevant current rooms, status, allocations and evidence-backed performance/readiness.
7. **MintTick Mind:** recent evidence-backed workspace insights **only if available**; otherwise setup/disabled/explanation state.
8. **Needs your attention:** contextual actionable cards ranked by severity and applicability.
9. **Learn & improve:** relevant educational material, when curated, published and backed by progress state.
10. **Shared bottom navigation:** Home / Rooms / Markets / Portfolio / More (no second redundant tab bar).

### 1.3 Information priority on 412 × 915 mobile viewport

- Visible above fold: header, greeting, mode, compact daily summary and first set of meaningful quick actions. Trading Rooms starts near or shortly after fold; do not attempt to force the entire mock screenshot into a single viewport.
- Use a **single scrolling content view** below a fixed safe-area header/navigation. Avoid nested vertical scroll lists, clipped sticky footers, and oversized blank space.
- Use a **responsive horizontal quick-action rail** or 2-row adaptive grid instead of squeezing five 90-px cards into a 360–412 logical-pixel width.
- Keep one primary action/visual emphasis at a time. Mind and status cards must not all glow simultaneously.

## 2. Current repo evidence and gaps — mandatory starting point

These are real repository paths identified in `dev`, not assumptions:

| Area | Existing code | Reality / implementation consequence |
|---|---|---|
| Customer Home | `apps/trading_client/lib/src/features/trading_rooms/presentation/minttick_customer_home.dart` | Renders profile, mode, market preview, planned allocation, draft/funded rooms and static Mind status. Replace content hierarchy; preserve valid profile/route behaviors. |
| App navigation | `apps/trading_client/lib/src/app.dart` | Already has `/`, `/rooms`, `/market`, `/portfolio`, `/brokers`, `/broker/angel-one`, `/broker/upstox`, account and notifications entry points. Reuse existing navigation and guards. |
| Paper/Live selector | `.../domain/trading_workspace_mode.dart` + `.../presentation/minttick_mode_switcher.dart` | Selection stored under verified customer ID; view mode is **not** execution permission. Continue this contract. |
| Room model and HTTP client | `.../domain/trading_room_draft.dart`, `.../data/customer_room_repository.dart` | `DRAFT` rooms; `PAPER_FUNDED` paper accounts; planned budget is **not** balance. No customer ACTIVE execution status. |
| Room backend | `backend/src/main/java/in/aitradingsystem/tradingrooms/CustomerTradingRoomController.java` | Existing customer-owned `GET/POST /api/customer/trading-rooms`, GET by ID, DELETE draft. Extend carefully, do not fork room data model. |
| Paper wallet | `.../CustomerPaperWalletController.java`, `.../CustomerPaperWalletService.java` | Paper activation, per-room account and ledger; initial/available/reserved/realized fields. Execution intentionally halted. |
| Order readiness | `.../CustomerExecutionReadinessController.java`, `.../CustomerExecutionReadinessService.java` | `GET /api/customer/trading-rooms/{id}/execution/readiness`; explicit blockers and **executionEnabled=false**. Use for truth-based health/readiness. |
| Blocked order intents | `.../CustomerTradeIntentController.java`; `V66__customer_paper_live_order_intent_custody.sql` | Durable preflight-blocked intents, **not executable orders, fills, open AI ideas or trades**. |
| Mode-specific portfolio | `.../presentation/minttick_customer_portfolio.dart` | Paper currently shows wallet cash; Live can read broker funds/holdings, but no assumption that live execution is enabled. |
| Personal brokers | `.../provider/angelone/CustomerAngelOneController.java`, Upstox counterparts | Broker status and verified login/read; keep separate from app-managed market-feed identity and market browsing. |
| App market feed | `.../provider/angelone/AppAngelOneMarketFeedService.java` and `.../marketdata/CustomerMarketDataController.java` | Private app-managed market data already separated from personal broker; market analytics remain a distinct Markets feature. |
| Shared design | `apps/trading_client/lib/src/theme/app_theme.dart`, `.../brand/minttick_brand.dart`, `.../presentation/minttick_customer_widgets.dart` | Near-black `#0B0D10` exists. Reuse components; avoid second independent design system. |
| Existing notifications | `backend/src/main/java/in/aitradingsystem/api/NotificationController.java` | Operator/system notifications are not a ready-made customer-owned Home alert inbox; inspect auth and add tenant-scoped notifications separately where required. |
| Watchlist | No `watchlist` source path found in audited tree | Must implement or feature-gate; do not render saved items that do not exist. |
| Learning library | Research-learning backend exists, **not** a customer course/catalogue and progress system | New feature or omit card until usable. Do not expose research datasets as user-facing lessons. |
| MintTick Mind | No user-facing `MintTick Mind` insight service found | Build evidence/eligibility checks + a useful disabled fallback before generating any personalized text. |
| Database | Migrations `V64` rooms, `V65` Paper wallet, `V66` blocked intents | Add only new, forward-only Flyway migrations with validated constraints. Do not alter historical migrations. |

**Scope warning:** The mock image depicts two ACTIVE Paper rooms with financial P&L and AI coaching. The current backend only has `DRAFT` and `PAPER_FUNDED`, and explicitly disables the customer trading engine. **Implement accurate current-state UX first**; provide richer figures when subsequent audited financial services exist.

## 3. Shared frontend technical architecture

### 3.1 Component tree

```text
TradingApp (existing router/session and bottom navigation)
└── MintTickCustomerHomePage (route /, only verified CUSTOMER)
    └── HomeDashboardController (state + orchestration; no business math)
        ├── HomeHeader
        │   ├── MintTickLockup
        │   ├── NotificationBell(unreadCount, safe navigation)
        │   └── AccountAvatar(verified profile)
        ├── HomeGreeting(displayName, IST-appropriate time)
        ├── TradingWorkspaceSwitcher (existing ChangeNotifier)
        ├── WorkspaceSummaryCard (mode, KPI values, each with quality state)
        ├── QuickActionsRail (capability-aware actions)
        ├── TradingRoomsPreview (0–3 rooms; scoped detail links)
        ├── MintTickMindPanel (evidence/eligibility/status)
        ├── AttentionList (highest-priority actionable items)
        └── LearningPreview (eligible published lessons + progress)
    [Shared bottom navigation owned by TradingApp, not duplicated]
```

Suggested files (adapt to actual repo layout; avoid unnecessary restructuring):

```text
apps/trading_client/lib/src/features/home/
    presentation/minttick_home_v2_page.dart
    presentation/widgets/home_header.dart
    presentation/widgets/home_greeting.dart
    presentation/widgets/workspace_summary_card.dart
    presentation/widgets/home_quick_actions.dart
    presentation/widgets/home_rooms_preview.dart
    presentation/widgets/home_mind_panel.dart
    presentation/widgets/home_attention_section.dart
    presentation/widgets/home_learning_section.dart
    data/customer_home_repository.dart
    domain/home_dashboard_snapshot.dart
    domain/home_action.dart
    state/home_dashboard_controller.dart
    utils/home_formatters.dart
```

One common reusable styling module should provide `HomePanel`, `HomeSectionHeader`, `HomeStatus`, `MetricValue`, `DataFreshness`, `HomeSkeleton` and `HomeErrorCard`. Use the existing theme/colors and navigation framework instead of adding a new state-management package by default. Repository interfaces must be injectable for widget tests.

### 3.2 State model and lifecycle

```text
UNAUTHENTICATED  -> sign-in (never load customer Home)
AUTHENTICATING   -> existing auth flow; no customer data
INITIAL_LOADING  -> branded skeleton from first frame
PARTIAL_READY    -> render independently available sections + errors
READY            -> mode-specific verified data
REFRESHING       -> show last valid values with dated/freshness markers
AUTH_EXPIRED     -> erase private snapshot, require reauthentication
MODE_SWITCH      -> immediately clear other-mode financial cards, reload
ERROR            -> isolate per-section, retry; no invented values
OFFLINE          -> show last-known only if explicitly marked cached/stale
```

- Load once on verified customer entry, refresh on pull-to-refresh, app resume, mutation completion, mode change and selective TTL expiry. Do not spam backend calls with a widget rebuild.
- Cancel stale responses using generation IDs and ignore async results from an old customer/mode/session. On logout clear memory and mode-specific private caches.
- Bounded API timeouts with network-specific error codes and retry with exponential backoff for idempotent GETs. Do not retry POST actions without idempotency handling.
- Home is a **snapshot**, not a market streaming screen. No SSE/WebSocket required on Home for first phase. Manual refresh + limited on-resume checks suffice.
- Never store financial values or access tokens in unencrypted shared preferences. Existing customer mode preference may stay persisted by customer identifier; switching accounts must not leak state.

### 3.3 Visual design tokens and responsive rules

Use `MintTickPalette.nearBlack = #0B0D10` as the **screen canvas**, `MintTickPalette.teal = #00BFA5` and `mint = #00E676` as existing brand references. Dark theme already uses `surfaceContainerLow #1B2732`, `surfaceContainerHigh #283845`, off-white text, muted teal and outline variants. **Do not change existing global accents** without design-review approval.

| Design attribute | Implementation target |
|---|---|
| Canvas | `#0B0D10` throughout all Home screen surfaces, including bottom sheet background and safe area |
| Content panels | Theme `surfaceContainerLow`, subtly bordered; elevated high-contrast panel only for primary summary or urgent item |
| Text | Primary theme `onSurface`, secondary `onSurfaceVariant`; avoid tiny faint labels |
| Primary action | Teal/mint emphasis; not five equally bright buttons |
| Profit/loss | Semantic positive/negative color distinct from brand; include sign and label for color-blind users |
| Disabled / unavailable | Textual explanation, subdued styling, no animated loading indefinitely |
| Cards | 12–16 px radius; 1 px subtle border; reserved whitespace; no glows on every card |
| Spacing | 4/8/12/16/20/24 scale, 16–20 horizontal padding, consistent 8–12 gaps |
| Tap target | Minimum 44 logical px; 48 preferred for frequent actions |
| Typography | Existing MintTick typography; summary > section heading > metric > caption; large numbers with tabular figures |
| Animation | ~180–250 ms; reduced-motion preference respected; no finance values counting up before verification |
| Layout | 320/360/390/412 logical-px phones; 600–840 tablet; >840 desktop responsive grid. No RenderFlex overflow. |
| Sticky elements | Shared bottom navigation + safe area only; scrolling content must have bottom inset so nothing is hidden |

**Screenshot-fidelity corrections:** The illustration has very bright neon gradients and excessively compressed multi-column room data. On a real 412 logical-px phone, retain the hierarchy and brand while using a more compact 2×2 summary metric grid and 2–3 line room cards. Do not require horizontally scrolling the primary P&L table to read status.

## 4. Section-by-section behavior + UI + API and backend data sources

### 4.1 Global header: MintTick brand, notifications, avatar

**Frontend:** Existing `MintTickLockup`; right-aligned bell with integer unread badge only if available, and tappable profile/avatar. Preserve status-bar/safe-area and keyboard-friendly navigation. Bell opens customer notifications list; avatar opens `/account`. Profile name/avatar initials from verified `/api/auth/cognito/me`, never compile the mock user's name or email into the code. Default avatar fallback must use no third-party photo URL.

**Backend:** Reuse existing Cognito profile route. Add distinct, customer-scoped endpoints `GET /api/customer/notifications?unreadOnly=false&limit=...`, `POST /api/customer/notifications/{id}/read` when inbox is implemented; do not expose existing operator/system notifications. Unread count from real unread rows. Notification kinds: room-blocked, risk-review, broker-expiring, strategy-review, lesson-reminder if opted in, and system account changes; all identity/room scoped.

**Rules:** Deduplicate by event and customer, store createdAt/readAt/sourceId; rate-limit to avoid noisy repeated alerts. If notifications don't exist yet, bell may open a well-labeled empty state with zero badge, never a fabricated positive count. Auth-expired removes badge/data and routes to login.

**Tests:** tapping bell/avatar; unread count 0/>0; cross-user 404; no badge for unread-query failure; secure redirects; accessibility semantics.

### 4.2 Greeting and purpose statement

**Frontend:** Greeting = time of day in configured locale (Asia/Kolkata for this market) + safely escaped preferred display name; fallback `Welcome back`. Subtitle `Your trading workspace`. For new accounts, use `Let's set up your first trading room`. Avoid emoji by default in production if inconsistent with brand; the mock wave is optional decorative art.

**Backend:** Prefer existing verified profile fields, not a new stored greeting. If the profile lacks preferred name, use a neutral message; never display email as a personal name by default. Do not calculate session time from screenshot clock.

**Tests:** locale/time boundaries (00:00, 12:00, 18:00), no name, long name, screen-reader announcement, user switch.

### 4.3 Paper Trading / Live Trading switcher

**Frontend:** Reuse `TradingWorkspaceMode`, `MintTickModeSwitcher`; two accessible segments. Retain last selected UI mode per verified customer. Mode change triggers cancellation/invalidation of all Home requests and rebuilding of cards. Paper and Live Home use identical layout but different labels, financial sources, actions and capability guards. Show a short `PAPER · Simulated funds` or `LIVE · Broker-linked account` descriptor. Do **not** auto-connect broker or place orders when user switches.

**Backend:** Mode is explicit enum `PAPER|LIVE`, validated server-side for all summaries, room lists, actions, notifications and insights. Always filter by `user_id` from verified JWT and `mode`. Never trust client-provided user UUID or session-only mode to determine ownership. If Live has no broker, present setup action and `Not connected` rather than zero balance or a sign-in blockade. Personal broker connection is not required to access Markets, Paper rooms or public educational content.

**Tests:** mode persistence, switching during in-flight GET, independence of balances and rooms, Live disconnected, no market feed gate, no execution side effects.

### 4.4 “Today in your workspace” — KPI summary

**Four KPIs pictured:** Active Rooms, Virtual Capital, Today's P&L, Open Ideas.

**Required truthful first-release behavior:**

1. **Rooms**: display `Rooms` total (or `Ready rooms` if meaningful); return separate counts `draftCount`, `paperFundedCount`, `executionEnabledCount`. Only count ACTIVE if there is a genuine persisted active/executing state; currently `PAPER_FUNDED` is funded **but halted**, not active execution. Prefer label `Paper rooms: 2` or `1 funded · 1 draft`.
2. **Capital (Paper)**: show `Virtual allocated capital` only for genuinely funded `customer_paper_account.initial_cash_paise`; show `Planned allocations` separately for draft budgets, never add both without disclosing allocation type. For Live use verified broker account funds with source/time, not total planned budget as real invested capital. No personal broker = `— · Connect account to view`.
3. **Today's P&L**: must be nullable until the customer-specific fill/position/valuation engine exists. When available, return a financial amount in paise, previous closing mark, realized and unrealized components, fees/charges, valuation time and market-data quality. Never calculate from a room's planned budget, general paper-account operator data, stock price alone or the screenshot. For draft/funded-but-unexecuted rooms show `No executed trades yet` or `— · Not available`, **not +₹820** or +4.10%.
4. **Open Ideas**: only count persistent, user-scoped, eligible idea records with defined states (`OPEN`, `REVIEW`, `EXPIRED`, `DISMISSED`) and evidence. The current `PRECHECK_BLOCKED` customer trade intent must **not** be counted as an open trading idea. Until the new service exists show `— · Not configured` or replace this card with `Review items` (real count) via feature flag.

**Date definition:** The card title `Today` is a business-date-aware session window in IST. Backend resolves the market calendar and explicit trade/session date; handles holidays, weekends, special sessions, previous-market-day carryover. Never silently carry forward stale data as today's. All computed fields have `asOf`, `status`, and `source`.

**Backend aggregator:** Implement a bounded, read-only `CustomerHomeSnapshotService` that loads verified identity, rooms (single query), funded wallets (single query), optional broker balance and optional performance/idea services. Use `user_id + mode` in all queries. Use database sums of paise with overflow protection and account-level dedup. Do not call quote API once per room just to build Home. Result can return `partial` and per-KPI availability statuses.

**Tests:** 0 rooms, 2 drafts, 1 funded and 1 draft, two owners, mode isolation, non-eligible ideas, market closed, stale marks, partial unavailable, round-trip paise formatting, fail-closed on missing proof.

### 4.5 Quick actions (five illustrated)

On <420 logical-px phones use horizontally scrollable `ListView`/Wrap with peek affordance or 2×N grid. Each tile has icon, title, state-specific subtitle, target route, enabled flag, aria/semantic label, and one unified click handler. Prefer server capabilities to infer required setup.

| Action | Visible label and behavior | Backend check / navigation |
|---|---|---|
| Create Room | `Create Room` (Paper) / `Plan Live Room` (Live) | Use existing room creation flow and POST `/api/customer/trading-rooms`; server persists `DRAFT`, ownership/mode/constraints validated. Never fund automatically. |
| Resume Room | `Continue Room` or `Resume Room` only for a real eligible room; otherwise `Choose a Room` or disabled explanatory state | Pick deterministic `lastViewedAt` (new preference) or latest actionable room, validate ownership/mode, open **specific** room details; no background trading implied. |
| Portfolio | `Portfolio` | Existing `/portfolio`, respects mode; Paper wallet state and Live broker read-only state. No fake holdings. |
| Watchlist | `Watchlist`; optional `0 saved` | Opens new owned watchlist route when implemented; if absent, route to proper setup/empty watchlist experience, not to unrelated market list. Market data uses application feed, not broker connection. |
| Broker Setup | `Broker Setup` or `Broker Connected` **only** if relevant, particularly in Live mode | Existing `/brokers`, status from authorized customer Angel One/Upstox; never request 4 personal fields again when an approved configured owner login is already available and authorized. Not a global prerequisite for Markets. |

**Alternate Paper fifth action:** `Review risk` or `Learn` if broker setup isn't applicable; do not clutter a Paper workspace with an unnecessary broker CTA.

**Tests:** each action navigates, respects mode/ownership, nonexistent room cannot open, no broker credential prompt on Paper Home, create action idempotency/double tap behavior, semantics and 320–412 widths.

### 4.6 Your Trading Rooms

**Frontend layout:** header `Your Trading Rooms` + View all. Show up to 2–3 room cards ranked by `lastVisitedAt` and then creation time, only current mode. Card fields: icon, room name, `PAPER|LIVE`, focus/strategy (fallback from existing `INTRADAY/SWING/LONG_TERM` focus), persisted status (`DRAFT` or `PAPER_FUNDED`), confirmed allocation type/amount, P&L **only if supported**, readiness label and relevant action. Card tap deep-links to the exact room by ID (not only `/rooms`).

**Current-state truth table:**

| Status | Capital | P&L | Health/readiness | Primary action |
|---|---|---|---|---|
| `DRAFT` Paper | `₹xx planned` | `—` | `Setup incomplete` | `Continue setup` |
| `PAPER_FUNDED`, `halted=true` | `₹xx virtual funded` | `No trades yet` / verified valuation if later supported | `Execution disabled` and blockers | `View Paper room` |
| `DRAFT` Live | `₹xx planned` (NOT brokerage balance) | `—` | `Broker/risk setup needed` | `Configure Live room` |
| Future `ACTIVE`, once legitimately implemented | Backend-verified room-specific capital | Backend-verified, dated P&L only | Evidence-based readiness | `Open room` |

**Health field in mock:** 88% / 76% numbers are **not real**. Replace with `Ready`, `Needs setup`, `Blocked`, `Execution disabled`, derived from `/execution/readiness` and explanatory blocker codes. Only add a numeric readiness score in a separately reviewed PR with documented denominator, weighting, version, source and tests. Risk-policy readiness is not expected return or probability of profit.

**Backend:** Extend existing `GET /api/customer/trading-rooms` with efficient read-only related summary or build new Home projection; do not alter canonical room table status semantics. Obtain wallet and readiness with batched repository queries rather than one query per card. If endpoint currently returns 100 rooms, page/rank defensively in aggregator, avoid per-room network calls. Add `last_viewed_at` per user/room only when navigation writes are designed; never infer from `created_at` that a room was resumed recently.

**Tests:** owner isolation, mode filter, missing room and stale routes, existing DTO compatibility, funded/halted truth, no invented health score/P&L, 80-character ellipsis, chip contrast and proper navigation.

### 4.7 MintTick Mind — personal workspace insight card

**Product role:** An *evidence-backed assistant for the user's rooms, decisions and risk*, **not** a generic stock-recommendation generator, not a promise of profits, not a duplicate Markets feed. Use a small number of highest-value insights (e.g. recent room is blocked by missing risk checks; strategy lacks enough executions to evaluate; no recent realized performance data; review pending setup action). Do not assert `Paper Trading 1 is outperforming today` unless there is reliable comparable measured performance.

**Frontend:** branded card with Brain/MintTick Mind icon, state label `Workspace insight`, timestamp/source, maximum 1–3 structured findings, one actionable CTA `Review room` / `Review risk`. Expand into a dedicated details sheet/page for rationale, evidence, freshness and limits. Never render mock financial claims. Separate *status* from *generated financial insight*.

**Backend decision flow:**

```text
Verified customer + selected workspace
  -> collect owned room statuses, readiness blockers, eligible performance
  -> validate freshness, coverage, lineage, minimum sample sufficiency
  -> produce DETERMINISTIC first-party rule insight (phase 1)
  -> optional LLM narrative ONLY with evidence IDs, fact constraints and audit
  -> return scored/ranked, non-executable, expiring insights
```

Possible insight kinds: `ROOM_SETUP_REQUIRED`, `PAPER_EXECUTION_UNAVAILABLE`, `LIVE_BROKER_NOT_LINKED`, `RISK_POLICY_REVIEW`, `LACK_OF_PERFORMANCE_EVIDENCE`, `SINGLE_ROOM_CONCENTRATION`, `DATA_STALE`, `RESEARCH_EVIDENCE_AVAILABLE`. A Mind suggestion must have an actionable but safe destination, source IDs and `asOf`.

**Safety:** LLM must never invent missing metrics, predict guaranteed returns, issue order commands or bypass risk controls; user-specific source data must be authorized and minimized; use JSON schema validation, prompt-injection isolation, provenance and bounded latency. No hidden automatic order submission from Home. Use deterministic insight templates first, optional generative summary behind a feature flag after evidence-governance review.

**Fallback:** `MintTick Mind is getting your workspace ready` or `No verified performance insights yet` with `Review your rooms`, rather than pretending AI generated performance exists.

**Tests:** unsupported data -> no return figures, stale evidence -> suppressed, cross-account source injection -> reject, disabled model -> deterministic fallback, actionable deep links, redaction, hallucination-resistant template rendering.

### 4.8 Needs your attention

**Frontend:** 1–3 prioritized small action cards, section View all. In mock: `Review room setup`, `Complete broker link`, `Check risk settings`. These MUST be calculated from current state. Do not always show broker setup in Paper mode; if already connected, don't display `Complete broker link`. Zero eligible tasks => `All caught up` or omit the section if the layout would be redundant.

**Backend:** new read-only `CustomerHomeActionService` / actionable rules, preferably alongside Home aggregator. Define shape: `id`, `kind`, `priority` (CRITICAL/HIGH/NORMAL), `mode`, `roomId?`, `headline`, `description`, `route`, `source`, `createdAt`, `expiresAt?`, `canDismiss`, `dedupeKey`. Source rules:

- Pending DRAFT room setup: `CONTINUE_ROOM_SETUP` (Paper/Live).
- Paper funded but halted: `PAPER_EXECUTION_DISABLED` (informative, no green trade CTA).
- Live room without broker verified: `CONNECT_BROKER`.
- Connected broker expiring soon: `REAUTH_BROKER`.
- Any actual execution-readiness blockers: `REVIEW_RISK_BLOCKERS` with safe route.
- Data freshness/portfolio valuation unavailable: `VERIFY_DATA_FRESHNESS` (avoid panic color when market closed).
- Verification/session errors: `ACCOUNT_REVIEW_REQUIRED`, never expose sensitive internal IDs.

Prioritization: blockers and account security before educational nudges; max three Home cards. Backend generates action IDs stable across refreshes. Dismiss only allowed for noncritical reminders and only for that customer; do not let dismiss alter authoritative risk/readiness.

**Tests:** trigger matrices, correct route, dedupe/priority, broker missing only in Live, switching mode hides stale tasks, dismissal cannot suppress critical execution block, customer isolation.

### 4.9 Learn & improve

**Frontend:** optional 1–2 curated learning cards with title, concise description, estimated duration from actual content, progress (`Not started`, `% completed` if stored), skill level, CTA `Start` / `Continue`. In screenshot: Risk Management, Strategy Review. Link to a real in-app learning detail route or verified first-party resource; do not render a play icon for a video that doesn't exist.

**Backend:** implement a small governed read-only content catalogue and per-customer completion record, or intentionally hide this section until there are at least two published, reviewed lessons. MVP content can be version-controlled Markdown/JSON packaged as **real lessons**, with authorship, revision, publish state, content type and update date. No automatic generation of unsupervised investment advice. Track completion with idempotent writes. Serve static content via the existing web deployment/CDN if appropriate; protect user progress APIs, not public articles.

**Recommended minimum lessons:** Paper vs Live and market data separation; Risk Management & Position Sizing; Trading Journal and Drawdown; What a Trade Room Is; Interpreting P&L and Charges; Strategy Review without hindsight bias. Mark educational and avoid misleading certainty.

**Tests:** no content -> section hidden; published-only filter; progress owned per customer; invalid completion ID, lesson revision, accessibility and working route.

### 4.10 Shared bottom navigation and user journeys

Retain `Home`, `Rooms`, `Markets`, `Portfolio`, `More`. Existing `TradingApp` already owns it. Each tab preserves selected state and deep-links to correct destination; never display duplicate bottom bars. Keep selection when Home opens a room detail or returns. Profile and notifications are header routes, not additional bottom tabs.

User journeys that must all work:

- New verified Paper user -> zero-state summary -> Create Paper Room -> server `DRAFT` -> return Home -> `1 Draft` -> Continue setup -> explicit virtual fund activation when user requests.
- Returning user -> select Paper -> exact room view -> view wallet/readiness (execution disabled clearly shown).
- Returning user selects Live -> no broker -> Live Home still loads -> connect broker if authorized -> shows broker state without enabling orders.
- Live connected user -> review account read-only balances; system still shows execution blockers and never claims active trading.
- No Mind eligibility -> explanatory Mind placeholder, no fabricated personalized financial narrative.
- No lessons/watchlists yet -> meaningful setup/empty states or omitted sections; no inert cards.
- Logged-out, expired or switched user -> all private workspace info cleared; verified reauthentication required.

---

## 5. Backend architecture: read-optimized Home BFF

### 5.1 Architecture and dependencies

```text
Flutter Home V2 (authenticated / CUSTOMER)
  ├── Existing Cognito JWT SDK / authenticated HTTP client
  ├── GET /api/customer/home?mode=PAPER|LIVE    [new Home BFF]
  │    └── CustomerHomeController
  │         └── CustomerHomeSnapshotService
  │              ├── CustomerIdentityStore (JWT issuer + subject -> ACTIVE CUSTOMER UUID)
  │              ├── CustomerRoomProjectionRepository (batched owner+mode room view)
  │              ├── CustomerPaperWalletService/Repository (PAPER only)
  │              ├── CustomerExecutionReadinessService (status+blockers, batch optimized)
  │              ├── CustomerBrokerReadFacade (LIVE only; scoped, bounded, no auto-login)
  │              ├── CustomerPerformanceSummaryPort [disabled until real customer ledger]
  │              ├── CustomerIdeasPort [disabled until persisted, evidenced ideas]
  │              ├── CustomerHomeActionService [new deterministic actionable rules]
  │              ├── CustomerMindInsightService [new; rule-based first]
  │              ├── CustomerWatchlistSummaryPort [new/optional]
  │              └── LearningRecommendationPort [new/optional]
  ├── GET /api/customer/home/attention?mode=... [optional expansion]
  ├── GET /api/customer/home/mind?mode=... [optional expansion]
  ├── Existing /api/customer/trading-rooms endpoints (mutations/room details)
  ├── Existing /api/customer/trading-rooms/{id}/paper/account (verified wallet)
  ├── Existing /api/customer/trading-rooms/{id}/execution/readiness
  ├── Existing /api/customer/brokers/{provider}/status, /funds, etc.
  └── New Watchlist, Learning, Customer Notifications as independent modules
```

**BFF principle:** Home returns a single cohesive snapshot for initial render; do not require Flutter to coordinate 12 per-room calls, quote polling or independently changing timestamps. Avoid turning the BFF into another source of truth: use authoritative domain services and published read projections, keep mutations in their owning modules.

**Layer boundaries:**

- Controller: JWT/customer auth, mode validation, DTO and HTTP semantics only.
- Snapshot service: section fan-out and bounded composition; no direct broker API secret handling.
- Read repositories: user+mode scoped SQL batched reads; no N+1.
- Calculators: deterministic domain math for counters, cash and (once supported) realized/unrealized P&L.
- Insight/action rules: pure functions from typed, verified, timestamped evidence.
- Personal broker adapter: bounded read-only status/account fields, **never** the app-market-feed service.
- Persistence: only genuinely durable facts; don't persist screenshot summaries as authoritative finance balances.

### 5.2 Proposed HTTP API map

Existing routes must keep working. Add the following **new APIs only where the feature is implemented**:

| HTTP | Endpoint | Purpose | Status |
|---|---|---|---|
| `GET` | `/api/customer/home?mode=PAPER` | One authenticated snapshot, sections with independent quality | **New, must implement** |
| `GET` | `/api/customer/home?mode=LIVE` | Same schema, live-only selected account data | **New, must implement** |
| `GET` | `/api/customer/home/attention?mode=PAPER&limit=20` | Full attention inbox/detail page | New optional; can derive from snapshot initially |
| `POST` | `/api/customer/home/attention/{id}/dismiss` | Dismiss allowed low-priority reminder, idempotent | New optional; only if dismiss UX exists |
| `GET` | `/api/customer/home/insights?mode=PAPER&limit=10` | Expanded Mind evidence feed | New optional, not required for first card |
| `GET` | `/api/customer/watchlists` | Customer-owned watchlists and counts | New module; feature-gated |
| `POST` | `/api/customer/watchlists` | Create list with immutable owner | New module; optional |
| `POST` | `/api/customer/watchlists/{id}/instruments` | Add canonical instrument ID with unique constraint | New module; optional |
| `DELETE` | `/api/customer/watchlists/{id}/instruments/{instrumentId}` | Remove saved instrument, no effect on Markets feed | New module; optional |
| `GET` | `/api/customer/learn/recommendations` | Published curated lessons + owned progress | New module; optional |
| `GET` | `/api/customer/learn/lessons/{slug}` | Real lesson content | New module; optional |
| `PUT` | `/api/customer/learn/lessons/{slug}/progress` | Idempotent per-user completion | New module; optional |
| `GET` | `/api/customer/notifications?limit=...` | Customer-owned notification inbox | New, avoid operator notification API |
| `POST` | `/api/customer/notifications/{id}/read` | Mark owned item read | New |
| `GET` | `/api/customer/trading-rooms` | Existing authoritative list | **Reuse** |
| `GET` | `/api/customer/trading-rooms/{id}` | Existing authoritative room details | **Reuse** |
| `POST` | `/api/customer/trading-rooms` | Existing draft create; exact payload/status | **Reuse** |
| `POST` | `/api/customer/trading-rooms/{id}/paper/activate` | Existing user-initiated Paper funding | **Reuse; do not call from Home load** |
| `GET` | `/api/customer/trading-rooms/{id}/paper/account` | Existing per-room Paper wallet read | **Reuse** |
| `GET` | `/api/customer/trading-rooms/{id}/execution/readiness` | Existing fail-closed readiness | **Reuse** |
| `GET` | `/api/customer/brokers/angel-one/status` | Personal broker connection state | **Reuse, Live only if relevant** |
| `GET` | `/api/auth/cognito/me` | Verified account information | **Reuse** |

**Do not** add `POST /api/customer/home/execute`, `startAllRooms`, `autoTrade`, broker credentials to Home, or uncontrolled trading APIs. `Home` is primarily read-only and navigational.

### 5.3 Proposed versioned snapshot response contract

API schema should be documented in OpenAPI and represented as typed records in Java and immutable typed Dart models. `snake_case` vs `camelCase` can follow existing repo conventions; choose **one** and test both serialization ends. Here is a **contract illustration** only (no real market/financial assertions):

```json
{
  "schemaVersion": "home.v2",
  "mode": "PAPER",
  "generatedAt": "2026-11-09T04:00:00Z",
  "session": {
    "marketSessionDate": "2026-11-09",
    "timezone": "Asia/Kolkata",
    "state": "PRE_OPEN",
    "lastCompletedSessionDate": "2026-11-06"
  },
  "customer": {
    "displayName": null,
    "avatarUrl": null,
    "unreadNotifications": 0
  },
  "summary": {
    "status": "PARTIAL",
    "totalRooms": 2,
    "draftRooms": 1,
    "fundedPaperRooms": 1,
    "executionEnabledRooms": 0,
    "virtualInitialCapitalPaise": 2000000,
    "plannedDraftCapitalPaise": 2000000,
    "liveBrokerAvailableFundsPaise": null,
    "todayPnlPaise": null,
    "todayPnlPercent": null,
    "todayPnlState": "NOT_SUPPORTED",
    "openIdeasCount": null,
    "openIdeasState": "NOT_CONFIGURED",
    "valuationAsOf": null,
    "valuationQuality": "UNAVAILABLE"
  },
  "rooms": [
    {
      "id": "11111111-1111-4111-8111-111111111111",
      "name": "Paper Trading 1",
      "mode": "PAPER",
      "status": "PAPER_FUNDED",
      "focus": "INTRADAY",
      "plannedBudgetInr": 20000,
      "virtualInitialCapitalPaise": 2000000,
      "paperAvailableCashPaise": 2000000,
      "todayPnlPaise": null,
      "performanceState": "NO_EXECUTED_TRADES",
      "executionEnabled": false,
      "readiness": "BLOCKED",
      "readinessBlockers": ["CUSTOMER_PAPER_ORDER_ENGINE_NOT_ENABLED", "EXECUTION_DISABLED"],
      "lastViewedAt": null
    },
    {
      "id": "22222222-2222-4222-8222-222222222222",
      "name": "Swing Capture",
      "mode": "PAPER",
      "status": "DRAFT",
      "focus": "SWING",
      "plannedBudgetInr": 20000,
      "virtualInitialCapitalPaise": null,
      "paperAvailableCashPaise": null,
      "todayPnlPaise": null,
      "performanceState": "NOT_APPLICABLE",
      "executionEnabled": false,
      "readiness": "NEEDS_SETUP",
      "readinessBlockers": ["PAPER_WALLET_NOT_FUNDED", "EXECUTION_DISABLED"],
      "lastViewedAt": null
    }
  ],
  "mind": {
    "status": "RULE_BASED_ONLY",
    "items": [
      {
        "id": "room-setup-22222222",
        "kind": "ROOM_SETUP_REQUIRED",
        "text": "Swing Capture is still a draft. Complete its setup before use.",
        "sourceRefs": ["room:22222222-2222-4222-8222-222222222222"],
        "asOf": "2026-11-09T04:00:00Z",
        "actionRoute": "/rooms/22222222-2222-4222-8222-222222222222"
      }
    ]
  },
  "attention": [
    {
      "id": "continue-setup-22222222",
      "kind": "CONTINUE_ROOM_SETUP",
      "priority": "NORMAL",
      "headline": "Continue room setup",
      "description": "Swing Capture is a draft",
      "actionRoute": "/rooms/22222222-2222-4222-8222-222222222222",
      "dismissible": false
    }
  ],
  "quickActions": [
    { "kind": "CREATE_ROOM", "enabled": true, "route": "/rooms/create" },
    { "kind": "CONTINUE_ROOM", "enabled": true, "route": "/rooms/22222222-2222-4222-8222-222222222222" },
    { "kind": "PORTFOLIO", "enabled": true, "route": "/portfolio" },
    { "kind": "WATCHLIST", "enabled": false, "route": null },
    { "kind": "REVIEW_RISK", "enabled": true, "route": "/rooms/22222222-2222-4222-8222-222222222222" }
  ],
  "learning": { "status": "NOT_CONFIGURED", "items": [] },
  "watchlists": { "status": "NOT_CONFIGURED", "listCount": null },
  "notifications": { "status": "NOT_CONFIGURED", "unreadCount": null },
  "capabilities": {
    "paperRoomCreate": true,
    "paperRoomFunding": true,
    "customerPaperExecution": false,
    "liveBrokerRead": false,
    "liveOrderExecution": false,
    "customerWatchlists": false,
    "customerLearning": false,
    "mindNarrative": false
  },
  "issues": [
    { "section": "PERFORMANCE", "code": "CUSTOMER_PNL_NOT_SUPPORTED", "retryable": false },
    { "section": "LEARNING", "code": "FEATURE_NOT_READY", "retryable": false }
  ]
}
```

**Important about that example:** all UUIDs, amounts, business date, status and customer data are *fictional test fixtures*. The initial Paper amount in the example is legitimate *only if* the customer's actual Paper wallet row exists and returns it. Do not ship this JSON verbatim or use it for production UI; populate from verified server state.

### 5.4 API semantics, errors and freshness

- `200` for independently partial snapshots when the user is valid; use `summary.status = READY|PARTIAL|EMPTY|UNAVAILABLE` plus `issues[]` for noncritical sections.
- `400` for invalid `mode`, pagination, malformed entity IDs and unsupported filters.
- `401` for missing/expired Cognito JWT; `403` for role/permission failure; `404` for non-owned room/watchlist/notification (do not disclose resource existence).
- `409` for already-existing / conflicting write; `429` for rate limit; `503` if primary customer identity/domain data store is unavailable; do not disguise a DB outage as an empty workspace.
- `Cache-Control: private, no-store` for customer Home, financial, notifications and progress responses. Avoid caching entire per-user BFF responses in shared Caffeine unless key includes tenant+mode+permissions and invalidation is formally proven; safer default is no shared cache.
- `generatedAt`, `asOf`, `source`, `quality` required on financially relevant fields. No 'LIVE' label if feed is stale, paused, broker disconnected or exchange closed. Market session state from server-side exchange calendar (special sessions/holidays supported), not a hardcoded weekday check.
- DTOs should use typed `long` paise or decimal strings for money (never binary floating point); represent unavailable figures as nullable with explicit reason. Percentages as `BigDecimal` with documented denominator and rounding.
- Where a backend service cannot verify data, set `null`; do **not** put `0` unless a real zero was measured.
- Include `requestId`/correlation ID in errors; keep provider/network internals out of user messages.

### 5.5 Data calculations — strict source-of-truth rules

#### Room counts

```text
roomTotal = count(owned customer_trading_room where mode = selected mode)
roomDrafts = count(status == DRAFT)
fundedPaperRooms = count(mode == PAPER && status == PAPER_FUNDED && wallet row exists)
executionEnabledRooms = count(EXPLICIT verified readiness.executionEnabled == true)
```

**Don't relabel `PAPER_FUNDED` as `ACTIVE`**. Current `CustomerExecutionReadinessService` includes unconditional execution blockers and `executionEnabled=false`; honor it.

#### Paper funds

```text
paperVirtualInitialCapitalPaise = sum(initial_cash_paise for distinct, owned, funded PAPER rooms)
plannedDraftCapitalPaise = sum(planned_budget_inr * 100 for distinct owned PAPER DRAFT rooms)
```

Use separate labels. Available cash and reserved cash come from wallet, not from summing room budgets; the account can have no positions and still hold virtual capital. Existing initial event is idempotent; Home reads must never call `/paper/activate`.

#### Live funds

From verified personal brokerage `funds` endpoint, only in Live mode and only when connected to explicitly selected broker account. Distinguish `available`, `utilized`, `invested`, `holdings`, `portfolio value`, which are **not interchangeable**. Read-only user API; no account-link fallback behind the user's back. If multiple brokers configured, never accidentally aggregate both as if one room/account without clear account IDs and selection.

#### P&L (future, once a customer execution/position ledger is legitimately implemented)

```text
realizedPnL = sum(realized closed-position proceeds - matched costs - eligible fees/taxes)
unrealizedPnL = sum((verified current mark - average/matched cost basis) * open quantity)
totalPnL = realizedPnL + unrealizedPnL - non-double-counted charges
```

For a **daily** P&L, choose and document one invariant, preferably `current verified net liquidation value - prior session close net liquidation value - today's net external cash flows` with adjustment for realized cash flows. Reconcile with realized/unrealized position-level computations and corporate actions. Do not simply take `current availableCash - initialCash`, because that ignores holdings, reservations and flows.

- For an active Paper execution engine, valuation marks must have provider/exchange timestamps, maturity checks and fallback policies; never silently substitute live-market price for a missing mark.
- For broker Live mode, prefer broker-reported realized/unrealized balances plus source metadata rather than mixing them with paper calculations.
- `todayPnlPercent` denominator must be an explicitly defined, nonzero opening capital / prior net liquidation, with a documented capital-flow treatment. If missing: percent null.
- Preserve paise precision (integer or BigDecimal) across API -> Dart -> display. Currency formatter uses Indian grouping, signs, charges and currency symbol.
- On a weekend or exchange holiday, show `Previous session` when presenting carried-forward figures, never imply today's trading happened.

#### Ideas

An idea needs ID, canonical instrument identity, source strategy, current state, originatedAt, expiresAt, rationale/evidence IDs, owner, mode, review state and risk eligibility. Do not infer '5 ideas' from 5 customer trade intents, 5 market stories or arbitrary model outputs. An `OPEN` idea is not an order and must not be counted as execution-ready.

#### Room health/readiness

Prefer readiness `READY|NEEDS_SETUP|BLOCKED|UNAVAILABLE` derived from the existing readiness service. If future numeric score is approved: include exact named checks and denominator, algorithm version, all blockers, scope and timestamp. Never call it probability of winning or a guaranteed strategy rating. Current `executionEnabled=false` must always result in BLOCKED for actual trading.

### 5.6 New database schema — only when the corresponding feature ships

First run `ls backend/src/main/resources/db/migration` and select the next available V-number. **Do not assume V67 still available**. Add Flyway migrations, zero downtime / backward-compatible DDL, indexes, migration tests and rollback plan.

Potential new tables (adapt names/column types to current `app_user`, `instrument`, constraints and naming conventions):

```sql
-- Example logical DDL; adjust migration version and exact PostgreSQL types.
CREATE TABLE customer_watchlist (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);
CREATE INDEX ix_customer_watchlist_owner ON customer_watchlist(user_id, updated_at DESC);

CREATE TABLE customer_watchlist_item (
    id UUID PRIMARY KEY,
    watchlist_id UUID NOT NULL REFERENCES customer_watchlist(id) ON DELETE CASCADE,
    -- Add owner_id and composite FK if needed to enforce same-user ownership in DB.
    instrument_id BIGINT NOT NULL REFERENCES instrument(id),
    rank INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (watchlist_id, instrument_id)
);
CREATE INDEX ix_customer_watchlist_item_rank ON customer_watchlist_item(watchlist_id, rank, id);

CREATE TABLE customer_home_action_dismissal (
    user_id UUID NOT NULL REFERENCES app_user(id),
    action_dedupe_key VARCHAR(160) NOT NULL,
    dismissed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id, action_dedupe_key)
);

CREATE TABLE customer_learning_progress (
    user_id UUID NOT NULL REFERENCES app_user(id),
    lesson_slug VARCHAR(100) NOT NULL,
    lesson_revision INTEGER NOT NULL,
    progress_percent SMALLINT NOT NULL CHECK (progress_percent BETWEEN 0 AND 100),
    last_viewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NULL,
    PRIMARY KEY(user_id, lesson_slug)
);

CREATE TABLE customer_notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    kind VARCHAR(50) NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    title VARCHAR(140) NOT NULL,
    body VARCHAR(500) NOT NULL,
    route VARCHAR(250),
    priority VARCHAR(12) NOT NULL CHECK (priority IN ('CRITICAL','HIGH','NORMAL')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ,
    UNIQUE(user_id, dedupe_key)
);
CREATE INDEX ix_customer_notification_owner_unread
    ON customer_notification(user_id, read_at, created_at DESC);

CREATE TABLE customer_room_last_viewed (
    user_id UUID NOT NULL REFERENCES app_user(id),
    room_id UUID NOT NULL REFERENCES customer_trading_room(id),
    last_viewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, room_id)
);
```

Do not create a generic `user_balance` / `pnl` table from the mock; customer financial accounting requires its own separately reviewed source-of-truth design, ledger reconciliation and audit trail. The watchlist foreign key must reference the **actual** instrument primary-key type/name in the repo, not assume it equals the illustrative statement.

**DB safety details:** owner/room composite constraints, indexed owner+mode filters, FK integrity, unique idempotency keys, no room deletion cascade to posted fills/immutable ledger, no cross-tenant joins, and account hard-delete governed by data-retention policy. Add tests for simultaneous watchlist add and duplicate input, ownership checks on every SELECT/UPDATE/DELETE.

### 5.7 Data access and performance

- One `GET /home` composed via a small number of **batched** DB queries. Do not fetch the whole instrument catalogue or prices for a Home preview.
- Avoid `N+1`: e.g. `SELECT rooms ... WHERE user_id = :id AND mode = :mode LIMIT ...`, grouped Paper accounts and `EXISTS` readiness flags in a batched query. Reuse domain readiness rules without changing safety semantics; if safe batching is not yet available, bound max rooms and fan-out concurrency.
- BFF goal (not guarantee): p95 <= 600 ms in warm single-region dev under healthy dependencies, excluding network RTT from device. Server p95 > target should trigger profiling, **not** removing auth checks.
- First meaningful content from cached Flutter shell immediately; initial Home target <= 2 seconds under healthy backend. No blocking sequential navigation before rendering skeletons.
- Fetch source TTL guidance: rooms on action/navigation (short cache 10–30 sec in **customer memory**), Paper wallet on load/resume, notification counts 30–60 sec, published education catalogue hourly, broker status when Live mode selected. These are starting targets, refine after backend/provider constraints.
- No polling market tickers on Home, no websocket subscription there for V2. No more than one in-flight refresh per section and a capped retry count.
- Conditional requests (`ETag`/version) if worthwhile, but default customer private `no-store`; avoid shared CDN caching of customer response.

### 5.8 Security and compliance

- JWT issuer/audience/signature and `ROLE_CUSTOMER` verified with existing Spring Security + `CustomerIdentityStore`. Resolve ACTIVE app user server-side, never accept `userId` as a request selector.
- Scope every room, watchlist, insight, task, notification and progress read/write to authenticated owner; cross-owner ID returns 404. Noncustomer role is denied.
- Personal broker tokens remain ephemeral server-side, secrets in AWS SSM; not copied into Home or public error response.
- Paper virtual money is explicitly labeled, never confused with real funds. Live order execution remains disabled without explicit approved readiness, authorization, registered IP, reconciliation and account risk gating.
- Feature flags cannot override order-readiness checks. No direct Flutter-to-Angel One use. A backend JSON field reporting an enabled CTA is never permission to perform prohibited backend actions.
- Input validation, length limits, HTML escaping, bounded pagination; idempotency for user writes. Avoid network-based logo URLs from untrusted text.
- No third-party analytics upload of portfolio, P&L, broker identifiers, personally identifying content or LLM evidence. Redact sensitive logs, avoid request bodies and auth headers in telemetry.
- For MintTick Mind, keep source references and user opt-in where relevant; use deterministic rules before external model processing of financial data. All suggestions are informational, non-executable and explainable.
- Preserve API Gateway CORS/JWT protections; never make wildcard credentials/CORS a production solution to local IntelliJ problems.

---

## 6. Frontend implementation contracts and interaction specs

### 6.1 State objects and repository design

Define strongly typed Dart entities; do not pass arbitrary `Map<String, dynamic>` deeply into presentation components. Recommended shapes:

```dart
// Conceptual interfaces: adapt to existing naming, do not blindly paste.
enum HomeAvailability { ready, partial, empty, unavailable, notConfigured }
enum HomeMetricState { verified, stale, noTrades, notSupported, unavailable }
enum RoomReadiness { ready, needsSetup, blocked, unavailable }
enum HomeActionKind { createRoom, continueRoom, portfolio, watchlist,
  brokerSetup, reviewRisk, learn, markets }

abstract interface class CustomerHomeRepository {
  Future<CustomerHomeSnapshot> load({required String mode});
}

class HomeDashboardController extends ChangeNotifier {
  // mode/customer generation guard, cancel/ignore stale requests
  // loading/partial/ready/error state, last successful snapshot
  // refresh, onResume, onMutation, clearOnLogout
}
```

`CustomerHomeSnapshot` should be immutable and include explicit `schemaVersion`, `mode`, `generatedAt`, `customer`, `summary`, `rooms`, `mind`, `attention`, `learning`, `quickActions`, `capabilities`, `issues`. Implement `fromJson` with strict type checks, required fields and defaults only for **safe nonfinancial optional fields**. Bad numeric fields must show unavailable, not silently convert to zero. Prefer sealed/enum domain types and centralized formatters for `paise -> ₹`, percent, IST timestamps and relative freshness.

### 6.2 Rendering rules by component

| Component | Loading | Zero/empty | Partial/failure | Interaction |
|---|---|---|---|---|
| Header | Show brand + account placeholder | Greeting fallback | 401 clears Home and routes to sign-in | Bell → inbox; avatar → profile |
| Workspace switch | Last safe mode or Paper default | Paper selected | Show unavailable sections in place; never swap live/paper cached values | Paper ↔ Live selection reload |
| Summary | 2×2 metric skeleton; not fake digits | `0 rooms` from verified query; money only when measured | `—` with hint `Not available`; show data-status icon | Metric tap → corresponding details |
| Quick actions | Actions with disabled reason until capabilities loaded | Create Room remains visible | Network dependent actions disabled with retry | Real destinations, no placeholders |
| Rooms | 2–3 skeleton rows | Invite creation | Failed rooms GET = section error, not empty rooms | Room card → exact owned room ID |
| MintTick Mind | Small status skeleton or rule-based neutral placeholder | `No verified insights yet` | `Unavailable` + limited retry | Open explainable evidence or relevant room |
| Attention | Small skeleton | `All caught up` only after verified rules evaluation | `Unable to check actions` | Action-specific route, dismiss noncritical only |
| Learn | Optional skeleton | Hide if zero real published courses | Explain service unavailable or hide | Open real content/progress |
| Bottom nav | Always visible only on authenticated shell | N/A | Route guard protects data | One shared navigation owner |

### 6.3 Skeletons, feedback and refresh

- Keep the visual skeleton size close to final content to avoid layout shifts. Use subtle theme-aligned shimmer only when not reduced-motion; avoid spinning the whole page while optional Mind/learn data is fetched.
- Pull-to-refresh updates the BFF snapshot, preserves current scroll and selected mode, then updates room/action cards atomically for the matching generation. After a successful create/fund/connection mutation invalidate the exact affected snapshot and relevant room detail.
- A data fetch may be retried on network restoration; give user a clearly labeled `Retry` after 503. No unbounded exponential queue or auto reconnect storm.
- UI money and timestamps should include `asOf` and stale indicator if relevant; tooltip/semantics expose detailed source. `₹—` is preferable to fake `₹0.00` for unavailable.
- No unnecessary custom loading splash after an already authenticated session; render first frame with skeletons rather than requiring all data/services to respond before Home appears.
- Respect system text scale, screen readers, contrast. Positive/negative colors require `+/-` and accessible labels as well.

### 6.4 Navigation and deep link implementation

Existing `TradingApp` uses named routes and a custom `_navigate` that rebuilds the active route; review how it interacts with route changes and bottom-nav selected index. Introduce specific `/rooms/:roomId` deep-link resolution through a route generator or arguments compatible with Flutter web hash URLs, with real fetch+owner check. Do not treat a URL room ID as proof of authorization. Preserve back navigation and when returning Home, restore selected mode and scroll where appropriate.

Proposed destinations:

```text
/                          Home
/rooms                     Trading Rooms list
/rooms/create              Create Room (existing flow)
/rooms/:roomId             Authenticated individual Room detail
/market                    Markets (single definitive route)
/portfolio                 Mode-scoped Portfolio
/brokers                   Personal broker hub
/account                   Profile
/customer/notifications    Customer inbox (new, or fit existing routing)
/watchlists                Watchlists (new)
/learn                     Lesson catalogue (new)
/learn/:slug               Lesson detail (new)
/home/attention            Full action list (optional)
/home/insights             MintTick Mind insights (optional)
```

When reusing `/activity`, `/market-data` and other older routes, keep backward-compatible links or redirect to the canonical equivalent. Avoid creating a completely separate route stack that loses the shared mobile nav.

### 6.5 Optional Home V2 feature flag

During phased rollout, support a server-controlled or compile-time **`minttick_home_v2_enabled`** presentation flag **only** for choosing old vs new Home widgets. This flag must not bypass backend business validations or change order execution permissions. Include a safe, reversible fallback to previous Home if a critical customer rendering regression is detected. No silent server-control of financial calculations; there is only one authoritative backend data source.

### 6.6 Visual fidelity acceptance

Match the approved screenshot's overall hierarchy, near-black theme, official MintTick lockup, mint/teal active mode, concise summary panel, quick actions, room previews, MintTick Mind, attention and learning. **Deliberately deviate** when needed to respect actual data, existing status vocabulary, fewer simultaneous glows, smaller phone widths, responsiveness and accessible contrast.

Do not copy screenshot artifacts as a full-screen background bitmap or paste generated marketing art into a Flutter widget as a shortcut; all text, cards, navigation and indicators must be native accessible Flutter components and real state.

## 7. Test design and release acceptance

### 7.1 Unit tests — data and calculations

Create coverage for:

- Signed/unsigned paise -> INR with Indian lakh/crore group separators; zero vs `null`; negative and huge numbers.
- Summary owner+mode grouping; funded vs planned amounts; duplicate wallet and room read failures.
- Today's session date vs previous trading day, weekend, special trading session and holiday; source quality.
- P&L unavailable with no ledger, no position marks, bad timestamps or mismatched account; no invented `%`.
- Room readiness blocks always reflected, including when customer Paper wallet is funded, Live broker connected and mode switched.
- Insight rules and alert rules deterministic and stable; no P&L claim without source IDs.
- `OPEN` idea only if persisted/evidence-backed, not blocked order-intent record.
- Action dedupe/prioritization/dismissability and mode-specific visibility.
- Robust typed model decoding; schemaVersion validation and malformed numeric types.

### 7.2 Backend integration and security tests

Use existing Spring Boot / JUnit + Testcontainers/PostgreSQL patterns and Flyway; do not mock out the entire security layer for contract tests.

- Customer A Paper snapshot sees only A Paper rooms/wallets; not B, not A Live, not operator paper account.
- Customer A Live snapshot sees no Paper money; missing personal broker -> null funds and appropriate setup CTA.
- Guest, expired JWT, wrong role -> 401/403; cross-owner room, watchlist and notification IDs -> 404.
- SQL owner scopes verified on every new repository method including dismiss and progress writes.
- Paper funded but execution disabled -> status `PAPER_FUNDED`, readiness `BLOCKED`, no active-execution or made-up P&L.
- Backend unavailable (broker 503, database timeout, Mind disabled) -> expected `partial` behavior vs total failure; no data from wrong mode.
- Idempotent create / fund / progress / dismiss; concurrent update race tests.
- Notification count exact; consumer-specific vs system notifications.
- Migration integrity + indexes, unique constraints, FK owner checks, no rewrite of older migrations.
- API response excludes broker keys, session JWT, PII beyond necessary display name; cache-control `no-store`.
- Contract snapshots serializable/deserializable with Dart frontend model tests.

### 7.3 Flutter widget/golden tests

At **320, 360, 390, 412, 600, 768 and >=1024** logical pixel widths, plus light and dark themes where supported:

- No overflow/pixel clipping across every section, including 1.5× and 2.0× text scale and long names.
- Summary 0/positive/negative/unavailable; no fabricated green percentage.
- Loading and partial failure; retry functionality; mode switch during pending response.
- Action navigation route destinations, room drilldown, back navigation and bottom-nav selection.
- Paper/Live isolation in widget tree while switching mode; stale requests cannot paint other-mode values.
- Empty, draft-only, funded-but-halted, Live-disconnected, Mind-disabled, lessons-unavailable, notifications-zero scenarios.
- Header tap targets and Semantics labels; content order for screen readers.
- Keyboard/tab navigation for Flutter Web and accessible focus, scroll-to-visible in mobile landscape.
- Match the approved visual hierarchy, but do **not** golden-test mock profits as production fixture.

### 7.4 End-to-end tests

Use local IntelliJ Flutter with existing AWS dev backend, plus CI Testcontainers for authoritative backend behavior. Never rely on a manual developer's personal broker secrets in PR CI.

1. Cognito customer login -> initial Home state -> Paper selected -> 0/draft real state.
2. Create Paper Room -> verified `DRAFT` appears on Home -> open exact room -> authorize one-time Paper funding explicitly -> returned wallet and still blocked execution.
3. Change to Live -> no paper balances; show optional broker setup, no app market-data lock. Verify Markets remains browsable without personal connection.
4. Existing configured Angel One test-owner account -> user-specific broker status only; if not connected show correct prompt; never expose credential form as a global Home dependency.
5. Simulated backend partial failures -> Home does not display fake zero or fake metrics, and next successful refresh restores source data.
6. Noncustomer or cross-user session -> Home private data inaccessible.
7. Compare post-deploy `GET /home` schema and visuals with local web client; one verified test for Android if new widgets affect mobile layout.

### 7.5 Definition of Done checklist

A PR/phase is done only if:

- [ ] No hardcoded user display name, financial values, percentage/health score, idea count or AI claims in customer Home.
- [ ] Every screenshot section has frontend behavior and a documented source/availability rule.
- [ ] `PAPER`/`LIVE` mode and account data stay isolated on every navigation and API call.
- [ ] Market feed on Markets does not require user broker connection; no redundant quote cards on Home.
- [ ] All primary actions navigate successfully and are disabled appropriately when unsupported.
- [ ] Auth, authorization, scoped SQL, `no-store`, tenant tests and safety gates pass.
- [ ] Performance/paper funds are calculated/verified on backend only; nulls and timestamps handled precisely.
- [ ] UI works at target phone sizes, screen reader, dark/light if supported, and has no overflow.
- [ ] Existing trading room, portfolio, broker, market and Cognito functionality unchanged or covered by tests.
- [ ] Flyway migrations, backend integration, Flutter `analyze`, `test`, web build, applicable Android smoke build, visual goldens and CI all pass.
- [ ] Docs include API schema, empty/error/partial states, feature flags, run/test commands and rollback steps.
- [ ] Successful AWS/website deployment **independently verified**, not inferred from green CI. Do not auto-start an EC2 host simply to deploy UI code.

## 8. Observability, reliability and runtime operations

Metrics, logs and traces must distinguish **Home availability** from **underlying trading execution**:

- API: `customer_home_requests_total{mode,status}`, `customer_home_duration_ms`, `home_partial_section_total{section}`, `home_room_projection_query_ms`, `home_broker_status_error_total`, `home_mind_ineligible_total`.
- Client: `home_first_content_ms`, `home_refresh_failed`, `home_mode_switched`, `home_action_selected{kind}`, `home_room_opened` (no room ID or user ID in public telemetry), `home_section_error{section}`.
- Structured backend log includes request correlation UUID, API path, environment, mode and redacted error code; no tokens, broker credentials, raw user email, full balances or LLM prompt payloads.
- Alert on persistent elevated 5xx / p95 degradation and abnormal blank Home error state. Do not treat market closed or broker disconnected as system errors.
- Cost: the existing dev PAPER EC2 can be stopped on its schedule. No startup-on-push policy hidden in Home CI/CD; an offline backend means local Flutter shows a truthful retry state.
- Release: validate schema migration compatibility; deploy backend read endpoints first; then Flutter Home V2 behind flag; verify authenticated smoke (with test credentials kept out of source control), monitor errors; rollback frontend via previous web bundle or feature flag; keep additive backend migrations compatible with previous build.
- Rollback should never revert/destroy customer financial rows. Use forward-only migrations and code compatibility windows.

## 9. Phase-by-phase implementation plan for Copilot

Work in small PRs. The **whole spec** should be fully considered, but do not pretend all missing financial engines exist at the outset. The frontend can be complete with honest absent-state UIs while backend capabilities are added incrementally.

| Phase | PR theme | Backend | Flutter | Exit gate |
|---|---|---|---|---|
| **0 — Audit** | Inventory existing behavior and contracts | Document real customer schemas, role guards, current execution blockers, migration head | Document route/bottom-nav/theme/room mode/data flow | Evidence-backed gap list and no backend mutation |
| **1 — Foundation** | New Home read snapshot | Add `GET /api/customer/home` with owner+mode scoped rooms, Paper wallet summaries, absent-state financial fields, capability and data issue reports; unit/integration tests | Immutable DTOs, repository/controller, skeleton/refresh/error, feature flag | Empty/draft/funded examples accurate; no invented P&L |
| **2 — Main screen** | Core visual rework | Refine summary and batch reads; no new financial mutation | Header, greeting, mode switch, summary, quick actions, rooms preview, shared nav, no Market quote duplication | Golden/interaction tests all device sizes |
| **3 — Action engine** | Needs attention | Deterministic priority/dedupe rules from rooms/readiness/broker, optional customer dismissal | Attention cards, View all, modes/CTA logic | Each card grounded and actionable |
| **4 — Mind V1** | Evidence-based user workspace insight | Deterministic insight records with source refs and freshness; no unsupported performance narratives | Mind panel / explainability / disabled states | Insights never make unproven financial claims |
| **5 — Watchlists** | Customer-owned save/list/remove | Owned watchlist/item tables and APIs; canonical instrument IDs and indexing | Quick action and real watchlist empty/list UI; Markets adds save affordance | Owners isolated; market feed independent of broker |
| **6 — Notifications** | Customer alerts | Scoped inbox + unread count, dedupe, read state, event triggers | Bell badge, inbox page, deep-links | No operator data leak; useful empty state |
| **7 — Learn** | Published education + progress | Curated content contract and idempotent per-user progress | Learn & Improve cards and lesson detail | Every displayed item opens real content |
| **8 — Financial accuracy extension** | Customer portfolio/P&L foundation **separate approval** | Design, implement and reconcile customer-owned fills/positions/marks/fees and an eligible summary, or explicitly defer | Display verified return figures only if this work is approved, data available and fresh | Ledger accounting/provenance/risk tests; no altered execution permission |
| **9 — Release** | E2E, performance, accessibility, deployment | API schema, tracing, auth/security, migration compatibility, partial failures | Mobile/Web goldens, smoke, reduced motion, scrolling, offline and state transitions | CI green + real local connected test + reviewed dev deployment |

**PR rules:** Every PR description states exact files/endpoint/DDL, backward compatibility, sample requests, test output, feature flag, known limitations and what remains. Copilot should not open ten unreviewed PRs that silently merge into one another or deploy incomplete features. A phase may be implemented via multiple small PRs if necessary.

### 9.1 Minimum viable truthful Home vs final mock feature parity

**Immediate achievable Home:** Official brand + personal greeting + Paper/Live, verified room counts, draft/planned vs funded paper amounts, explicit P&L unavailable state, quick actions wired to existing routes, owned room summaries, actual readiness blockers, basic deterministic Mind status, rules-based setup actions, dynamic placeholders/hidden sections for unbuilt watchlist/learning/notifications.

**Requires new functionality:** Persisted watchlists; customer notifications; published courses/progress; persisted idea records; customer-specific executed fills and P&L; advanced MintTick Mind. Implement these separately with explicit source data and testing—not by guessing values for the mock.

### 9.2 Explicit non-goals and future-ready interfaces

- Home is **not** a Markets dashboard. No index movers, daily stock gainers/losers, duplicate 3-ticker quote cards or entire market feed on Home.
- Home is **not** a standalone trading terminal or auto-execution engine.
- No futures/options enabling just because a design card mentions trading; current first phase remains cash equity/Paper/Live safety constraints.
- No fake AI personal performance review or automatic investment recommendations.
- No new third-party UI package without evaluating existing Flutter components and costs.
- No requirement to spin up an EC2 server on every commit, or to deploy a mobile APK automatically on every PR.

## 10. Suggested engineering outputs

After implementation, the repository should contain:

1. `docs/home/MINTTICK_HOME_V2_SPEC.md` — maintained source-of-truth updated as code evolves.
2. `docs/home/MINTTICK_HOME_V2_API_OPENAPI.yaml` — complete schemas and error/quality enums; cross-language DTO contract tests.
3. `docs/home/MINTTICK_HOME_V2_DATA_LINEAGE.md` — mapping every displayed KPI or insight to authoritative source(s), formula, freshness and missing-state rule.
4. `docs/home/MINTTICK_HOME_V2_TEST_MATRIX.md` — section/edge cases, mode and ownership tests, golden baselines.
5. Flutter Home V2 widgets, typed repository/state and navigation updates.
6. Spring Boot Home BFF, repositories, action/insight rules and tests.
7. Any required forward-only Flyway migrations for shipped watchlists, notifications and learning progress.
8. Feature flag and dev rollout/rollback runbook.
9. A final evidence report with before/after screenshots, tested endpoints, CI run IDs, readiness behavior and known not-yet-supported features.

## 11. Copilot execution prompt — paste this together with the spec file and approved mockup

> **ROLE:** Act as MintTick's principal product architect, senior Flutter UX engineer, staff Java Spring Boot backend engineer, database architect, security reviewer, and DevOps engineer. Use this spec as the authoritative design brief, and inspect the latest `dev` repository before changing any code. The attached picture is a target visual hierarchy, NOT live-data evidence.
>
> **MISSION:** Build the complete authenticated MintTick Home V2 experience end to end, aligning its design to the approved mock while treating Home as a customer Trading Workspace command center, not a duplicate Markets screen. Implement every visible section: global header, dynamic greeting, Paper/Live switch, truthful summary KPIs, quick actions, owned Trading Room previews, MintTick Mind panel, Needs Your Attention, Learn & Improve, and shared bottom navigation. Include all missing backend/BFF contracts, persistence only where justified, data quality states, responsive and accessible Flutter components, integration tests and release safety.
>
> **START WITH AUDIT:** Read `minttick_customer_home.dart`, `minttick_mode_switcher.dart`, `trading_workspace_mode.dart`, `customer_room_repository.dart`, `minttick_customer_portfolio.dart`, `app.dart`, `app_theme.dart`, `minttick_brand.dart`, the backend `CustomerTradingRoomController`, `CustomerPaperWalletService`, `CustomerExecutionReadinessService`, broker status APIs and Flyway migrations V64–V66; confirm latest repository state and migration head. Report a precise existing-vs-missing table before implementation.
>
> **RULES:** Reuse approved MintTick near-black theme `#0B0D10` and official brand; minimize visual noise. Use actual JWT-owned customer data and app-managed market feeds. Broker connection is not required for browsing market data. Do not fabricate screenshot figures: P&L, 88% health, ACTIVE execution, 5 ideas, AI coaching, lesson progress or unread alerts must be backed by real implemented services or shown as unavailable. Current customer order execution is blocked by design: never enable it as part of a Home UI change. Keep Paper/Live separate at all layers, with safe error/empty/loading states and no hardcoded names.
>
> **IMPLEMENT:** Follow Phases 0–9 in this spec, in small feature branches and PRs targeting `dev`; preserve existing API contracts and routes. Implement `GET /api/customer/home?mode=PAPER|LIVE` as a secure read-only BFF with typed response and batch DB queries. Add real user-owned features only when their backend + tests are built; hide or truthfully disable unready modules. Build native Flutter responsive components (not screenshot background); wire all actions and status badges to verified data. Maintain explicit source/freshness/quality for amounts and insights.
>
> **VERIFY:** Backend integration and security isolation, customer-room mode tests, no-P&L-without-ledger checks, 320–412px Flutter widget/golden tests, screen reader, navigation and full local IntelliJ Flutter experience against the existing AWS backend. Run applicable `flutter analyze`, `flutter test`, backend Maven tests, Flyway migration tests, and Web build. Do not claim live deployment or APK installation without evidence. Never auto-start stopped EC2 as part of routine dev work.
>
> **DELIVER:** At each PR, provide code diff summary, tests run/results, exact visual screenshots, API contract, feature availability table, and known gaps. Finish with a consolidated verification and developer handoff. Keep the approved mock and this specification under version-controlled docs.

## 12. Source code pointers for Copilot

These links identify the **audited source locations**, not a claim that a new endpoint already exists:

- [Customer Home](https://github.com/operationReact/AiTradingSystem/blob/dev/apps/trading_client/lib/src/features/trading_rooms/presentation/minttick_customer_home.dart)
- [App navigation](https://github.com/operationReact/AiTradingSystem/blob/dev/apps/trading_client/lib/src/app.dart)
- [Mode selector](https://github.com/operationReact/AiTradingSystem/blob/dev/apps/trading_client/lib/src/features/trading_rooms/domain/trading_workspace_mode.dart)
- [Home shared widgets](https://github.com/operationReact/AiTradingSystem/blob/dev/apps/trading_client/lib/src/features/trading_rooms/presentation/minttick_customer_widgets.dart)
- [Customer room HTTP repository](https://github.com/operationReact/AiTradingSystem/blob/dev/apps/trading_client/lib/src/features/trading_rooms/data/customer_room_repository.dart)
- [Customer room controller](https://github.com/operationReact/AiTradingSystem/blob/dev/backend/src/main/java/in/aitradingsystem/tradingrooms/CustomerTradingRoomController.java)
- [Paper wallet service](https://github.com/operationReact/AiTradingSystem/blob/dev/backend/src/main/java/in/aitradingsystem/tradingrooms/CustomerPaperWalletService.java)
- [Execution readiness](https://github.com/operationReact/AiTradingSystem/blob/dev/backend/src/main/java/in/aitradingsystem/tradingrooms/CustomerExecutionReadinessService.java)
- [Customer portfolio](https://github.com/operationReact/AiTradingSystem/blob/dev/apps/trading_client/lib/src/features/trading_rooms/presentation/minttick_customer_portfolio.dart)
- [Personal Angel One connection](https://github.com/operationReact/AiTradingSystem/blob/dev/backend/src/main/java/in/aitradingsystem/provider/angelone/CustomerAngelOneController.java)
- [App-managed market feed](https://github.com/operationReact/AiTradingSystem/blob/dev/backend/src/main/java/in/aitradingsystem/provider/angelone/AppAngelOneMarketFeedService.java)
- [Canonical palette](https://github.com/operationReact/AiTradingSystem/blob/dev/apps/trading_client/lib/src/brand/minttick_brand.dart)
- [Theme](https://github.com/operationReact/AiTradingSystem/blob/dev/apps/trading_client/lib/src/theme/app_theme.dart)
- [Existing Flyway room migration V64](https://github.com/operationReact/AiTradingSystem/blob/dev/backend/src/main/resources/db/migration/V64__customer_trading_rooms_dual_mode.sql)
- [Paper wallet migration V65](https://github.com/operationReact/AiTradingSystem/blob/dev/backend/src/main/resources/db/migration/V65__customer_paper_room_wallet.sql)
- [Blocked order intents migration V66](https://github.com/operationReact/AiTradingSystem/blob/dev/backend/src/main/resources/db/migration/V66__customer_paper_live_order_intent_custody.sql)

---

**Final quality standard:** MintTick Home V2 should feel premium because it is clear, useful, truthful, responsive and actionable—not because it fills the screen with glowing cards. Every number, claim, action, alert and AI statement must have an authoritative implementation path or an honest unavailable state. Markets owns market-wide intelligence; Home owns *the user's workspace and next actions*.
