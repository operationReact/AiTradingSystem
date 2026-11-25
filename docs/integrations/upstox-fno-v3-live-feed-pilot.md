# MintTick — Upstox F&O Market Data V3 WebSocket pilot

Issue #1063. Owner-requested next slice following merged #1064 and #1068.

## Shipped in this PR
- **Same existing Analytics SSM token** `/minttick/upstox/analytics-token`, read by `UpstoxAnalyticsSource`. No second credential loader, customer OAuth, public F&O quote API, order placement, or new AWS resources.
- `GET /v3/feed/market-data-feed/authorize` through existing read-only Upstox REST helper. Validate the returned one-use `wss://*.upstox.com/market-data-feeder/v3/...` destination (host, TLS, port, query, path), do not log or cache the signed URL, request a fresh one on reconnect.
- JDK 21 `java.net.http.WebSocket`, **binary UTF-8 JSON** V3 `sub` control message, `full` mode and Upstox V3 binary Protobuf feed decoder. V2 `option_chain` subscription mode is explicitly rejected.
- Current pilot supports **up to 64 option contract keys**, on one connection, selected only from live Upstox-listed, same-expiry, CE+PE pairs nearest the verified current underlying spot. No full-universe subscription, no order action or streaming through Flutter.
- Decode bounded frames (max 1 MiB), first-level bid/ask, LTP, **exchange last-traded time** (`ltt`), frame `currentTs`, OI, IV and available Greeks from `FirstLevelWithGreeks` and `FullFeed.marketFF`; test using synthetic binary V3 wire records built from the published schema.
- Strictly bounded one-socket reconnect with fresh one-time authorization for each retry, capped exponential cooldown and 5 reconnect attempts. Private in-memory observations only, no retention, publishing or training.
- Operator controls behind an explicit OFF-by-default feature flag:
  - `POST /api/admin/market-data/upstox-analytics/fno/live/start?expiry=YYYY-MM-DD`
  - `GET /api/admin/market-data/upstox-analytics/fno/live/status`
  - `POST /api/admin/market-data/upstox-analytics/fno/live/stop`
- All routes require `ADMIN` or `OPERATOR` and are **not** allowed through customer Account API edge. They expose **status/counters only**, no raw quotes, token, signed URL, provider error or customer data.
- Client state has explicit STOPPED / CONNECTING / SUBSCRIBING / CONNECTED_AWAITING_TICKS / CONNECTED / RECONNECTING / FAILED_RECONNECT_LIMIT states. A socket handshake is not equivalent to a valid F&O market tick.

## Safe activation prerequisites
1. Merge only after required exact-head CI; no connected production proof implied by mock tests.
2. Confirm current deploy's exact revision and existing SSM `SecureString` read rights.
3. Confirm permitted read/display/storage/training uses separately. **Analytics API access does not grant blanket redistribution/model-training rights.**
4. In protected backend runtime configuration, enable existing `MINTTICK_UPSTOX_ANALYTICS_ENABLED=true` and set **`MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED=true`** (Spring relaxed binding for `minttick.upstox-fno.v3-feed-enabled`). Defaults remain OFF in source control.
5. Choose a valid actual NIFTY expiry returned by the Upstox current instrument master; never guess future expiries.
6. From an authenticated private ADMIN/OPERATOR session, call `POST .../live/start?expiry=YYYY-MM-DD` against the existing operator-accessible backend, **not the customer account edge**.
7. Check `GET .../live/status`: verified socket subscription state, actual decoded frame count, observed instrument count, latest exchange trade time and safe error/cooldown state. A Saturday or market-closed last tick is not live/tradable. Close with the stop route.
8. Confirm no changes to live broker order routing and no paid feed/data-retention settings.

## Important limitations and next implementation
- The first pilot does **not** publish raw live market data to Flutter or the Mind engine, since display and model usage rights have not yet been verified. It provides a real protocol implementation that can be activated and observed privately.
- The codec is deliberately schema-scoped; retain observed original last-trade timestamps, do not replace them with server receipt times. An expired or absent `ltt` is not executable freshness.
- No account-wide order authorization exists here. Even after a successful WebSocket source read, **F&O PAPER trading** needs a separate verified quote/contract/risk path and **LIVE** needs separate broker OAuth, segment entitlement, funds/margin, IP, consent, order idempotency and reconciliation.
- Before broadening beyond the pilot, implement distributed subscription budgeting across any other Upstox streams and strict source rights; Upstox standard access currently allows two WebSocket connections/user, and V3 full-mode limits are larger than this intentionally bounded pilot.
- Advance capability status only from real provider runtime receipts and matching deployed revision, not GitHub CI.

## Provider documentation
- https://upstox.com/developer/api-documentation/analytics-token/
- https://upstox.com/developer/api-documentation/v3/get-market-data-feed/
- https://upstox.com/developer/api-documentation/get-market-data-feed-authorize-v3/
- https://raw.githubusercontent.com/upstox/upstox-python/master/examples/websocket/market_data/v3/MarketDataFeedV3.proto

## Scope isolation
This PR touches only the existing Upstox source's new V3 authorization method and new `UpstoxFnoV3*` classes/tests/docs. No Claude-owned Mind screens, no worker-owned canonical ingestion or Golden dataset tables. Source remains read-only and inactive by default.
