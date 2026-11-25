# MintTick — Architecture Discovery

Audit date: 2026-11 (repository snapshot on the current working tree). Discovery only; no production code was changed.
Canonical sources read: `AGENTS.md`, `README.md`, `docs/architecture/flutter-delivery.md`, `docs/FLUTTER_CORRECTION_AUDIT.md` (partially), `docs/design-system/minttick-single-design-ux-audit-20261106.md` (partially).
Canonical Notion/Figma sources were **not** accessed in this audit (evidence gap, see §8).

## 1. Repository shape

| Area | Path | Role | Status |
|---|---|---|---|
| Product client (target) | `apps/trading_client/` | Flutter for Web, Android, iOS | Production target per AGENTS.md. ~120 `lib` files, 73 unit/widget test files, 1 integration test |
| Backend | `backend/` | Java/Spring Boot, PostgreSQL, Flyway | Authoritative for risk, auth, execution gating, idempotency |
| Research / ML | `intelligence/` | Python | Research and scanner logic |
| Market feed | `market-feed/` | Lambda + `live_tick_integrity.py` | Feed ingestion |
| Legacy UI | `frontend/` | React/Vite | Behavior reference only; no new product UI (AGENTS.md) |
| Docs | `docs/` (149 files), `frontend/docs/minttick/` (8 screenshots) | Design/audit/architecture | Historical records preserved |
| CI | `.github/workflows/` (74 files) | Flutter analyze/test/web build; connected Android/iOS in separate workflows; visual goldens | See §7 |

## 2. Flutter client architecture (`apps/trading_client/lib/src/`)

- **Entry and shell:** `app.dart` (~656 lines) builds one `MaterialApp`. Its `builder` uses `LayoutBuilder`: width ≥ 900 renders `_DesktopProductRail`; otherwise a bottom `_MobileProductNavigation` with a "More" sheet (`_MoreProductSheet`, ~lines 600–660).
- **Routing:** string-keyed route table inside `app.dart` (`/`, `/rooms`, `/brokers`, `/market`, `/portfolio`, `/activity`, `/notifications`, `/account`, `/settings`, `/admin/*`, operator routes). No typed router.
- **Two IAs, one shell:** `session.isCustomerAuthenticated` selects either the **customer** set (Home, Rooms, Markets, Portfolio, More) or the **operator/PAPER** set (Overview, Markets, AI, Portfolio, Risk, News, Audit, Model control). The same paths (for example `/`, `/portfolio`) render different products depending on session.
- **Admin routes** (`/admin/*`) are registered with no client-side role guard. Enforcement is server-side only (`SecurityConfig.java`).
- **State management:** no Riverpod, Bloc, or Provider in `pubspec.yaml` (grep confirmed). Pattern is `StatefulWidget` + `ChangeNotifier`/`Listenable` (e.g., `TradingWorkspaceMode`, `TradingRoomDraftStore`) plus per-screen repositories.
- **Repositories and API:** `AuthenticatedApiClient` is created via a getter (`_api` in `app.dart`), and repositories are instantiated inside route builders (e.g., `HttpCustomerMarketRepository(_api)`). Nothing is shared or cached across screens.
- **Auth:** Amplify (`amplify_flutter` 2.11.0 in `pubspec.yaml`) with Cognito. A password flow exists only under `kDebugMode` (`customer_identity.dart` ~40–49). Sign-out is in `customer_account_page.dart`.
- **Persistence:** `SharedPreferencesAsync` stores the PAPER/LIVE workspace mode per Cognito user ID (`trading_workspace_mode.dart`).
- **Theme:** `theme/app_theme.dart` (Material 3, seed `#00BFA5`), `brand/minttick_brand.dart` (palette constants), `theme/minttick_typography.dart`. Inter is **not bundled**; the file says so explicitly (line 5).
- **Market-data client:** `features/trading_rooms/data/customer_market_repository.dart` strictly validates `CustomerLiveQuote`. Status must be one of `LIVE, CONNECTING, RECONNECTING, STALE, UNAVAILABLE, MARKET_CLOSED, BROKER_CONNECTION_REQUIRED`. `LIVE` requires `source == 'ANGEL_ONE_SMARTAPI_WS_V2'`. Prices are rejected on non-LIVE. This is a strong provenance boundary.
- **Live refresh:** HTTP polling, not streaming. `minttick_customer_market_page.dart` (~210–241) polls a selected quote every 2 s, with idle back-off (15 ticks) for closed/unavailable states, and pauses on app pause (~70–75).

## 3. Backend architecture

