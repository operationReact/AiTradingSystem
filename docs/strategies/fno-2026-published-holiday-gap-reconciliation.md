# MintTick F&O historical coverage — 2026 NSE scheduled holiday reconciliation

## Authoritative published schedule
NSE F&O circular **NSE/FAOP/71777**, dated 12 December 2025:
https://nsearchives.nseindia.com/content/circulars/FAOP71777.pdf

The source coverage diagnostic now distinguishes **published 2026 F&O trading
holidays** from unexplained weekday candle gaps. Two flagged candidate dates,
**2026-05-01** and **2026-10-02**, are explicitly listed as closed in this
circular. The official calendar also lists 2026-05-28, 2026-10-20 and other
holidays. It is not correct to count every missing weekday as a missing
exchange trading session.

## Data and validation semantics
The diagnostic preserves its existing `missingWeekdayCandidates` count and
adds, per expiry-month:

- `nsePublishedHolidaySlots`: weekday contract/date slots falling on a
  published 2026 F&O holiday, whether provider returned a row or not.
- `nseHolidayMissingSlots`: those officially scheduled closure slots in
  which no provider daily candle exists.
- `remainingWeekdayGapCandidates`: all original weekday candidates minus
  the absent published-holiday slots.

If `nsePublishedHolidaySlots > nseHolidayMissingSlots`, source data was
observed on a scheduled closure. Do **not** silently regard it as a normal
trading-session candle: investigate its source timestamp, contract identity,
exchange calendar revisions and provenance independently.

The diagnostic does **not** assert all unexplained slots are provider
errors: illiquid/nontrading contracts, instrument life cycle, unscheduled
closures and other omissions still need independent verification.

The calendar is an offline published **2026 schedule**, not a guarantee of
historical sessions, amendments, special Sunday sessions, or 2025 coverage.
`exchangeSessionCalendarVerified` remains **false** and no trading/backtest
approval, broker entitlement, rights receipt, or LIVE permission changes.

## Validation
The offline unit suite tests two original published holidays and preserves
an unrelated weekday gap, including the case where a candle unexpectedly
exists on an NSE scheduled holiday. The existing protected source-coverage
workflow checks aggregate counter integrity. An exact-host read-only rerun
is separately required to measure the remaining private cache cohort;
merging this code alone cannot fill missing bars or establish 43-strategy E2E.

Tracking: https://github.com/operationReact/AiTradingSystem/issues/1661
