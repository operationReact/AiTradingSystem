# STR-176 independently sourced NSE replacement research

The last genuine host evaluation [45397528126](https://github.com/operationReact/AiTradingSystem/actions/runs/45397528126)
measured 11/12 clean Upstox cohorts, 666 bars and 488/529 evaluated decision
windows. One cohort remained quarantined after three noncanonical daily-time
chunk failures. Five modeled training trades lost 7,785,069 paise; 150 valid
OOS windows produced no signals and no trades. Independent certification is
0/43. These are measured research results, not proof of strategy acceptance.

## Separate exchange-source view

`str176_nse_reconciled_research.py` preserves all original Upstox observations
and whole-contract quarantine. A fixed request permits the existing 49
Upstox discovery/history GETs and at most 66 official NSE final UDiFF archives
for only the December 30, 2025 NIFTY futures cohort's original observed dates
inside its preceding 90 calendar days. If the actual quarantined source set
is not exactly that reviewed cohort/fault pattern, the run rejects it.

Every original session date must have exactly one original midnight observation
for a unit/identity audit. Ambiguous observations are not silently deduplicated
or selected as accepted EOD prices. Each day is then independently sourced
from the fixed official archive host, final archive/member filename, exact
business date, NIFTY/IDF identity and authenticated expiry/lot metadata.
NSE provides ALL accepted OHLC, OI and volume values for the replacement
cohort. The parser reuses the existing bounded, strict NSE UDiFF parser.

Lot-normalized NSE volume (`TtlTradgVol * NewBrdLotQty`), OI and close must
match the original midnight unit/identity observation on every day, with at
least one positive-volume anchor. This is an actual per-day source comparison,
not an assumption that all NSE/Upstox quantity fields always share units.
OHLC discrepancies are counted and the independent NSE prices retained;
the original Upstox data is neither overwritten nor declared valid.
A missing/invalid/duplicate exchange contract row, unavailable archive,
wrong lot, quantity disagreement, malformed original timestamp or request
bound violation rejects the entire replacement cohort. A 404 is unavailable
evidence, never an inferred holiday.

The immutable raw originals remain unchanged. A separate in-memory source
view is replayed using the existing fixed signal/expiry thresholds, calendar
purge, chronological realized drawdown and 20/30/50bps friction sensitivity.
The receipt preserves original quarantine counts separately from the number
of NSE-reconciled cohorts and accepted research source bars. It includes an
SHA256 over canonical parsed Upstox response views and a separate SHA256
manifest of actual NSE archive-byte hashes. These do not establish original
historical publication times. No source rows, instrument keys or raw archives
leave the existing private owner host; nothing is persisted by this runner.

## Limits and controls

This is NOT complete exchange-calendar validation: NSE retrieval is driven by
the original provider's observed dates, so a session absent from both these
inputs is not independently excluded. `calendarCompletenessVerified=false`
and `authenticSourceOriginalFirstPublishedAtVerified=false` remain explicit.
A complete reconciliation does not qualify strategy certification, prove
Java production-evaluator parity, provide actual bid/ask/depth, source-backed
execution costs, or create OOS trades when the unchanged strategy has none.
`independentHistoricalE2ECertified=false`, `certifiedStrategies=0` and all
no-order/no-write controls remain mandatory, even if 12 cohorts are evaluated.

The SSM command bundles the exact PR-revision evaluator, NSE parser and new
runner in memory, rather than importing stale host copies. It uses existing
AWS OIDC, owner grant and SSM token, fixed owner PAPER host, a strictly checked
separate one-file request, no redirects/auth bypass, bounded ZIP/CSV sizes,
request pacing and timeouts. No new AWS resources, broker orders, production
backend deployment, rights mutation or Flutter changes are included.

Run only after exact-head CI passes and the code PR merges; a separate
one-file request triggers the genuine owner-host evaluation. Network-free
fixtures test rejection and protocol boundaries and never count as a real
source reconciliation or strategy certificate. Tracker: [#1658](https://github.com/operationReact/AiTradingSystem/issues/1658).
