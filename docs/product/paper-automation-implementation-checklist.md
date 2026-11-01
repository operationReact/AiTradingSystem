# PAPER automation implementation checklist

Status: IN PROGRESS

- [x] Define account-scoped PAPER/LIVE and MANUAL/AUTOMATED product semantics.
- [x] Keep LIVE broker submission explicitly disabled/fail-closed.
- [x] Define one mandatory server-authoritative risk gate for manual and automated trade intents.
- [x] Define automated PAPER lifecycle and acceptance evidence.
- [ ] Persist account-scoped trading mode and execution mode.
- [ ] Implement shared risk-decision service for every PAPER order-intent path.
- [ ] Implement automated opportunity eligibility and idempotent simulated order creation.
- [ ] Implement simulated fills and position/capital updates.
- [ ] Implement mark-to-market unrealized P&L.
- [ ] Implement stop-loss, target and emergency-stop exits.
- [ ] Implement realized P&L and closed-position/history updates.
- [ ] Add end-to-end backend tests covering accepted/rejected/manual/automated/emergency/idempotent paths.
- [ ] Connect Flutter global controls to account-scoped backend state.
- [ ] Add customer-facing automated lifecycle states without raw backend vocabulary.
- [ ] Add phone-size Neon/Clean visual acceptance evidence.
- [ ] Pass exact-head Flutter analyze/tests and backend PAPER E2E gates.
- [ ] Package Android APK with LIVE submission still disabled.

Next implementation cursor: persist account-scoped trading/execution modes and route manual PAPER order intent through the shared risk decision boundary before adding the automated executor.
