# F&O historical source-to-evaluator: STR-183–186

The platform has 43/43 typed Java research evaluator dispatch cases.
That is NOT proof of 43 independently verified historical backtests.

This feature adds historical as-of research replay for four actual
two-leg option spread evaluators, without single-option, LTP or
synthetic spread substitution:

| Strategy | Evaluator | Source | Scope |
| --- | --- | --- | --- |
| STR-183 | FnoBullCallDebitSpreadV1 | V107 debit | Buy lower CE, sell higher CE; same expiry/lot |
| STR-184 | FnoBearPutDebitSpreadV1 | V107 debit | Buy higher PE, sell lower PE; same expiry/lot |
| STR-185 | FnoBullPutCreditSpreadV1 | V108 credit | Sell higher PE, buy lower PE; real broker margin |
| STR-186 | FnoBearCallCreditSpreadV1 | V108 credit | Sell lower CE, buy higher CE; real broker margin |

The underlying original FnoVerifiedNiftyLongOptionSourceStore joins
35+ independently rights-attested completed NIFTY daily closes, source
first-known times and verified NSE trading calendar. Original V107 and
V108 sources separately require verified MODEL_INFERENCE rights to the
index quote, option lower/upper quotes with OI/depth, contract listing
and fee/capital inputs. Credit V108 additionally requires broker margin,
variation stress, cash and settlement reserve evidence. Original
observations/custody and rights must predate each historical decision.

FnoDebitSpreadResearchService.evaluateOne and
FnoCreditSpreadResearchService.evaluateOne call only the selected
evaluator through the existing rights-gated source (no unnecessary
sibling option lookup). The rights flags remain OFF by default.

Fno183186VerifiedHistoricalReplayService requires the exact fixed
operator-configured primary option key and also a distinct matching
second leg, a historical as-of within 366 days, and up to 64 strictly
increasing decision windows. It produces deterministic research-only
decision hashes and rejects missing rights, margin, custody, timeliness
or listed contract identities.

A new operator/admin-only GET endpoint at path
/api/admin/research/fno-43/spread-historical-replay accepts
strategyId, contractKey, and ISO asOf. It returns only sanitized
aggregate counts and fixed outcome codes with no-store. No market
premiums, account identities, margin values, source hashes, broker
secrets or positions leave this route. No orders are submitted.

**Acceptance boundary:** CI uses synthetic/mock V107/V108 rows, not
independent actual source replay. The rights-attested V107/V108 source
tables may be empty on production EC2. There is no real after-cost
out-of-sample P&L, source-rights inspection, or broker execution proof.

Historical market E2E therefore remains 0/43 until each strategy
has original rights/first-known custody and genuinely executed
point-in-time walk-forward after-cost replay with independent proof.
Reference: issue #1658.
