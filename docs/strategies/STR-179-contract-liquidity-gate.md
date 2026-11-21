# STR-179 — Contract Liquidity Gate (research-only)

**MintTick Mind catalog:** `strategy.STR-179`, F&O strategy 10 of 43. **Readiness: DATA_BLOCKED** until actual provider permission, market-data integration and realistic execution evidence are verified.

## Purpose and distinction

Unlike STR-170–178, **STR-179 is not an alpha/trading strategy**. It is a *shared contract-level liquidity eligibility screen* that can help other research evaluators avoid illiquid NSE futures and options contracts.

An instrument can have a valid trading signal but poor bid/ask liquidity, thin open interest, stale broker quotes, or high execution friction. STR-179 detects these issues **before** downstream strategy research. Even a `RESEARCH_PASS` is **not authorization to send PAPER or LIVE orders**, and L1 depth alone cannot prove fill probability or capacity.

## Input contract

A licensed upstream F&O data adapter must supply:

- A genuinely source-verified listed `NSE_FO` contract: exact instrument key, matching canonical NSE equity/index underlying, expiry, source-listed lot size and derivative kind (`FUTURE`, `CALL` or `PUT`). Call/put contracts require a positive strike price; futures must not have a strike. A provider's full strike/expiry identity must be checked at adapter level rather than inferred from any string suffix.
- **Three chronologically ordered, same-provider, exchange-timed L1 bid/ask snapshots** from an actual verified active trading session, with original exchange event timestamps and observed-at timestamps. Exchange timestamps must be strictly increasing, each gap must not exceed an explicitly reviewed threshold (up to 45 seconds), the latest quote must be no more than the reviewed maximum age (up to 15 seconds), and the oldest quote no more than 90 seconds old. No mixing provider snapshots, WebSocket synthetic records or stale quoted values.
- For **every** snapshot: independently verified bid/ask and per-side displayed depth, exchange-reported futures/options open interest and **cumulative session volume**, all in comparable **underlying units**, not a mixture of contracts, lots and shares. Counter timestamps must be consistent and as-of known. Total volume must not regress within the same session. Original OI publication/first-observed timestamps must be known no later than the quote's own observation; observed OI can lag the live quote and must not be silently backdated.
- Separate source-use authorization for contract discovery, quote, OI and volume research. **Market-data display/redistribution/training rights and LIVE trading segment permissions remain independent.**
- Explicit thresholds: minimum OI and day volume in lots, minimum displayed **BID and ASK** depths in lots, maximum observed spread in bps, maximum friction (spread plus explicitly assumed per-leg impact), maximum quote age/gap and maximum unexplained quote-mid jump between snapshots. No automatic thresholds from a broker's entitlements.

## Deterministic checks

1. Validate rights, exact contract identity and metadata. Reject missing/negative/crossed quotes, invalid OI/volume/depth, future-known quote or OI, missing exchange session proof, unknown provider and mixed instrument keys.
2. Require **three** properly sequenced snapshots. Reject counter regressions, nonmonotonic OI/volume first-observed timestamps, or unexplained large mid-quote changes; these fail as `BLOCKED_DATA`, not merely `ILLIQUID`.
3. Compute every snapshot's **spread in bps** = `10000 × (ask − bid) / [(ask + bid) / 2]`, and take the worst of the three. Track the **minimum** bid/ask depth across the sequence, normalized to the actual contract's lot size.
4. Require latest normalized OI and cumulative session volume to exceed explicitly configured lot thresholds. Require the minimum depth on **both** sides throughout the sampled window to cover the configured number of lots.
5. Estimated illustrative friction bps = **maximum observed L1 spread bps + 2 × assumed per-leg price impact bps**; reject excessive spread or modeled cost even if absolute volume seems high. This is an estimate, not an observed order-book sweep or a fill price.

Outcome statuses are:

- `BLOCKED_RIGHTS`: necessary research-use entitlement is not verified.
- `BLOCKED_DATA`: contract/source/session/as-of/units/chronology/quote quality failure.
- `ILLIQUID`: low OI or session volume, thin displayed two-sided depth, wide spread, or excessive modeled price impact.
- `RESEARCH_PASS`: all supplied source/quality and threshold checks pass; permits **further research screening only**.

Every outcome reports `executable=false`, `orderPlaced=false` and `liveTradeAuthorized=false`.

