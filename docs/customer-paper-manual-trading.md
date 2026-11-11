# MintTick Customer Paper Trading Rooms: manual virtual fills V1

A customer can create a PAPER room, activate a virtual wallet once, and
explicitly resume/pause its manually simulated order engine. LIVE Rooms cannot
fund a virtual wallet, resume it, or submit to this Paper order service.

## Routes (Cognito JWT, exact ACTIVE CUSTOMER, UUID-bound)

- `POST /api/customer/trading-rooms/{roomId}/paper/activate`: idempotently
  allocate the room's planned virtual INR amount (existing API).
- `POST /api/customer/trading-rooms/{roomId}/paper/resume`: explicitly arm
  *manual Paper* fills. No automated strategies or broker orders start.
- `POST /api/customer/trading-rooms/{roomId}/paper/pause`: immediately halt
  further manual Paper fills.
- `POST /api/customer/trading-rooms/{roomId}/paper/orders`: body
  `{"instrumentId":42,"side":"BUY","quantity":1,"limitPrice":100.05}`,
  header `Idempotency-Key: opaque-unique-key`. No immediate fill unless the
  latest independently verified LIVE broker tick crosses the limit. The
  exchange must be currently in a verified cash session, and the tick's
  exchange timestamp must be no older than 15 seconds; stale, closed-market,
  missing, mismatched or off-tick-price quotes fail closed with no debit.
- `GET /api/customer/trading-rooms/{roomId}/paper/orders`: maximum 100
  immutable virtual fill receipts, each recording exchange quote timestamp,
  fill price, modeled fees and cash/P&L changes.
- `GET /api/customer/trading-rooms/{roomId}/paper/positions`: open simulated
  cash-equity share quantities and per-share virtual cost.

No margin/leverage, no short-selling, maximum 20% of starting virtual cash
per new BUY, no quantity over available shares on SELL, no orders on unlisted
instruments or F&O, same per-owner room/account row lock for every wallet
mutation, transactionally persistent idempotency keys, and **no broker order
API call**.

## Scope and limitations

This first slice supports **manual, immediate execution** of eligible NSE/BSE
cash-equity limit orders at the latest verified tick. If a limit is not
marketable, it is rejected rather than silently queued. It is *not* a
matching engine, and the modeled fee is a simple 0.05% of notional (minimum
₹1); it does not estimate all Indian brokerage, statutory taxes, exchange
fees or slippage. These assumptions are disclosed instead of claiming
realistic after-cost fills.

This slice is separate from existing fail-closed customer *trade intent*
custody. The custody endpoint remains non-executing until a dedicated
risk/order-intent transition is deliberately implemented. Mintick Mind,
autonomous orders, LIVE brokerage APIs, other customers' account funds
and Upstox/Angel One credentials are outside this service.

## Deployment prerequisites

- New versioned Flyway V83 migration must be validated and deployed through
  the existing exact-head private EC2 release.
- Both the account-edge `infra/minttick-domain/account_proxy.py` and its
  CloudFormation mirror need a narrowly scoped allowlist for the six Paper
  routes/methods. The private backend filter alone is **not sufficient**.
- Wire a broker-verified instrument picker, resume/pause actions,
  explicit order confirmation, order receipts and positions into the
  Flutter customer Room Detail page before announcing an end-to-end feature.
- Validate tests against actual PostgreSQL using a fixed trading-session
  clock, fresh/stale ticks, same-key retries, owner isolation and LIVE
  rejection. Never treat a green Java unit job as proof of a successful
  EC2 database migration or real mobile execution.
