# V4-09 — Java PAPER/SHADOW decision evidence bridge (backend increment)

**Scope:** PostgreSQL append-only, Java-authoritative storage of multiple model decisions on an identical declared market/news input, with an *optional reference to an already-existing automated PAPER order*. This connects durable backend model identity and real PAPER order/fill ledger references to V4-09's [PR #380](https://github.com/operationReact/AiTradingSystem/pull/380) offline counterfactual comparison contract. It does **not** co-run models, place PAPER orders, invoke Angel One, submit SHADOW orders, provision cloud resources or enable LIVE.

## Data model

Flyway V56 introduces:

- `paper_shadow_decision_case`: one immutable case key, symbol, decision time, observed market/news availability times, full source/identical-input hashes, recording actor and fingerprint; independent data-custody and capital/LIVE flags default FALSE and are CHECK-constrained to FALSE.
- `paper_shadow_model_decision`: exactly one versioned model record per case, its precise registered version fingerprint, champion/challenger role, ENTER/WAIT/ABSTAIN action, predecision prediction time and optional *existing* PAPER order ID. Partial unique indexes prevent duplicate champions and duplicate linked PAPER orders.
- The Java service checks the complete roster (1 PAPER champion plus 1–9 SHADOW challengers), exact model-version existence, frozen source-time and equal input identity, duplicate model candidates, role/action for any PAPER order reference and linked order's symbol, AUTOMATED origin and ordering time.
- Queries join the **real existing** `paper_order` plus `paper_fill` totals (filled quantity, simulated fee and slippage) rather than generating a fabricated fill. Any absent order link remains absent, especially for SHADOW.
- Append-only case identity: repeat the **same** case key and exact inputs returns the same evidence; conflicting replay is rejected. Existing source and decision records are never updated. Concurrent duplicate inserts fail with a conflict and should be resolved by querying the original case, never blindly resubmitting an order.
- ADMIN-only endpoints with server-side RBAC and an audit event at immutable capture.

## API routes

All require real ADMIN authorization. No model or client token alone grants access.

```
POST /api/admin/model-governance/paper-shadow/evidence/cases
GET  /api/admin/model-governance/paper-shadow/evidence/cases/{caseId}
GET  /api/admin/model-governance/paper-shadow/evidence/cases?limit=25
```

Example shape of a *synthetic* request — replace version UUIDs with previously registered model-version IDs, and use actual source capture evidence rather than inventing timestamps:

```json
{
  "caseKey": "synthetic-reliance-001",
  "symbol": "RELIANCE",
  "marketAvailableAt": "2026-10-29T04:00:00Z",
  "newsAvailableAt": "2026-10-29T04:00:10Z",
  "decisionAt": "2026-10-29T04:01:00Z",
  "marketSourceFingerprint": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "newsSourceFingerprint": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "inputFingerprint": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "candidates": [
    {
      "modelVersionId": "00000000-0000-0000-0000-000000000001",
      "role": "PAPER_CHAMPION",
      "action": "ENTER",
      "predictedAt": "2026-10-29T04:01:00Z",
      "paperOrderId": null
    },
    {
      "modelVersionId": "00000000-0000-0000-0000-000000000002",
      "role": "SHADOW_CHALLENGER",
      "action": "WAIT",
      "predictedAt": "2026-10-29T04:01:00Z",
      "paperOrderId": null
    }
  ]
}
```

**Do not submit this sample as performance evidence.** Its UUIDs and hashes are placeholders. A PAPER order link is optional; it must already exist, must be AUTOMATED, and must be created no earlier than the model decision.

## What remains for operational V4-09 acceptance

This PR delivers only the *backend durable intake*, not automatic runtime capture. The API can accept operator-supplied historical timestamps and hashes, so it never claims that the decisions truly ran prospectively, used authentic licensed sources, continuously soaked, or generated net trading alpha.

Next work:
1. Wire actual Java automated PAPER model inference/candidate orchestration to the storage API/service in the same bounded runtime decision loop, not from a submitted unverified JSON body.
2. Record durable decision-to-PAPER order intents, candidate disagreement timing, all paper-fill transitions and after-cost TCA/reconciliation. Never auto-replay UNKNOWN orders.
3. Export exactly matched cohorts into the Python PR #380 evaluator after outcomes become available with source-time/label embargo, licensing and independent witness checks.
4. Add Flutter V4-11 read-only model inventory and PAPER/SHADOW evidence views; separate Admin action controls need explicit safety and role gates.
5. Prove real simultaneous multi-model PAPER/SHADOW runtimes and independent soak before any model promotion or LIVE release.

The user confirmed static IP already configured; subscriptions, credentials and other provider configuration can be completed later for operational acceptance. No configuration or paid subscription is performed in this PR. Flutter impact is future Admin UI only; passing Flutter policy CI does not certify web, Android or iOS runtime acceptance.
