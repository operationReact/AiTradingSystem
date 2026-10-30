# Phase 7 approval completion evidence

Phase 7 is **NOT VERIFIED**. Merged component PRs are not evidence that approvals gate the PAPER fill path.

## P07-GATE01 findings

| Requirement | Finding at dev `44677db` | Corrective evidence |
| --- | --- | --- |
| Expiry-aware decisions | Pending approvals could be approved after expiry | Decision CAS now chooses EXPIRED when `expires_at <= decision time`; exact-boundary test |
| Exactly-once audit | Controller before/after comparison could audit two identical concurrent clicks | Audit belongs to the successful SQL update inside the service transaction; concurrent identical-decision regression |
| Stale/duplicate decisions | Updates must preserve row-version semantics | PostgreSQL tests verify no mutation or additional audit for stale/replayed requests |
| Policy before PAPER execution | `PaperOrderService` went directly from hard-risk approval to READY/FILLED | **OPEN: wire policy resolution/evaluation and durable approval creation into order placement** |
| Fresh revalidation before fill | `ExecutionReadinessService` had no production caller | **OPEN: invoke revalidation in serialized settlement transaction after approval** |
| Complete lifecycle | Operator decisions did not resume/reject/cancel the associated order | **OPEN: integrate order transitions and exactly-once settlement; pending cancellation/expiry must release intent** |
| Dual-experience E2E | Approval screen exists; no approval lifecycle browser test | **OPEN: integrated API and browser evidence, then exit review** |

## Decision invariants

- Compare-and-set on PENDING and the expected row version determines the sole winner.
- At or after expiry, an attempted decision expires the approval instead of granting authorization.
- The winning mutation and its audit event share one transaction. Losers return current persisted state.
- Direct service callers and HTTP callers share the same audit behavior.
- Approval never overrides deterministic hard risk, emergency stop, fresh market/account state or session checks.
- PAPER execution never implies LIVE submission.

The decision regression workflow is partial evidence only. P07-GATE01 cannot pass until the remaining integration and end-to-end obligations above are proven. The next phase remains P08-PR01 after Phase 7 verification.
