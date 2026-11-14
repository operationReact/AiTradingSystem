# MintTick Upstox F&O — operator source verification (issue #1063)

## Purpose
Validate the existing SSM-backed Upstox Analytics option-contract and option-chain GET methods **without** exposing raw market data or credentials, enabling a customer-facing F&O API, model training, historical retention or live orders.

The internal endpoint is:
```
POST /api/admin/market-data/upstox-analytics/fno/probe?expiry=YYYY-MM-DD
```
Example (run **only** from the existing protected operator/admin environment, with its existing authentication):
```
POST http://127.0.0.1:8080/api/admin/market-data/upstox-analytics/fno/probe?expiry=<verified-near-term-NIFTY-expiry>
```
This is **not** an instruction to put a credential on the command line, in GitHub, in the mobile app or in this document.

## Preconditions and access boundaries
- Existing `MINTTICK_UPSTOX_ANALYTICS_ENABLED=true` must be configured on the approved running backend, with EC2 permission to read exactly `/minttick/upstox/analytics-token` via SSM.
- Reuse the existing `minttick.upstox-analytics.probe-enabled=true` feature gate (corresponding protected runtime flag `MINTTICK_UPSTOX_ANALYTICS_PROBE_ENABLED=true` as documented for the equity readiness probe).
- Caller must have Spring Security `ADMIN` or `OPERATOR` role. This is an internal `/api/admin/...` path and must **not** be added to the public account edge Lambda or the `AccountApiOnlyFilter` customer route allowlist.
- POST only; expiry must be a valid ISO date from today through the following 90 days. Use an actual listed expiry from a trusted instrument source; the system does not guess rollovers or change the exchange calendar.
- Strict 15-minute per-process cooldown, reserved before any provider call even when upstream authentication fails. This is a conservative local gate, **not** a distributed quota guarantee; multi-instance deployments must use a shared limiter before increasing traffic.
- Input underlying is intentionally fixed to `NSE_INDEX|Nifty 50` to limit access and eliminate arbitrary caller-provided instruments during verification.

## Source operations and return values
Each admitted request performs, at most, two read-only requests using the EXISTING `UpstoxAnalyticsSource`:
1. `currentFnoOptionContracts` for the selected underlying and expiry;
2. `currentFnoOptionChain` for that same underlying and expiry.

Both retain the bounded response-size, row-count, schema validation, SSM and timeout protections from PR #1064. The probe cross-checks per-side option instrument keys from the chain against the option-contract response. Unmatched legs and absent data are `PARTIAL_DATA`, not complete verification. A false/inconsistent provenance assertion yields `INVALID_PROVENANCE`.

Response fields are counts and safe flags only:
`status`, `underlyingKey`, `expiry`, `validatedContracts`, `validatedStrikes`, `callsWithContract`, `putsWithContract`, `rowsWithCompleteGreeks`, `observedAt`, `exchangeQuoteTimeVerified=false`, `stored=false`, `displayRightsVerified=false`, `modelTrainingRightsVerified=false`, `liveTradingEnabled=false`.

Statuses: `READS_VERIFIED`, `PARTIAL_DATA`, `INVALID_PROVENANCE`, `PROVIDER_UNAVAILABLE`, `DISABLED`, `INVALID_EXPIRY`, `COOLDOWN`.

`READS_VERIFIED` means a bounded read validated nonzero option-contract/chain data and matching call/put instrument identities for one selected expiry. It does NOT mean a fresh exchange quote, live execution permission, complete F&O universe, historical retention rights, Redis cache availability, model training rights, or a deployed Flutter screen.

`observedAt` is the server's observation time, **not** an exchange tick timestamp. No executable-price freshness is established from REST option-chain payloads; live/PAPER execution must use separately verified authorized broker quotes.

## Next acceptance
1. Verify this PR's exact-head CI and safe merge into the latest `dev`.
2. Deploy through the already-approved dev backend workflow (no manual AWS provision).
3. Confirm the existing operator authentication and the private endpoint is unreachable through the public account API.
4. Run a private one-shot probe with actual listed expiry and record only safe status, counts, clock/revision and availability; never raw contract snapshots, bearer tokens or account funds.
5. Independently confirm permitted **display/retention/training** rights before exposing option chain values to customer screens or persisted datasets.
6. Unblock Flutter F&O, Option Chain and Payoff Builder consumption only after actual verified source, consumer/API permission checks, and deployed Android runtime evidence.

Official references:
- https://upstox.com/developer/api-documentation/analytics-token/
- https://upstox.com/developer/api-documentation/get-option-contracts/
- https://upstox.com/developer/api-documentation/get-pc-option-chain/
