# Authenticated F&O research runtime evidence — read-only gate

## Purpose

The standalone Upstox sandbox order lifecycle previously succeeded, but
that is not proof Mintick Mind made a valid signal or a live/PAPER trade.
The Mind F&O research bridge PR #1770 added authenticated GET semantics,
but previous tests only checked unit fixtures and unauthenticated 401.

This change extends the existing **Mind live edge verification** using its
already configured, per-run short-lived Cognito **verification customer**.
It makes exactly one read-only authenticated
`GET /api/customer/mind/v1/fno-evaluation` after the existing Mind read
checks. The token never appears in CI logs. The original customer JSON
is discarded on the runner; no quote, instrument key, token, account,
P&L or order data is exported.

`scripts/mind/check_fno_research_runtime.py` emits a sanitized result:

- `HTTP_200` with `BLOCKED`, `NO_TRADE` or a provenance-checked
  `RESEARCH_CANDIDATE`, including only bounded completed bar count,
  allowlisted reason category and strategy class.
- `AUTH_DENIED` (401/403), `NOT_DEPLOYED` (404), `BACKEND_ERROR`
  (5xx), or fail-closed invalid response labels.
- Every marker explicitly declares `execution=false`. Even a valid
  `RESEARCH_CANDIDATE` is a **read-only research signal** and does not
  authorize sandbox, PAPER or LIVE orders.

The probe uses the **verification customer's** own identity; a blocked
rights or account-specific result here must not be interpreted as the
owner's session outcome.

## Evidence and remaining gates

Run the [Mind live edge verification workflow](https://github.com/operationReact/AiTradingSystem/actions/workflows/mind-live-verify.yml)
and inspect the `MIND_FNO_RESEARCH_RUNTIME:` marker.

- Valid authenticated runtime decision, no real signal: the correct
  current status is **not independently validated E2E**.
- Genuine market-hours candidate: still requires sandbox order ACK,
  duplicate suppression and independent broker order state.
- Full 43 strategies: separate strategy-specific rights-attested original
  data, historical replay, PAPER and LIVE proof are mandatory.

This probe never changes live order permissions, provisions AWS resources,
places orders, or changes the broker credential configuration.