- **Security:** `security/SecurityConfig.java` uses session-based auth, CORS (~84–101), and globally disabled CSRF (`csrf.disable()`, ~38) with a custom `SessionCsrfFilter`. Role matrix (~51–71):
  - `/api/customer/**` → `ROLE_CUSTOMER`
  - `/api/paper/**`, `/api/research/**`, `/api/approvals/**` → role-specific rules
  - other `/api/**` → ADMIN/OPERATOR
  - Upstox OAuth callback → `permitAll` (~61)
- **Dev session bootstrap:** `AuthSessionController.java:55–57`, gated by `app.security.dev-session-bootstrap-enabled` (default false). Acceptable.
- **Customer namespace (`/api/customer/**`):**
  - `CustomerMarketDataController` (`instruments`, `quotes`; lines 40, 92, 99, 127)
  - `CustomerTradingRoomController` (rooms)
  - `CustomerPaperWalletController` (`/activate`, `/account`, `/ledger`)
  - `CustomerExecutionReadinessController` (`/execution/readiness`, `/orders/preflight`)
  - `CustomerTradeIntentController` (`/orders/intents` POST/GET)
  - `CustomerAngelOneController`, `CustomerUpstoxController` (status, connect, funds, holdings, positions, orders, disconnect)
- **Operator namespace:** paper orders, research, approvals, risk, backtests, operations.
- **Persistence:** PostgreSQL with Flyway migrations. Order, risk, and accounting logic remain in Java (AGENTS.md: do not rewrite in Dart).

## 4. Integration map

```
Flutter (web/Android/iOS)
  └─ Amplify/Cognito session ──► Spring Boot (session cookie + CSRF filter)
        ├─ /api/customer/market-data/*  ──► Angel One SmartAPI WS v2 (LIVE source)
        ├─ /api/customer/broker/*       ──► Angel One / Upstox (connect, funds, holdings, positions)
        ├─ /api/customer/paper/*        ──► paper wallet ledger (PostgreSQL)
        ├─ /api/customer/orders/*       ──► preflight + trade intents (no Flutter UI consumes intents)
        └─ /api/paper|research|approvals ──► operator console
  Flutter ──► www.google.com/s2/favicons (third-party logo fetch, see UX-05)
market-feed Lambda ──► live_tick_integrity.py (feed ingestion)
intelligence/ (Python) ──► research/scanner outputs consumed by operator AI screens
```

## 5. Legacy React app (`frontend/`)

- Routes and nav in `frontend/src/App.jsx` and `frontend/src/components/AppShell.jsx`: Overview, Signals, Paper trades, Portfolio, Risk, Approvals, Instruments, Market data, Research, Backtesting, Operations, Notifications, Audit, Settings.
- Breadcrumb shows **"SignalForge"** (legacy brand; `AppShell.jsx` ~37).
- `frontend/src/components/*/pro|clear/*.jsx` are 4-line re-export stubs for "Pro Terminal" and "Clear Invest" variants: duplicate scaffolding with no behavior.
- Per AGENTS.md, React is reference only. No React product features are to be added.

## 6. Confirmed architectural risks

1. Two IAs and four route families in one client (customer, operator, admin, legacy) increase maintenance cost and confusion.
2. Route-level client objects are re-created per access; no shared cache or stream; repeated fetches on navigation.
3. Live pricing is polled per selected symbol, not streamed; the market list has no batched price feed.
4. Customer order-intent and preflight endpoints exist, but no Flutter screen consumes them. The customer execution lifecycle is not represented in the client (feature gap, not a backend bug).
5. Visual tokens are split between `app_theme.dart`, `minttick_brand.dart`, and hard-coded colors/sizes in feature screens (see `design-system-proposal.md`).

## 7. Build and test pipeline

- `.github/workflows/flutter-client.yml`: `flutter pub get`, `flutter analyze`, `flutter test --file-reporter json`, `flutter build web --release`.
- Android/iOS: `flutter-connected-runtime.yml`, `minttick-connected-android.yml`, `phase11-android-apk.yml`.
- Visual regression: `flutter-visual-goldens.yml`.
- **Not executed in this audit.** The baseline pass/fail of analyze and tests is unknown here (evidence gap).

## 8. Assumptions and evidence gaps

- The eight images in `frontend/docs/minttick/` are assumed to be the intended screenshot set. Confirm if more exist.
- Notion (product requirements, tracker) and Figma (`KjBWVCtUYona0bOGb5NDov`, handoff `30:2`) were not accessed. Design conformance is unverified.
- No running app, device, or browser session was used. All runtime statements derive from source and screenshots.
- `docs/FLUTTER_CORRECTION_AUDIT.md` was read only in part (header and findings list). Its phase-by-phase table was not reconciled line by line.

## 9. Confirmed non-issues

- Non-ASCII mojibake (`â‚¹`, `Â·`) in terminal output is a console decoding artifact; source files are clean.
- No glass/blur effects: `BackdropFilter`/`ImageFilter` have zero matches under `apps/trading_client/lib`.
