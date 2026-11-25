# MintTick Mind F&O — verified source-readiness checkpoint (22 November 2026 IST)

This report separates **real AWS-host audit evidence** from unit-test
fixtures and source-adapter code completion. The source of truth is the
restricted owner-private F&O cache on the existing EC2 instance; **no raw
price, instrument key, broker token or personally identifiable trade
record was copied from the host**.

## Executed, scoped on-host acceptance checks

- [F&O Phase 2 historical quality audit, GitHub run 44942109297](https://github.com/operationReact/AiTradingSystem/actions/runs/44942109297):
  - Both SQLite `PRAGMA quick_check` inspections passed.
  - **276/276** backfill journal jobs and **276** batch receipts; **4,251**
    original cached daily candle records across **12 expiry months** and
    **276 selected contracts**.
  - **321 duplicate contract+exchange-date candle records** in December
    2025, with **316 conflicting value duplicates** and five identical
    repeats. Observed duplicate timestamps included 00:00 and 09:15 IST.
    The original December 2025 group is **quarantined**, not deduplicated
    by choosing a convenient or favorable bar.
  - Eleven other expiry months provide **3,608 structurally cleaner
    candle rows across 253 selected contracts**, but this is only a
    *research candidate*, NOT license-verified, source-reconciled,
    exchange-calendar complete or accepted for backtesting.
  - Full 12-month structural result: `REVIEW`, NOT `PASS`.
- [Upstox original provider metadata cross-check, run 44942217454](https://github.com/operationReact/AiTradingSystem/actions/runs/44942217454):
  **276/276** selected original contract identities matched:
  **12 FUT, 132 CE, 132 PE**, no unmatched or unexpected selected
  contracts; 25 bounded metadata GETs. The 321 December duplicates
  were independently identified. Candidate weekday gaps, including
  some isolated in May and October, require an authentic NSE holiday
  calendar and original exchange-session reconciliation.
  `priceIndependentVerified=false`,
  `exchangeCalendarVerified=false`,
  `retainedDataRightsVerified=false`.
- [Zero-provider-GET runtime readiness, run 44942325861](https://github.com/operationReact/AiTradingSystem/actions/runs/44942325861):
  original private host matched; downloader code `PRESENT`, checkpoint
  journal `PRESENT`, expected progress snapshot `MISSING`,
  inspected historical downloader service `STOPPED`, data rights
  `UNVERIFIED`; outcome `READY_FOR_RIGHTS_REVIEW`. This status
  does not establish whether the separate private-cache backfill unit
  is stopped; do not confuse the two systemd service identities.

## Risk corrections in this PR

For **STR-203 through STR-212**, a single new source-provenance
boundary independently enforces:

1. Every nested `provider` field matches the provider whose six
   independent source-use receipts bound the original database envelope.
   A licensed provider-A receipt cannot be attached to a mixed provider-B
   quote, options book or completion history.
2. All nested `firstKnownAt`, `brokerFirstKnownAt` and
   `bookFirstKnownAt` ISO timestamps are at or BEFORE the outer
   research evidence's original first-known timestamp, itself bounded
   by evaluation time. This denies retrospective hindsight at the
   envelope boundary as well as the individual Java evaluator.
3. Malformed, ambiguous non-ISO numeric timestamps, overly deep JSON,
   oversized payloads, missing providers and empty timestamps are
   denied without broker side effects.
4. Gamma Scalping (STR-203) explicitly blocks either
   `retrospectiveOnly=false` OR unexpected actual-fill observation,
   avoiding the previously weaker combined boolean condition.

The ten existing strategy-specific `Fno*V1.evaluate` implementations
retain all separate contract/lot/expiry/book/Greek/history checks.
This is an extra defensive validation boundary; the synthetic JUnit
fixtures cannot certify actual market source rights.

## Explicit non-completion boundaries

| Milestone | Verified evidence |
|---|---|
| 43 unique direct research evaluator callsites | 43/43 |
| Existing real host private selected contract source identities | 276/276 |
| Original public NSE calendar, independent price validation, retained licensed source | NOT VERIFIED |
| Complete data for 43 strategies, original IV/Greeks, source rights | NOT VERIFIED |
| Real exchange historical strategy E2E | 0/43 verified |
| Paper broker/ledger lifecycle E2E | 0/43 verified |
| Live broker order lifecycle E2E | 0/43 verified |
| Signed APK / production release from this PR | Not produced |

No source rights are presumed from a paid personal Upstox subscription.
No research receipt, unit test, cache-quarantine or metadata match
authorizes real-money orders, a backtest promotion or rights flag changes.

## Remaining acceptance gate

Before a strategy can advance from `DATA_BLOCKED`, acquire independently
verifiable original research-use rights, a valid NSE trading calendar,
price reconciliations and a point-in-time, retained source for every
required evaluator input (real IV, Greeks, quotes, current broker
holdings, official expiry and event source as applicable). Then run
honest out-of-sample historical acceptance and independent paper
broker-ledger lifecycle tests, recording execution costs and settlement
failure behavior. Live execution requires a separate explicit
risk/entitlements acceptance. No single provider catalog match can
substitute for these proofs.
