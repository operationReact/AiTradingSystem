# PAPER automation account isolation

Every preference, risk decision, order/fill, position, capital balance, P&L and history record must resolve to the authenticated account on the server.

Required properties:

- one account enabling Automated mode does not affect another account;
- idempotency scope includes account identity when integrated with persisted production services;
- portfolio/P&L/history queries cannot cross account boundaries;
- emergency stop and risk limits are evaluated for the owning account;
- AI opportunity eligibility may be global research input, but execution decisions are account-scoped;
- the client cannot select another account by supplying an arbitrary account identifier.

Existing Phase 10 authorization/session controls remain mandatory and must be reused.
