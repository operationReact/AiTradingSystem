# MintTick live feed — implementation and acceptance contract

## Verified baseline (2026-11-08, PAPER)
- On-host catalogue activation and independent read-only inventory succeeded after PRs #619–#622.
- 2,778 active NSE/BSE cash-equity instruments; last import SUCCEEDED.
- The current Flutter home quote panel polls the read-only HTTP quote API every 60 seconds. Its fetchedAt is the backend retrieval time, **not** the exchange timestamp. It is NOT a live WebSocket feed.
- **Special-session exception:** Sunday, November 8, 2026 has Diwali Muhurat trading. NSE cash continuous session opens 14:20 IST; non-CAS securities close 15:30 IST and CAS securities transition to closing auction from 15:15 IST. The correct test window is exchange-calendar-driven, not a blanket Sunday closure. See NSE capital-market circular NSE/CMTR/76941 (Oct 19, 2026). Passing build and infrastructure checks cannot establish actual open-market streaming acceptance.

## Target architecture (not yet deployed)
1. Keep user/session credentials and Angel One JWT/feedToken server-side in approved secure storage and a dedicated, long-lived read-only streaming process. Do **not** put provider credentials or broker tokens into Flutter, browser URLs, logs, GitHub Actions or an untrusted client.
2. Connect using Angel One SmartAPI WebSocket V2 (wss://smartapisocket.angelone.in/smart-stream), with authenticated server-side headers, ping/pong heartbeat, bounded reconnect/backoff, session renewal, and resubscription after reconnect.
3. Resolve the already-active catalogue to canonical instrument IDs *before* subscribing; NSE_CM = 1, BSE_CM = 3. Subscribe in LTP mode 1. Enforce the broker's 1,000 token-mode subscriptions per connection and maximum three connections per client code. Budget subscriptions to actual watched instruments, not every catalogue entry.
4. Decode binary LTP frames; use **exchange timestamp** and sequence number, not HTTP retrieval time. Apply the pure, offline-tested gate in market-feed/live_tick_integrity.py to reject unknown/unsupported tokens, nonpositive prices, malformed packets, stale/future ticks, replay/out-of-order data and disconnects. Heartbeats never count as quote freshness.
5. Publish only verified canonical instrument ID, venue, price, exchange timestamp, receive timestamp and source through a separately authenticated/authorized server-to-Flutter channel. Do not expose raw broker credentials or broker symbol tokens.
6. Server-side exchange calendar and market phase control MARKET_CLOSED. A closed market shows the last close if separately labelled; no historical price can be labelled LIVE. During open market, stale/missing/reconnecting status must disable live-based decisions until validated ticks resume.
7. Execution/risk services must independently verify market state/source/freshness/identity server-side, never trust a UI LIVE badge. Preserve PAPER mode and existing approvals; no order APIs in this stream.

## Why this PR is deliberately narrow
This PR adds the deterministic **read-only WebSocket V2 LTP validation boundary**, mock binary-frame tests and subscription planning. It does **not** start a network socket, create cloud resources, activate LIVE trading, change Lambda auth, expose data to customers or claim real quote delivery. The current private Lambda remains a bounded, on-demand REST read service; do not run a persistent WebSocket inside that Lambda.

## Acceptance gates, in order
- Offline tests pass for valid NSE/BSE packets and all reject cases; market-feed CI is green.
- A separately authorized, long-lived private worker can establish the broker socket and show sanitized connect/subscription/heartbeat/expiry evidence with no credentials in logs.
- During NSE/BSE open hours, receive genuine provider ticks for a small permitted watchlist; verify exchange timestamp, instrument identity, continuity and latency using broker-sourced evidence.
- Simulate feed loss, replay, stale timestamp, heartbeat-only connection, auth/session expiry and reconnect; prove no quote is presented as LIVE and risk decisions fail closed.
- Verify Flutter web/Android/iOS displays LIVE, DELAYED, RECONNECTING, CLOSED and UNAVAILABLE based only on backend-authored state and safely handles suspend/resume.
- Confirm Angel One and exchange market-data display/redistribution entitlements before any multi-user production release. PAPER pilot results do not authorize commercial use.
- Trading activation remains blocked until separate approval and independent operational acceptance.

Sources:
- https://smartapi.angelone.in/docs (SmartAPI WebSocket Streaming 2.0).
- https://nsearchives.nseindia.com/content/circulars/CMTR76941.pdf (NSE Diwali 2026 special cash session).
- https://github.com/angel-one/smartapi-python/blob/main/SmartApi/smartWebSocketV2.py (reference V2 binary layout).
