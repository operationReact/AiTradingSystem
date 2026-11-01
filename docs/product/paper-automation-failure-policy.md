# PAPER automation fail-closed policy

When required authoritative data is unavailable or inconsistent, automated PAPER execution must prefer no new trade over guessing.

Fail closed for:

- missing/expired account session;
- unavailable account preference state;
- unavailable emergency-stop/risk state;
- missing/invalid opportunity identity or trade parameters;
- unavailable PAPER buying power/exposure state;
- duplicate/idempotency uncertainty;
- inability to persist an accepted order/fill/position transition;
- stale or unavailable market price when a safe decision cannot be made.

Customer UI should explain the temporary inability to proceed without exposing raw internal codes. Existing open positions remain governed by the repository's authoritative safety/recovery rules; integration must not invent a second recovery policy.
