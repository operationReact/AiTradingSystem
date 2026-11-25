# STR-170 — Futures Basis Carry (research-only)

**Catalog:** strategy.STR-170, first of 43 F&O strategies. This PR introduces an independent deterministic evaluator and unit tests. The catalog remains DATA_BLOCKED until source rights, genuine data and historical/backtest evidence are verified. No automated PAPER or LIVE order path is changed.

## Verified input contract

- NSE single-stock spot identity \`NSE_EQ|...\` and the exact matching NSE futures contract \`NSE_FO|...\`, lot size, expiry (3–90 days), deliverable spot eligibility, and independently verified contract provenance.
- Separate authorized source-use rights for both legs, verified exchange session, and paired exchange-timestamped bid/ask quotes no more than 15 seconds old with at most 10 seconds between the two exchange timestamps.
- Explicit assumptions: annual financing, dividend yield, stock borrowing rate, all-in round-trip charges/slippage/taxes and minimum net return hurdle. Rates/charges are **illustrative and not verified brokerage or exchange settlement schedules**. Reverse carry is excluded unless the stock borrow leg is independently verified.

## Approximate research economics

T = calendar days until expiry / 365. Rates and costs are converted from basis points to decimal fractions.

- Cash and carry: **futures BID − spot ASK × [1 + (funding − dividend) × T] − round-trip costs**.
- Reverse carry (borrow proof required): **spot BID × [1 + (funding − dividend − borrow) × T] − futures ASK − round-trip costs**.
- Only net gaps strictly above the user-provided minimum basis-point hurdle are research candidates. The INR-per-lot value is an indicative estimate, **not a guaranteed/arbitrage profit**.

Index futures are **not supported** in this single-stock implementation: an index quote cannot be purchased or delivered as a spot share. Cash-basket hedging requires a separate, thoroughly costed model.

## Safeguards

The evaluator has no broker calls, no persistence, no endpoint, and no order-routing dependencies. Every result returns executable=false and orderPlaced=false. Missing rights, unverifiable contract, stale/future-known quotes, mismatched underlying, bad fees, near-expiry contracts, and unverified short borrow all fail closed. The tests exercise cash carry, reverse borrow gating, normal/no-edge, cost gates, quote/timestamp validation and reproducibility.

## Remaining before STR-170 is operational

1. Wire a canonical, licensed, rights-checked Upstox/Angel One **single-stock spot + futures** snapshot adapter to this evaluator, using actual matching contract metadata and as-of exchange timestamps.
2. Replace illustrative assumptions with documented funding, discrete dividends and corporate actions, borrowing/SLB, margin, settlement and all-in charges; include India physical-delivery risks.
3. Build historical as-of replay/backtesting, out-of-sample and walk-forward evaluation, slippage/latency/fees stress tests, and auditable strategy eligibility.
4. Run separately approved PAPER-only integration; LIVE trading waits on account/broker permissions, risk control and explicit order authorization.

**Definition of done for this PR:** compile + deterministic evaluator unit tests + no-trading safety contract. Provider data, backend deployment, APK, PAPER results and profitability remain **NOT VERIFIED**.
