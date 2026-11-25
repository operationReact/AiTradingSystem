# MintTick — Architecture Improvements

Scope: recommendations that support the redesign. The backend remains authoritative for risk, authorization, source identity, expiry, and idempotency (AGENTS.md). No Dart re-implementation of risk or accounting. No offline mutation queue; no automatic retry after UNKNOWN.

## 1. Provider-independent market-data interface

**Problem:** the client validates `source == 'ANGEL_ONE_SMARTAPI_WS_V2'` (`customer_market_repository.dart`), so the UI is coupled to one broker.

**Recommendation:** a backend-owned quote contract:
```
{ instrumentId, symbol, exchange, status, price?, change?, changePct?,
  asOf, receivedAt, source: { provider, feedId }, sessionState }
```
- `status` enum stays as-is (`LIVE, CONNECTING, RECONNECTING, STALE, UNAVAILABLE, MARKET_CLOSED, BROKER_CONNECTION_REQUIRED`).
- The client does not branch on provider names; it renders `source.provider` as a label.
- Versioned contract with a backend contract test and a Flutter parser test.

## 2. Instrument master and symbol resolution

- Backend serves an instrument master: `instrumentId`, `symbol`, `exchange`, `displayName`, `sector?`, `logoAssetId?`, `nameSource`.
- If `displayName` is missing, return `nameUnavailable: true`; the client renders the symbol with an explicit label, not a guess.
- Cache on the client by version; no per-row lookups.

## 3. Logo and branding pipeline

- Remove the third-party favicon fetch (`minttick_equity_avatar.dart`).
- Logos served from a backend asset store with license metadata and fixed size variants (48, 96).
- Client uses `logoAssetId` only; the fallback monogram is deterministic.

## 4. Streaming lifecycle

- Backend: SSE or WebSocket endpoint (e.g., `/api/customer/market-data/stream`) with per-instrument subscriptions, heartbeat, and `asOf` on every message.
- Client: one stream per session; subscribe on screen visibility; pause on app background; reconnect with exponential backoff and jitter; on reconnect, reconcile via snapshot (`/quotes`) before resuming updates.
- Degrade: on stream failure, fall back to polling at the current 2 s with back-off; show STALE if `asOf` is older than the threshold.
- Preserve the current idle back-off and app-pause behavior (`minttick_customer_market_page.dart` ~70–75, ~210–241).

## 5. Freshness and provenance

- Every price response carries `asOf` (provider timestamp) and `receivedAt` (server time).
- Client computes staleness from `asOf` against a configured threshold and shows the status.
- Logs record source and staleness for audit.

## 6. Authentication and broker-owner binding

- Verify that every broker endpoint (`CustomerAngelOneController`, `CustomerUpstoxController`) binds to the authenticated principal's customer ID, not a client-supplied one.
- The Upstox OAuth callback is `permitAll` (`SecurityConfig.java` ~61). Verify `state` binding and single-use semantics, and document the check.
- CSRF is globally disabled (`SecurityConfig.java` ~38) with `SessionCsrfFilter`. Verify that all state-changing customer endpoints are covered (evidence gap).
- Dev session bootstrap remains off by default (`AuthSessionController.java:55–57`). Add a test that the production profile cannot enable it.

## 7. Trading workflow state machine

Customer order lifecycle (backend-owned, client-rendered):

```
DRAFT ─► PREFLIGHT_OK ─► AWAITING_APPROVAL ─► SUBMITTED ─► FILLED
   │          │                 │                 │
   │          └─► BLOCKED       └─► REJECTED      └─► REJECTED / CANCELLED
   └─► EXPIRED                                      └─► UNKNOWN (check status; no auto-retry)
```
- Each transition carries an idempotency key and `expiresAt`.
- The client shows only server state; no optimistic "submitted".
- UNKNOWN requires an explicit user action to re-check status.

## 8. Risk, auditability, and safe automation

- Automation has a server-side pause flag; the client shows it and can request a pause.
- Risk limits (per-day loss, maximum exposure) are server-enforced; the client displays them with source.
- Audit: every customer action (mode change, room plan, preflight, intent) is recorded with the user ID (masked in UI) and timestamp.
- LIVE execution stays disabled until separately gated. The client reads readiness and does not infer permission.

## 9. Contracts and generated clients