## Verification scope and limitations

25 deterministic JUnit tests cover futures and option contract metadata, positive/negative research gates, independent rights, contract and provider changes, unverified/closed-session snapshots, bad prices/depth/OI/volume, future-known observations including OI arriving after a quote, freshness (both last age and full sampled window), snapshot gaps/order/duplicates, cumulative session counter regression, OI/volume first-observed timestamp ordering, insufficient OI/volume, either-side depth, spread, modeled cost, unstable mids, invalid thresholds, and reproducible nonexecution.

**Fixture thresholds are not calibrated for production:** example minimum 100 OI lots, 20 cumulative-volume lots, two displayed bid/ask lots, maximum 20 bps spread, 50 bps inclusive spread/impact, 5 bps assumed impact per leg, latest quote age <=10 seconds, intersnapshot gap <=30 seconds, and intersnapshot mid move <=500 bps. They illustrate deterministic tests, not NSE-wide recommended values.

A Level-1 snapshot **does not** prove marketable depth or fills at the quoted price, because of queue priority, rapid cancels, sweep depth, slippage, price impact, quote entitlement, trading halts, exchange hours and broker/exchange order restrictions. OI can be delayed intraday and its units/cutoff differ by provider; the source adapter must verify those details. The screen doesn't validate option Greeks/expiry liquidity decay, implied volatility or physical-settlement suitability, and cannot replace room/portfolio risk guards.

## Still required for runtime/PAPER readiness

1. Source-permitted canonical F&O feed adapter with actual futures/options instrument identity/expiry/strike/lot, verified bid/ask/order-book data, true provider/exchange timestamps, OI/volume unit normalization, monitored source-use rights and rate limits. Share the single authorized subscription rather than creating a per-strategy socket.
2. Exchange-session calendar and source latency/disruption evidence, actual first-known OI and cumulative session volume facts; instrument listing history, expiries and contract discontinuation verified.
3. Real as-of historical and forward liquidity-sampling research, market depth/slippage/fill studies, regime and expiry stress, false-pass/false-reject rates and out-of-sample acceptance.
4. Separately authorized **PAPER-only** integration with auditable strategy routing and risk enforcement, followed by Flutter Android/iOS/web runtime acceptance. This is **not** a request to enable LIVE F&O or broker permissions.

**Client impact:** None in this first backend-only PR. No Flutter product route or APK is generated. The manifest remains `DATA_BLOCKED` despite successful unit tests.

## Read-only source integration — 21 November 2026

The live operator research service now invokes the **distinct**
`FnoContractLiquidityGateV1.evaluate` on three properly source-observed
quotes. This is intentionally a **shared risk/liquidity screening primitive,
not a standalone profitable strategy or an order router**.

The new V104 table and `FnoVerifiedIntradayLiquidityStore` offer a
read-only, empty-at-deploy source boundary for true exchange-time Level-1
bid/ask, two-sided underlying-unit depth, finalized/open-interest
first-observed times and cumulative volume original timestamps. Historical
candles or current LTP are not substituted. Each row requires independent
currently valid provider-specific research rights receipts for
`nse_fo_intraday_l1_depth`, `nse_fo_intraday_oi` and
`nse_fo_intraday_volume`, with source digests, an exchange-session
attestation and one-day maximum retention.

The first direct adapter supports **genuine current NIFTY futures only**.
It uses the verified current instrument master for key/underlying/lot/
expiry, the shared Upstox V3 feed for one fresh real BID/ASK anchor, and
exact price/time agreement with the newest attested depth snapshot.
A future option-specific adapter must obtain full independently verified
CE/PE/strike/expiry/lot identity rather than guessing from symbol names.

The flag
`minttick.fno-research.liquidity-research-rights-verified` defaults false.
No receipt records are automatically inserted and a connected broker feed
is not evidence that source retention/research permissions exist.
Three original-time snapshots can return `RESEARCH_PASS` **only**
with valid source receipts; even then this does not verify fillability,
profitability, brokerage execution rights, PAPER acceptance, or LIVE
order approval.

**Remaining:** authorized source ingestion and exchange-session
attestation, actual feed/OI/volume coverage under verified entitlements,
rigorous unit audits and source reconciliation, true options contract
catalog, multi-regime replay, out-of-sample slippage and paper-fill
monitoring. Readiness stays `DATA_BLOCKED` at the manifest level.
