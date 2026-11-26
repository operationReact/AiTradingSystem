# STR176 / STR177 independent-certification status (2026-11-26)

Tracker: issue #1658 ("F&O 43: independently verify market replay, PAPER ledger
and LIVE broker E2E before readiness"). This note records the *truthful* state of
STR176 and STR177 after the STR177 open-interest-change repair. It does **not**
claim certification. Independent historical-E2E certification remains **0/43**.

## Scope and rules honored

- No thresholds, expiry guards, timestamps, trades, fills, or certification
  counters were loosened, invented, or set manually.
- Exchange values and source provenance are preserved exactly as received.
- Backend risk/authorization/source-identity/expiry/idempotency logic is
  unchanged. The STR177 evaluator remains research-only and non-executable.
- No orders, no LIVE activation, no rights changes, no data export.

## STR177 — ten-session NIFTY futures mean-reversion

### Blocker (confirmed from real host data)

The evaluator rejected the only eligible window with
`BLOCKED_DATA / FUT_MR_DAILY_CHANGE_OUTLIER` because open interest changed by
more than the 50% (`maxOiChangePerStepBps = 5000`) per-step guard between the
18 Nov and 19 Nov sessions.

Real protected-host receipts (AWS OIDC/SSM, instance `i-09c01b470fb39b85a`,
no orders), identical across two independent runs:

- Run 45866314157 (commit `a792aa4`, 2026-11-26) and
  run 45870238716 (commit `ab3b33d`, dev HEAD, 2026-11-26):
  ```json
  {"state":"RETAINED_ARCHIVE_SIGNAL_RESEARCH_ONLY","sessions":10,
   "decisionDate":"2026-11-27","expiry":"2026-12-29",
   "outcome":"BLOCKED_DATA","pattern":"NONE","reason":"FUT_MR_DAILY_CHANGE_OUTLIER",
   "outlier":{"previous":"2026-11-18","current":"2026-11-19",
              "fields":["OPEN_INTEREST"],"exchangeDeltaMatches":true},
   "modifiedHeaders":10,"modifiedBeforeNextDecision":10,
   "certifiedStrategies":0,"orders":false}
  ```

`exchangeDeltaMatches: true` means the computed day-over-day OI delta
(OI₁₉ − OI₁₈) equals, to the unit, NSE's independently published
`ChngInOpnIntrst` for 19 Nov. The jump is **authentic exchange data** (expiry-week
position build / rollover), not a data defect. The guard was conflating a real
market event with corruption.

### Repair (documented, defensible rule)

`FnoFuturesMeanReversionV1`:

- `CompletedSession` gains `exchangeReportedOiChangeUnits` (signed; the NSE UDiFF
  `ChngInOpnIntrst`, same units as `openInterestUnits`; `null` when the source
  publishes no independent change field). A backward-compatible 13-argument
  constructor delegates with `null`, so every existing caller (production V102
  replay, research service, dispatch) behaves exactly as before.
- The per-step guard is split:
  1. **Close move** over `maxSingleSessionCloseMoveBps` → `FUT_MR_DAILY_CHANGE_OUTLIER`
     (price-data integrity, unchanged).
  2. **Exchange-reported OI change present but ≠ computed delta** →
     `FUT_MR_OI_CHANGE_CONTRADICTS_EXCHANGE` (the OI series is internally
     inconsistent, i.e. corrupt — rejected at any magnitude).
  3. **OI move over `maxOiChangePerStepBps`** → `FUT_MR_DAILY_CHANGE_OUTLIER`
     **only when it is not reconciled** by an exact exchange-reported change.
     A reconciled large move is authentic and is used unchanged.

The magnitude threshold is **not** lowered. Only an independently
exchange-reconciled change is admitted; everything else still fails closed.

### Eligible windows searched

The retained history is exactly ten NSE F&O UDiFF archives: 12, 13, 16, 17, 18,
19, 20, 23, 25, 26 Nov 2026 (24 Nov is an NSE F&O holiday; 21–22 Nov is a
weekend). The evaluator requires exactly ten consecutive verified exchange
sessions and does not treat overlapping windows as independent. Therefore there
is **exactly one** eligible ten-session window, and it is the window the host run
evaluates (decision date 2026-11-27, Dec-2026 expiry). There are no additional
non-overlapping ten-session windows to run.

### Local end-to-end validation (real evaluator + real launcher)

Compiling the exact launcher that `str176_forward_nse_capture.transport(True,True)`
ships, against the updated evaluator, and feeding a synthetic ten-session window
that reproduces the 18→19 Nov OI jump:

