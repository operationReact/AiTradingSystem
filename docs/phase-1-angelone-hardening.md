# Phase 1 — Angel One client and session hardening

## Scope
This phase hardens the existing read-only SmartAPI adapter. It does not implement instrument discovery, streaming, candle persistence, or any live-order path.

## Changes
- JWTs stay in process memory only; refresh tokens are not retained after login and tokens are never persisted or returned to the frontend.
- Session recovery is token-aware: a delayed expiry response for an older JWT cannot invalidate a newer session.
- Quote recovery performs at most one retry and only after an explicit provider session-expiry response. HTTP 429, network failures, timeouts, server errors, malformed responses and other rejections are not retried.
- Ordinary auth/quote requests remain fail-fast throttled. Recovery waits at most `session-retry-max-wait-ms` for a quota slot, then fails with a categorized error.
- Invalid TOTP secrets fail as configuration errors without issuing a provider request.
- Provider URL must be an HTTPS origin without user information, query, fragment, or app path. Connect/read timeouts remain configured.
- Provider health now reports session activity/age, last quote time, last successful call, last error code and last check. The view contains no secrets.
- Explicit connection-test failures update health state. The dashboard displays session and quote freshness.

## Configuration
Backend-only env vars: `ANGELONE_API_KEY`, `ANGELONE_CLIENT_CODE`, `ANGELONE_PASSWORD`, and `ANGELONE_TOTP_SECRET`. Recovery wait uses `app.providers.angel-one.session-retry-max-wait-ms` (default 6000 ms; allowed 0–10000). Keep provider request intervals conservative until verified against current SmartAPI limits.

## Regression coverage
Tests cover session reuse and max-age refresh, single expiry recovery, bounded quota waits, not attempting login when the quote quota is blocked, stale token handling, invalid TOTP configuration, HTTP timeout/error classification, secret redaction, and health failure recording.

## Validation boundaries
The current workflow `.github/workflows/phase0-validation.yml` runs backend Maven tests and frontend production build on pushes and PRs targeting `dev`. Both checks on the final Phase 1 commit must finish successfully before this phase is considered validated. Live SmartAPI login remains a manual smoke test using authorized credentials only. No credentials are committed, and no live-order path is added. The limiter remains process-local; use shared coordination or a single provider-owning instance before scaling to multiple backend replicas.
