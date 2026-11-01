# PAPER automation review request

Review this branch as a Draft foundation, not as a merge candidate.

Focus review on:

- whether new safety/risk abstractions can cleanly delegate to existing persisted Phase 7/8/9 services;
- whether account preference semantics fit existing Phase 10 persistence/session model;
- whether any new class accidentally creates a competing production source of truth;
- whether LIVE/broker submission can remain structurally impossible;
- whether tests cover Manual/Automated parity, emergency stop, idempotency, P&L/capital/history and account isolation;
- whether Flutter acceptance contracts match the canonical customer product direction.

Expected review outcome at this checkpoint: identify integration/refactor work, not approval to merge.
