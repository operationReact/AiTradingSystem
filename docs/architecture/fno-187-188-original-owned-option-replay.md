# STR-187 Protective Put / STR-188 Covered Call — original owned-stock replay

This is a **read-only historical research integration**, not an approval to
trade options, not a paper-portfolio simulation, and not independent
real-market historical E2E acceptance.

The existing V110 original evidence tables and
\`FnoVerifiedEquityOwnedOptionStore\` enforce independently verified
source-use rights and point-in-time custody of true NSE_EQ historical
completed candles, broker-reconciled settled and physically deliverable
full-lot shares, true L1 option depth, real quotes, option instrument master,
fees/capital, valid trade dates and original first-known times. They reject
paper holdings, watchlist proxies, liens, unverified ownership, later
backfilled first-known time, insufficient stock to cover one option lot,
and account mismatch. Rights/config fields all default to false/empty.

\`Fno187188VerifiedHistoricalReplayService\` reuses the existing
\`FnoOwnedEquityOptionsResearchService.evaluateAll(asOf)\` that invokes
the actual unchanged \`FnoProtectivePutV1.evaluate\` and
\`FnoCoveredCallV1.evaluate\`. It limits historical lookback, validates
exact as-of result, 35+ real calendar-complete NSE_EQ sessions, one
source-listed contract, and actual synchronized stock/option quotes.
It produces only sanitized source/evaluator counts and fixed reason codes;
the still-configured broker account and stock/option identities remain
secret. The protected GET route
\`/api/admin/research/fno-43/owned-equity-historical-replay\` accepts
only STR-187 or STR-188 plus historical asOf and requires ADMIN/OPERATOR,
with no-store and no broker action.

**Safety boundary:** a full-lot owned covered call is not equivalent to
a naked short call; if the original at-that-time settled shares, delivery,
pledge/lien or matching lot receipt is absent, the result is blocked. The
historical balance available NOW is never substituted for a historical
settled position. A candidate is a nonexecutable research observation.

Synthetic CI verifies evaluator wiring, denied rights, no settled shares,
time boundaries, and redaction. Production original V110 data availability,
source-use permissions and after-cost out-of-sample results have **not**
been independently accepted, and no deployment or provider fetch follows
from this PR. The overall accepted historical E2E remains **0/43**, even
if the typed dispatch count is 43/43 and more original-source adapters
compile successfully. PAPER and LIVE broker E2E remain separate.

Tracking issue: https://github.com/operationReact/AiTradingSystem/issues/1658
