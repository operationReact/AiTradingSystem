# Next integration cursor

The safety/lifecycle foundation is now present on the branch. The next code change must integrate it into existing authoritative production services.

Search/inspect the current `dev` lineage for the existing implementations of:

- Phase 10 account/user preferences and authenticated principal/session;
- Phase 7/8 risk policy and emergency stop;
- Phase 8/9 PAPER order intent, order/fill, position, portfolio and history;
- Phase 4 research/scenario opportunity models.

Then refactor/delegate so manual and automated PAPER execution share one persisted risk/execution path. Do not add another database schema or parallel ledger until the existing schema/service boundaries have been inspected.

After persistence integration, wire Flutter global controls and lifecycle UI, then exact-head gates, visual acceptance and APK packaging.
