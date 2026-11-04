# V4-05 — Offline Gemini Advisory Independent-Holdout Benchmark

## Purpose

This is a **research-only, deterministic, offline assessment** of previously captured Gemini advisory *direction scores*. It does not call Gemini, download a FinBERT checkpoint, place orders, determine production model eligibility, infer executable trading P&L, or make claims about the Gemini service's true predictive power from synthetic fixtures.

The implementation is in `india_gemini_sentiment_benchmark.py`. Its two authoritative inputs are the *already frozen*:

1. `india-financial-event-corpus.v1` from V4-05 PR #373: separate chronological TRAIN / CALIBRATION / INDEPENDENT_HOLDOUT event annotations. The training-facing `inputs_for()` projection deliberately **does not contain** the human sentiment label or any future market outcome.
2. `india-financial-nlp-causal-corpus.v1` from V4-05 PR #374: Java news / local text-rights / Gemini evidence / NSE-BSE five-minute market integration. It keeps Gemini direction and confidence only when evidence was available by the market decision. Missing or late Gemini evidence has no score.

## Causal alignment

The benchmark additionally validates the exact upstream `IndiaFinancialNlpCorpusConfig` fingerprint and requires `advisory_provider=GEMINI`; a different provider or model configuration cannot silently be reported as Gemini. Supply `--causal-source-config /path/to/source-config.json` when the upstream source policy differs from the standard Gemini default. This binds the declared source configuration, but remains distinct from externally verifying a genuine API call.\n\nThe benchmark uses only independent-holdout annotations. Each source ID must have exactly one matching causal news-market case. The matching cohort must have identical source URL, identical decision and label-availability times, compatible text-capture chronology, and the same forward market return (basis points in #373 versus fractional return in #374). Any mismatch, duplicate or incomplete set **fails closed**. The source builder enforces ordered TRAIN/CALIBRATION/HOLDOUT with earlier partitions' labels available before subsequent decisions.

The sentiment conversion is a **fixed, declared** neutral band, default ±0.20 on the Gemini direction score (−1 to +1). Thresholds are not tuned against future holdout labels; no model is fitted. The pre-existing #373 human sentiment label is used **only after predictions** for comparison. It is never supplied to Gemini as a prompt or prediction feature by this benchmark. The return label is checked only for source alignment, **never** used in the classifier's predicted sentiment.

Missing/late advisories are counted in the **coverage denominator**, but never counted as correct or incorrect sentiment predictions. The report produces ordered negative/neutral/positive confusion counts, holdout coverage, macro-F1 and accuracy when the predeclared minimum sample and per-class supports are met. If evidence is insufficient, status is `INCONCLUSIVE`, and quality values are null instead of extrapolated.

### Usage (offline)

```bash
cd intelligence
python -m aitrading_intelligence.india_gemini_sentiment_benchmark \
  /path/to/frozen-annotated-events.json \
  /path/to/frozen-causal-news-market-report.json \
  --minimum-evaluable 20
```

Run regression tests with:

```bash
cd intelligence
python -m pytest -q tests/test_india_gemini_sentiment_benchmark.py
```

## Interpretation and non-goals

- Synthetic original news text, mock Gemini responses and synthetic NSE/BSE candles test **software contracts**. Even 100% accuracy in such tests **is not** measured real Gemini advisory quality.
- The report deliberately fixes all external verification and capital authority flags to false: provider/API authenticity, independent human annotation, news content licensing, market-price provenance and trading-value evidence remain unverified.
- **Sentiment agreement is not market alpha.** The forward-return field is an alignment control, not an estimate of causal trading uplift, net brokerage profit, backtest Sharpe or hypothetical order execution.
- The #374 case does not carry a complete licensed headline or a separately witnessed market-session label boundary. Source URL, capture time, future label-availability time and return alignment are consistency checks, **not an external source-rights audit or full provider-custody proof**.
- Real acceptance requires licensed/authenticated Indian news, immutable independently witnessed time-stamped market and model evidence, separate human annotation QA, prospectively preregistered evaluation, a measured real Gemini provider call, leakage audit and multiple-trial controls. A further PAPER/SHADOW stage and Java capital-risk gate are prerequisites to any production trading influence.
- FinBERT pretrained weights under noncommercial terms are not included or deployed. Any commercial use requires a distinct approved license or commercially compatible model.

**Safety:** `llm_order_authority_allowed=false` and `can_authorize_capital=false` are schema-enforced in all reports. Java remains sole authority for risk, portfolio and broker execution. Gemini advisory remains optional research evidence, never an instruction to place an order.
