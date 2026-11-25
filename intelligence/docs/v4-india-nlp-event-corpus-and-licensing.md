# V4-05 — India financial NLP event corpus and legal/provenance gate

**Contract:** `india-financial-event-corpus.v1`

This first V4-05 increment deliberately implements the data/rights and
point-in-time evaluation foundation, **not** a pretrained FinBERT or Gemini
model and not a trading signal.

## Critical FinBERT licensing distinction (verified 2026-11-04)

Primary sources:

- ProsusAI code repository:
  https://github.com/ProsusAI/finBERT
- ProsusAI notice effective October 12, 2026:
  https://github.com/ProsusAI/finBERT/blob/master/README.md
- Original `ProsusAI/finbert` model card:
  https://huggingface.co/ProsusAI/finbert

The source repository's **Apache-2.0 applies to code, notebooks and training
scripts only**, not to pretrained weights or underlying training datasets.
The currently published official model card identifies the **pretrained
weights as CC BY-NC-SA 4.0** and says **commercial use is restricted**.
This application is intended for a monetized trading product; therefore
do not add, download or ship these pretrained weights without separately
documented permission or a properly licensed commercial alternative.
The Financial PhraseBank training corpus has separate rights and is not
automatically reusable because the source code is Apache-2.0.
A license-review note is not legal clearance for production or commercial use.

## Input and data rights contract

Every event supplies an immutable, fingerprinted record of:

- Source URL, kind (`OFFICIAL`, `LICENSED_PROVIDER`, `SYNTHETIC`)
- A **self-declared** research-reuse basis and HTTPS documentation URL
  for non-synthetic source text; an official government or exchange domain
  is **not** automatically interpreted as permission to redistribute it
- A bounded headline and cryptographic exact text digest
- Original publication time, first captured/available time, intended
  model-decision time, future-market label window and label-availability time
- Training, calibration or genuinely later independent holdout partition
- A human/research sentiment label and a **self-declared** subsequent
  market return in basis points, explicitly NOT authenticated market P&L

All timestamps require timezone offsets and
`published <= captured <= decision < market_start < market_end <= label_available`.
The corpus rejects repeated event IDs/text, missing train/calibration/holdout
coverage, and any training label whose availability overlaps calibration
decisions, or calibration label overlapping independent holdout decisions.
Each entire record and the corpus are immutable and digest-verified.

`IndiaFinancialEventCorpus.inputs_for(split)` returns only information
available before each model decision: event ID, source, headline, exact
content hash, publication/capture and decision time. It **excludes**
future sentiment labels, market outcomes and reuse approval metadata,
so benchmarks cannot accidentally place a future price in an LLM prompt.

## Illustrative use (synthetic records only)

```python
from aitrading_intelligence.india_nlp_event_corpus import (
    build_india_financial_event_sample,
    freeze_india_financial_event_corpus,
)

# First create separate TRAIN/CALIBRATION/INDEPENDENT_HOLDOUT
# synthetic or permission-reviewed records with causal timestamps.
corpus = freeze_india_financial_event_corpus(
    samples,
    research_created_at=after_all_labels_are_available,
)
historically_available_model_inputs = corpus.inputs_for("INDEPENDENT_HOLDOUT")
assert corpus.can_authorize_capital is False
```

Unit/CI tests use **original synthetic descriptions and invented outcomes**.
They do not retrieve or copy Indian regulatory, newspaper, exchange or
broker content, download a FinBERT model, make Gemini API requests or
establish a licensed real India market-news evaluation set.

## Next acceptance gates

1. Determine which regulator/publication/source permits the intended
   training, storage, display and commercial use of its text. Retain
   actual rights evidence (the existence of an official URL is not proof).
2. Independently verify source publication and ingestion timestamps,
   plus subsequent NSE/BSE outcome feed and realistic fees/slippage.
3. Review exact model *weight* and dataset licenses; select a commercial-
   eligible FinBERT-style alternative if ProsusAI permission is unavailable.
4. Add a separately reviewable Gemini advisory baseline, compare it against
   the appropriately licensed finance classifier on exact identical
   historical inputs and outcome labels, and check latency/cost/calibration.
5. Keep LLMs unable to issue orders or bypass deterministic risk gates.

`prosus_finbert_weights_commercial_approved=false`,
`third_party_corpus_reuse_independently_verified=false`,
`india_market_labels_independently_verified=false`,
`gemini_output_allowed_to_issue_orders=false`,
`model_promotion_allowed=false` and
`can_authorize_capital=false` are all enforced in this version.
