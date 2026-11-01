# PAPER automation authoritative integration checks

Before replacing any temporary foundation adapter, verify the existing authoritative service and preserve its invariants.

For each concern record: existing class/service/repository, persisted table/model, account-scoping mechanism, idempotency key, transaction boundary, risk/emergency behavior, current tests and new adapter/refactor needed.

Concerns:

- account preference;
- authenticated principal/session;
- risk policy;
- emergency stop;
- PAPER intent/order;
- simulated fill;
- open/closed position;
- capital/portfolio;
- unrealized/realized P&L;
- history;
- AI/research opportunity identity.

Do not create a new Flyway migration merely to mirror state already represented by an existing authoritative table. Schema changes require a demonstrated missing persisted concept.
