# STR-176 / STR-177 public-source OOS research evidence (2026-11-26)

**Research-only. Not certification evidence.** Everything here is derived from
*retrospectively downloaded* public NSE UDiFF F&O bhavcopy archives. It is kept
strictly separate from the owner-gated protected-host capture path
(`../str176_forward_nse_capture.py`), which privately retains original bytes and
is the only source that can contribute to independent certification.
Retrospectively downloaded public data **does not** satisfy the
`first-observed <= decision-time` provenance gate in issue #1658, so none of it
changes the independent certification count (**0/43**).

Scope rules honored: unchanged strategy rules and thresholds, no fabricated
fills/receipts, no certification-counter change, no orders/LIVE, `main`
untouched.

## 1. Why STR-177 returned NO_SIGNAL / FUT_MR_RECOVERY_NOT_CONFIRMED (host run #35)

Protected host run [#35](https://github.com/operationReact/AiTradingSystem/actions/runs/45875678327)
(post-fix, on merged `dev`) reported 10 sessions, `outlier: null`,
`outcome: NO_SIGNAL`, `reason: FUT_MR_RECOVERY_NOT_CONFIRMED`.

Reproduced independently from the public bhavcopies for the same ten sessions,
run through the **real compiled evaluator** — identical verdict
`NO_SIGNAL NONE FUT_MR_RECOVERY_NOT_CONFIRMED`. The arithmetic (via
`str177_recovery_trace.py`):

| quantity | value |
| --- | --- |
| baseline mean (12–23 Nov, 8 closes) | 25545.4250 |
| baseline population std | 179.6241 (70.32 bps, floor 25) |
| 9th session (25 Nov) close | 25937.00 → z = **+2.1800** (HIGH extreme, floor 2.00) |
| 10th session (26 Nov) close | **26029.40** |
| partial-recovery test (high) | needs `latest < extreme AND latest > mean`; `26029.40 < 25937.00` is **False** → recovery **not** confirmed |

**Verdict: correct behavior, not a defect.** STR-177 requires a *partial*
reversion (10th close moves back toward the mean but stays on the excursion
side). Here the 10th session pushed **further above** the 9th-session high
extreme, i.e. the move continued rather than reverting — there is genuinely no
mean-reversion to flag. This matches the strategy spec
(`docs/strategies/STR-177-futures-mean-reversion.md`, step 3) exactly. Checked:
session ordering, weekday sequence, units (OHLC in rupees; volume×lot; OI in
contracts), expiry handling (Dec-2026, 7–120 days out), and no look-ahead
(each session's observation ≥ close, ≤ asOf).

## 2. Per-session OI reconciliation (public NSE ChngInOpnIntrst)

Contract FinInstrmId `58875`, NIFTY fut expiry 2026-12-29, lot 65. All nine
computable day-over-day OI deltas equal NSE's independently published
`ChngInOpnIntrst` **exactly**:

| date | close OI | prev OI | computed Δ | NSE ChngInOpnIntrst | match | Δ% |
| --- | ---: | ---: | ---: | ---: | :---: | ---: |
| 2026-11-12 | 1731665 | — | — | 184925 | n/a (first) | |
| 2026-11-13 | 1761435 | 1731665 | 29770 | 29770 | ✓ | 1.7% |
| 2026-11-16 | 1761435 | 1761435 | 0 | 0 | ✓ | 0.0% |
| 2026-11-17 | 2248220 | 1761435 | 486785 | 486785 | ✓ | 27.6% |
| 2026-11-18 | 3183505 | 2248220 | 935285 | 935285 | ✓ | 41.6% |
| 2026-11-19 | 5078060 | 3183505 | 1894555 | 1894555 | ✓ | **59.5%** |
| 2026-11-20 | 7682610 | 5078060 | 2604550 | 2604550 | ✓ | **51.3%** |
| 2026-11-23 | 11790610 | 7682610 | 4108000 | 4108000 | ✓ | **53.5%** |
| 2026-11-25 | 12010310 | 11790610 | 219700 | 219700 | ✓ | 1.9% |
| 2026-11-26 | 12500280 | 12010310 | 489970 | 489970 | ✓ | 4.1% |

Three steps exceed the 50% (`maxOiChangePerStepBps = 5000`) guard — all three
reconcile exactly, so the PR #2065 fix correctly **admits** them (authentic
expiry-week position build into the Dec contract) instead of blocking. The old
code blocked at the first such step (18→19 Nov). Source provenance: each row's
archive SHA-256 is in §4; nothing from the private host archive is exposed —
these are the public bhavcopy values.

## 3. Broad out-of-sample research replay (14 expiry cohorts, 221 trading days)

Machine-readable results: `str176_str177_public_oos_results.json`. Dataset:
NIFTY index futures from public NSE UDiFF bhavcopies, expiries 2026-01-27 …
2027-02-23.

### STR-177 (unchanged evaluator, 544 windows)

| outcome | reason | windows |
| --- | --- | ---: |
| NO_SIGNAL | FUT_MR_EXTREME_NOT_CONFIRMED | 203 |
| NO_SIGNAL | FUT_MR_VOLUME_OR_OI_LOW | 111 |
| BLOCKED_DATA | FUT_MR_OI_CHANGE_CONTRADICTS_EXCHANGE | 77 |
| BLOCKED_DATA | FUT_MR_CONTRACT_INVALID | 63 |
| NO_SIGNAL | FUT_MR_RECOVERY_NOT_CONFIRMED | 44 |
| NO_SIGNAL | FUT_MR_REVERSAL_OR_COST_HURDLE_NOT_MET | 31 |
| RESEARCH_CANDIDATE | FUT_MR_LOW_EXTREME_REBOUND_OBSERVED | **15** |

The unchanged strategy **does** fire: 15 LOW_EXTREME_REBOUND research candidates,
clustered in the Feb–Mar 2026 market dip (z from −2.03 to −3.80). These are
**non-executable price-shape observations** (`executable=false`); the strategy
defines no entry/exit/stop, so no after-cost *trade* P&L is derivable without
inventing un-spec'd rules — which is deliberately not done.

Data-quality note: the 77 `FUT_MR_OI_CHANGE_CONTRADICTS_EXCHANGE` blocks trace
to 11 of 649 consecutive-session pairs (1.7%) where the reconstructed OI delta
did not equal NSE's reported `ChngInOpnIntrst` (one bad pair taints several
overlapping windows). None involves a missing trading day (no gap > weekend; no
NSE Saturday special session in range). The guard **correctly fails closed** on
these; it never fires on the production path (the retained host archives
reconcile 100%, §2). Relaxing it would require an authoritative NSE OI
reconciliation spec and is not done here (it would be unjustified tuning, and
the strict check only ever fails *safe*).

