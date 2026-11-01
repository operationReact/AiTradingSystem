# PAPER automation idempotency

Idempotency is required across opportunity evaluation, intent acceptance, simulated order/fill creation and position exit.

Production integration requirements:

- stable key includes authenticated account plus source opportunity/manual request identity;
- retrying an accepted intent cannot create another order/fill;
- retrying market monitoring cannot close a position twice;
- restart/recovery rereads persisted idempotency/order/position state;
- rejected risk decisions do not reserve a permanent accepted key unless existing policy explicitly requires it;
- concurrent workers use database/transaction constraints or equivalent authoritative serialization, not process-local sets.

The branch's in-process set/map behavior demonstrates semantics only and must be replaced/delegated for production.
