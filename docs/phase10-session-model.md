# Phase 10 W02 — server-side session model

## Boundary

P10-W02 introduces opaque server-side sessions without claiming production login-provider completion.

- Session cookie: `ATS_SESSION`, HttpOnly, SameSite=Strict, Secure by default, path `/`.
- Raw session tokens are never persisted; PostgreSQL stores SHA-256 token hashes only.
- CSRF secrets are independently generated and stored as hashes only.
- Session lifetime defaults to 8 hours and is capped at 7 days.
- Expired, revoked, or disabled-user sessions fail closed.
- Session records carry created/expires/revoked/last-seen timestamps.
- User-agent and IP metadata are hashed before persistence and are not used as authentication factors.
- Cookie-authenticated state-changing requests require `X-CSRF-Token` matching the bound session secret.

## Endpoints

- `POST /api/auth/dev/session` — temporary local/dev bridge from the legacy operator API key to a cookie session. Disabled by default and must never be enabled in public production.
- `GET /api/auth/session` — returns the authenticated session identity and expiry.
- `DELETE /api/auth/session` — revokes the current session and clears the cookie; CSRF-protected for cookie-authenticated callers.

The bootstrap response includes the CSRF token for the new session. A production identity-provider/login exchange must provide the corresponding CSRF value as part of its authenticated session establishment flow.

## Migration state

The existing `X-API-Key` filter remains temporarily available for local/dev and automation compatibility. It no longer overwrites an already-authenticated cookie session. Removing it from untrusted Web/Android/iOS clients and enforcing the production authorization matrix are later Phase 10 slices.

## Explicit non-claims

- No external IdP or password-login provider is selected or implemented by W02.
- No production website deployment is authorized by W02.
- No LIVE trading or broker submission capability is enabled.
