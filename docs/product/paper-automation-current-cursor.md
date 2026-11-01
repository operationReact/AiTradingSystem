# Current PAPER automation cursor

Branch head after the foundation work should now be treated as an integration checkpoint, not a merge-ready release.

Completed foundation themes:

- mode semantics and fail-closed LIVE boundary;
- shared risk decision;
- account-isolated Automated opt-in;
- AI opportunity eligibility;
- idempotent simulated fill;
- position/P&L/capital/history lifecycle;
- emergency stop behavior;
- customer-safe state/copy/API/UI/visual contracts;
- acceptance, security, CI and definition-of-done contracts.

Highest-priority unfinished work:

1. Inspect and adapt the current authoritative persisted Phase 10 account-preference/session code.
2. Inspect and adapt authoritative Phase 7/8/9 risk, emergency stop, PAPER order/fill/position/portfolio/history code.
3. Replace/delegate temporary in-process adapters so there is one production ledger.
4. Implement actual Flutter controls and lifecycle state.
5. Run exact-head gates and visual/APK acceptance.

Keep Draft. Do not merge this checkpoint merely because the new foundation tests are internally consistent.
