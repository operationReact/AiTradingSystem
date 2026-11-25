# V4-09 — Link canonical PAPER/SHADOW decision sessions to existing PAPER orders

## Why this is a separate PR

V4-09 PR [#381](https://github.com/operationReact/AiTradingSystem/pull/381) already delivered the authoritative Java `v4_matched_session`, `v4_matched_participant`, `v4_matched_case` and `v4_matched_decision` append-only ledger with ADMIN-only evidence API and PostgreSQL tests (Flyway V56). Do not create a second decision ledger. Closed superseded PRs #382/#383 attempted overlapping schema and must **not** be merged or used as completed evidence.

This increment adds only Flyway **V57**: `v4_matched_paper_order_link` connects one immutable V56 PAPER champion ENTER decision to exactly one **pre-existing** Java PAPER BUY order, with a unique order reference and a model/version/decision fingerprint. SHADOW candidates cannot link orders. No broker request or `PaperOrderService.placeOrder` call is made.

## API, no new execution path

```http
POST /api/admin/model-governance/paper-shadow/sessions/{sessionId}/observations/{caseKey}/paper-order-link
Content-Type: application/json

{"paperOrderId":"<UUID of an already-existing AUTOMATED PAPER BUY>"}

GET /api/admin/model-governance/paper-shadow/sessions/{sessionId}/observations/{caseKey}/paper-order-link
```

Both routes require ADMIN RBAC. The Java service checks PAPER execution mode, exact session/case and champion-model registry identity from PR #381, champion action ENTER, linked order BUY/AUTOMATED provenance, exact symbol and order creation time no earlier than the model decision. Duplicate identical links replay the original; conflicting links fail closed. There is no update/delete path or independent trading authority.

The GET reads the existing `paper_order` status and **current** `paper_fill` ledger: submitted quantity, order-reported filled quantity, recorded fill quantity, exact BigDecimal simulated fees and slippage, plus a simple filled-quantity parity indicator. These amounts are not P&L, not after-cost TCA, not order-execution acceptance and not an independently verified exchange fill. Since the original PAPER order/fill ledger may change after linking, the GET surfaces the current ledger instead of freezing/stating future fill values at initial capture. Actual reconciliation still depends on the existing Java PAPER reconciliation services.

A source case and an ADMIN-submitted order reference are operator declarations; even with precise DB timestamps, they **do not independently prove** the model actually caused the order, that data licensing is valid, that the exchange market was live, or that multiple models ran simultaneously.

The response permanently has `independentlyVerifiedRuntime=false`, `brokerOrderSubmitted=false`, and `canAuthorizeCapital=false`. Matching simulated PAPER fill quantities does not convert it to a verified trade signal or capital-authorizing artifact.

## Regression / pending

`MatchedPaperShadowControllerIT` covers real Postgres/Flyway session enrollment, immutable case intake, linked automated PAPER order and actual fill reading, exact replay, rejected alternate order IDs, nonexistent/manual/mismatched/early orders, non-ENTER decisions and ADMIN-only access.

V4-09 remains **IN PROGRESS**, not finished. Next:
1. Instrument the *authoritative Java inference/PAPER decision runtime* to publish model results into V56 sessions without accepting operator-invented timestamps.
2. Bind runtime signals to PAPER order intents in-process, retain failure/timeout/UNKNOWN evidence, and prove simulated fills/TCA/reconciliation on complete cohorts.
3. Implement paired Python PR #380 export and V4-11 Flutter Admin read-only evidence dashboards; separately add Admin enrollment, pause and quarantine controls with safe server policy.
4. Validate independent real-source data, qualified PAPER/SHADOW soak and LIVE risk/capital controls before deployment.

Licensing, provider credentials, subscription setup and the user's already-configured static IP remain operational configuration; no payments, deployment or LIVE activation occur as part of this code-only PR.
