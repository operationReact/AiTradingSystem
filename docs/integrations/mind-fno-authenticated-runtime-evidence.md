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

## After-hours capability and expiry diagnosis (23 November follow-up)

The research endpoint returns `NO_TRADE / MARKET_CLOSED` before reading
historical candles or live quotes; that result cannot diagnose the feed.
The protected customer-only, read-only endpoints are now tested alongside it:

- `GET /api/customer/markets/fno/capability`: source-ready, V3 configured,
  discovery/display flags, market session, stream state and bounded counts.
- `GET /api/customer/markets/fno/expiries`: listed expiry status/count only,
  with HTTP 409 classified as configuration blocked. No expiry or instrument
  identities are exported into CI logs.

The same verification account obtains a new short-lived Cognito access token.
A deliberate 409, market-closed status, missing source or display gate is an
**observed blocker**, never proof of licensing or automatically a trading
permission. The user's stated personal-use permission does not mutate backend
configuration and no technical source-data or strategy-E2E claims are promoted
without observed original provenance and independent account/trade checks.

Output markers: `MIND_FNO_CAPABILITY_RUNTIME:` and
`MIND_FNO_EXPIRIES_RUNTIME:` followed by fixed status flags and aggregates.
No orders are submitted or position, user, quote or provider secrets logged.
