# P09-W04 correction — PAPER Portfolio and Orders

Status: IN PROGRESS / stacked corrective Flutter slice

## Canonical requirement

This slice restores production Flutter coverage for the Phase 9 required-screen inventory using real backend contracts. Historical React/browser evidence is reference only.

Canonical Figma:

- file `KjBWVCtUYona0bOGb5NDov`
- Phase 6 portfolio/execution section `23:2949`
- Phase 8 orders/history/notifications section `23:3931`
- Phase 9 appearance/interaction section `23:4422`
- Flutter handoff `30:2`

## Backend authority

Portfolio is read from:

- `GET /api/paper-portfolio`

Orders are read or changed only through the existing PAPER APIs:

- `GET /api/paper-trades`
- `POST /api/paper-orders`
- `POST /api/paper-orders/{id}/cancel`

`POST /api/paper-orders` requires `Idempotency-Key`. The Flutter client keeps the same generated key after an ambiguous/failed request and clears it only after a confirmed response. The backend remains authoritative for risk evaluation, order status, rejection reasons, fills, accounting and reconciliation.

There is no LIVE broker submission path in this slice.

## Flutter implementation

- `data/paper_trading_repository.dart`
  - typed portfolio/position/order/result models
  - authenticated `X-API-Key` requests
  - explicit PAPER order idempotency header
- `presentation/paper_portfolio_page.dart`
  - authoritative PAPER mode/reconciliation/account values
  - positions with backend mark provenance/time
  - loading, empty, error and last-known/stale refresh state
  - explicit fail-safe warning when backend mode evidence is not PAPER-only
- `presentation/paper_orders_page.dart`
  - explicit PAPER MARKET intent form
  - no invented signal timestamp: user/backend evidence must supply it
  - bounded local shape validation matching backend invariants
  - backend risk result/reason display
  - retained idempotency key across failed/ambiguous retry
  - authoritative recent-order list and cancel action
  - loading, empty, error and stale list states
- `app.dart`
  - `/portfolio` now resolves to the real PAPER portfolio screen
  - `/orders` and `/paper-orders` resolve to the real PAPER orders screen

## Tests

- portfolio API parsing and `X-API-Key`
- exact order paging contract
- exact PAPER order POST payload and `Idempotency-Key`
- Flutter portfolio mode/reconciliation/position rendering
- BUY validation blocks incomplete orders before repository submission
- Flutter order result renders backend risk status/reason evidence

## Safety and remaining gaps

This PR does not claim Phase 9 completion. It does not enable LIVE, infer broker fills, bypass backend risk/approval rules, persist operator credentials, or fabricate market/portfolio values.

Still open after this slice: Risk/Approvals production Flutter surfaces, News/Notifications/Audit/Settings, explicit scenario/regime coverage and any remaining required-screen inventory. W09 final test gate remains downstream of those corrections.
