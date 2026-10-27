# Phase 2 — Single Paper / Live mode control

The dashboard has one operator-controlled execution-mode selector. The backend is authoritative; the browser does not store the selected mode.

## Current guarantees

- Flyway migration `V7__trading_execution_mode.sql` initializes one mode row as `PAPER`.
- The mode endpoint is protected by `X-API-Key`.
- `GET /api/system/execution-mode` returns persisted mode, live availability, blockers, and check time.
- `PUT /api/system/execution-mode` persists a request for `PAPER` and returns the server-confirmed state.
- A request for `LIVE` requires confirmation text `SWITCH TO LIVE`, then returns HTTP 409 while the live order adapter, verified credentials, and live safety conditions are not implemented.
- LIVE is never reported available merely because a UI control was clicked.
- The startup guard still rejects non-paper configuration. This build has no live broker order path.
- The setting is shared across browser sessions because it is stored in PostgreSQL.

## API

```http
GET /api/system/execution-mode
X-API-Key: <operator key>
```

```http
PUT /api/system/execution-mode
X-API-Key: <operator key>
Content-Type: application/json

{"mode":"PAPER"}
```

A LIVE request remains blocked until real order execution is implemented and independently validated:

```json
{"mode":"LIVE","confirmation":"SWITCH TO LIVE"}
```

## Release validation

Run CI on `dev`, apply Flyway through V7, and confirm `activeMode=PAPER` and `liveAvailable=false`.

**Important:** This adds the requested control and persisted mode state. It does not enable real-money orders. Live rollout needs an actual broker order adapter, broker-side reconciliation, idempotency, pre-trade risk checks, a kill switch, audit trails, and live-environment tests.
