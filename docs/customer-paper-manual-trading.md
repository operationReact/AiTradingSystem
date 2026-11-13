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

## Paper performance monitoring — rollout and data fidelity

The Flutter Room Detail **Performance** tab is a read-only first slice. It
displays (a) server-ledger realized P&L, initial/available virtual cash, manual
pause state and realized P&L relative to opening cash, and (b) the number of
open positions and fee/turnover sums for the **latest 100** immutable receipts
returned by the existing authenticated owner-scoped APIs. The panel fetches
positions and receipts on demand, labels the limited sample, and refuses to
display activity totals after a transport/parse failure. It never invokes an
order API. Realized P&L is **not** total equity or portfolio performance:
open-position marks are missing. A cost model of 0.05% (minimum ₹1) remains
deliberately incomplete for brokerage, taxes, exchange costs and slippage.

Do not display win rate, equity curve, drawdown, Sharpe, total return or
unrealized P&L from this API slice. Client-side reconstruction from the 100
receipt window would be mathematically wrong and can lose historical trades.

### Required durable monitoring backend (separate implementation gate)

1. **Ledger-derived source of truth:** append-only room-scoped fill, fee,
   realized P&L, cash and position transitions with immutable event IDs,
   source/exchange timestamps, execution mode, strategy/intent attribution and
   broker/quote provenance. Never mix LIVE and PAPER ledger entries.
2. **Valuation snapshots:** record `customer_paper_equity_snapshot` per
   owner/room/market-day, with UTC as-of, IST market session, quote source,
   exchange event time, quote age and validity, cash, marked position market
   value, unrealized/realized P&L, fee components and net equity. Use only
   independently verified marks; record explicit `UNPRICED`/`STALE` and
   partial coverage rather than assume last fill is current LTP.
3. **Full-history aggregates:** transactionally project day/week/month/all-time
   realized and unrealized P&L, net absolute/percentage return, max drawdown,
   win/loss on **closed positions**, profit factor, exposure by symbol/sector,
   turnover, modeled brokerage/tax/slippage and capital utilization. Require
   versioned calculators and reproducible date ranges; never compute metrics
   for a history window whose source completeness is unknown.
4. **Read-only customer API:** add
   `GET /api/customer/trading-rooms/{uuid}/paper/performance?range=1D|1W|1M|1Y|ALL`
   with authenticated exact-owner UUID binding, bounded pagination,
   `asOf`, `coverage`, `sampleCount`, `modelVersion`, error reasons and
   explicit `unavailableMetrics`. Introduce only a narrow JWT-gated edge
   GET allowlist; LIVE remains blocked.
5. **Flutter Performance screen:** support range selector, equity curve
   with missing-data gaps, realized versus unrealized P&L, fees and
   execution-quality summary, exposure/position heatmap, sortable closed
   trades, broker-quote freshness, drawdown/risk dashboard and timestamp
   / source disclosure. No numeric chart when evidence is missing; show
   loading, empty, partial, stale, error and reconnect states. Never duplicate
   broad Markets pages in the trading room.
6. **Monitoring and alerts:** count stale/missing quotes, out-of-session
   fills rejected, idempotency replays, unexpected reconciliation drift,
   missing snapshots and scheduler failures. Publish redacted aggregate
   health and correlation IDs; never send credentials, PIN, TOTP or broker
   access tokens to telemetry.
7. **Acceptance gates:** PostgreSQL tests for lot accounting, fee/slippage
   model, EOD marked equity, corporate actions, split-adjusted positions,
   absent/future quote timestamps, daylight/IST boundary, idempotent retry
   and per-customer isolation. Run connected web/Android/iOS Flutter journeys
   and deployment verification separately from unit CI before labeling
   historical performance fully operational.

This performance specification is intentionally separated from real-money
order routing: no monitor health status can authorize LIVE execution.
