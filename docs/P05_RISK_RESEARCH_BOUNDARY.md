# Phase 5 risk / research boundary

Phase 5 establishes a permanent safety boundary: deterministic risk policy is authoritative for risk eligibility. Research evidence is advisory and cannot soften a hard risk rejection.

Scanner, backtest and scenario outputs may be retained as order provenance in later phases, but they must not:

- override `RiskOutcome.HARD_REJECT`;
- convert a hard rejection into `APPROVAL_REQUIRED` or `PASS`;
- replace server-owned cash, equity, exposure, position, market-session, freshness, emergency-stop or active-policy state;
- alter a `RiskRule` configured limit at evaluation time;
- authorize LIVE execution.

The Phase 5 production rules consume only the server-built `RiskContext` and active immutable `RiskPolicySnapshot`. No research service/provider is injected into a hard risk rule. Future approval workflows may act only on explicitly soft approval-policy outcomes; they cannot waive hard risk controls.
