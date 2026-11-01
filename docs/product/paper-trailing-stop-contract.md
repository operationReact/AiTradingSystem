# V2-PAPER-04 — Trailing-stop product contract

## Purpose

Define the customer and execution semantics for optional trailing protection before backend persistence or automated execution is added.

This is intentionally UI/contract-first. The Flutter editor exists behind an explicit feature seam and must remain hidden from the production PAPER order flow until V2-PAPER-05 persists the state and the automated monitor can honor it.

## Customer contract

- Trailing protection is optional and OFF by default.
- No trailing percentage is preset or silently chosen for the customer.
- The customer explicitly opts in and enters a percentage trail distance.
- The UI performs only structural validation (`> 0%` and `< 100%`) in PAPER-04. PAPER-05 must obtain any narrower operational limits from the authoritative server-side risk/policy contract rather than hard-code a second client policy.
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

Example: fill ₹100, initial stop ₹95, customer-selected trail 2%. The initial trailing threshold is ₹98, so the effective stop is ₹98. If the high-water mark rises to ₹110, the trailing threshold becomes ₹107.80. If price later falls, the effective stop remains ₹107.80; it does not move back down.

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
- Emergency Stop remains authoritative and may not be bypassed by trailing supervision.
- Risk Engine, execution policy, readiness revalidation, fills, accounting, P&L and audit remain the existing shared services.
- No direct `PaperExecutionEngine` call is allowed from trailing supervision.
- No LIVE broker order or LIVE activation is introduced.
- Invalid or incomplete trailing state fails closed; no server or client layer may invent a percentage.

## V2-PAPER-04 Flutter acceptance

The UI-first editor must:

- present an optional trailing-protection control for PAPER BUY setup;
- explain that it follows the highest PAPER price after fill and can only tighten protection;
- have no preset percentage;
- require explicit opt-in and explicit customer entry;
- clear/disable the trailing choice when the side is not BUY;
- remain behind the production feature seam until PAPER-05 backend support is complete;
- if explicitly enabled in focused acceptance tests before backend support, block order submission rather than silently discard the setting;
- remain usable at phone width and expose no LIVE execution control.

## Definition of done

V2-PAPER-04 is complete when this contract, the reusable Flutter editor, feature seam and phone-width tests are exact-head green. Backend persistence, server policy limits, high-water monitoring and automated trailing exits belong to V2-PAPER-05.
