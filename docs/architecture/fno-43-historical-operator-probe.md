# Protected F&O historical source-to-evaluator readiness API

The operator-only GET endpoint is:

/api/admin/research/fno-43/historical-replay?strategyId=strategy.STR-174&contractKey=NSE_FO%7CEXACT_KEY&asOf=2026-11-20T11%3A00%3A00Z

It is only for roles ADMIN and OPERATOR, never a customer/public route.
Requires exact listed instrument key and historical asOf no later than server
time. It sends no broker orders and has no POST, write, export or
source-entitlement activation operation. It returns Cache-Control: no-store.

The endpoint intentionally supports only three **genuine-source-backed**
Java research evaluator adapters:
- STR-174: original authenticated V100 final futures CLOSE and finalized OI
  rights/calendar-joined source, five completed sessions;
- STR-176: original V102 completed OHLCV + OI with separate first-known
  timestamps and immutable source digests, seven sessions;
- STR-177: original V102 completed OHLCV + OI and source rights, ten sessions.

All 43 strategies also have distinct typed Java structural replay dispatch
paths. The other 40 currently cannot be queried through this endpoint for a
genuine source replay because their required underlying, quote, option IV/
Greeks, depth, broker positions and calendar sources have not yet been
historically bound to an independent rights/custody source.

The response only contains strategy ID, as-of date, evaluator/rights
status, fixed reason code, original row counts and session window size,
and FALSE independentHistoricalE2E/PAPER/LIVE/broker order flags. It never
returns contract keys, user holdings, instruments, prices, premiums, Greeks,
source data hashes, data provider tokens, raw SQL or private file contents.

A source-rights flag can be disabled, original source data can be missing
in the deployed database or historical as-of rights may not be qualified.
In those cases the service correctly reports BLOCKED; it must not silently
fabricate an as-of history from a later daily download.

Even when a local historical Java research evaluator produces a candidate,
independent real-market E2E acceptance still requires independently
audited, permitted source custody, reproducible point-in-time replay,
after-fees/slippage/taxes and non-leaking out-of-sample/walk-forward
performance and an immutable private acceptance receipt. This API does not
claim those proofs, nor paper ledger or live broker execution. It does not
enable new research/download rights or bypass permissions.

Tracker: https://github.com/operationReact/AiTradingSystem/issues/1658
