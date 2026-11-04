# V4-09 — Java matched PAPER/SHADOW session evidence summary

**Scope:** Read-only PostgreSQL aggregation of the already merged V56 matched model-decision ledger and V57 links to real Java PAPER order/fill records. This is **not** a new source of model inference, a scoring system, an actual soak run, or a LIVE trading pathway.

The ADMIN-only endpoint is:

`GET /api/admin/model-governance/paper-shadow/sessions/{sessionId}/evidence`

Version: `v4-matched-paper-shadow-session-evidence.v1`.

The report is computed from the database in one read-only repeatable-read transaction. It uses the registered session and exact enrolled champion/challengers, and counts:

- total matched cases, incomplete model rosters, and champion ENTER/WAIT/ABSTAIN records;
- distinct cases with at least one champion-versus-challenger action disagreement;
- existing V57 linked automated PAPER orders, unlinked champion ENTER decisions, and order-versus-actual PAPER fill-quantity mismatches;
- recorded PAPER fill quantity, existing fee amount and slippage amount from `paper_fill`;
- first/last decision timestamps and observed span (not proof of continuous running).

The counters are observational and never silently equate estimated model returns with realized cash/broker P&L. Even a perfectly reconciled local fill quantity cannot independently prove actual economic performance, exchange/data rights, model provenance, continuous runtime soak, broker reconciliation or LIVE readiness.

All reports explicitly contain `actualInferenceProducerAttested=false`, `originalLicensedSourceCustodyVerified=false`, `paperShadowSoakAccepted=false`, `brokerReconciliationAccepted=false`, `independentEconomicPerformanceVerified=false`, `liveOrderExecutionEnabled=false`, and `canAuthorizeCapital=false`. Missing/damaged evidence is surfaced as stable `reasonCodes` rather than being treated as successful acceptance.

Regression uses the existing Java 21/PostgreSQL/Flyway MockMvc tests for empty sessions, matched champion/challenger disagreement, missing links, complete enrolled roster, mismatched PAPER fill quantities, actual recorded fills and fees, ADMIN-only access, missing sessions, and permanent false authority flags. No network model or broker credentials are used.

### Next implementation steps

1. Bind the read-only JSON summary into the Flutter V4-11 Model Control Center after concurrent Flutter PRs are merged.
2. Add an authenticated in-process model decision publisher/runner that produces *actual* registered model inference, uses bounded provider timeouts, permanently records failures/abstentions and does not depend on operator-submitted timestamps.
3. Reconcile canonical decision capture with actual simulated PAPER order/fill and TCA, use a qualified SHADOW runtime soak policy, then freeze and independently verify evidence before model promotion.
4. Defer paid NSE/BSE/news subscriptions and provider configuration until the operational configuration phase as requested; external legality and LIVE-release acceptance requirements remain intact.

No LIVE mode transition or real brokerage order is implemented or authorized here.
