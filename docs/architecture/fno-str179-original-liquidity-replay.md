# STR-179 original rights-attested intraday liquidity research replay

Fno179VerifiedLiquidityReplayService is a read-only, FUTURES-ONLY
adapter from the existing zero-seeded V104 PostgreSQL original
near-market Level-1 bid/ask/depth, OI and cumulative session volume
source to the unchanged FnoContractLiquidityGateV1 Java evaluator.

The source SQL independently requires verified source-use receipts for
nse_fo_intraday_l1_depth, nse_fo_intraday_oi and
nse_fo_intraday_volume, each with MODEL_INFERENCE rights, captured_at,
original observed timestamps and valid retention. The source table
also requires verified-session and original SHA digests. The adapter
checks the calendar's NSE trading day and market hours, original event
times, same exact futures instrument/provider/underlying/lot/expiry,
three ordered distinct exchange observations and historical as-of.

It never infers Level-1 liquidity or depth from daily OHLCV, closing
snapshots, LTP, exchange-session-midnight cache or synthetic price.
The existing V104 table is intentionally seeded with ZERO rows.
A fictional test fixture passing does not establish that genuine
entitled historical records are installed on the host.

The evaluator's RESEARCH_PASS outcome is a qualitative liquidity
screen, not a fill, order, tradability guarantee or after-cost profit
signal. All research/discovery rights flags default to disabled;
missing real source or rights yields a fixed BLOCKED response. The
service is not public, performs no write, and cannot place broker
orders or mutate rights.

This adds an independent original source-to-evaluator code adapter
for STR-179, subject to V104 availability and rights evidence. The
actual independently audited historical market E2E and PAPER/LIVE
broker acceptance counts remain 0/43 until original receipts,
historical walk-forward/OOS validation, after-fees/impact, and real
execution-ledger verification qualify.

Acceptance tracker: https://github.com/operationReact/AiTradingSystem/issues/1658
