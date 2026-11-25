# V4-05 — Causal India financial NLP research corpus

**Contract:** `india-financial-nlp-causal-corpus.v1`.

This module is the **pre-benchmark, offline dataset integrity layer**
for future FinBERT, Gemini advisory and (subject to licensing) FinGPT
comparison. It introduces **no external model API calls, pretrained weights,
new broker access, production data collection, or trading authority**.

## Existing first-party sources reused

- Java `news-events.v1` exposes content SHA-256, publisher/source URL,
  fetched/available time and linked NSE/BSE symbols.
- Java `news-ai-evidence.v1` exposes optional Gemini provider/model,
  evidence-available time, prompt/request/response fingerprints, sentiment,
  directional score and confidence. It is advisory evidence only.
- Existing `market-history.v1` captures five-minute NSE/BSE cash-equity
  candles with source IDs, instrument catalogue version, observed and
  fetched times.
- Existing `forward-return-after-costs.v1` defines the evaluation horizon
  and assumed round-trip brokerage/India fee/slippage cost.

The builder takes these validated exports plus a **separate, locally
supplied set of article texts** carrying a declared research-use rights
reference. No textual article content is embedded in the output report.

## Source rights and text-integrity gate

For any article admitted to evaluation, the locally provided title and
content must match the exported Java `NewsText.hash(title, content)`
SHA-256, using the bounded HTML entity / NFKC / whitespace / lower-case
normalization profile. Some uncommon Unicode lower-casing differences
between Java and Python can cause a *fail-closed hash mismatch*; they
are not silently considered valid. Real source/feed text reuse rights
are not inferred from a URL or a paid market data subscription.

The rights reference is **self-declared**, not a provider license
verification. A missing text record is written to the deterministic
exclusion ledger as `NO_DECLARED_RESEARCH_TEXT_RIGHTS`; wrong hashes,
duplicate article IDs and orphan text records fail closed.

## Point-in-time decision and label rules

For each article linking the studied NSE/BSE cash-equity instrument:

1. Treat Java news **availableAt**, not possibly earlier publishedAt, as
   the earliest usable news time.
2. Treat the licensed local text's **capturedAt** as an additional latency
   boundary; the selected decision is the first candle close with
   `availableAt >= max(news.availableAt, text.capturedAt)`.
3. Attach only the specific configured Gemini provider/model evidence if
   its evidenceAvailableAt is **no later than that decision** and its
   original sourceAvailableAt and linked symbols match Java news.
   Late/missing advisory output is recorded as `MISSING_OR_LATE`, with
   **no leaked sentiment/score/confidence**. Duplicate configured
   analyses are refused rather than cherry-picked.
4. Calculate future forward return using only a later, contiguous
   in-session candle sequence and a fixed 1–6 candle horizon.
   Cases with no future labels, session-crossing labels or missing bars
   are recorded with specific exclusion reasons.
5. Store the future return separately as a **retrospective research
   label**, with a hypothetical buy/hold after-assumed-cost return. This
   is not a model signal, broker fill, paper trading result or realized P&L.

Every accepted case is immutable, contains source/content hashes, rights
reference, timestamps, target symbol and optional causally available
Gemini evidence, and is included in the fingerprinted corpus report.
All cases/exclusions are deterministic by original Java news source ID.

## What remains before FinBERT/Gemini benchmarks

- Independently verify and obtain permission for source **text** and
  genuinely licensed market candles. Hash equality proves content
  consistency, **not** legal permission or third-party source authenticity.
- Prospectively freeze a sufficiently large, India-representative set
  of articles and future market outcomes, not backfit based on model
  results. Avoid duplicated articles and overlapping event windows.
- Benchmark a separately reviewed, locally pinned FinBERT checkpoint
  against the Java Gemini advisory baseline **on the same predeclared
  news and decision rows** with measured availability/latency.
- Report direction calibration, sample sufficiency, provider cost,
  after-assumed-cost incremental decision utility and uncertainty;
  reject models adding no value.
- Apply the registered-trial / multiple-comparison protections from V4-03
  to any model selection and preserve PAPER/SHADOW/LIVE authorization gates.

The current CI tests use **invented synthetic articles**, a self-declared
rights reference and synthetic India-market candles. Never count these as
a permitted public/official licensed evaluation corpus or proof of
FinBERT/Gemini market performance.

`independent_news_licensing_verified=false`,
`independent_market_source_verified=false`,
`prospectively_frozen_holdout_verified=false`,
`financial_nlp_model_benchmark_completed=false`,
`order_execution_allowed=false`, and
`can_authorize_capital=false` are immutable.
