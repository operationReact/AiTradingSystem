# V4-04: Chronos-2 source, checkpoint licence and bounded research integration

**Reviewed:** 2026-11-04. **Status:** published open-source license checked;
checkpoint execution, artifact revision verification, full dependency review,
commercial release obligations and independent Indian market-data rights remain
**UNVERIFIED**. This is not legal advice or production approval.

## Evidence: official first-party sources

- Amazon Chronos-2 Hugging Face model checkpoint:
  https://huggingface.co/amazon/chronos-2 — model card declares **Apache-2.0**
  for the `amazon/chronos-2` checkpoint.
- Amazon Science official inference repository:
  https://github.com/amazon-science/chronos-forecasting — `LICENSE` is
  Apache License 2.0; repository `NOTICE` should be retained where applicable.
- License text: https://github.com/amazon-science/chronos-forecasting/blob/main/LICENSE
- The official usage examples document
  `Chronos2Pipeline.from_pretrained(...)` and
  `predict_df(..., prediction_length, quantile_levels)`:
  https://github.com/amazon-science/chronos-forecasting/blob/main/README.md
- The same checkpoint is approximately **120 million parameters**. For CPU
  experiments cap the number of prediction calls and do not assume that one
  run is suitable for low-latency trading.

Apache-2.0 normally permits commercial use subject to obligations including
license copies, attribution/NOTICE retention and modification notices, as
applicable. This only covers material actually offered under that license;
it does **not** grant rights in broker market data, unrelated datasets,
privately licensed checkpoints, API terms or Indian securities activities.
Complete a production dependency/security/legal review before distribution,
hosting or automated trading.

## Opt-in zero-shot NSE/BSE adapter

`india-chronos2-zero-shot-research.v1` implements the documented Chronos-2
`predict_df` interface. It accepts only the literal model ID
`amazon/chronos-2`, requires an explicit 40-character commit SHA revision,
and uses `device_map="cpu", local_files_only=True` so running the CLI
cannot silently fetch different checkpoint weights. The runtime must first
install a separately reviewed `chronos-forecasting` environment and cache
that exact approved revision. No package/model weights are added to the
production intelligence dependency list, Git repository or CI caches.

```bash
# ONLY in a separately reviewed isolated research environment.
cd intelligence
# Prepare dependencies and cache the approved exact checkpoint outside this tool.
# Do not place broker credentials or real production datasets in this environment.
python -m aitrading_intelligence.india_chronos2_zero_shot \
  train-market-history.json holdout-market-history.json india-fee-book.json \
  --cost-bps 25 --checkpoint-revision '<verified 40-character model commit SHA>'
```

The input is a strictly point-in-time series of **already available**
five-minute returns per NSE/BSE cash-equity session, and the adapter predicts
the **next** return only. It refuses 2+ step targets, disconnected market bars,
wrong instrument/provider lineage and understated Indian fees/slippage by
reusing the V4-03 independent holdout and V4-04 causal baseline checks.
Predicted 0.1/0.5/0.9 quantiles must be a single finite ordered next-bar
record. Training-export rows alone calibrate a conservative absolute-residual
interval; the disjoint holdout is used only for evaluation. The baseline
and candidate use **identical** observation fingerprints and assumed costs
to compute paired block-bootstrap after-cost research evidence with
abstention and no direct order authority.

The output is immutable, fingerprinted and always `RESEARCH_COMPARISON_ONLY`
or `INCONCLUSIVE`; it does not declare statistical improvement, approved
weights, data-source authenticity, preregistration, PAPER/SHADOW or LIVE
eligibility. `adapter_invocation_completed=true` only indicates that an
object implementing the Chronos interface was called — **not** that the
authentic Amazon checkpoint was executed. Separate checkpoint supply-chain
attestation and real-model test evidence are required.

Normal CI uses a deliberately **fake pipeline** (no model download,
no actual Chronos-2 inference). Therefore this PR supplies an integration
contract and license record, **not verified execution of the 120M checkpoint**.
Before marking V4-04 Chronos-2 fully done: verify source and weight commit,
record dependency-license/NOTICE obligations, run exact-revision inference
in isolated CPU or GPU research environment on independently authenticated,
prospectively frozen NSE/BSE data, measure latency/memory and compare
economic value against registered baseline and other models. Reject models
that add no net value.

**No actual trading model is automatically promoted and no capital or order
execution can be authorized by this adapter.**
