# STR-172 — Futures Rollover Signal (research-only)

**Catalog:** `strategy.STR-172`, third of 43 dedicated F&O strategies. The manifest remains **DATA_BLOCKED** until a genuinely permission-verified source snapshot and historical/PAPER runtime evidence exists.

## Hypothesis

As a front-month NSE futures contract approaches expiry, positions may migrate to the next contract. A **decrease in near-expiry open interest** coupled with an **increase in next-expiry open interest**, plus trading-volume confirmation, is a *position migration observation*. It is **not** evidence that the underlying price will rise or fall, an executable trade instruction, or a guaranteed profitable arbitrage.

### Deterministic detection contract

An upstream source/quality adapter must supply, with provenance and permitted internal research rights:

- Two **provider-verified NSE_FO futures contracts** on the same exact underlying, with identical units/lot size, different source-listed expiries. Near expiry must be between **3 and 15 calendar days away** at evaluation; far expiry must be within **100 days**.
- **Exactly three** consecutive, chronologically ordered, **completed** NSE trading-session snapshots of both contracts. The upstream session-calendar verifier must attest each one. This standalone evaluator rejects weekends, gaps greater than five calendar days, duplicates and out-of-order sessions. It does *not* independently prove NSE holiday status.
- Each leg's independently observed session-end **OI, traded volume, bid/ask**, exchange quote time and first observed/published time. OI and volume must use the **same underlying units** for both contracts (not an unnormalized mix of lots and shares). Both legs and all sessions must come from the same trusted source.
- Closing quote timestamps must fall inside 10 minutes of the modeled 15:30 IST session close, near/far timestamps within 2 minutes, and **observedAt must be after completed session close and no later than the evaluation as-of time**. The latest completed session may be at most five calendar days old.
- An explicit, reviewed settings object: minimum decline in near OI, minimum rise in far OI, minimum increase in far OI share, minimum share of far-contract traded volume, assumed two-leg fees/slippage and maximum quote-spread-plus-fees friction. **No trading thresholds or costs are automatically inferred.**

Three-session OI calculations (basis points; 1 bp = 0.01%):

- nearOiDecline = `10,000 × (nearOI_first − nearOI_last) / nearOI_first`
- farOiIncrease = `10,000 × (farOI_last − farOI_first) / farOI_first`
- farOiShareShift = `10,000 × [farOI_last / (nearOI_last + farOI_last) − farOI_first / (nearOI_first + farOI_first)]`
- farVolumeShare = `10,000 × farVolume_last / (nearVolume_last + farVolume_last)`
- estimatedFriction = half of near bid/ask spread in bps + half of far bid/ask spread in bps + explicit two-leg fee/slippage estimate

**Research candidate only when:** near OI decreases in **both transitions**, far OI increases in **both transitions**, all four threshold comparisons pass, and estimated friction does not exceed its explicit ceiling. Otherwise: `NO_SIGNAL` with an auditable reason.

**Fail closed:** `BLOCKED_RIGHTS` if data-use/discovery is unverified; `BLOCKED_DATA` for mismatched contract key/underlying/expiry/lot, source divergence, bad prices, missing or invalid session/OI/volume/time, stale or forward-known observation, invalid thresholds or zero baseline/total OI. A rollover candidate has `executable=false`, `orderPlaced=false`, `directionalBuyOrSell=false` unconditionally.

### Source and execution limits

The standalone Java evaluator does not call a broker, subscribe to a WebSocket, use AWS credentials, save data or submit PAPER/LIVE orders. All tested snapshots are deterministic **fixtures**, not actual Upstox or Angel One receipts. The bid/ask friction estimate is **not** a complete, exchange-verified transaction-cost model. Volume/OI publication delays, OI unit conventions, holiday calendars and contract adjustment are source integration obligations.

Before operational release: (1) integrate the shared authorized contract/snapshot pipeline from [F&O canonical feed architecture](../architecture/fno-canonical-strategy-feed.md), (2) validate source OI timestamp/units/contract migration history, (3) run strictly as-of backtests and walk-forward tests across many expiries, corporate actions and source disruptions, (4) prove a separate PAPER-only observation lifecycle. Actual LIVE account/segment approval and order access remain independently gated.

**Client impact:** None in this first slice. No Flutter route or API is added; UI and live strategy progress remain unverified.
