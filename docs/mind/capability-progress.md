# Mintick Mind capability progress (issue #998)

Scope: Flutter client (`apps/trading_client`) + Spring Boot backend. Validates the
in-app progress tracker and the first PAPER vertical slice only. It does not
validate LIVE trading, the other 41 screens' functionality, or model training.

## Inventory (audited 2026-11-13 against `dev` f11c2a7)

`scripts/mind/build_capability_manifest.py` parses the approved Flutter source
(43 `MintickMindScreens.all` routes, 232 `MindFixtures` strategies in 20
families, 18 `MindFixtures.models`) and writes
`backend/src/main/resources/mind/capability-manifest.v1.json`. An implementation
link is recorded only when its source and test files exist; the script fails
otherwise. `--check` verifies that the committed manifest is current. The Flutter
test `mind_progress_repository_test.dart` fails if the manifest and UI diverge.

| Kind | Denominator | Audit result |
|---|---|---|
| Screens | 43 | 19 IMPLEMENTING (overview, agent-evidence, coverage, room-detail, strategies, strategy-detail, models, model-detail, portfolio, risk, decisions, decision-detail, journal, performance, controls, dependencies, trading-rooms, culprit-catcher, signal-scorecard), 24 MOCK |
| Strategies | 232 | 21 TESTED code links (6 Java evaluators, 6 Java risk gates, 9 Python research), 1 RIGHTS_BLOCKED, 113 DATA_BLOCKED, 97 catalog name only |
| Models | 18 | 12 TESTED Python research code, 3 RIGHTS_BLOCKED, 1 IMPLEMENTING, 2 not implemented; no deployed trained artifact |

The fixture metrics shown on the models screen (AUC 0.81 and similar) are not
measured results.

## Status rules

- The manifest may declare only `NOT_STARTED`, `MOCK`, `IMPLEMENTING`,
  `CODE_COMPLETE`, `TESTED`, `DATA_BLOCKED` or `RIGHTS_BLOCKED`.
- `RUNTIME_VERIFIED` (the only green DONE) requires a `SYSTEM` runtime event in
  `mind_capability_event` recorded by engine code on the **current, known**
  backend revision (`APP_BUILD_REVISION`, set from `RELEASE_SHA` in
  `infra/ec2-paper/paper-compose.yml`) within 7 days. A later `RUNTIME_FAILED`
  or `DATA_BLOCKED` event reverts it. The event table is append-only.
- Customers can add notes or recheck requests. Those never change status, and
  each customer sees only their own.
- The Flutter parser rejects any payload that claims DONE without that proof.
  If the data is unavailable, the UI shows "Not verified", never 0.

## PAPER vertical slice

`POST /api/customer/trading-rooms/{roomId}/mind/paper-cycles` runs
`MindPaperCycleService`:

1. Stored completed candles pass the quality gate (`StrategyService`).
2. `SMA_TREND_VOLUME_V1` produces a candidate.
3. The candle source must be on the permitted list
   (`app.mind.paper.permitted-candle-sources`, default `ANGEL_ONE_SMARTAPI`).
   This is provisional until the #924 rights manifest exists.
4. The candle must be fresh.
5. A verified LTP no more than 15s old is required, plus a 3% divergence guard.
6. Sizing is automatic: 1% risk budget, 20% exposure cap and available cash.
7. The order goes through the existing `CustomerPaperOrderService`, with a
   deterministic order key per signal and side.
8. Positions, cash and P&L are reported, and runtime evidence is recorded.

Exits fire when the trend breaks or the entry stop or target is crossed. Runs
are owner-initiated only, with no scheduler and no retry after an unknown
outcome. Only PAPER rooms are accepted.
