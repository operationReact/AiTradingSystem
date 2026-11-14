# MintTick F&O source phase 0 — Upstox Analytics (issue #1063)

## Scope and exact ownership
This slice extends **the existing** `UpstoxAnalyticsSource` to read bounded current Upstox option-contract metadata and a put/call option-chain snapshot with the existing SSM `/minttick/upstox/analytics-token`. It does not create a second token loader, provider integration, new downloader, public route, automatic scheduler, paper/live broker order path, database write, market-data archive, training permission or AWS resource.

Source owner is Upstox Analytics Worker 1. Canonical dataset Worker 2 owns subsequent broker-neutral F&O identity and rights contracts. Claude owns customer-facing Futures & Options, Option Chain and Payoff Builder pages under #998. Workers 3–5 retain their coverage, fundamentals and operations scopes.

## Supported read-only methods (no public controller)

- `currentFnoOptionContracts(underlyingKey, expiry)`: bounded `GET /v2/option/contract`, validates request and response; returns contract key, CE/PE, strike, expiry, lot size, tick size and trading symbol.
- `currentFnoOptionChain(underlyingKey, expiry)`: bounded `GET /v2/option/chain`, validates underlying, expiry, strike uniqueness, bid/ask/LTP, volume, OI, previous OI and available Greeks.
- Both reuse the existing source's `MINTTICK_UPSTOX_ANALYTICS_ENABLED` flag, SSM SecureString retrieval, HTTP timeout, status validation and 256 KiB response cap.
- Date is an explicit ISO expiry, not an unbounded arbitrary URL or relative-expiry keyword. Parsed responses are bounded to 1024 rows.
- The option-chain endpoint does **not** document a per-leg exchange quote timestamp. The returned `retrievedAt` is a server observation time **only** and `exchangeQuoteTimeVerified` remains false. This snapshot must not be mistaken for a broker-authoritative fresh tick eligible for PAPER or LIVE execution.
- Empty API `data: []` is observed-zero, not inferred missing or successful complete market coverage. Missing Greeks remain unknown (null), not zero.
- Bad upstream payloads, cross-underlying values and invalid expiry/contract keys fail closed with safe codes. Provider raw body, bearer token and SSM values are never returned.

## Official API references

- https://upstox.com/developer/api-documentation/analytics-token/
- https://upstox.com/developer/api-documentation/get-option-contracts/
- https://upstox.com/developer/api-documentation/get-pc-option-chain/
- https://upstox.com/developer/api-documentation/instruments/

## What must happen before unblocking the Mind screens

1. Exact-head CI on this PR.
2. Worker 1 independently checks existing dev runtime and safe SSM rights; tests with a valid active underlying/expiry and posts only sanitized nonzero/zero counts and error codes.
3. Document and verify provider-specific **display, retention, training and redistribution rights independently**. Upstox API read authorization is not blanket entitlement.
4. Canonical Worker 2 adds source-aware point-in-time F&O identity, rights, quality and dataset-version contract, without editing the source adapter.
5. Claude adds read-only customer APIs behind current account-edge policy and three Flutter screens, with actual end-to-end runtime verification before DONE.
6. Historical expired contracts and candles require a separate subscribed entitlement/rights audit; no historical backfill is enabled here.

## Verification status

Code and synthetic tests are committed for review; no live Upstox provider read, server deployment, verified F&O rights, runtime freshness or Android acceptance has been established by this phase.
