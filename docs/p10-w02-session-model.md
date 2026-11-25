# P10-W02 — Server-owned authentication session model

## Scope

This slice adds the server-side session primitive required by Phase 10. It deliberately does **not** expose a login/session HTTP endpoint and does not yet replace the transitional `X-API-Key` filter; those belong to the later authentication-controller/filter work item.

## Locked security contract

- Sessions are server-owned, expiring and explicitly revocable.
- A newly issued session credential is an opaque 32-byte cryptographically random value encoded with URL-safe Base64 without padding.
- The raw session credential is returned only to the caller that creates the session. It is never stored in PostgreSQL, audit metadata or logs.
- PostgreSQL stores only the lowercase SHA-256 hex digest in `auth_session.token_hash`.
- Resolution accepts only a matching session that is not revoked, has not expired, and belongs to an `ACTIVE` `app_user`.
- Session TTL must be greater than zero and cannot exceed 30 days.
- Revocation is persisted with `revoked_at` and actor metadata.
- `last_seen_at` is server-controlled; a later authentication filter can update it without changing the credential.
- Web delivery must eventually use an HttpOnly + Secure + SameSite cookie. Android/iOS must use platform-secure credential storage. Browser `localStorage` is forbidden for session credentials.

## Database

Flyway `V39__auth_session.sql` creates `auth_session` with explicit `created_at`, `expires_at`, `last_seen_at`, `revoked_at`, audit actors, uniqueness on token hash, and active-session/expiry indexes.

## Java boundary

- `AuthSession` contains non-secret session/user metadata only.
- `AuthSessionRepository` persists and resolves token hashes and enforces active-user/expiry/revocation constraints in the database query boundary.
- `AuthSessionService` generates opaque credentials, hashes them before persistence, resolves by digest, and revokes by digest.

## Verification

`AuthSessionServiceTest` proves that the repository receives a 64-character SHA-256 digest rather than the raw token, verifies deterministic hashing, and rejects invalid/overlong TTL values. Phase 0 PostgreSQL/Flyway CI remains the migration/integration authority.

## Explicitly not claimed by W02

- No `/api/auth/session` contract yet.
- No cookie issuance yet.
- No native secure-storage integration yet.
- No role/permission model yet.
- No replacement of the shared `X-API-Key` authentication path yet.
- No production deployment authorization.

The next cursor is **P10-W03 — role and permission model**.
