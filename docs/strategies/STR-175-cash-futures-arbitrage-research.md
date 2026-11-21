# STR-175 — Cash Futures Arbitrage Research (feasibility overlay)

**Catalog ID:** `strategy.STR-175` (F&O strategy 6 of 43). **Readiness: DATA_BLOCKED.** This is deterministic and research-only; neither an arbitrage guarantee nor authority to trade.

## Distinction from STR-170

- **STR-170 Futures Basis Carry:** computes theoretical cash-and-carry/reverse-carry price gap using the same stock's cash ASK and futures BID, contract expiry, financing, dividends and estimated round-trip costs.
- **STR-175 Cash Futures Arbitrage Research:** *reuses STR-170*, then evaluates **one matched, deliverable, cash-stock BUY + futures SELL lot** for available two-leg depth, full cash-and-carry capital, broker-verified margin/stress reserves, physical settlement readiness and incrementally costed uncertainty. It does not duplicate STR-170's basis formula and explicitly does not model short-stock reverse carry or index-as-deliverable-spot.

## Canonical input contract

- Genuine provider-verified NSE_EQ cash stock and exactly matching NSE_FO single-stock future, future underlying cash-key match, expiry/lot and delivery identity. Unverified index-as-cash substitution is blocked by STR-170.
- Same licensed, named source for both legs (UPSTOX_V3 / ANGEL_ONE_SMARTAPI); signed/verified instrument-key and quote provenance, research-use source rights, exchange session, synchronized exchange bid/ask timestamps, <=15 seconds quote age and <=10 seconds cross-leg exchange-time skew via STR-170.
- **Ask-side available depth** for cash stock and **bid-side available depth** for future each at least the futures lot size; the future cannot be shorted for a lot while spot is purchased for a different quantity.
- **Verified capital** inputs: deployable cash, actual broker initial futures margin, a strictly positive stress variation margin reserve and separate positive delivery buffer. Required capital = spot ASK × lot size + future initial margin + stress variation reserve + settlement delivery buffer. This is an illustrative feasibility sum, not a broker risk or settlement certification.
- **Incremental, non-duplicated costs** in INR: extra price impact/slippage per share, extra statutory/tax/fee amounts per lot, dividend uncertainty and corporate action stress. All are **separate** from the bps costs already modeled by STR-170, with independent verification flags. Final net = STR-170 illustrative net per lot − extra slippage × lot − incremental fees − uncertainty buffers.
- An explicit positive minimum stressed-net-per-lot INR hurdle. Formula outputs are cost/stress *research estimates*; a positive result is **not guaranteed profit**.

## Fail-closed states

- `BLOCKED_RIGHTS`: either leg's verified research use missing.
- `BLOCKED_DATA`: missing/invalid underlying, source/quotes, exchange time, baseline carry costs, margin/settlement/cost evidence, duplicate fee declaration or bad input.
- `INFEASIBLE`: available displayed depth below the entire lot, deployable capital below required, or reverse carry/stock-borrow path not modeled.
- `NO_EDGE`: STR-170 fails the carry hurdle, or positive baseline carry is removed by extra stress charges/minimum hurdle.
- `RESEARCH_CANDIDATE`: one-lot cash-stock/futures short passes all explicit checks and stressed illustrative net exceeds the hurdle. This is not a simultaneous fill or a risk-free arbitrage.

All outcomes report `executable=false`, `orderPlaced=false`, `riskFreeArbitrageVerified=false`.

## Test scope and pending evidence

15 JUnit regression tests cover good one-lot candidate, high incremental charges, each leg's depth, cash/margin deficiency, delivery/settlement proof, fee/dedup/dividend/corporate actions, separate market-data rights, mixed/unknown source, contract identity and provenance, stale/future-known quotes, time skew and invalid expiry, baseline no-edge, unsupported reverse carry, market closure, invalid baseline costs and determinism.

**Missing before any operational use:** actual provider quotes and order-book depth rights/provenance, complete Indian cash/future charges (including delivery-related costs, exchange/stamp/STT/GST charges where applicable), actual broker margin and settlement constraints, verified dividend/corporate-action events and borrowing restrictions, no-lookahead historical depth backtests and walk-forward stress tests, PAPER integration and independent source/Flutter/runtime acceptance. Data publication/redistribution rights and LIVE F&O trading permission remain separately gated.

**Client impact:** no API or Flutter UI changes. The catalog stays DATA_BLOCKED; unit tests validate fixture mathematics, not an exchange-connected trade or profit.

## E2E-2 direct research integration (21 November 2026)

The operator-only `FnoFuturesResearchService.evaluateAll` now invokes the
**distinct** STR-175 `FnoCashFuturesArbitrageResearchV1.evaluate(...)` using
the existing shared Upstox V3 spot/futures quote feed for observed BID/ASK,
plus the new read-only `FnoCashFuturesFeasibilityStore` as an independent
account-scoped, provider-scoped evidence boundary.

The new `V101__fno_cash_futures_feasibility_evidence.sql` migration creates
an **empty** append-only evidence table. No source rows, rights flags, broker
accounts or capital figures are auto-populated. Proof rows require all of:

- Matching exact NSE_EQ stock and NSE_FO same-stock futures key, expiry, lot,
  provider and configured opaque broker account scope.
- Actual ask-side cash and bid-side futures displayed depth, **with their
  quoted prices and first-observed times**, matched exactly to a current
  shared-feed quote within bounded timing and availability windows.
- Separately verified, currently active research-use rights for
  `nse_eq_quote_depth` and `nse_fo_quote_depth`, owned by the same
  provider. A successful authenticated market feed is **not** such a grant.
- Explicit positive futures margin, stress margin, delivery cash buffer and
  current broker funds; independently evidenced funding/dividend/stock borrow
  assumptions and statutory/tax/slippage/dividend/corporate-event costs.
  The V101 digest fields are *evidence references* for external verification,
  not cryptographic proof of their underlying source contents.
- A current instrument-master digest, open F&O exchange session and
  verified deliverable underlying. No index is treated as deliverable cash.
  Full-lot book depth is mandatory. A depth or fee receipt from a different
  account/provider/expiry is rejected rather than silently reused.

V101 financial/market-depth records have a **maximum seven-day retention**.
A retention-only purge deletes expired rows; any edits or early erasure are
blocked. No customer response exposes account funds.

**Configuration remains fail-closed by default:**
`minttick.fno-research.cash-feasibility-research-rights-verified=false`;
`minttick.fno-research.cash-feasibility-account-scope` is empty unless
explicitly configured. Existing STR-170 carry research does not certify
delivery, funding, original depth, or feasibility on its own.

The new integration tests exercise direct evaluator invocation with mocked
complete receipts, missing rights, missing account identity, quote/depth
mismatch, mixed providers, short book depth, missing live quotes, market
closure and sanitized source exceptions. SQL/schema tests ensure independent
rights and retention. **Mocked positive outcomes are NOT proof of live
data, source permissions, settled cash, return on capital, profitability,
PAPER execution or LIVE orders.** The manifest remains DATA_BLOCKED.

Next work before independently validated operational STR-175:
approved server-side evidence ingestion/attestation and broker funds/margin
reconciliation, up-to-date corporate-action and complete Indian cost
receipts, actual exchange depth coverage, multi-day point-in-time
walk-forward including fill/settlement constraints, PAPER ledger, and
separately authorized LIVE execution.
