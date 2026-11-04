# V4-09 — Java durable PAPER/SHADOW decision journal

## Scope

The [V4 master implementation plan](https://app.notion.com/p/40d36a828cb6812594c0e657fe5db1b3) requires simultaneous model inference on identical market/news observations, disagreement evidence and a stable link to simulated PAPER execution/fills/TCA. PR #380 established an offline Python comparison contract. This PR adds an actual PostgreSQL-backed **Admin-only** receipt ledger in the Java backend, without creating a second execution engine or enabling LIVE order execution.

The product owner confirms Angel One static IP is configured and explicitly wants implementation completed before configuring commercial exchange/news subscriptions, credentials or rollout. Those remain release checks but **do not block** this code PR.

### Contract and endpoints

Migration: `V56__v4_paper_shadow_decision_journal.sql`

Backend:

- `POST /api/admin/model-governance/paper-shadow/sessions`: enroll 2–10 registered model versions, exactly one PAPER champion and one or more SHADOW challengers; freeze source kind `CI_SYNTHETIC` or `RUNTIME_EXPORT`, source/target cohort hashes and version-specific registry fingerprints. Every model must be eligible under the current Java emergency-disable/fallback governance policy at enrollment.
- `GET /api/admin/model-governance/paper-shadow/sessions/{id}`: read immutable enrollment.
- `POST /api/admin/model-governance/paper-shadow/sessions/{id}/cases`: append an exact per-market-case roster of model ENTER/WAIT/ABSTAIN decisions, predicted timestamps, model evidence hashes and a *shared* market/news/input SHA-256. Every prediction must be after enrollment and after both sources were available, and by the declared decision time; the decision time cannot be in the future or before enrollment. Duplicate identical case submissions are idempotent; a changed case ID/body is rejected.
- `GET /api/admin/model-governance/paper-shadow/sessions/{id}/cases?limit=100` and `GET .../cases/{caseKey}`: bounded, read-only evidence for Flutter Admin screen and research bridge.
- `POST .../cases/{caseKey}/paper-order-links` with existing `orderId`: attach **only** a recorded PAPER champion ENTER to an **already persisted** automated BUY PAPER order with matching symbol and subsequent creation time. No order placement or status change occurs. Link identity is unique and immutable. Query joins the existing PAPER fill ledger on every read, exposing the exact aggregated count/fees/slippage and current PAPER status without inventing actual fills.

An ADMIN model-decision intake is not independent evidence of simultaneous live model inference. `RUNTIME_EXPORT` is self-declared; paper-order linkage asserts a temporal/source relationship and cannot by itself prove that a particular inference *caused* that order. Neither case input nor decision records accept market outcomes as prediction features. Later Python analysis must bind a separately authenticated, causally later outcome and test all candidates on an identical cohort.

### Safety and limits

- Database constraints enforce frozen member/model-role identities, exact model-version FKs, one PAPER champion per session, one decision per enrolled version per case, and one paper-order link per case/order.
- All routes require `ADMIN` role, preserve audit records and never expose credentials, raw licensed news or private market feeds.
- No POST in this PR invokes `PaperOrderService.placeOrder()`, any `AngelOneApiClient`, execution-mode changes, model promotion or LIVE/capital authority.
- Existing `TradingExecutionAuthority` continues to reject LIVE; Java risk, approval and reconciliation remain the only actual PAPER execution owners.
- CI uses synthetic models and PAPER order/fill fixtures. It cannot establish live source custody, broker/exchange compliance, authentic execution economics, external timestamp witnesses, production PAPER/SHADOW soak or a real Flutter platform acceptance.

## Next code-only increments

1. Capture actual registered Java/Python inference at the originating runtime, and automatically publish it to this journal as part of the same decision pipeline (avoid manually asserted `RUNTIME_EXPORT`).
2. Link genuine automated PAPER order outcomes through a trusted identity emitted by the order service. Bind risk-decision, fill, TCA and reconciliation IDs from the authoritative tables.
3. Provide read-only Flutter Admin roster/decision/disagreement/filled-cost dashboards with permissions, stale/error/empty/loading states.
4. Complete V4-09 continuous soak, V4-10 promotion/rollback and V4-11 operator-control UI. External licensing and production secrets are deferred, **not waived**.