- Publish OpenAPI from the Spring backend; generate Dart models or add contract tests against fixtures.
- Single error envelope: `{ code, message, retryable, asOf? }`. The client maps `code` to a state view (UX-33).

## 10. Client architecture

- Feature modules: `customer/`, `operator/`, `admin/`, `shared/design/`.
- Dependency injection at the app root: one `ApiClient`, one repository per feature, provided per session.
- Routing: typed route keys; role guard in the router, not in widgets.
- Split `app.dart` (~656 lines) into shell, router, and feature registry.
- State: adopt an explicit async-data type (a `Result`/`AsyncValue`-style pattern) or keep `ChangeNotifier` but standardize loading, error, and data states.
- Observability: privacy-safe event schema (screen, state, error code, latency); no PII, no full user IDs, no symbol lists beyond counts.

## 11. Testing strategy

- Contract tests: backend OpenAPI snapshot vs Flutter parsers.
- Widget tests: every quote status, every error code, every execution-state variant.
- Golden tests: 390×844, 320/360/411/600 widths, 1.0/1.3/1.5 text scale, both themes.
- Route tests: role guards and module boundaries.
- Device runs: Android APK and iOS on supported devices (separate evidence; existing connected workflows).
- Network test: no third-party calls during browsing.

## 12. Backend items to verify before client promises

| Item | Verify |
|---|---|
| Batched quote endpoint | Not found in the customer controllers reviewed |
| Notifications for customers | `NotificationController` customer scope |
| Customer paper wallet UI | Endpoint exists; client usage to confirm |
| Order intent client consumption | None found in client |
| Insight/automation status API | Not found |
| Instrument master display names | Coverage of 2,756 equities |

## 13. Frontend-only vs backend-dependent issues

- Frontend-only: hard-coded colors and sizes, placeholder text, favicon fetch, mode-control wording, chip layout, empty-state layout.
- Backend-dependent: names, batched quotes, readiness reasons, P&L, insight status, notifications, streaming.
- Do not synthesize data in the UI to hide backend gaps.

## 14. Migration order

1. Copy and safety fixes (no backend change).
2. Design tokens and shared components.
3. Backend contracts for quotes and names.
4. Streaming and freshness.
5. Trading state machine and P&L.
6. Mind surface.

## 15. Markets M3 contract: day OHLC detail (app-session only)

- Route: `GET /api/customer/market-data/quotes/detail?instrumentId=` (customer role, cash equity only, read-only).
- Source: the application SmartAPI session, FULL mode, one token per request, on the MARKET_QUOTE limiter. Returns 503 when the app feed is unavailable; it never falls back to the broker or Lambda.
- Response: `instrumentId, exchange, tradingSymbol, ltp, open, high, low, previousClose, volume, exchangeAt, fetchedAt, source` (`ANGEL_ONE_SMARTAPI_FULL_APP`), status `ON_DEMAND`, `Cache-Control: no-store`.
- Validation: identity must match the instrument master, feed time must parse and not be in the future, prices positive, high >= low, LTP and open within [low, high], volume >= 0. `previousClose` is excluded from range checks.
- Open items: confirm FULL field names and the meaning of `close` against a live sample; the 50-token cap is a repo assumption; the bulk screener and index rows need a rate-budget decision.

## 16. Markets M4 contract: owner-scoped watchlist

- Routes (customer role, ccount-api allowlist only): GET /api/customer/watchlist, POST /api/customer/watchlist with {"instrumentId": n}, DELETE /api/customer/watchlist/items/{instrumentId}.
- Storage: customer_watchlist_item (V67), keyed by (user_id, instrument_id), with exchange and 	rading_symbol snapshotted at add time. All queries are bound to the authenticated owner.
- Add: the instrument must resolve in the active catalogue (404 INSTRUMENT_NOT_FOUND otherwise). Returns 201 when added, 200 when already present, 422 WATCHLIST_LIMIT_REACHED at 100 items. Remove is idempotent (204).
- Reconciliation: `GET /api/customer/watchlist` checks each stored row against the active catalogue. A row whose ID still maps to the same exchange and trading symbol is `ACTIVE`. A row whose ID no longer matches is repointed to the single active instrument with the same exchange and symbol, and is `ACTIVE` afterwards. A row with no unique match is returned as `UNAVAILABLE` with its stored ID, and it is never rewritten. A repoint onto an ID the owner already holds removes the stale row, so there are no duplicates.
- Known limitation: reconciliation runs on read, so a stale row is repaired on the owner's next list request, not at catalogue activation. `UNAVAILABLE` rows can only be removed. The Flutter client matches membership by the current ID, so an `UNAVAILABLE` row never matches an instrument.
- Verified: `CustomerWatchlistReconciliationIT` runs the lookups and repoint SQL against PostgreSQL 16 (Testcontainers) with Flyway migrations applied. Controller unit tests cover the reconciliation paths.

