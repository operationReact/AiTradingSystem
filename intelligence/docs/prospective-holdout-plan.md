## V4-03 prospectively frozen India holdout (self-declared research record)

`india-holdout-freeze-plan.v1` records a **prospective** NSE/BSE cash-equity
five-minute holdout window *before* its first candle is observed. The freeze
fingerprints the already captured training export, registered model families,
model/target configuration and point-in-time India fee/tax schedule. It rejects
backdated windows relative to its supplied timestamp and source-generation
metadata, changed inputs, unregistered model families, and a shifted evaluation
window. Its generated JSON is immutable by fingerprint and written once
without overwriting an existing file (mode 0600).

Use an **actually future** holdout; dates below are illustrative and should
be replaced by the next eligible market sessions:

```bash
python -m aitrading_intelligence.india_holdout_freeze freeze \
  train-market-history.json india-fee-book.json locked-plan.json \
  --plan-id india-five-minute-nov26 --from-at 2026-11-05T09:15:00+05:30 \
  --to-at 2026-11-06T15:30:00+05:30 --cost-bps 25

# After the specified holdout is collected and evaluated:
python -m aitrading_intelligence.india_holdout_freeze bind \
  locked-plan.json train-market-history.json holdout-market-history.json \
  india-fee-book.json --cost-bps 25 --report result-holdout.json
```

The `bind` command verifies the frozen training export, exact declared future
window and exchange/instrument/catalogue/provider fields; fee book, target,
model-config fingerprints; and (when supplied) model-result lineage.
Any discrepancy is a hard error. Generate and externally timestamp the plan
**before collecting/evaluating holdout data**. Save the plan digest in an
independent, access-controlled timestamped system to establish stronger
preregistration evidence. This CLI alone cannot prove that its local timestamps
were not backdated or that the data provider, licensing or broker fees are
genuine.

The resulting `india-holdout-plan-binding.v1` *always* states
`SELF_DECLARED_PLAN_CONSISTENT`, never independently preregistered or source
verified. It cannot promote models, validate market profitability, or authorize
PAPER/SHADOW/LIVE trading. Real data-sourcing audits and market-evaluation
results are still required.
