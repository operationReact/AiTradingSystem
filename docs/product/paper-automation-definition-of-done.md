# PAPER automation definition of done

The slice is complete only when all conditions hold on one reviewed exact head:

- account-scoped PAPER/LIVE and Manual/Automated state uses the existing persisted account preference infrastructure;
- manual and automated PAPER intents use one authoritative risk/execution path;
- automated eligible AI opportunity can produce an idempotent simulated order/fill, position, unrealized P&L, stop/target/emergency exit, realized P&L and history;
- emergency stop blocks new intents and safely exits/halts applicable PAPER automation;
- Flutter Home/AI/Portfolio/More expose customer-grade controls and lifecycle state using canonical Neon-first/Clean-secondary design;
- no raw backend/operator vocabulary appears in normal customer flows;
- exact-head backend + Flutter tests/analyze are green;
- phone-size visual evidence is accepted against canonical Figma;
- Android APK packaging is green;
- LIVE availability=false and broker submission=false remain proven by tests/configuration;
- no production/cloud deployment is performed as part of acceptance.

Until every item above is evidenced, keep the PR Draft and the Notion phase/checklist IN PROGRESS.
