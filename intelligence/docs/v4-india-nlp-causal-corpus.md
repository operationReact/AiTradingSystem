# V4-05 — Indian financial-event NLP corpus: rights and causal time gate

**Contract:** `india-nlp-causal-rights-corpus.v1`  
**Scope:** The first V4-05 prerequisite for FinBERT, Gemini and other
finance-text challengers; no model has been downloaded or executed.

The existing `news-events.v1` event export records source IDs, publisher,
content hashes, publication time and first news availability. **It does
not contain the underlying text or confirm permission to reuse it.**
Fetching arbitrary articles for a training corpus is therefore neither
technically safe nor a verified grant of data rights.

This increment accepts only separately supplied, explicitly declared
research excerpts whose user/operator declares they own or have permission
to use (`OWNED`, `LICENSED`, or `OPEN_LICENSE`). Every excerpt is bounded
to 2,000 characters and must include an exact SHA-256, original news
`sourceId` + content-hash match, first text capture timestamp, language
and an HTTPS URL to the claimed usage-rights basis. These are **claims**:
the system does not verify the license or contact the publisher.

## Chronology and look-ahead prevention

A news source may report `publishedAt` as 09:00, while the researcher
first captures the licensed text at 11:45 or days later. The causal
eligibility time is:

```
first_usable_at = max(news.availableAt, text.captured_at)
```

**Never** split a later-captured text into the historical training data
based on an earlier publication date. Freeze a chronological
`india-nlp-corpus-split-plan.v1` with a non-overlapping train,
calibration and holdout time window, explicit allowed providers,
minimum documents per window and a claimed registration timestamp
**before the future holdout begins**. The plan and output are
fingerprinted, but local timestamps are not trusted external witnesses.

All included news must be in the existing `news-events.v1` export. Text
must not be captured before the event was actually available or after
the requested `as_of` time; every matching document must belong to
exactly one frozen fold. Duplicated source IDs or syndicated duplicate
text (same SHA-256 across documents/folds) fail closed to prevent hidden
train/holdout reuse. Empty or underpopulated folds yield
`INCONCLUSIVE`, not training/evaluation success.

The event and rights-reference URLs must be HTTPS; only the local
files are read. There are **no network calls**, news crawlers, broker
APIs, third-party model downloads, model weights, secrets, live orders
or background requests.

## Offline command

```bash
cd intelligence
python -m aitrading_intelligence.india_nlp_causal_corpus \
  locked-india-nlp-plan.json already-captured-news-events.json \
  self-declared-licensed-text.json --as-of 2026-11-04T12:00:00+05:30
```

The rights text file is a JSON list of `DeclaredNlpTextUse` records,
including `source_id`, `source_content_hash`, `excerpt`,
`excerpt_sha256`, `language`, `captured_at`, `rights_basis`,
`rights_reference`, and
`model_research_use_declared_permitted: true`. The plan is created
with `make_india_nlp_split_plan(...)`, using explicit, immutable
research-window dates and permitted source names.

## Remaining acceptance

- Obtain actually licensed/official India event text and independently
  verify its publisher/rights; CI uses only short **original synthetic
  fictional notices**.
- Freeze a genuinely prospective, independently timestamped event and
  market cohort and capture before the holdout starts.
- Construct independent human-labelled sentiment/event ground truth,
  including adjudication and disagreement, before comparing models.
- Compare approved FinBERT, Gemini advisory and potentially FinGPT
  challengers on **exact identical, non-leaky documents**, measure F1,
  probability calibration, adverse events, late availability and
  subsequent after-cost market decision value. Reject added models
  with no incremental value.
- Treat all model outputs as optional advisory evidence. The model,
  corpus and LLM have **no authority to place an order**.
- Real NSE/BSE market source authenticity, external pre-registration,
  data custody, licensed content, and capital/PAPER acceptance remain
  independently unverified.

The contract explicitly sets
`independent_publication_verification=false`,
`independent_licence_verification=false`,
`independent_timestamp_verification=false`,
`sentiment_model_evaluated=false`,
`market_outcome_linked=false`,
`any_llm_can_place_orders=false`, and
`can_authorize_capital=false`.
