# STR-180 original expiry-basis research replay — protected source gate

## Implementation

The real original Java `FnoExpiryBasisReversionV1.evaluate` method is now
called through `Fno180VerifiedHistoricalReplayService`. The adapter uses
the existing V105 `FnoVerifiedExpiryBasisPairStore.readExactPair`: five
completed, chronologically verified cash NSE_EQ plus matched NSE_FO
near-close pairs, never midnight OHLC labels or settlement-price substitutes.

The V105 SQL joins independent VERIFIED/MODEL_INFERENCE receipts for
spot quote, futures quote, original carry/cost/delivery assumptions, and
separate cash and futures NSE trading-session calendars. It applies
original first-known and capture times at the historical `asOf` and
respectful retention gates. The service independently validates one
unrolled listed contract, same provider, original contract and carry
source SHA-256 digests, exact cash and futures exchange close, five
consecutive exchange sessions and 3–14 days remaining to expiry.

The decision remains a research-only classification: a real quoted
basis convergence does not establish executable trades, margin,
physical-delivery feasibility, or profitability after actual fills.
The adapter blocks missing rights, source exceptions, inconsistent
source identity, future-known values and calendar gaps.

## Protected operator visibility

`GET /api/admin/research/fno-43/expiry-basis-replay` accepts
`spotKey`, `contractKey`, `expiry`, `lotSize` and `asOf`,
requires ADMIN or OPERATOR and emits only state/counter/fixed reason
codes, with Cache-Control no-store. No original quote prices, source
digests, account identifiers or broker order endpoints appear.

## Evidence and completion

The V105 table was originally empty. Synthetic Maven/JUnit success
does not prove a V105 qualifying row exists in deployment, nor
historical point-in-time custody/permissions for actual market data,
nor out-of-sample after-cost P&L, nor real broker reconciliation.
The adapter, protected endpoint, and CI are implementation work,
**not independently accepted historical market E2E**. The aggregate
acceptance counter remains 0/43 until original eligible source rows
are independently inspected, after-cost walk-forward/OOS is executed
and signed, and an external audit passes.

STR-179 already has its own original V104 liquidity replay service
on dev; this change does not duplicate or replace it. STR-181 and
STR-182 similarly have their independent original option source
service on dev. The all-43 typed dispatcher is routing coverage only.

Tracking: https://github.com/operationReact/AiTradingSystem/issues/1658
