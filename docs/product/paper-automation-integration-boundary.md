# Existing-service integration boundary

The new `in.aitradingsystem.trading` package is a product/safety foundation for the PAPER automation release slice. It must not become an independent second production ledger.

Before merge, integration work should identify and adapt the existing authoritative components for:

- authenticated account/session identity;
- persisted user/account preferences;
- risk policy/emergency stop;
- PAPER order intents/orders/fills;
- positions and portfolio balances;
- P&L/history;
- research/AI opportunities.

Where an authoritative existing service already implements a concern, prefer delegation/refactoring over duplicate persistence. New safety rules must remain testable at a shared boundary used by both manual and automated PAPER execution.

No broker adapter should be invoked by simulated PAPER services. Angel One remains authentication/read-only data integration until a separate LIVE release is explicitly approved.
