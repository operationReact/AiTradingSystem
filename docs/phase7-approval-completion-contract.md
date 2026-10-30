# Phase 7 approval completion evidence

Phase 7 completion is conditional on green PostgreSQL lifecycle, full baseline/regression and dedicated browser evidence for the final gate commit. Component merges alone do not establish the exit condition.

## P07-GATE01 findings

| Requirement | Finding at dev `44677db` | Corrective evidence |
| --- | --- | --- |
| Expiry-aware decisions | Pending approvals could be approved after expiry | Decision CAS now chooses EXPIRED when `expires_at <= decision time`; exact-boundary test |
| Exactly-once audit | Controller before/after comparison could audit two identical concurrent clicks | Audit belongs to the successful SQL update inside the service transaction; concurrent identical-decision regression |
| Stale/duplicate decisions | Updates must preserve row-version semantics | PostgreSQL tests verify no mutation or additional audit for stale/replayed requests |
| Policy before PAPER execution | `PaperOrderService` went directly from hard-risk approval to READY/FILLED | Implemented in #137: both PAPER APIs create policy-linked durable approvals; missing policy rejects |
| Fresh revalidation before fill | `ExecutionReadinessService` had no production caller | Implemented in #137: the sole fill call follows fresh revalidation under account, policy and stop-state locks |
| Complete lifecycle | Operator decisions did not resume/reject/cancel the associated order | Implemented in #137: serialized decisions resume or terminate orders; the final gate adds queue-expiry and order-cancel regression evidence |
| Dual-experience E2E | Approval screen exists; no approval lifecycle browser test | PaperApprovalWorkflowIT proves real PostgreSQL/API behavior; approvals.spec.mjs proves the separate browser interaction contract in Pro Terminal desktop and Clear Invest mobile |

Policy version correction #138 preserves historical policy values and creates a successor ID/version when approval mode changes. Existing approvals fail readiness when the current policy changes.

## Decision invariants

- Compare-and-set on PENDING and the expected row version determines the sole winner.
- At or after expiry, an attempted decision expires the approval instead of granting authorization.
- The winning mutation and its audit event share one transaction. Losers return current persisted state.
- Direct service callers and HTTP callers share the same audit behavior.
- Approval never overrides deterministic hard risk, emergency stop, fresh market/account state or session checks.
- PAPER execution never implies LIVE submission.

## Evidence commands and limits

- Full baseline: `mvn --batch-mode --no-transfer-progress test` (Java 21 and PostgreSQL/Testcontainers).
- Dedicated backend gate: the `approval-decision-evidence` job runs policy/schema, exact-boundary expiry, concurrent identical decisions, transactional audit, production readiness, real PAPER lifecycle, replay and accounting regressions.
- Dedicated browser gate: `npx playwright test e2e/approvals.spec.mjs --reporter=line` covers explicit confirmation/cancel, expected row version, fill versus blocked execution, expired cached approvals and recoverable-error retry; Pro Terminal at 1440px and Clear Invest at 390px include axe serious/critical checks and overflow checks.
- Browser tests use HTTP fixtures; they do not claim to run a live PostgreSQL backend. The backend job separately proves the transactional API behavior against PostgreSQL. Both jobs are required; neither substitutes for the other.
- Screenshots and test reports are uploaded by the dedicated workflow. No external provider or LIVE broker is invoked.

After the final gate commit passes all required jobs and is merged, the canonical exit condition can be marked VERIFIED: MANUAL/AUTO/HYBRID are deterministic, audited, expiry-aware, concurrency-safe and unable to override hard risk. The next allocation is P08-PR01.
