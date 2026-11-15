# NSE_FO Monday PAPER — independent read-only acceptance gate v1

Scope: Worker 3 coverage diagnostics only; does not call Upstox, change source clients, orders, PAPER, LIVE, or UI. Issue #1074; canonical source boundaries #924/#997.

## Evidence join (one instrument, one exchange session)

Use existing authorized persisted source/contract/quote receipts only. The following fields are required for each eligible contract; absence means UNKNOWN/BLOCKED, never zero or READY:

| Field | Acceptance rule |
|---|---|
| provider and source receipt | UPSTOX, unique immutable receipt ID, acquisition time, source provenance |
| exchange/instrument | NSE_FO, canonical listing/contract ID, provider instrument key, symbol, underlying=NIFTY, CE/PE or FUT |
| contract | exact expiry date, active listing effective interval, positive verified lot size, strike and option side where applicable |
| rights | time-valid explicit permitted ephemeral read, private derived display and PAPER simulation use; no inferred permission from API success |
| quote | matching contract key, positive exchange LTT timestamp, acquisition/available timestamp, nonnegative age and session-local timestamp; bid/ask only if source provides them |
| session | exchange calendar trading day, regular session, no stale/market-closed snapshot classified live |
| revision | latest verified contract episode/lot effective at quote exchange time; duplicates keyed by provider, contract, exchange LTT and revision, not row count |

## Independent diagnostic classifications

- `VERIFIED_FRESH`: all contract/rights/quote/session fields pass for the **same** listed contract at the checked trade time; source receipt IDs and time window retained for audit.
- `STALE`: authenticated quote exists but exchange LTT is outside the consumer-approved freshness threshold or market is closed. Never label as trade-time available.
- `RIGHTS_BLOCKED`: source-use rights missing, expired, contradictory or not approved for simulation.
- `CONTRACT_BLOCKED`: missing/ambiguous expiry, symbol, lot, listing effective date, or contract identity.
- `UNKNOWN`: no persisted observation or cannot join receipt provenance; unknown is not zero contracts.
- `SESSION_BLOCKED`: exchange session not independently verified open at evaluation time.

The exact freshness threshold is an explicit versioned consumer requirement, not an invented hardcoded default. Never infer real-time readiness from WebSocket connection, REST 200, an earlier contract count, a CI pass, or a last snapshot. Count unique eligible contracts only after all gates pass. Record numerator, fixed contract universe denominator/as-of, evaluated-at UTC/IST, receipt IDs and rejection counts; report denominator UNKNOWN if the listed universe is not verified.

## Monday verification checklist

1. Worker 1 supplies rights-approved, bounded source receipts for active NIFTY NSE_FO contracts and trade-session quote exchange timestamps; this worker makes no source calls.
2. Worker 2 confirms canonical contract/listing episode and effective lot mapping, without schema duplication.
3. Worker 3 joins existing read-only receipts and produces per-contract classification, gap reasons and versioned audit snapshot; no writes to trading domains.
4. Claude separately verifies PAPER execution and Android path; data diagnostic passing alone never means PAPER readiness.
5. Worker 5 displays persisted verified counts separately from planning progress and GitHub activity.

## Current evidence limitation

Historical 284-contract source probe is contract-discovery evidence only; not a fresh quote, rights grant, persisted coverage count or PAPER fill. Rights and live exchange-time receipts not independently observed by this document. Runtime eligible count UNKNOWN; acceptance delta +0.0pp. Do not turn missing rights rows into whole-market 0% completion.
