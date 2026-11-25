# STR-204 — Delta Hedge Research (Strategy 35/43)

**Status: `DATA_BLOCKED`; strictly source-verified research, NON-EXECUTABLE.**

This is intentionally different from STR-203 Gamma Scalping: it computes **current signed delta of an already broker-confirmed NIFTY options portfolio** and a **hypothetical adjustment** to a separately genuine NSE-listed NIFTY futures contract. It does **not** forecast gamma capture, theta, hedge profitability, options P&L, or authorize any PAPER or LIVE trade.

## Genuine inputs, original provenance and rights

1. **Six independent source permissions**: broker position provenance, NIFTY index, actual NSE F&O option chain, originally reported per-unit option delta, original futures market data, and genuine completed index history.
2. **Existing broker-confirmed positions** in one or more distinct actual NIFTY CE/PE instruments with signed lot quantities (negative quantities are short), actual official lot sizes, verified tick/strike/expiry/listing and original broker first-known timestamps. Do **not** infer a zero futures position without broker evidence.
3. **A separately listed NIFTY futures instrument** with independently verified lot, expiry and source key. The NIFTY index price itself cannot serve as an executable futures bid or ask.
4. **Same-provider original-as-of index + futures bid/ask/depth + option bid/ask/delta/OI** at a synchronized historical market instant. All exchange and first-known timestamps must be authentic, ordered and no later than `asOf`, with a configurable maximum source age and skew. CE delta is strictly between 0 and +1 and PE delta between -1 and 0, per underlying unit. All source marks must be genuinely licensed; no modeled synthetic Greeks accepted.
5. At least **35 originally timestamped completed NIFTY index daily closes** with the same provider, published before the research snapshot. Fixtures in unit tests are **synthetic test cases only**, not real price data or research evidence.

## Calculation (all hypothetical)

For signed option position lots `L_i`, authentic option lot size `Q_i`, source-original delta `d_i`, independently verified futures lot size `Q_f`, and current *broker-confirmed* futures lots `F_current`:

- `optionDeltaUnits = SUM(L_i × Q_i × d_i)`.
- `currentNetDeltaUnits = optionDeltaUnits + F_current × Q_f`.
- `F_target = roundHalfAwayFromZero(−optionDeltaUnits / Q_f)` (signed integer futures lots, not fractional futures).
- `hypotheticalChangeLots = F_target − F_current`.
- `hypotheticalResidualDeltaUnits = optionDeltaUnits + F_target × Q_f`.

A research rebalance is shown **only** when it reduces current absolute delta, the resulting residual is within a declared research tolerance, the absolute future-lot target meets the policy limit, the genuine current futures **bid depth** for a sale or **ask depth** for a purchase covers the entire hypothetical adjustment, and the modeled spread/slippage/fees meet the one-way cost cap. For **sales use observed futures BID**, for **purchases use observed futures ASK**; apply direction-specific slippage and positive modeled nonzero fees. These costs are hypothetical spread-and-fee friction, **not actual broker fills, realized trading costs, margin, funding or strategy P&L**.

No action is suggested when an existing hedge already makes net delta small, integer lots cannot improve delta enough, trade-side liquidity is inadequate, or costs exceed the research policy. Missing rights, original deltas, instruments, timestamps, prices, broker positions, or completed history **fail closed**.

## Restrictions and outstanding acceptance

All statuses return `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false` and `liveTradeAuthorized=false`. `DATA_BLOCKED` remains in the capability catalog. The evaluator is a deterministic pure Java function and touches no broker order endpoints, flags, AWS, SSM, workers, Flutter UI or deployed runtime.

Before PAPER: source-licensed **real** NIFTY holdings, futures, as-of Greeks and official expiry/lot master, verified broker-reconciled signed positions and cash/pledged margin, actual India cost/settlement model, asynchronous fills/partial fills/cancel/retry ledger, safe futures expiry rollover, portfolio risk controls, and independent original-first-known out-of-sample plus walk-forward hedging tests including extreme gaps and costs. Before LIVE: independently verify permitted broker F&O entitlements and consent, hard exposure/margin kill-switches, risk limits and order reconciliation, then explicit PAPER and LIVE acceptance. **Completing the catalog counter does not satisfy those gates.**

## Evidence

- Deterministic evaluator: `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoDeltaHedgeV1.java`
- JUnit scenarios: `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoDeltaHedgeV1Test.java`
- Capability manifest: `backend/src/main/resources/mind/capability-manifest.v1.json` (`strategy.STR-204`)
