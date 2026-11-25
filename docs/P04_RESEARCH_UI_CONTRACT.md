# P04 Research UI Contract

This document freezes the Phase 4 research presentation contract. Visual implementation is deferred to Phase 9.

## CandidateResearchDrawer

`CandidateResearchDrawer` is a research-evidence surface. It is never an order ticket and must always display the exact label:

> Research only — not an order

The drawer consumes the persisted candidate-evaluation and scenario APIs introduced in P04-W05:

- `GET /api/research/candidates/{candidateId}/evaluations`
- `GET /api/research/simulations/{scenarioRunId}`

The visual implementation must not infer or recompute persisted probabilities, R metrics, regime, methodology, fallback tier, or provenance.

## Required sections

### ScenarioProbabilityChart

Present the persisted mutually exclusive scenario outcomes:

- target-first probability;
- stop-first probability;
- neither probability.

The chart is historical simulation evidence, not a forecast guarantee. Percentages must retain accessible text values and cannot be communicated only by color.

### RDistributionSummary

Present:

- expected R;
- P05 R;
- P50 R;
- P95 R.

Historical R-distribution evidence must remain visually separate from live account P&L, buying power, or broker balance.

### RegimeChips

Present persisted point-in-time research context:

- `regime` — the actual `MarketRegime` used at the evaluation cutoff (`LOW_VOL`, `NORMAL_VOL`, or `HIGH_VOL`);
- `fallbackTier` — the block-sampling compatibility tier (`EXACT_VOLATILITY_AND_VOLUME`, `SAME_VOLATILITY`, or `ALL_ELIGIBLE`).

`AUTO_CLASSIFIED` is not an acceptable display value. Phase 4 persists the resolved market regime before the Phase 9 visual implementation consumes it.

### MethodologyProvenanceDrawer

Expose persisted evidence sufficient to understand and reproduce the scenario:

- methodology;
- strategy and strategy-config version;
- scan-run and candidate IDs;
- request fingerprint;
- history-from/history-to and data cutoff;
- simulation seed/count;
- horizon and block size;
- historical sample count and eligible block count;
- lifecycle status and bounded failure evidence when applicable.

Do not reconstruct provenance from current/live data.

## Evaluation state

The drawer may show only the research decision labels persisted by the backend:

- `FAVORABLE`;
- `MIXED`;
- `UNFAVORABLE`;
- `INSUFFICIENT_DATA`.

These labels are research summaries. They are not order approval, execution authorization, or trading advice. Reason codes remain visible as evidence.

## Forbidden actions

The Phase 4 contract does not contain `placeOrder`, `submitOrder`, `approveOrder`, `executeTrade`, or any equivalent action. Any later handoff from research to an order intent must go through the separately authorized risk/execution phases and server-side policy gates.

## Phase 9 visual handoff

Phase 9 must implement the above contract using the canonical P4 Figma research workspace while preserving the existing Neon Futuristic AI and Clean Modern design systems, responsive desktop/mobile behavior, accessible text alternatives, explicit loading/error/insufficient-data states, and the research-only safety label.