### STR-176 (unchanged reference signal + documented fill/cost model)

Fill model (documented, from `str176_expanded_upstox_research.evaluate`): enter
at the next session's open after the decision, exit at the next-next session's
open; `cost = notional × bps`; evaluated at 20/30/50 bps. Non-overlapping
candidate trades: **8** (6 LONG_BREAKOUT, 5 SHORT_BREAKOUT signals, 3 overlaps
skipped).

| decision | dir | entry | exit | gross (paise) | net@20 | net@30 | net@50 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2026-03-16 (Mar/Apr/May exp ×3) | SHORT | ~23235–23475 | ~23520–23757 | −1.76M…−1.85M each | loss | loss | loss |
| 2026-05-13 (Jun/Jul exp ×2) | SHORT | ~23528–23600 | ~23625–23750 | −0.63M / −0.97M | loss | loss | loss |
| 2026-11-06 (Nov/Dec/Jan exp ×3) | LONG | ~24450–24715 | ~25059–25273 | +2.65M…+3.96M each | win | win | win |
| **aggregate** | | | | | **+7,190** | **−5,316** | **−30,328** (Rs) |

**No demonstrated edge.** The 8 trades reduce to **3 distinct decision events**
(one short cluster 2026-03-16, one 2026-05-13, one long cluster 2026-11-06),
each duplicated across parallel monthly contracts — not independent samples. The
only positive is the single 2026-11-06 up-move; after realistic costs (≥30 bps)
the aggregate is **negative**. Under a proper train/OOS split (first 8 cohorts
training), the only OOS-period event is that one 2026-11-06 up-move — a single
event is not evidence of out-of-sample profitability. This is consistent with
the prior Upstox 12-cohort study (0 OOS candidates): STR-176 has no shown
after-cost edge.

## 4. Reproduction

Public archives are fetched out-of-band (this repo adds no committed network
path). For each session `YYYYMMDD`:

```
curl -O "https://nsearchives.nseindia.in/content/fo/BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip"
```

Ten-session window SHA-256 (verify downloads):

```
79285823ee47f0e6406e0a69c544071178d2337e6538ae32b0f9b1b94530f9b3  20261112
7455447c19eb138dd6799d701aab4fed7d94f0bf0dbac3a3f14ebe257bdeab2c  20261113
8d05f146bb8d2d03fb47c5bcba0c8e9ede5cd1e3485fef33843f445a8e6f1d0e  20261116
056b8567e71017a5e9b586e20ea0c7c98c4b63ae1a59f82cb6d3cca466dad7da  20261117
ec1a2fdad59b8e9804ee6da81bf7f1879a44c01446e5d10862491668ce34e771  20261118
4bc9834a61dbc0ad73559df1d12aa59cd6193e0ae491eff7e67ab625510e0ce7  20261119
becc894692f4877071bcd9fa9dc1c91b09ea6274e830f8b299e8e4f43dfc4c6b  20261120
103861e74b2103f8226f4f408dd0a25af45bf24412c12b6d7c8ac43605b4d487  20261123
a7b925423e14081586c3ea70f65fcd2fb88c5a6e71c6c179954b7a2e4c524924  20261125
87f43cdbc879a0939bfbbf329fc45e13ae2e836396fca5dfd8d1c9650d9f5988  20261126
```

Then (offline):

```
python3 -I nse_udiff_fut_parser.py <zip> [<zip> ...] > rows.json
python3 -I str177_recovery_trace.py rows.json 2026-12-29
```

The STR-177 broad replay runs the **real** evaluator: compile
`FnoFuturesMeanReversionV1.java` with a thin batch `main` (same source transform
the host launcher uses in `str176_forward_nse_capture.transport`) and feed ten
rows per window on stdin. STR-176 uses the unchanged
`str176_expanded_upstox_research.signal` and the fill/cost formula from that
module's `evaluate`.

## 5. Certification status

Independent historical-E2E certification is unchanged at **0/43**. The post-fix
STR-177 host window is a single research-only NO_SIGNAL; the broad public replay
is research-only (provenance-blocked). The concrete remaining acceptance
artifacts are unchanged: an **independently attested original first-known
(≤ decision) provenance** source and an **independent (non-self) after-cost OOS
replay with auditable fill receipts** (plus PAPER ledger), none of which the
public retrospective path can provide.
