# Phase 8 completion contract

P08-GATE01 verifies the six Phase 8 implementation allocations together. The dedicated PostgreSQL operator journey uses real controllers, security, migrations, transactions, PAPER risk/approval/settlement and materialization; only time is controlled. It owns a fresh container so historical fixtures cannot silently change its arithmetic.

| Exit evidence | Assertion |
| --- | --- |
| PAPER round trip | BUY and SELL each wait for manual approval, then persist exactly two fills after real revalidation. |
| Accounting | 100000 opening capital; 9.69 net / 10.00 gross gain; 0.10 fees / 0.21 slippage. Rebuild twice preserves a single daily row and the original fills. |
| Supported analytics | Complete daily equity, strategy attribution, one winning exit, zero current exposure, explicitly advisory research. |
| Operational understanding | Reconciliation is CLEAN; health reports its evidence, PAPER mode and no LIVE availability. Unconfigured provider/news/scanner evidence may correctly remain UNKNOWN. |
| Incident response | Emergency stop produces a critical notification. Repeated authenticated acknowledgement creates one audit and leaves the stop active. |
| Auditability | Rebuild actor is authenticated operator; downloadable filtered CSV includes the orders and revalidation events. |

Focused suites additionally cover missing marks, capital flows, ledger/attribution conflicts, concurrent rebuild/deduplication, bounded audit queries, CSV injection and redaction, safe health failures, deferred incident projection and authenticated API contracts. The completion workflow retains Surefire evidence. The baseline workflow also runs the backend suite.

Performance uses last persisted canonical marks before each boundary and records their provenance; it does not claim an exchange closing price. Sampled/daily-close drawdown bases and missing denominators remain explicit. Research summaries are historical and advisory. Health diagnostics never log in to providers or authorize execution. Notification acknowledgement never resumes trading. Audit identity remains the shared authenticated operator until Phase 10 individual users.

Phase 8 exits only after the gate and required regressions pass on the reviewed PR head and the PR is merged. Full dual-experience, four-appearance responsive UI coverage belongs to Phase 9; this API journey does not claim browser coverage.