## 17. Markets M3/M4 contract: Nifty 50 overview, sectors, index definitions

- Route: `GET /api/customer/market-data/overview` (customer role, allowlisted in `AccountApiOnlyFilter`, read-only, `Cache-Control: no-store`). Served from a 30-second in-process cache.
- Source: the application SmartAPI session, FULL bulk quotes in batches of at most 50 tokens. No broker fallback. Returns 503 when the app feed is unavailable.
- Scope: breadth, movers (gainers, losers, gap up, gap down), and sector summaries cover Nifty 50 constituents only. The response states this in `breadthScope: NIFTY50_CONSTITUENTS`. It is not a whole-market scan.
- Universe: `market_universe_member` (V68) holds 50 NIFTY50 members with the NSE Industry column as sector. Snapshot `as_of` 2026-11-09. It needs periodic refresh from the NSE constituent file.
- Indices: `market_index_definition` (V68) holds NIFTY50, BANKNIFTY, NIFTYNXT50, NIFTYIT, NIFTYAUTO and INDIAVIX with their verified tokens. Indices are not in `instrument_master`, because NSE index symbols contain spaces that the importer rejects. SENSEX is excluded until its token is verified.
- Validation: the batch parser checks exchange, token and trading symbol. Index rows must carry the verified display name (for example "Nifty 50", "Nifty Next 50"). Rows with a mismatched name are dropped. Live FULL sample (2026-11-08, EC2) confirmed all six index tokens resolve with these names.
- Not verified: a live `/overview` sample during market hours, index freshness during market hours (index rows report zero `lastTradeQty` and `tradeVolume` in the sample), the 50-token cap (repo constant, not checked against current SmartAPI docs).
- Index freshness: each index row carries `freshness`, computed server-side from `exchangeAt` (exchFeedTime, IST) and today's cash-session open. `LIVE` when `exchangeAt` is on or after the session open. `STALE` when it is before the open, during the session. The app hides the value and shows "Stale · last tick" instead. `LAST_CLOSE` when the cash session is closed or it is a non-trading day. The app shows "Last close". Assumption to verify on a trading day: index `exchFeedTime` advances during market hours. If it does not, indices show STALE during hours and this rule must change.
- M5 (MintTick Mind card) is deferred by product decision. The Markets page has no Mind card. The Home Mind cards stay static and non-clickable until a backend status endpoint exists.
- Flutter: Overview, Movers and Sectors tabs poll every 30 seconds while the app is active. Pause clears overview values. Errors show a labelled message, never stale values. Mover rows open stock detail. The overview does not return `instrumentId`, so the app resolves the instrument by exact `tradingSymbol` through catalogue search and shows a message when there is no exact match.

## 18. Stock detail contract: depth, VWAP and 52-week range (img_4)

- Source: the same SmartAPI FULL quote used by `GET /api/customer/market-data/quotes/detail` (application session only, one call per stock).
- New fields on the response: `averagePrice` (provider `avgPrice`, VWAP), `week52High`, `week52Low` (provider `52WeekHigh`/`52WeekLow`), `totalBuyQuantity`, `totalSellQuantity`, and `bids`/`asks` (up to five levels, zero-priced levels dropped).
- A zero or missing provider value is returned as null (VWAP, 52W) or omitted (depth levels). The client shows a dash, never an estimate.
- Verification status: parser and controller unit tests pass. Whether a live NSE stock returns non-zero `avgPrice`, `52WeekHigh`/`52WeekLow` and depth during market hours is UNVERIFIED; the EC2 sample checked so far was index tokens, which return zeros.
- Still open for img_4 (not built): intraday 09:15–15:30 line chart and 1W/1M/3M/1Y ranges, candle toggle and fullscreen, News (no provider; shown unavailable), Sector and Similar Stocks (rules-based, not built), MintTick Mind card (rules-based text from real fields, not built), and the "Analyze in Trade Room" target (undecided).
