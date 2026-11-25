# P10-W01 — User account and preference persistence domain

This slice establishes the server-side persistence boundary required before browser/mobile clients can move away from the shared operator API key.

## Scope

- `app_user` stores an opaque external identity subject plus bounded profile metadata.
- `user_preference` owns experience, theme, density, spacing, motion/haptics, data defaults, diagnostics and stale-data defaults.
- `user_notification_preference` owns channel/digest/operator-alert preferences.
- Preference rows use an optimistic `version`; updates are compare-and-set and return no result on stale versions.
- Security alerts are fail-closed and cannot be disabled by the persistence API.
- Audit metadata stores actor names and timestamps only. Authentication tokens, API keys and credentials are never persisted in these tables.

## Locked defaults

The database defaults match the Phase 10 design contract: Neon AI, System theme, Auto density, 1.0 spacing, motion on, haptics on, mobile download prompt on, Future route, 5m/500 candles, diagnostics opt-out, one stale refresh attempt with a five-second threshold, all notification channels on, one weekly digest, security alerts on, root-cause aggregation on and an 80% operator-coverage threshold.

## Security boundary

This PR deliberately does **not** expose `/api/auth/session` or replace authentication. The existing shared `X-API-Key` filter remains a known release blocker until the later Phase 10 authentication slice replaces it for untrusted Web/Android/iOS clients. No production deployment or LIVE trading is enabled by this work.
