# MintTick — Feature Inventory

Status legend: **Working** (real backend or provider data, verified in source), **Partial** (some real data or flow, key parts missing), **Placeholder** (UI copy only), **Mocked** (fabricated data; none found), **Gap** (backend or UI missing), **Unverified** (not exercised at runtime).

Scope: the Flutter client (`apps/trading_client/lib/src/app.dart` routes) is the production surface. Legacy React routes are listed separately.

## A. Customer surface (signed-in customer)

| # | Screen / Route | Purpose | Implementation | Status | Evidence |
|---|---|---|---|---|---|
| C1 | Sign in / welcome / create account / password recovery | Authentication | Amplify/Cognito; password flow debug-only | Partial | `customer_identity.dart` ~40–49; welcome and create-account files hard-code colors |
| C2 | Home (`/`) | Workspace summary, market overview, planned allocations | Market overview via `minttick_market_preview.dart`; allocation card `₹0` labeled "not actual balances"; Mind card is static copy | Partial | `minttick_customer_home.dart` ~225, ~297–304 |
| C3 | Market overview tiles (Home) | Quick quotes for RELIANCE/TCS/INFY | Fixed symbols (`-EQ`); shows "Unavailable" when broker not connected | Working (honest empty state) | `minttick_market_preview.dart` ~96, ~130–216 |
| C4 | Markets (`/market`) | Browse 2,756 cash equities | Paged list, search, exchange filter; no price in rows; prices only on selection | Partial | `minttick_customer_market_page.dart` ~399–414, ~210–241 |
| C5 | Stock detail (within Markets) | Live quote for selected instrument | Quote via `/api/customer/market-data/quotes`; strict provenance validation; 2 s poll | Working (requires Angel One) | `customer_market_repository.dart` |
| C6 | Trading Rooms (`/rooms`) | Plan rooms in PAPER or LIVE | Room creation with PAPER/LIVE segmented control; Mind preview labeled "Examples only" | Partial | `minttick_rooms_pages.dart` ~335–338, ~447–481 |
| C7 | Room Activity tab | Room events | Tab exists; content depends on room state | Partial | `minttick_rooms_pages.dart` ~916–929 |
| C8 | Live room creation | Plan a LIVE room | UI and CTA ("Plan a Live Room", "Create Live room") present; execution not enabled per README | Partial / Safety-sensitive | screenshots `19.32.51 (1)`, `19.32.52`; README |
| C9 | Trading readiness / preflight | Check order readiness | Backend endpoints exist (`CustomerExecutionReadinessController`); client only reads readiness | Partial | `CustomerExecutionReadinessController.java:35` |
| C10 | Order intent placement / approval | Submit trade intent | Backend `CustomerTradeIntentController` exists (`/orders/intents`); **no Flutter UI consumes it** | Gap (client) | grep for `orders/intents` in `apps/trading_client/lib` returned no client call |
| C11 | Portfolio (`/portfolio`), Paper | Customer paper wallet | Backend `CustomerPaperWalletController` (`/account`, `/ledger`); Paper branch copy says "No customer Paper orders or strategy execution are enabled" | Mismatch (UI copy vs backend) | `minttick_customer_portfolio.dart` ~240 |
| C12 | Portfolio, Live (Angel One) | Broker funds, holdings, positions, orders | Backend `CustomerAngelOneController` (status, funds, holdings, positions, orders); UI shows "Portfolio data could not be verified" when unavailable; copy says "Live order execution is not authorized" | Partial (read-only) | `minttick_customer_portfolio.dart` ~277 |
| C13 | Brokers (`/brokers`, broker hub) | Connect Angel One / Upstox | `CustomerAngelOneController`, `CustomerUpstoxController`; Upstox daily-cutoff note in `app.dart` sign-out | Partial | `minttick_broker_hub_page.dart` |
| C14 | Activity (`/activity`) | Mind/room activity log | Placeholder: "No Mantic Mind activity yet" | Placeholder | `app.dart` route → `_customerState('Activity')`; `minttick_customer_states.dart:41` |
| C15 | Notifications (`/notifications`) | Customer alerts | Placeholder; backend notification controller not wired for customer | Placeholder / Gap | `minttick_customer_states.dart` |
| C16 | Account (`/account`) | Profile, verified email, sign out | Full permanent user UUID displayed, selectable | Working | `customer_account_page.dart:136` |
| C17 | Settings (`/settings`) | Preferences | Appearance (dark/light) present | Partial (verify) | `app.dart` route table |
| C18 | MintTick Mind (AI) | Insights, explanations, strategy | Customer surface is copy only; Mind card static; no backend status | Placeholder | `minttick_customer_home.dart:297–304`; `app.dart` "Mantic Mind" at 539, 602 |
| C19 | P&L (realized/unrealized), risk, exposure for customers | Position health | Not present in customer UI | Gap | none |

