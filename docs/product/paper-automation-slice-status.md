# PAPER automation slice status

Current branch: `product/paper-automation-risk-controls`

Base: `dev` after merged PR #206 (`700d026e9265c2be48d7ecafc1ef3afc0cb20801`).

Implemented in this branch so far:

- PAPER/LIVE and MANUAL/AUTOMATED domain semantics.
- Fail-closed release policy that denies LIVE broker submission.
- Shared manual/automated PAPER risk-decision boundary.
- Emergency-stop, capital, stop-loss, target, risk-budget and reward/risk checks.
- Idempotent manual/AI PAPER intent boundary.
- AI opportunity eligibility/confidence gate.
- Simulated PAPER fill foundation with no broker-adapter dependency.
- PAPER position mark-to-market, stop/target/emergency exit and realized P&L foundation.
- Account-scoped safe defaults (`PAPER + MANUAL`) and Automated opt-in boundary.
- Automated PAPER lifecycle orchestration foundation.
- Customer-safe lifecycle event/copy contract.
- Focused unit/acceptance tests and explicit LIVE safety guards.

Important integration boundary:

The repository already contains mature persisted PAPER execution, portfolio, risk, account preference and acceptance infrastructure from earlier phases. The next step must adapt these new product controls into that existing infrastructure rather than ship the in-process foundation as a parallel production stack.

Still required before merge/release completion:

1. Wire account trading/execution modes into the existing persisted preference store and authenticated account context.
2. Route existing persisted PAPER order creation through the shared risk boundary.
3. Connect automated opportunities to existing persisted orders/fills/positions/history.
4. Connect Flutter global PAPER + Manual/Automated controls and lifecycle states.
5. Run exact-head backend + Flutter gates and fix root causes.
6. Produce phone-size visual evidence and Android APK packaging.

LIVE broker submission remains disabled and is not part of this slice.
