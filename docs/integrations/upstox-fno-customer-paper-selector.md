# MintTick — rights-gated NIFTY F&O PAPER customer selector

## API contract

`GET /api/customer/trading-rooms/{roomId}/paper/fno/market?expiry=YYYY-MM-DD`

- Requires a signed-in ACTIVE CUSTOMER, an existing funded PAPER room owned by the customer, and an expiry between today's IST date and 90 days away.
- The handler is absent unless **both** `minttick.upstox-fno.paper-enabled=true` and `minttick.upstox-fno.v3-feed-enabled=true`.
- Even when enabled, returns conflict until `minttick.upstox-fno.display-rights-verified=true`. This flag must not be enabled without externally verified source-data rights; a token alone is not permission to redistribute or retain quotes.
- Additional `minttick.upstox-fno.paper-usage-rights-verified` controls the per-leg `paperEligible` indicator independently. The existing F&O PAPER POST service enforces this flag and fresh trade-time evidence again, server-side.
- The existing Analytics Token remains on the instance SSM policy. It never appears in Flutter, this response or customer cookies.
- The controller reads only official NIFTY CE and PE contract identities, actual Upstox lot sizes and tick increments, and strictly paired strikes. Max 24 closest-to-ATM paired strikes are offered per request, returning in strike order. No client-selected arbitrary lot size/contract is trusted.
- The Upstox REST option-chain snapshot **does not have a verified last-traded exchange timestamp**. Therefore its price, bid/ask, OI and Greeks are not forwarded or used as executable prices.
- `bid`, `ask`, `exchangeAt` and `observedAt` are nullable and only populated by the in-memory Upstox V3 quote eligibility service (connected socket, bid/ask consistency and both timestamps within 15 seconds). Stale/market-closed prices are **null** rather than falsely "live".
- `paperEligible` is true **only when** the V3 quote is fresh, the exchange session is open and independently verified PAPER-use rights are explicitly set. Client must also handle POST rejection and trade-time changes, not trust UI eligibility forever.
- Market status is `MARKET_CLOSED`, `NO_FRESH_QUOTES` or `FRESH_QUOTES`; this is not an assurance that all legs have a quote. Each leg has its own freshness and eligibility.
- No source write, retention, order placement, customer LIVE execution or training use. The Lambda gateway and Spring filter permit exactly this GET and CORS OPTIONS, not modifying methods.

## Client sequence

1. Fetch funded PAPER room, and choose provider-listed NIFTY expiry. If not yet available, block PAPER orders (no fabricated expiry).
2. GET the rights-gated market selector; render CE/PE side-by-side with labels, lot size, strike, nullable bid/ask and quote age.
3. Disable BUY if `paperEligible=false`; show reason e.g. market closed, rights unavailable, disconnected or expired quote.
4. POST `/api/customer/trading-rooms/{roomId}/paper/fno/orders` with `Idempotency-Key` and the selected exact `instrumentKey`, `expiry`, `side`, `lots` and tick-aligned `limitPrice`. Never submit to Upstox LIVE order API from this PAPER screen.
5. GET `/paper/account`, `/paper/fno/positions` and `/paper/fno/orders` for real wallet, trade receipt, sell-to-close and derived P&L. Respect the server's idempotency and quote-time validation.

## Blockers to Monday acceptance

This PR does **not** prove real contract/chain provider receipts or a streaming feed. The private F&O probe most recently returned COOLDOWN; wait at least 15 minutes between calls. Nor does it certify data display/PAPER simulation rights. The selector remains inaccessible until source-use flags are independently verified and the existing authorized EC2/edge release is republished. Claude owns the Flutter screens; coordinate through #998 and overall #1074.

No green CI screenshot, artifact build or GitHub merge can replace an authenticated Monday NSE-session PAPER BUY and sell-to-close with ledger and Android receipts.
