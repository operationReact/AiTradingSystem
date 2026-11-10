# Mintick Mind D20-05B — persisted historical exchange session evidence

**Status:** Java/PostgreSQL persistence and read-only contracts, not a provider licence or data download. V81 is intentionally empty after deployment. No historical NSE/BSE sessions, 20-year candles, source adapters, cloud storage, model features, frontend or broker-order behavior are installed/activated.

## Why this slice

D20-05A correctly treats an unverified trading calendar as UNKNOWN rather than guessing weekdays. D20-05B adds a read-only source of actual **historical exchange-session evidence** with recorded publication and verification times. It must be populated only by a future reviewed licensed/calendar evidence pipeline; synthetic rows in integration tests are not evidence that official sessions are now loaded.

## Data custody

- V81 `golden_historical_exchange_session_evidence` stores provider, source domain, NSE/BSE, actual local session date, TRADING/CLOSED state, actual historical session close, source revision, external source digest, published/first-available times, verified/captured times, attestor and source reference.
- All values are append-only; corrections are new source revisions and old records remain. SQL rejects a CLOSED session with a close timestamp, or TRADING without one. A trigger rejects UPDATE and DELETE. Actual calendar correctness must still be checked against independently licensed source evidence.
- `GoldenJdbcHistoricalListingEvidence` reads permanent V77 listing identity/venue and listed/delisted episode, never current broker token or symbol inference. Unregistered identities return unknown.
- `GoldenPersistedHistoricalSessionEvidence` returns latest already verified revision per external provider for the exact source domain `exchange_calendar` when current `RAW_RETENTION` rights are recorded in V78. Missing, unlicensed, future, or more than one competing provider evidence record means UNKNOWN. No direct read fallback to `market_holiday`, `IndianCashMarketCalendar`, or live data is permitted.
- Both can be provided to the dormant `GoldenDailyBarValidator`, but **nothing invokes the actual ingestion worker automatically**. The integration test proves a strictly synthetic single-listing window covering a special Sunday trading session and a genuinely missing bar.

## Explicit external gates for D20-05C first real pilot

1. Verify an authorized historic exchange calendar provider with NSE/BSE session exceptions, closures and source publication times for the requested period. Grant rights only from independently verified legal records. Source registries remain default-deny here.
2. Run independent historical listing-episode reconciliation for ONE authorized listing, with verified exchange and dates. A today's Angel One symbol token cannot bootstrap a 20-year identity.
3. Store licensed **vendor-native raw bytes** and the derived daily validation envelope with linking hashes and revisions, in reviewed versioned/immutable storage. D20-04B/C provide code and offline policy previews, **not** an active archival bucket.
4. Preflight one source, listing and <=31-calendar-day range with full session evidence, currently verified retention rights, session availability timestamps and expected counts. If any missing, block/quarantine and report without inventing candles.
5. Run an explicitly authorized, bounded test, reporting the actual expected/valid sessions and missing dates, source cost, adjusted-vs-raw corporate actions and source object SHA-256. Only then expand 1 → 50 → 500 listings under an explicit budget and licensing approval.

The 43 Mintick Mind screens, 18 model entries and 232 strategy templates are NOT data-ready as a result of this slice. The rest of the Golden Source model availability and feature materialization roadmap remains pending.

**Tests:** `GoldenPersistedHistoricalSessionEvidenceIT` executes exclusively with synthetic source registry/rights/rows in runner-local PostgreSQL. It validates empty default denial, missing rights, legal-revocation override, future attestation, source ambiguity, append-only revisions, listing lookup, DB immutability and offline validator integration.
