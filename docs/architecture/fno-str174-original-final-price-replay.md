# STR-174 source-attested original final-price/OI historical replay

Fno174VerifiedHistoricalReplayService replays five originally finalized
futures CLOSE and independent finalized open-interest observations against
the existing FnoFuturesPriceOiDivergenceV1 Java evaluator. A divergence is
a retrospective research observation; it is not a buy/sell instruction,
after-cost return estimate or independently accepted historical backtest.

## Genuine source boundary

The pre-existing FnoVerifiedFinalPriceOiSessionStore.readExactContract
SQL joins external provider source, VALID historical model-inference rights
for final futures price and final open interest, independently attested
NSE trading-calendar sessions, and retention with original capture,
verification, and first-available timestamps <= each as-of decision.

This source is NOT generic OHLCV, last traded price, synthetic settlement,
or an exchange-session-midnight-labelled daily candle. No fallback source
is permitted. The adapter checks finalized exchange session close times,
chronology, identical contract/expiry/lot/underlying/provider, a complete
five-session official NSE trading calendar, separate final-price and OI
first-available timestamps, and explicit rights flags defaulting to false.

Any missing source or entitlement is a fixed BLOCKED state. Invalid original
source does not produce a synthetic divergence. A source row that arrived
after the historical decision does not count as available at that decision.

## Evidence status

- Source-to-real-Java-evaluator integration: implemented, tested with
  fictional database objects and deterministic JUnit.
- Actual on-host V100 finalized futures price/OI sessions with qualifying
  original research-use receipts: NOT independently verified here.
- Source-signed, point-in-time after-cost walk-forward and out-of-sample
  profit/loss proof: NOT available.
- PAPER ledger E2E and LIVE broker E2E: NOT verified.

Do not increment historical E2E from zero for this adapter until original
qualifying host market data, independently accepted OOS calculations and
retained source custody receipts exist. No trading rights or orders are
changed by this read-only component.

Acceptance tracker:
https://github.com/operationReact/AiTradingSystem/issues/1658
