# PAPER automation mode transitions

Safe customer transitions for the current release:

- PAPER + Manual -> PAPER + Automated: allowed after authenticated account preference update.
- PAPER + Automated -> PAPER + Manual: allowed; stops creation of new automated intents. Existing PAPER positions remain managed by authoritative risk/exit rules.
- Any state -> Trading paused: emergency stop overrides intent creation and triggers the authoritative safety behavior for existing PAPER positions.
- Trading paused -> active: only after authoritative emergency-stop state is cleared; client state alone cannot resume execution.
- PAPER -> LIVE: unavailable/fail closed in this release.

Mode changes must not duplicate, orphan or silently convert existing PAPER orders/positions. Integration with persisted production services must define transaction/idempotency behavior for concurrent transitions.