## B. Operator / PAPER surface

| # | Screen / Route | Purpose | Status | Notes |
|---|---|---|---|---|
| O1 | Overview | Operator health summary | Partial | Real backend; depth unverified |
| O2 | Markets (market data page) | Instrument/candle browsing | Partial | Backend supplies catalogue/candles; live LTP not present (2026-11-06 audit) |
| O3 | AI / Research (`research_page`) | Scanner form and research evidence | Partial | Scanner form shown before context (2026-11-06 audit P1) |
| O4 | Paper orders (`paper_orders_page`) | Simulated orders | Working (backend) | Operator-only |
| O5 | Risk and approvals | Approval queue, risk state | Partial | `risk_approvals_page` |
| O6 | News | News adapters | Partial | Existing adapters; ML/news confidence not proven (`FLUTTER_CORRECTION_AUDIT.md` finding 7) |
| O7 | Audit | Audit log | Partial | Depth unverified |
| O8 | Model control | Model governance | Gap / Unverified | P16–P21 scope unproven |
| O9 | Operations health, trading control, paper runtime cards | Runtime status | Partial | `operations_health_page.dart`, `trading_control_card.dart`, `paper_runtime_card.dart` |

## C. Admin surface (`/admin/*`)

Routes exist in `app.dart` without a client-side role guard. Access is enforced server-side (`SecurityConfig.java`). Status: Partial; functional depth not audited.

## D. Legacy React (reference only)

| Route | Status |
|---|---|
| Overview, Signals, Paper trades, Portfolio, Risk, Approvals, Instruments, Market data, Research, Backtesting, Operations, Notifications, Audit, Settings | Legacy; do not extend |
| "Pro Terminal" / "Clear Invest" experiences | Stubs (4-line re-exports) |

## E. Cross-cutting

| Capability | Status | Evidence |
|---|---|---|
| Provider-independent market-data interface | Gap | Client is tied to the Angel One payload contract (`ANGEL_ONE_SMARTAPI_WS_V2`) |
| Instrument master with names | Partial | 2,756 equities listed; many titles fall back to symbol (screenshot `19.32.52 (2)`) |
| Company logos | Partial | 14-domain map plus third-party favicon fetch (see UX-05) |
| Streaming (WebSocket/SSE) to client | Gap | Polling only |
| Market-data freshness/source display | Partial | Shown on selection; absent in lists |
| Offline mutation queue | Intentionally absent (AGENTS.md) | none |
| Test coverage | Partial | 73 unit/widget tests, 1 integration test; goldens workflow exists |
| Accessibility tests | Partial | `accessibility_contrast_test.dart` exists; no device-level evidence |

## F. Gaps summary (product level)

1. Customer order lifecycle (intent → preflight → approval → status) has backend endpoints but no client flow.
2. Customer AI (MintTick Mind) has no backend-fed status, explanation, or control surface.
3. Customer P&L, exposure, and risk are not shown.
4. Notifications and Activity are placeholders for customers.
5. Customer paper-portfolio copy contradicts the existing backend wallet endpoints.
