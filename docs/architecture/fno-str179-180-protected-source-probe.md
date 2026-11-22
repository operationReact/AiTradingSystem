# Operator-only original V104/V105 historical source checks

This extends the protected, read-only admin F&O research diagnostics to
the independently source-backed STR-179 and STR-180 research adapters.

Two GET-only routes:

- /api/admin/research/fno-43/liquidity-replay
  Parameters: contractKey, expiry, lotSize, asOf.
  Validates a genuine NSE_FO futures instrument and invokes the V104
  rights-attested 3-observation Level-1/market depth, OI and volume
  liquidity research source/evaluator STR-179.

- /api/admin/research/fno-43/basis-replay
  Parameters: spotKey, futuresKey, expiry, lotSize, asOf.
  Validates a genuine NSE_EQ single-stock cash instrument and
  corresponding NSE_FO futures identity, invoking the source-rights
  joined V105 five-session near-cash-close basis-reversion service
  STR-180 with separately approved carry/dividend/delivery costs.

Both require ADMIN/OPERATOR method security, checked against the server
clock, with Cache-Control: no-store. Response includes only research
strategy ID, requested asOf, sanitized evaluation status/constant reason,
original row count and evaluated window count, plus FALSE
historical-market-E2E, PAPER-ledger-E2E, LIVE-broker-E2E and order flags.

Response never includes raw source quotes, original provider custody SHA,
contract or spot key, account holdings, access tokens, rights receipts,
database row content or order details.

Both underlying tables V104/V105 were seeded empty; a CI result on
fictional rows does not confirm real original source records or rights
on the deployed backend. A BLOCKED response is expected until such
records are independently acquired and retained. This is not
permission for scraping, redistribution, training or trading.

The earlier /historical-replay endpoint handles STR-174/176/177.
Together these five strategies have bounded genuine-source Java
adapters and two protected diagnostic surfaces. All 43 strategy
evaluators have source-independent typed replay dispatcher routes;
the remaining 38 lack individually approved original historical
source adapters. None of the five has an independently accepted
real-market after-cost OOS historical E2E proof solely because the
source and controller code was merged.

Tracker: https://github.com/operationReact/AiTradingSystem/issues/1658
