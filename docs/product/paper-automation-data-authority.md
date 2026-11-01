# PAPER automation data authority

For production integration, authoritative customer state must come from the existing persisted backend services, not Flutter memory and not the temporary in-process foundation adapters on this branch.

Authoritative backend state includes:

- authenticated account identity;
- trading/execution preferences;
- emergency-stop/risk policy;
- PAPER orders/fills;
- open/closed positions;
- available/committed capital;
- unrealized/realized P&L;
- trade history;
- eligible AI/research opportunity identity.

Flutter renders and requests changes; it does not become the ledger or risk authority. On reconnect/restart, the client must reread authoritative backend state.
