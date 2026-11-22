# STR-201 — original 30-day ATM IV rank historical replay

The 43/43 static typed dispatcher does not prove 43 historical market E2E
backtests. STR-201 now has a dedicated as-of operator-only research replay
that invokes the existing original FnoIvRankResearchService.evaluate(asOf),
which in turn calls FnoIvRankMeanReversionV1.evaluate.

The existing FnoVerifiedNiftyIvRankSourceStore reads original paired
NIFTY daily OHLC and separately licensed 30-day constant-tenor ATM
option implied-volatility data. Each row is required to match a verified
underlying and IV original first-known time, a single original provider,
a matching NSE session, and independently VERIFIED MODEL_INFERENCE
source-use receipts for index, option chain, IV history and 30-day
normalization. It requires at least 200, at most 370 original sessions;
missing/expired rights and missing original data fail closed. It does
NOT estimate historical IV from present option chain or LTP.

The Fno201VerifiedHistoricalReplayService wraps that already typed
source/evaluator call with a <=366-day nonfuture decision scope,
a verifiable exact 200+ historical session and real current-IV count
gate. Research signal and no-signal output are never broker orders or
independent after-cost out-of-sample replay acceptance.

The ADMIN/OPERATOR GET
/api/admin/research/fno-43/iv-rank-replay?asOf=<ISO8601>
returns sanitized strategy, status, reason, 200+ original session
counts and source readiness only, with no-store. No raw quotes,
source hashes, broker positions or credentials are returned.

CI tests are fictional mocked source events, not independent historical
market E2E. A passed build is not proof that an original 200+ session IV
corpus exists in deployed PostgreSQL. Accepted historical market E2E,
PAPER ledger E2E and LIVE fills remain ZERO until separately verified
real custody, after-cost chronological OOS P&L, source rights and
execution evidence are actually completed.

Tracker: https://github.com/operationReact/AiTradingSystem/issues/1658
