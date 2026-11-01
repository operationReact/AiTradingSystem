# Trading safety boundary

This package owns customer trading/execution mode semantics and the shared PAPER risk decision boundary.

Rules:

- Manual and automated PAPER intents use `PaperRiskDecisionService`.
- `TradingSafetyPolicy` is the central release guard and currently denies every LIVE broker submission.
- UI preferences or client requests must never be treated as authority to enable LIVE submission.
- Risk rejections return customer-readable messages; raw internal codes belong in observability, not primary customer flows.

Next integration step: persist account-scoped mode preferences and route the existing PAPER order-intent service through this boundary before adding automated execution.
