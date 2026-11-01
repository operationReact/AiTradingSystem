# PAPER automation observability

Operational observability may retain technical identifiers internally, but primary customer surfaces must remain readable.

Minimum internal events for the integrated implementation:

- opportunity evaluated;
- risk accepted/rejected;
- simulated order accepted/filled;
- position opened/marked/closed;
- stop/target/emergency exit;
- idempotent duplicate suppressed;
- account automation enabled/disabled.

Do not log broker credentials, access tokens, session credentials or sensitive account data. Do not surface raw event names or internal rejection codes as customer UI copy.

LIVE broker submission is absent from the current PAPER automation event flow.
