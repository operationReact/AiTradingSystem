# STR-189 — Collar (research-only)

**Catalog:** `strategy.STR-189`, Strategy 20/43. **Status:** `DATA_BLOCKED` pending real source/portfolio evidence, historical validation and account permissions.

## Structure

A **three-leg equity collar** consists of (1) one previously owned, settled and unrestricted lot of source-reconciled NSE equity; (2) a bought **lower-strike PE** on that exact stock; and (3) a sold **higher-strike CE** on that exact stock, with the same verified expiry, equity underlying, physical-delivery identity and option lot.

This is **not** two unattached options, a naked short call, a long-put-only hedge, an index-proxy hedge, nor permission to purchase fictitious underlying shares. If a brokerage holding is missing/unsettled, any option deliverable or strike mapping is unverified, or the equity lot does not match both option lots, the model fails closed. Upstream permissions and account-specific covered-writing eligibility are not implied.

The research trigger is a drawdown of the latest verified **completed** equity daily close relative to the preceding 20 completed-session peak. Only original source-first-known data can trigger an evaluation; future/unfinished candles are prohibited.

## Model at expiry — hypothetical, not a fill

Let `L` be one verified source-listed PE strike, `H` one verified source-listed CE strike, `L < H`, and `Q` the verified owned-share/option lot size.

- `putPaidUnit = truePutAsk × (1 + buySlippageBps/10000)`
- `callReceivedUnit = trueCallBid × (1 − sellSlippageBps/10000)`
- `netCostLot = Q × (putPaidUnit − callReceivedUnit) + combinedAllInFeesTaxes`; may be negative (net credit).
- `scenarioStockValue = Q × (currentEquityBid × (1 − downsideScenarioBps/10000))`
- `collarScenarioValue = Q × clamp(scenarioSpot, L, H) − netCostLot`
- `theoreticalExpiryFloor = Q × L − netCostLot`
- `theoreticalExpiryCeiling = Q × H − netCostLot`

The theoretical floor/ceiling require **all three legs to remain continuously owned/covered, be deliverable together, have matching settlement rules, and reach expiry**. They do not guarantee a tradable liquidation price, protect against brokerage default, corporate-action adjustments, pre-expiry margin calls or non-atomic order fills. Upside is capped at the call strike; collar net cost and stock acquisition cost may still produce an actual negative total portfolio P&L. The ceiling/floor refer to **model expiry position value**, not a profit guarantee.

Gate the **entire existing stock notional** against a research exposure limit and any positive net debit against its separate budget. Zero modeled taxes/fees is invalid. Net credit does not imply free or riskless trade.

## Point-in-time and legal source requirements

- Rights to research *each* of equity quotes, equity holdings, instrument discovery and option-chain quotes. Broker-reconciled actual lot, originally observed in the preceding five minutes, valid source-listed NSE_EQ equity and two independently verified NSE_FO physically matching CE/PE contracts with valid listing, tick, lot and identical expiry (illustrative 3–45 calendar days).
- 35–512 historical completed equity daily 15:30 IST closes from one verified provider; original market-close and first-known publication timestamps; no future knowledge, duplicates or invented holidays. The adapter must independently certify actual NSE trading-calendar and corporate-action adjustments.
- **Three** contemporaneous same-provider spot/PE/CE bid-ask snapshots, noncrossed positive quotes and independently verified OI/depth, each <=180 seconds old at evaluation, max cross-leg skew <=90 seconds, exchange session local 09:15–15:30 IST, and latest completed equity close already published before the quote exchange events.
- Explicit Indian slippage/fee estimates, cap on per-leg spread, strike distance, full share-notional and debit budgets.

Every decision is `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, or `RESEARCH_CANDIDATE`. Each remains `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`. There is **no call to any order endpoint**.

## Validation

12 deterministic JUnit tests: valid three-leg candidate with distinct expiry model bounds, trigger absent, independent rights, mismatched option type/expiry/lot, invalid/unreconciled ownership, disallowed proxy index put, crossed or thin or mismatched-provider bids, stale/future quote timestamps, lookahead/duplicate candles, zero fees, excessive stock notional, excessive net debit, deterministic non-execution.

**Still required before any trading claim:** genuine licensed provider data with retained first-observation timestamps; official calendar, corporate actions, source-listed deliverables and lot; genuine broker reconciled holdings and rights; account-level F&O covered-call/put settlement and taxes, short-leg margin and multi-leg fill sequencing safeguards; no-lookahead out-of-sample/walk-forward replay; separately approved PAPER ledger and Flutter acceptance; independently audited LIVE brokerage permissions and kill switches. Tests use hypothetical fixtures only.

See [STR-187 Protective Put](STR-187-protective-put.md) and [STR-188 Covered Call](STR-188-covered-call.md).

## Source-backed direct adapter (21 November 2026)

The new read-only `FnoEquityCollarResearchService` invokes the actual
`FnoEquityCollarV1.evaluate` and requires **one verified settled and
unrestricted full lot of NSE_EQ stock, not two separately owned lots**, plus
the source-listed matching same-expiry long PE and short CE. Both options
must agree on provider, account, holding-acquisition cost and exact holding
snapshot timestamp. The existing V109 stock/option source is reused for
original-time 35+ consecutive official NSE daily closes and both options
without any new WebSocket or assumed broker position.

An additional **EMPTY** V110 account-scoped proof table independently
attests combined, **not double-counted**, Indian collar fees/taxes,
assignment/delivery review, room research-budget limits and a true
two-sided, original-time NSE_EQ BID/ASK quote. This specifically forbids
using one LTP as both stock BID and ASK. Separate currently verified
provider-scoped `MODEL_INFERENCE` research rights are required for
`nse_eq_owned_stock_collar_all_in_fees` and `nse_eq_quote_depth`,
in addition to original V109 stock-close/holdings/options/master rights.

Source timestamps, as-of, equity BID/ASK price containment, equity
last-price observation consistency and PE/CE quote synchronization are
checked before passing any model input to the distinct evaluator. If the
two broker holdings snapshots disagree, either option is missing,
combined fees/capital are not attested, or quote/right/retention
integrity is incomplete, the strategy remains BLOCKED.

`minttick.fno-research.collar-combined-fee-rights-verified=false`
is disabled by default, and all stock, PE/CE and account identifiers are
blank until configured and independently source verified. No settled
stock is inferred from a watchlist or PAPER holdings.

**Scope:** deterministic research-only payoff floor/ceiling. Actual
provider receipts, source rights, P&L after real fills, assignment
and delivery costs, independent market-data E2E, PAPER and LIVE
remain unverified. Manifest stays `DATA_BLOCKED`.
