# PAPER automation and risk-control release gate

## Purpose

Define the next customer-release slice after PR #206. The product remains PAPER-only. This gate must not enable or submit LIVE broker orders.

## Global customer controls

The customer experience must expose two independent, account-scoped controls:

1. Trading mode: `PAPER` or `LIVE`.
2. Execution mode: `MANUAL` or `AUTOMATED`.

`LIVE` is visible as a future mode but remains unavailable/fail-closed until a separately approved release enables broker submission. Selecting or requesting LIVE must never silently fall back to a real order path.

## Mandatory risk engine

Every trade path must pass the same server-authoritative risk evaluation before an order intent can be accepted. This applies to manual PAPER orders and automated PAPER opportunities.

Minimum checks:

- account/session authorization;
- emergency-stop state;
- allowed trading mode;
- available PAPER capital;
- per-trade risk budget;
- quantity and price validity;
- stop-loss presence and validity;
- target/risk-reward validity;
- duplicate/idempotent intent protection;
- maximum open-position/exposure limits;
- customer-readable rejection reason without internal/raw status codes.

Client-side validation is supplemental only and must not bypass server risk decisions.

## Automated PAPER lifecycle

Automated PAPER mode must provide a deterministic, auditable lifecycle:

`AI/research opportunity -> eligibility -> risk evaluation -> simulated order -> simulated fill -> open position -> mark-to-market P&L -> stop/target/exit evaluation -> closed position -> portfolio/history`

Required behavior:

- automation only acts on eligible research opportunities;
- risk rejection creates no order/fill/position;
- accepted intents create idempotent simulated orders;
- fills update positions and available PAPER capital consistently;
- mark-to-market updates unrealized P&L;
- stop-loss, target and explicit emergency stop can close positions;
- closed positions update realized P&L and history;
- emergency stop blocks new automated/manual intents and prevents new simulated fills;
- restarting/retrying processing must not duplicate an order, fill, or position.

## Customer UI

Primary mobile surfaces remain Home, Markets, AI/Research, Portfolio and More using Neon Futuristic AI as the default design and Clean Modern as secondary.

The normal customer UI should show understandable states such as PAPER, Manual/Automated, Risk protected, Paused, Filled, Open, Closed, Profit/Loss. Do not expose internal enum names, reconciliation vocabulary, audit plumbing, raw provider errors, access keys, or backend status codes.

## Acceptance evidence

This slice is not complete on CI green alone. Require:

- exact-head backend tests for manual and automated PAPER risk enforcement;
- end-to-end automated PAPER lifecycle tests through realized P&L/history;
- emergency-stop and idempotency tests;
- Flutter analyze/tests;
- customer-language tests for new controls/states;
- phone-size visual evidence for PAPER + Manual/Automated controls and automated lifecycle state;
- Android APK packaging;
- LIVE broker submission remains disabled in code/config/tests.

## Out of scope for this slice

- enabling LIVE broker order submission;
- production/cloud deployment;
- DNS, secrets, signing or store mutations;
- bypassing Secure Sign In/account scoping;
- weakening existing safety/release gates.
