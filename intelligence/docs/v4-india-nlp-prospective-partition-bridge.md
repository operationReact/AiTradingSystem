# V4-05 — Prospective NLP partition and causal holdout bridge

## Scope / why

V4-05 PR #373 freezes TRAIN/CALIBRATION/INDEPENDENT_HOLDOUT annotated events, and PR #374 emits an all-event Java news/Gemini/market causal report. PR #376 requires its causal input to contain exactly the held-out annotated source IDs. Until now there was no **audited handoff** from an entire all-split news cohort to an exact held-out-only corpus: hand-selecting the "best" held-out events after looking at outcomes would invalidate the experiment.

The `india-nlp-prospective-partition-bridge.v1` contract introduces a self-declared **pre-data split policy** and an exact, all-source join between the existing authoritative reports. It creates a holdout-only, immutable causal slice for the already merged PR #376 sentiment benchmark.

### What it checks

- A frozen plan records TRAIN/CALIBRATION/HOLDOUT decision-time windows, instrument, source configuration hash, minimum sample counts and a **declared** registration time earlier than the start of the first TRAIN window. Windows may have gaps, but cannot overlap or reorder. The plan is content-fingerprinted; plan timestamps are **not externally attested**.
- Every eligible PR #374 source ID must appear exactly once in the PR #373 annotations. Missing, duplicate, extra or cherry-picked events fail closed.
- Every matching case must have exactly the expected source URL, instrument, decision timestamp, label availability, compatible prior text capture/publication time and matching forward return (bps -> fraction).
- The source annotation's already frozen split must match a plan time window. No result labels or Gemini predictions are used to assign a split; PR #373's original earlier-fold label embargo and duplicate headline rules still apply.
- All three split counts must satisfy the declared minimum. Each join row has an immutable lineage fingerprint; the report binds the entire original annotated corpus and entire original causal corpus.
- The generated `holdout_causal_slice` contains only independent-holdout causal cases, and is itself a valid `IndiaFinancialNlpCorpusReport`. Non-holdout source exclusions are still represented by the full parent's fingerprint, but do not inflate the holdout coverage denominator. No training/calibration cases enter the PR #376 Gemini holdout evaluator.

### Operator workflow (offline, no Gemini calls)

First create a plan *before* the TRAIN collection starts, with timezone-aware dates and three disjoint windows:

```python
from datetime import datetime
from zoneinfo import ZoneInfo
from aitrading_intelligence.india_nlp_partition_bridge import (
    NlpPartitionWindow, freeze_nlp_partition_plan,
)

ist = ZoneInfo("Asia/Kolkata")
plan = freeze_nlp_partition_plan(
    registered_at=datetime(2026, 11, 4, 12, tzinfo=ist),
    target_symbol="RELIANCE-EQ",
    source_config_fingerprint="<SHA256 from frozen IndiaFinancialNlpCorpusConfig>",
    windows=[
        NlpPartitionWindow(
            split="TRAIN",
            from_inclusive=datetime(2026, 11, 5, tzinfo=ist),
            to_exclusive=datetime(2026, 11, 10, tzinfo=ist),
        ),
        NlpPartitionWindow(
            split="CALIBRATION",
            from_inclusive=datetime(2026, 11, 11, tzinfo=ist),
            to_exclusive=datetime(2026, 11, 15, tzinfo=ist),
        ),
        NlpPartitionWindow(
            split="INDEPENDENT_HOLDOUT",
            from_inclusive=datetime(2026, 11, 16, tzinfo=ist),
            to_exclusive=datetime(2026, 11, 21, tzinfo=ist),
        ),
    ],
)
```

These dates are a **usage illustration**, not an approved evaluation schedule or proof of source access. Hashing a plan after an event does not establish prospective registration; obtain an independently witnessed timestamp before the first case and verify it separately. No current implementation can assert an externally witnessed plan.

Write the plan JSON to a private file (do not commit data). Then run:

```bash
cd intelligence
python -m aitrading_intelligence.india_nlp_partition_bridge \
  /private/prospective-plan.json \
  /private/frozen-annotated-event-corpus.json \
  /private/full-causal-news-market-corpus.json \
  /private/new-bridge-report.json
```

The CLI refuses to overwrite a file and creates output with owner-only `0600` permissions. It prints only the report fingerprint. The report contains an embedded holdout causal report; to feed PR #376, extract `holdout_causal_slice` to a separate private JSON file and use that report beside the **original complete** PR #373 annotated event corpus.

Regression:

```bash
cd intelligence
python -m pytest -q tests/test_india_nlp_partition_bridge.py
```

## Known limitations and safety

- Inputs are operator-supplied reports and declarations. Content SHA-256 **is not** signature verification, independent custody, publisher permission, or proof of model execution. All external registration, news rights, market source, independent annotation and genuine Gemini evidence flags remain **false**.
- No copyrighted headlines, model credentials or raw licensed article text belong in source control or CI tests. Tests use original synthetic cases only.
- The bridge does not generate human sentiment annotations, purchase/news licensing, run Gemini or establish Indian-market economic uplift.
- PR #374's source report may have the independent data authenticity and rights gates set to false even when its contract passes. We preserve those limitations.
- This is a software and audit foundation only. It cannot promote models, enable PAPER/SHADOW or authorize capital/LIVE. Deterministic Java risk and explicit deployment gates remain independently required.
