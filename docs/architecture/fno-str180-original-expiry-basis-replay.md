# STR-180 V105 source-attested expiry-basis historical research replay

The STR-180 adapter uses the exact existing empty-by-default V105
FnoVerifiedExpiryBasisPairStore, not an index substitution, LTP,
session-labelled OHLCV candle, or a synthetic futures settlement.

Each historical observation requires synchronized final-session
NEAR-CASH-CLOSE NSE_EQ cash bid/ask and matching NSE_FO single-stock
futures bid/ask, a verified identical underlying/expiry/lot/provider,
independently captured EQ and FO original first-known publication
timestamps, both verified NSE exchange calendars, and independent
model-inference research-use rights for cash quotes, futures quotes,
and cash carry/physical-delivery costs.

The V105 record carries reviewed financing/dividend rates, original
contract and carry-cost source hashes, and explicit delivery, dividend
event, corporate action, statutory cost and physical delivery checks.
The service intentionally disallows mixing cost models or contract
source digests between historical sessions. It uses exactly five
completed chronological NSE trading sessions, a near-close 5-minute
window, 2-minute synchronized spot/future quote skew and original
cash/futures publication times before historical decision as-of.

It invokes the unchanged FnoExpiryBasisReversionV1 evaluator with
the stored independent original source assumptions. The result
is an expiry-basis convergence research classification only, NOT an
actual borrow/cash/future hedge, physical delivery guarantee,
two-leg fill, or after-cost realized profit.

All research/expiry-basis/contract discovery flags default false.
The V105 table was created with zero seeded market rows. Fixture
JUnit cannot prove any original market-data rows exist on the
deployed host. A full independently accepted historical E2E
requires original source data and rights/custody confirmation,
chronological walk-forward/out-of-sample replay, after-broker
spread/slippage/fees/taxes/financing/delivery cost accounting and
signed private audit receipt. PAPER/LIVE broker execution is separate.

No provider API call, broker order, resource change, entitlement
mutation or public endpoint. 0/43 independently accepted historical
market E2E remains unchanged.

Tracker: https://github.com/operationReact/AiTradingSystem/issues/1658