| Scenario | Exchange-reported change | Evaluator result |
| --- | --- | --- |
| Reconciled large OI jump (+60000 = +6000 bps) | matches computed delta | `RESEARCH_CANDIDATE LOW_EXTREME_REBOUND FUT_MR_LOW_EXTREME_REBOUND_OBSERVED` — guard satisfied |
| Same jump, exchange reports 0 (contradiction) | mismatch | `BLOCKED_DATA FUT_MR_OI_CHANGE_CONTRADICTS_EXCHANGE` |
| Large jump, no exchange field (13-arg ctor, null) | absent | `BLOCKED_DATA FUT_MR_DAILY_CHANGE_OUTLIER` (unchanged behavior) |

JUnit: `FnoFuturesMeanReversionV1Test` 28/28 pass (6 new reconciliation tests).
Consumer suites `Fno177VerifiedHistoricalReplayServiceTest`,
`FnoFuturesResearchServiceTest`, `Fno43HistoricalReplayDispatchV1Test` 71/71 pass.
Python offline acceptance `test_str176_forward_nse_capture.py` 8/8 pass (new test
compiles the str177 launcher jar against the real evaluator).

### What the fix does and does not establish

After the fix, the single eligible window will pass the OI-integrity gate on the
host and the **unchanged** mean-reversion logic will produce a genuine verdict
(RESEARCH_CANDIDATE, NO_SIGNAL, or a later block) — that outcome is not forced in
either direction. Even a RESEARCH_CANDIDATE here is **research-only**: provider is
`NSE_RETAINED_EOD`, the decision is non-executable, there are no fills, no broker
ledger, and no independent OOS acceptance. It does **not** meet the issue #1658
certification gate on its own.

## STR176 — seven-session NIFTY futures breakout

- The unchanged STR176 signal was evaluated on genuine Upstox expired-futures
  history across 12 reconciled cohorts / 726 bars / 537 of 581 evaluated windows
  (run 45414003119, 2026-11-24). The 166 OOS windows produced **zero** candidates:
  16 EXPIRY_GUARD, 45 BREAKOUT_NOT_CONFIRMED, 44 BOTH_VOLUME_LOW,
  27 VOLUME_LIFT_LOW, 34 VOLUME_OI_LOW. Five *training* trades modelled
  −7,785,069 paise at 30 bps; training trades do not count as OOS certification.
- After-cost scenarios at 20 / 30 / 50 bps all yield **zero OOS net and zero OOS
  drawdown because there are zero OOS trades** — not a profitability result.
- The retained NSE archive ingestion into STR176 research (`retained_research`,
  7-session breakout over the retained window) already exists and is sound, but a
  forward research signal is explicitly excluded from certification by #1658.
- STR176 is blocked by the *absence of a genuine out-of-sample trade* under the
  unchanged signal, not by missing code or data volume (12 monthly cohorts is the
  practical ceiling of available expired NIFTY futures). Thresholds must not be
  tuned to manufacture an OOS signal, so STR176 cannot advance to 1/43 from
  current licensed sources.

## Remaining, concrete external dependencies for certification

| Strategy | Most advanced truthful state | Exact missing artifact |
| --- | --- | --- |
| STR177 | Evaluator correctly handles the real exchange-reconciled OI change; one eligible 10-session research window ready to replay | Independently attested original first-publication provenance; an independent (non-self) OOS replay with after-cost fills and auditable receipts; PAPER ledger lifecycle. The archive path is research-only. |
| STR176 | Full genuine signal replay over 12 cohorts; zero OOS candidates | A genuine OOS trade under the unchanged signal (none exists in available history) plus after-cost acceptance and independent receipts. Not reachable without new data or threshold changes, which are prohibited. |

**Independent historical-E2E certification is unchanged at 0/43.** Target 2/43 is
not reached and is not claimed.

## Owner-gated host re-run (to capture the post-fix STR177 receipt)

The AWS `exact-host-capture` job runs only on a push to `dev` whose single
changed file is `infra/ec2-paper/requests/str176-forward-nse-capture.json`, within
the capture window (2026-11-26 18:30 → 2026-11-28 06:30 IST). To capture the
post-fix receipt: (1) merge this PR into `dev` so the host receives the updated
evaluator + launcher; (2) push a dev commit touching only that request file (the
`str177_decision_date: 2026-11-27` request). The receipt will then show the
window's true post-fix outcome instead of `FUT_MR_DAILY_CHANGE_OUTLIER`. This
session cannot push to `dev` or invoke AWS directly, so that step is owner-gated.
