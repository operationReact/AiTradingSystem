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

## 2025 December pilot coverage
NSE F&O circular **NSE/FAOP/65588** dated 13 December 2024:
https://nsearchives.nseindia.com/content/circulars/FAOP65588.pdf

The November/December 2025 private pilot was previously outside the 2026
calendar lookup. **December 25, 2025** is explicitly an NSE F&O closure,
so a missing candle for that exchange-closed date is not an unexplained
missing trading session. The 2025 circular also lists **November 5**, which the real host
now confirms falls inside the selected November expiry lookback: all 23
original November weekday gap candidates are scheduled holiday slots. **October 21, 2025** is excluded from
all-day closure classification because the same circular announces a
special Muhurat trading session on that day.

This source adjustment does NOT reconcile the December duplicate provider
OHLCV/OI observations, nor does it supply the missing original first-known
publication receipts, verify dataset rights or authorize historical replay.

## Verified exact-host post-merge acceptance (22 November 2026 IST)

GitHub Actions source-coverage run
https://github.com/operationReact/AiTradingSystem/actions/runs/45039350868
completed its protected original AWS/SSM run successfully: 25 metadata GETs,
276/276 selected historical instrument identities matched, 12 expiry months,
12 FUT / 132 CE / 132 PE, no missing or unexpected selected instruments.

Across the **210 original weekday-without-candle slots**, **184** slots
coincide with published 2025/2026 NSE F&O trading holidays and are no longer
**unexplained session-gap candidates**. **26** remain: January 2026 (23
contract-date slots in one common-empty weekday), May 2026 (one isolated
contract-date slot), October 2026 (two isolated contract-date slots).
None of the 26 is proven to be a missing market trade without independent
session/instrument liquidity evidence; do not fabricate OHLCV/OI. The three
May/October partial gaps were also absent on the previously verified bounded
Upstox source re-fetch.

Source-rights verification, independent price comparison, official complete
session-by-session calendar proof, original-first-known and 43-strategy real
historical after-cost E2E are **still not accepted**. The original December
2025 321 duplicate keys (316 conflicting payloads) remain preserved under
quarantine. No paper/live trading authorization or backtest acceptance changes.

## January 15, 2026 — later NSE F&O calendar amendment

**NSE/FAOP/72262**, dated **12 January 2026**:
https://nsearchives.nseindia.com/content/circulars/FAOP72262.pdf

This official NSE Futures & Options circular explicitly **amends**
NSE/FAOP/71777 and declares **Thursday, January 15, 2026** a trading holiday
for municipal elections in Maharashtra. The annual 2026 F&O calendar alone
omits that later-announced closure. The original owner-private calendar
audit on 22 November 2026 had **23 remaining January contract-day gaps in one
common-empty weekday**, consistent with the missing Jan 15 closure. This
amendment is kept separate from the original annual calendar and is not
treated as information publicly available before **2026-01-12**.

The offline source-coverage diagnostics should now explain these 23 January
contract slots as exchange closure, leaving the 3 previously observed
contract-specific partial gaps (May: 1, October: 2) *if* the same original
276-contract pilot data and source remain unchanged. The latter count must
be confirmed by a fresh restricted AWS/SSM host receipt; do **not** claim
success based solely on fixture tests or holiday arithmetic.

The amendment is a calendar correction **only**. It does not alter original
December source duplicates (321, including 316 conflicting payloads),
price/volume/OI provenance, actual-first-known data custody, per-strategy IV,
Greeks, bid/ask/depth, retention/use rights or after-cost E2E acceptance.
Historical 43-strategy E2E, PAPER and LIVE remain 0/43 until independent
evidence proves otherwise.

## Final original weekday gap classification — verified 22 November 2026

The chronology above retains the **intermediate** 184/26 and 207/3
results. The original 210 weekday-absence slots were finally classified
by **two separate, read-only real-host checks**:

1. Official NSE F&O trading closures: **207** contract-day slots
   ([calendar run #45040413969](https://github.com/operationReact/AiTradingSystem/actions/runs/45040413969)).
   Includes the January 15 amendment, where the original annual schedule
   was incomplete.
2. Three **contract-specific exchange-confirmed zero-traded-volume days**
   (May one; October two), independently matched by authenticated provider
   contract-catalogue identity to NSE F&O UDiFF EOD
   ([run #45043188940](https://github.com/operationReact/AiTradingSystem/actions/runs/45043188940),
   merged PR #1704). The protected host fetched **2 official NSE daily ZIP
   archives**, used **4 bounded metadata GETs**, and returned
   `exchangeTraded=0`, `exchangeZeroVolume=3`,
   `exchangeRowAbsent=0`, `archiveUnavailable=0`.

**210/210 original weekday-absence candidate slots now have exchange-calendar
or zero-executed-trading-volume explanations for this one 276-contract pilot.**
They must **not** be treated as synthesized candles or as proof of valid
bid/ask, settlement values, Greeks, IV, OHLC or volume quality across the
entire F&O universe. The three zero-traded-volume slots should carry explicit
no-execution semantics, not fabricated close prices or backtest fills.

Independent OHLC/OI source accuracy, original data time provenance, rights,
the **316 original December 2025 conflicting daily candle payload pairs**,
and 43-strategy historical/PAPER/LIVE **0/43** verified E2E remain separate
blockers. Original prices, checkpoint records and retention stayed intact.
