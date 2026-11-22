# STR-177 original-source historical mean-reversion replay

This component uses the existing independently research-rights-joined,
original-first-known V102 completed-futures OHLCV/OI PostgreSQL reader to
evaluate STR-177's **ten completed futures sessions**. This is a different
typed strategy from STR-176's seven-session breakout momentum. Both use
futures OHLCV and original finalized open interest, but the actual
FnoFuturesMeanReversionV1 evaluator checks an eight-session baseline,
ninth-session extreme and tenth-session recovery with estimated research
friction. Its default research-only threshold assumptions in this adapter
are not independently OOS-calibrated.

## Data/permissions

The source reader FnoVerifiedCompletedOhlcvOiStore.readExactReplayContract
applies joined SQL gates for:
- original session-complete OHLCV and finalized OI separately known at or
  before the historical decision,
- independent VALID research use receipts from the external provider for
  both nse_fo_completed_ohlcv and nse_fo_final_oi,
- original NSE exchange calendar evidence, source provider and as-of,
- unexpired provider data retention, and original immutable OHLCV, OI, and
  instrument-listing digest fields.

The adapter separately checks a single exact listed contract, identical
provider, underlying, listing date, expiry and lot size across rows,
digest syntax, ten consecutive verified NSE trading sessions, no
duplicate/out-of-order session, and no source publication after the
historical decision. It does not infer first-known timestamps from
the owner-private session-midnight-labelled SQLite pilot.

The research/discovery flags are all default-off. Real source rights
receipts must already exist in the SQL-backed store. A GitHub CI test with
mocked source rows does not create source entitlements or populate V102.

## Read-only result

inspect(contractKey, asOf) returns one of:
- a fixed BLOCKED_NOT_HISTORICAL_E2E reason (no original source or
  missing rights, identities, calendar, ten sessions, or as-of),
- ORIGINAL_SOURCE_EVALUATOR_BLOCKED when the actual evaluator rejects
  original data,
- ORIGINAL_SOURCE_EVALUATOR_RESEARCH_ONLY after actually calling the
  original STR-177 Java evaluator with the verified source window.

Even a research-only candidate is **not a historical E2E pass**:
out-of-sample training/validation separation, bid-ask/slippage/statutory
fees, independent position/impact evidence, source-use approval, and
reproducible audited proof receipts still need separate acceptance.

No historical data export, no broker positions, no PAPER fill, no LIVE
order, no AWS resource change. No activation flag is changed. The accepted
historical E2E count stays 0/43 until genuine market replay evidence exists.

Parent tracker:
https://github.com/operationReact/AiTradingSystem/issues/1658
