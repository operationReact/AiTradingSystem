# PAPER automation API integration contract

This contract describes the customer API that the next integration commit should expose by adapting the existing authenticated account/session and persisted PAPER services. It is not permission to create a second production stack.

## Account preference

`GET /api/account/trading-preference`

Customer response:

```json
{
  "tradingMode": "PAPER",
  "executionMode": "MANUAL",
  "liveAvailable": false
}
```

`PUT /api/account/trading-preference`

Accept account-scoped execution-mode changes. Requests for LIVE must fail closed while `liveAvailable=false`.

## Automated PAPER evaluation

Automation consumes eligible AI/research opportunities server-side. The mobile client can display status and pause/resume the account preference but must not manufacture an authoritative AI opportunity or bypass risk evaluation.

## Lifecycle status

Customer-facing portfolio/order/history APIs should expose readable states for accepted, filled, open, closed, paused and profit/loss. Internal provider/reconciliation codes must not become primary UI strings.

## Safety

Every mutation requires the existing authenticated account-scoped session and CSRF/authorization protections. LIVE broker submission remains unavailable. Angel One is read-only in the current release boundary.
