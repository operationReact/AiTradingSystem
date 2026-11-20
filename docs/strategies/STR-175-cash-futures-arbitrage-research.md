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
