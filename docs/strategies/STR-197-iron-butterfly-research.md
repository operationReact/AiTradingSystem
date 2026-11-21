# STR-197 — Iron Butterfly (Strategy 28/43)

**Catalog state: DATA_BLOCKED.** This is a research-only deterministic four-leg option evaluator, **not** an order executor or broker permission.

## Structure

An iron butterfly differs from an iron condor because its two **short** options share a single center strike K. For four genuine NSE-listed NIFTY contracts, identical option expiry and lot:

- **Buy** lower-strike protective PE at A.
- **Sell** center-strike PE at K.
- **Sell** center-strike CE at K.
- **Buy** upper-strike protective CE at D.

Thus `A < K = K < D`. The CE and PE at K are distinct listed contracts. The genuine synchronized index quote must be reasonably near K (an independently configured ATM-basis-point threshold), not strictly between the two short strikes—that condition is mathematically impossible because they coincide.

All four legs must be independently source-verified with positive tick-aligned strikes, listing dates and correct actual lot sizes. No synthetic production instruments, IV or prices.

## Fees, theoretical expiry payoff and risk

With lot size Q, after-cost total credit C from **selling BOTH center contracts at true bids minus modeled slippage**, **buying BOTH protective wings at true asks plus modeled slippage**, and positive four-leg modeled fees:

`expiryPnl(S) = C - Q * (max(K-S,0)-max(A-S,0)+max(S-K,0)-max(S-D,0))`.

The *theoretical* maximum expiry profit is `C` at underlying `S=K`; the downside/upside breakevens are `K−C/Q` and `K+C/Q`. The theoretical maximum expiry loss, assuming ALL FOUR legs remain intact to expiry, is `max(0, Q×max(K−A,D−K)−C)`, explicitly using the wider of asymmetric wings. Loss of a protective wing, partial fills, inability to exit, broker liquidation or exercise mismatch can invalidate the defined-risk assumption.

Research criteria include strictly positive after-fee credit smaller than the maximum gross wing loss, full theoretical maximum expiry loss within a **research-only** capital-risk budget and a positive credit/loss ratio. None of these constitutes a broker SPAN margin model, order authorization or guarantee.

## Rights, IV and point-in-time gates

- Licensed rights to index, F&O option chain, instrument discovery, and separately licensed **source annualized option-IV percentages** for each of the four contracts.
- At least 35 verified, strictly ascending completed 15:30 IST NIFTY daily closes with consistent source, authentic first-known timestamps and no future publication or duplicate bars. Actual exchange holiday/special-session provenance remains an upstream dependency.
- Five synchronized source snapshots: true NIFTY index quote + four option bid/asks, identical licensed provider, actual event and first-known timestamps, <=180-second quote age and <=90-second overall exchange-time skew.
- Real bid-side order depth for BOTH sold center contracts, real ask-side order depth for BOTH bought wings; positive open interest, positive noncrossed quotes and configured spread checks; authentic IV for each option.
- A transparent **illustrative** IV one-standard-deviation move uses `spot × average(four annualized source IV percentages)/100 × sqrt(calendar DTE/365)`. Unlike STR-196, the center short strike has zero ATM distance, so the heuristic is compared against the **nearer protective wing width**. This heuristic is **not** a probability, loss guarantee or proof of profitable edge.

Any missing rights/data fail closed. All decision statuses (`BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, `RESEARCH_CANDIDATE`) return **`executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`**.

## Validation and remaining blockers

12 deterministic JUnit tests cover matched genuine four-leg center-strike structure, input rights, instrument/expiry/lot/strike order, ATM spot checks, missing/invalid IV, quote depth, crossed/mismatched-source data, stale or future-known events, genuine completed history, asymmetric protective wing risk, negative after-fee credit, hypothetical maximum-loss budget, illustrative high-IV rejection, zero modeled fees and invariant no-order behavior. The test fixtures are fictional, never real NSE quotes.

This evaluator **does not validate the actual trading strategy**. Still missing: independently licensed real option-chain+IV snapshots, exchange calendar/master and settlement fees, actual multi-leg execution/legging/margin safety, original-timestamp out-of-sample walk-forward and realistic transaction cost evidence, PAPER app/ledger validation, and separately signed-off LIVE broker permissions/risk kill switches. It remains **DATA_BLOCKED**.

Related: [STR-196 Iron Condor](STR-196-iron-condor-research.md).
