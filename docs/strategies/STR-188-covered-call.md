# STR-188 — Covered Call (research-only)

**Catalog:** `strategy.STR-188`, Strategy 19/43. **Status: DATA_BLOCKED**. No source feed, account execution or PAPER/LIVE permission is enabled.

## Mechanics

A *covered call* requires the investor already owns a reconciled, **settled and deliverable** lot of genuine NSE equity shares. One listed **CE** contract on exactly that equity and of exactly the same lot size can be sold against those shares. An unowned share, proxy index option, mismatched equity/lot, synthetic share, or unsettled/restricted holding is not a covered call.

The deterministic research trigger is a verified gain of the most recent **completed 15:30 IST equity session close** over independently reconciled acquisition cost. It is a screening rule, *not* financial advice or suitability and *not* an implied stock recommendation. There is no MA-based crossover for this strategy.

For hypothetical short CE:
- `receivedPerShare = call.bid × (1 - assumedSellSlippageBps/10000)`;
- `netCreditLot = receivedPerShare × lotSize - allInFeesAndTaxes`; must exceed a positive credit minimum;
- `hypotheticalSpotAtExpiry = currentSourceEquityPrice × (1 + upsideScenarioBps/10000)`;
- `uncoveredStockOnlyValue = hypotheticalSpotAtExpiry × settledShares`;
- `coveredValueAtExpiry = min(hypotheticalSpotAtExpiry, CEstrike) × settledShares + netCreditLot`;
- `relativeCoveredVsHoldingGain = coveredValueAtExpiry - uncoveredStockOnlyValue`;
- At equity zero, model leaves **only net call premium**, meaning **very large share downside still exists**. The strategy does not define maximum account-level loss as small, does not promise positive returns, and caps upside at the short CE strike, assuming valid expiry/settlement.

For research exposure the **full current value of held shares**, not the premium received, must fit the notional-capital budget. No naked short-call/margin entitlement may be inferred from a source holding. The calculation assumes an established covered position until expiry, no intervening share disposal and actual matching delivery. In real brokerage a lapse in share cover can create unlimited naked-short-call risk; assignment/settlement, corporate actions, charges and restrictions require independent account checks.

## Provenance and safety

- Genuine, currently broker-reconciled, **one full settled and deliverable lot** observed within five minutes; exactly matching physically deliverable equity-to-call mapping with source-verified contract, same source/broker identity, listing/strike/tick/lot and DTE (illustrative 3–45 days).
- Independent rights for underlying equity, option chain, source instrument discovery, and broker holdings research. Failure => `BLOCKED_RIGHTS`.
- 35–512 ascending authentic completed equity daily candles at NSE 15:30 IST with original first-known exchange publication, nonfuture observation, source continuity and independently verified sessions. Proper NSE holiday/special session/corporate-action attestation belongs in the adapter, not fabricated in this evaluator.
- Timestamp-aligned, real same-provider spot and CE bid/ask quotes, each exchange-time no older than 180 seconds, max skew 90 seconds, exchange-session window and first-known `asOf` guarantees; meaningful noncrossed bid/ask, actual bid-side depth and open interest in lots; source strike distance and option spread limits.
- Explicit nonzero round-trip Indian fees/taxes, sale slippage, positive premium cutoff, holdings notional cap and hypothetical scenario.

All outputs are only `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, `RESEARCH_CANDIDATE`. Each explicitly sets `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`.

## Tests and remaining gates

11 deterministic Java/JUnit unit tests cover owned-share research candidate and stock downside, no qualifying completed gain, missing holdings rights, partial/unreconciled holdings, mismatched/no delivery CE, source/provider/depth/crossed quote, quote staleness and future knowledge, historical lookahead and session duplicates, after-cost credit threshold, full stock notional budget and fee validation.

**Outstanding:** licensed first-seen NSE equity/CE feed, real brokerage holdings/restrictions (not invented), source-listed true delivery/expiry/lot and corporate-action verification, Indian settlement rules, margin and naked-short protection, historical out-of-sample replay and walk-forward, separately approved PAPER lifecycle/UI and LIVE rights. Synthetic JUnit fixtures are **not** claimed as live market prices.

See [STR-187](STR-187-protective-put.md) and [canonical F&O architecture](../architecture/fno-canonical-strategy-feed.md).


## Broker-owned stock source integration — 21 November 2026

`FnoOwnedEquityOptionsResearchService` now invokes the full, distinct
`FnoCoveredCallV1.evaluate` with a genuinely settled and unpledged
broker-reconciled NSE_EQ one-lot position and corresponding actual
NSE_FO same-stock CE. Verified 35+ completed equity close observations
include original first-known timestamps, while fresh same-provider
NSE_EQ and CE exchange quotes preserve their independent observed times.

V109's source records require separate currently valid research-use
receipts for the equity history, the **broker holdings** record,
actual equity tick, option-chain depth/OI, option master/physical
delivery mapping, and after-cost fee/notional-budget inputs. The
covered-call evaluator explicitly retains underlying stock downside
exposure and estimates hypothetical called-away upside rather than
presenting a risk-free income stream.

The stock account, stock instrument, CE instrument and rights flags
default unconfigured/false; no actual brokerage holdings are inserted
by the integration. Full call coverage must match real settled
shares and options lot. No source-verified real data E2E, PAPER
or LIVE order execution was performed or approved.
