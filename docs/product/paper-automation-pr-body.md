## What this Draft starts

Next customer-release slice after #206: PAPER automation + shared risk controls.

Implemented foundation:
- PAPER/LIVE × Manual/Automated product model;
- PAPER + Manual safe default and explicit Automated account opt-in;
- fail-closed LIVE/broker-submission policy;
- shared Manual/Automated PAPER risk decision;
- AI opportunity eligibility/confidence gate;
- idempotent simulated PAPER fill;
- position, mark-to-market P&L, capital, target/stop/emergency exit and history foundations;
- account isolation and customer-safe state/copy contracts;
- full automated PAPER journey/safety tests.

## Why this remains Draft

The branch foundation uses temporary in-process adapters for several concerns so behavior is executable. Before merge, these must be reconciled with existing persisted Phase 10 account/session/preferences and Phase 7/8/9 risk/PAPER order/fill/position/portfolio/history infrastructure. Actual Flutter Home/AI/Portfolio/More controls and lifecycle UI are also still pending.

## Merge gates

Exact-head backend + Flutter gates, authoritative persistence integration, real Flutter UI, canonical Figma phone evidence, Android APK packaging and required backend-connected/device evidence.

## Safety

PAPER only. LIVE unavailable. Broker submission unavailable. Angel One read-only. No production/cloud deployment.
