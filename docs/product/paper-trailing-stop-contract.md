# V2-PAPER-04 — Trailing-stop product contract

## Purpose

Define the customer and execution semantics for an optional trailing stop before any backend persistence or automated execution is added.

This story is intentionally UI/contract-first. The Flutter control is a preview and **must not be represented as active protection** until V2-PAPER-05 persists the state and connects it to the existing PAPER position monitor.

## Customer contract

- Trailing stop is **optional and OFF by default**.
- The customer configures a **percentage trail**.
- Supported contract range: **0.5% to 20.0%**.
- The UI may prefill `2.0%` as an editable example, but the user must explicitly enable trailing protection.
- The initial stop loss remains mandatory for a PAPER BUY under the existing order contract.
- A trailing stop never widens risk. It may only tighten the effective protective stop as the position makes a new high.
- Profit target, INTRADAY/time exit and other Risk Engine exits remain independent. The first eligible exit continues through the normal idempotent PAPER order/risk/accounting path.

## PAPER-05 execution semantics

For a long PAPER position after fill:

1. Initialize `highWaterMark` from the authoritative simulated fill/position price.
2. Accept only fresh authoritative marks under the existing market-data freshness policy.
3. Update `highWaterMark = max(previousHighWaterMark, freshMark)`.
4. Compute `trailingThreshold = highWaterMark * (1 - trailingPercent / 100)`.
5. Compute `effectiveProtectiveStop = max(initialStopLoss, trailingThreshold)`.
6. The effective stop can remain unchanged or move upward; it can never move downward.
7. If a fresh mark is at or below the effective stop, submit the full protective SELL through the existing `PaperTradingService` / `PaperOrderService` path.
8. Preserve deterministic idempotency so repeated scheduler runs cannot create duplicate exits.

Example: entry/fill ₹100, initial stop ₹95, trailing 2%. Initial trailing threshold is ₹98, so effective stop is ₹98. If the high-water mark rises to ₹110, the trailing threshold becomes ₹107.80. If price later falls, the effective stop remains ₹107.80; it does not move back down.

## Persisted state required in V2-PAPER-05

Persist against the existing PAPER position/account model rather than creating a parallel simulator:

- `trailingStopEnabled`
- `trailingPercent`
- `highWaterMark`
- `highWaterAt`
- `effectiveProtectiveStop`
- row/version information required for deterministic concurrency and idempotency

Exact storage shape may follow the existing migration/repository conventions, but there must be one authoritative position state.

## Safety and failure behavior

- Stale, missing or future-dated marks do not update the high-water mark and do not trigger a trailing exit.
- Emergency Stop semantics remain authoritative and may not be bypassed by trailing-stop monitoring.
- Risk Engine, execution policy, readiness revalidation, fills, accounting, P&L and audit remain the existing shared services.
- No direct `PaperExecutionEngine` call is allowed from trailing supervision.
- No LIVE broker order or LIVE activation is introduced.
- If persisted trailing state is incomplete or invalid, fail closed and surface customer-safe status rather than inventing a default.

## V2-PAPER-04 Flutter acceptance

The PAPER trade ticket must:

- show an optional `Trailing stop` control within protective exits;
- explain that it follows the highest PAPER price after fill and only tightens protection;
- show an editable percentage only when the control is enabled;
- communicate the 0.5%–20.0% contract;
- clearly label the control as a preview/not active until PAPER-05;
- prevent submission from implying trailing protection while the backend is not connected;
- preserve normal PAPER order submission when trailing stop remains off;
- remain usable at phone width and expose no LIVE execution control.

## Definition of done

V2-PAPER-04 is complete when the product contract and Flutter preview are exact-head green. Backend persistence/high-water monitoring/automated trailing exit belongs to V2-PAPER-05 and is not silently folded into this story.
