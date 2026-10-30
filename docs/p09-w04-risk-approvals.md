# P09-W04 correction — Risk and Approvals

Status: IN PROGRESS / stacked corrective Flutter slice

## Canonical evidence

- Figma file `KjBWVCtUYona0bOGb5NDov`
- Phase 5 risk section `23:2458`
- Phase 7 trading modes/approval policy `23:3440`
- Phase 9 appearance/interaction `23:4422`
- Flutter handoff `30:2`

Historical React/browser UI is not accepted as production Flutter completion.

## Backend authority

Risk evidence/actions use the existing Spring APIs:

- `GET /api/risk/config`
- `GET /api/risk/decisions?page=0&size=20`
- `PUT /api/risk/emergency-stop`

Approvals use:

- `GET /api/approvals?page=0&size=20`
- `POST /api/approvals/{approvalId}/approve`
- `POST /api/approvals/{approvalId}/reject`
- `POST /api/approvals/{approvalId}/cancel`

Approval decisions submit the exact backend `expectedRowVersion`; stale state must therefore fail at the server instead of being silently overwritten. Emergency-stop mutation is followed by a fresh `/api/risk/config` read so the UI never invents policy/mode state from the mutation response.

## Flutter implementation

- `data/risk_approval_repository.dart`
  - typed risk config, risk decision and approval models
  - concurrent authoritative read of config/decisions/approvals
  - version-aware approval mutations
  - emergency stop + mandatory authoritative config re-read
- `presentation/risk_approvals_page.dart`
  - PAPER/LIVE and new-order-halt state is textual, not color-only
  - explicit policy version, risk limits and persisted decision reasons
  - pending approvals show policy/row version and expiry
  - approve/reject/cancel use confirmation dialogs and require an actor
  - emergency halt requires an explicit reason and confirmation
  - loading/error/stale/empty states remain explicit
- `app.dart`
  - `/risk` and `/approvals` resolve to the real backend-backed Flutter surface

## Tests

- config/decision/approval read contracts
- emergency stop uses PUT and re-reads authoritative config
- approval mutation sends exact expected row version
- Flutter screen renders PAPER/LIVE/policy/row-version evidence
- empty emergency-stop reason is blocked before mutation
- approval confirmation preserves the source row version and explicit actor

## Safety and remaining gaps

No risk limit is evaluated or overridden in Flutter. No approval result is inferred locally. This slice does not enable LIVE, broker submission, credential persistence or offline replay.

Remaining P09-W04 corrective inventory after this slice includes News/Notifications/Audit/Settings, explicit Scenario/Regime coverage and any other inventory items not yet backed by real Flutter screens. W09 remains downstream of the complete correction inventory.
