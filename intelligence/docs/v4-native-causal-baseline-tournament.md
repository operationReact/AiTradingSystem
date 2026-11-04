# V4-04 — Native causal baseline and four-model tournament research

`india-native-causal-baseline-evidence.v1` and
`india-native-causal-baseline-tournament.v1` provide the missing
**causal moving-average baseline** to complement native bounded
patch-transformer/TimeMixer-style/TFT-style evidence in PRs #368–#369.

## Baseline-specific semantics, deliberately preserved

Unlike the neural models' uncertainty/edge decision, the original
`india-causal-forecast-baseline.v1` (PR #361) qualifies a hypothetical
long-only opportunity if

```
predicted_next_return - calibrated_error_quantile >=
  (assumed_round_trip_fees_bps + minimum_edge_bps
   + optional_extra_lower_bound_edge_bps) / 10000
```

This is **inclusive greater-than-or-equal-to**, not the neural helper's
strict greater-than. The evidence builder does not silently change the
baseline trading rule or its originally reported trade count and
hypothetical after-cost returns.

Its immutable native record contains:

- Original fully validated causal baseline report plus configuration
- Exact source/fees/target/holdout membership shared with PR #366
- Every earlier training-only calibration row, decision/label timestamps
  and deterministic signed-return forecast
- Every heldout source-row/decision/forecast/realized return
- Each hypothetical net result, including **zero for abstention**
- Validation of the original finite-sample calibration residual
  quantile, the INCLUSIVE trade rule, the original baseline trade count,
  after-assumed-cost sum, and the immutable evidence fingerprint

No neural pretrained weights or licensed checkpoint are involved. The
baseline is a deterministic algorithm, not a trained weight file.

## Tournament compatibility

`build_native_causal_baseline_tournament_submission(evidence)` invokes
the existing PR #367 train-only signed-return-to-directional-probability
bridge with the **original baseline-report fingerprint** as source-result
identity, not an invented run identifier. The wrapper replays the
directional submission using its stored native baseline evidence.

The pinned CPU PyTorch CI workflow trains the three bounded neural
challengers, builds their full PR #369 native directional submissions,
and calls PR #349's existing `screen_structured_alpha_tournament`
using this baseline plus all three challengers on the **same**
synthetic causal cohort and assumed Indian costs. This checks the
research program path, not real trading performance or statistical
significance on actual markets.

Run baseline evidence with:

```bash
cd intelligence
python -m aitrading_intelligence.native_causal_baseline \
  earlier-train-export.json disjoint-holdout-export.json india-fee-book.json \
  --cost-bps 25 --horizon-bars 1
```

## Still blocked

The model-family tournament is NOT yet bound to a legitimate
**prospectively frozen, independently timestamped** PR #359 NSE/BSE
holdout and the PR #360 all-attempts trial budget with third-party
source/fee custody. The published statistical threshold is only
defined for the self-declared family. The native records are program
evidence from offline synthetic test fixtures; they cannot prove that
the real market was traded or any model earned profits.

`independent_market_authenticity_verified=false`,
`externally_timestamped_registration_verified=false`,
`eligible_for_paper_or_live=false` and
`can_authorize_capital=false` are immutable. No production/broker
deployments, PAPER/SHADOW enrollment, LIVE exposure, or champion
promotion are initiated by this module.
