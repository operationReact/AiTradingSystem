# Risk policy activation clock

A Phase 5 CI failure exposed a real concurrency race. PostgreSQL `now()` is fixed at transaction start. A policy writer waiting for the serialized version lock can begin before the preceding writer, then observe its newly committed active row under READ COMMITTED while evaluating effective dates against an older instant. The active lookup can report no policy. Reusing that same old instant for activation can also close the preceding policy before its effective start.

Active lookup now uses the current statement timestamp, retaining both effective-date checks. Lifecycle activation captures one database wall-clock timestamp after locking current/target rows, uses it for both the old policy's end and successor's start, and returns the resolved active row in the same transaction. Existing writer/version serialization, immutable policy values, single-active uniqueness, audit transaction and fail-closed missing/future-policy behavior remain intact.

The deterministic PostgreSQL regression starts an older transaction, activates v2 on another connection, then reads v2 and activates v3 from the older transaction. It asserts increasing starts, an exact adjoining end/start boundary and one active policy. A separate negative test rejects future-effective active rows. The existing simultaneous-activation test remains. No sleep or test retry hides the race.

This fix changes lifecycle timing only; no risk limit, trading mode, order authorization or LIVE activation is changed. No migration is required. Lifecycle times remain database time; application audit creation timestamps retain their existing transaction-time semantics.
