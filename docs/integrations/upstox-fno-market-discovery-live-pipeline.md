# MintTick F&O — true V3 market-discovery pipeline

## What is now connected

- Customer `GET /api/customer/markets/fno/capability` reports **V3 socket state and fresh exchange-timed contracts**, not a misleading 'configured = live' badge.
- Customer `GET /api/customer/markets/fno/expiries` uses provider-listed NIFTY dates from the existing rights-gated Upstox expiry producer.
- Customer `GET /api/customer/markets/fno/market?expiry=YYYY-MM-DD` separates 60-second immutable Upstox contract identity from fresh **in-memory V3 Protobuf bid/ask/LTP/OI/Greeks**. REST chain prices are never used as current or executable ticks.
- Flutter F&O Markets polls the quote endpoint every 5 seconds while visible, refreshing fresh exchange timestamps; a quote disappears after 15 seconds without valid exchange+server observations and on any request failure. Pauses when Android is not active.
- All provider-listed NIFTY pairs (up to 256 strikes) are shown under **Chain** as actual listed contract identity, with All CE+PE default. Full analytics no longer truncates the 20 first legs. OI/IV/Greeks, lot, tick size, last price, previous close, spread and timestamps are shown only when an actual exchange-fresh V3 observation exists. Market data subscriptions remain intentionally bounded to 64 nearest contracts, so the remaining listed contracts are **identity-only**, never invented live quotes. Customer display remains independently rights-gated.
- New `UpstoxFnoAutoMarketFeed` uses the **same** service and the **same** provider-validated near-ATM 64-key CE/PE selector as the private admin start endpoint. It runs only inside verified NSE F&O sessions, stops **only its own socket** outside trading hours, never interrupts an operator-managed stream, and uses bounded 3-minute retry cooldown.
- No trading-room, broker OAuth or user-supplied API credential is needed to *discover* F&O quotes. The provider Analytics token stays private in existing AWS SSM.
- No automatic LIVE order, PAPER fill, model inference or model training is implied by a live market display.

## Explicit activation (reviewed operator workflow)

Defaults remain OFF. On the protected backend host only, the established runtime configuration must contain the following flags after independently validating account-level source access, customer-display rights and Upstox/exchange contractual permissions:

```dotenv
MINTTICK_UPSTOX_ANALYTICS_ENABLED=true
MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED=true
MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED=true
MINTTICK_UPSTOX_FNO_AUTO_MARKET_FEED_ENABLED=true
```

Do **not** set `...DISPLAY_RIGHTS_VERIFIED=true` solely because SSM has an Analytics token, because a UI example displays prices, or because CI passes. Preserve independent PAPER-use rights, model-use rights, and LIVE order controls. Deploy the reviewed exact commit via existing protected one-file deployment workflow. No new AWS account, public port, Lambda credential, customer API key or third-party feed is required.

## Runtime acceptance

1. Inspect exact running backend SHA, app flags, expiry source and provider egress without revealing credentials.
2. During open exchange hours, confirm `capability.status=LIVE_QUOTES`, `streamState=CONNECTED`, positive fresh counts and a recent `latestFreshExchangeTradeAt`. A connected socket without exchange-timed quotes yields WAITING_FOR_QUOTES or FEED_DISCONNECTED, **never LIVE**.
3. Open Markets → F&O → Explore/Chain. Verify actual Upstox NIFTY expiry, strike, CE/PE, bid, ask and quote age, with no sim keys.
4. Disconnect stream / pause Android; verify stale quotes disappear and empty states show source/market status. At F&O session close, the automated stream is stopped and displayed data is NOT marked live.
5. Independently verify permitted PAPER simulation and Mind model/derived-data use before using these ticks for virtual fills or predictions. Training, replay, persistence and customer redistribution are not authorized by this integration.

## Scope still to implement

Full BANKNIFTY / SENSEX / stock-option / futures / commodity universes require separate source validation, subscription budgets and exchange permissions. Popularity, intraday momentum, Greeks for non-subscribed strikes, historical charting and AI strategy inference require verified input history, coverage and explicit model-use rights.
