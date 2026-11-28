# News analysis outcomes and MintTick Mind usage

Status checked against `dev` on 2026-11-28. This note distinguishes stored article analysis from strategy decisions.

## What “Analyzed” means

`ANALYZED` is a processing status. It means an analysis record was stored; it does not say that the story is positive, material, correctly attributed to an issuer, fresh for a strategy, or predictive.

The stored article result includes article-level sentiment, direction score, model confidence, materiality, horizon, event category/type, impacted-symbol candidates, evidence, risks, ambiguity, and contradiction flags. These fields describe the model's reading of an article. Direction score is not an expected return.

## Current path into Mind

1. An article and its analysis are stored in `news_articles` and `news_analyses`.
2. When `app.news.mind-context.enabled` is on, `ResearchScanService` attaches a read-only `news.context.v1` snapshot after a strategy has already produced a candidate. A lookup failure is recorded as unavailable; it does not change the scan result.
3. `NewsFeatureService` applies point-in-time, source-rights, symbol, confidence, materiality, ambiguity, contradiction, freshness, and event-blackout checks. The analysis must have been available by the candidate's `asOf` timestamp.
4. The snapshot is stored on the candidate and returned by Mind decision history. A missing snapshot means “not recorded,” not “no news.”

This is shadow research context. The strategy's candidate, score, risk, position size, replay result, and order path do not consume it. Current walk-forward/replay evaluators do not compare news-enabled and no-news variants. No strategy should be described as news-driven until that evaluation exists and passes out-of-time checks.

## Product target: two independent signal paths

MintTick Mind must support both paths:

1. **Strategy-originated:** a technical/fundamental strategy produces a candidate, then Mind adds eligible point-in-time news as confirmation, caution, or context.
2. **News-originated:** a material news event independently creates a directional research candidate, even when no existing technical strategy emitted one. That candidate can then be checked by the relevant strategies, market/liquidity rules, and risk controls.

A news-originated candidate needs an explicit event thesis: verified affected issuer or instrument, event type, whether the information is new or already priced in, direction hypothesis, expected horizon, materiality, confidence, freshness, supporting evidence, and invalidation/uncertainty reasons. Positive wording alone must not imply a bullish candidate; results versus expectations, event scope, contradictory details, prior price reaction, and market context can reverse or neutralize the apparent tone.

**Implementation status in this change:** a separate, explicit `POST /api/research/news-driven-scans` endpoint now creates `NEWS_IMPACT_V1` research candidates independently of technical strategy emissions. It requires the news-context feature flag, eligible as-of evidence, low uncertainty, no event blackout, a directional aggregate of at least 0.50 absolute value, and 64 candles that pass the existing research-quality gate. Each candidate stores the exact news context and market data cutoff. It creates no order and does not yet feed PAPER or LIVE execution. The regular technical scan still records news only as an annotation.

**Remaining integration:** PAPER must accept the news-originated thesis as an input while re-checking current news, market session, candles, broker tick, risk limits, and paper-room ownership. Only after paper soak and separate proposal controls may a news-informed proposal enter the existing LIVE proposal flow; existing per-order confirmation remains required. Negative news can express a bearish research view now, but the current PAPER engine does not open short positions, so it must not turn that view into an unsupported short order.

## UI outcome contract

The analyzer should show these separately:

- **Processing state:** analyzed, pending, or failed.
- **Article-level result:** tone, direction score (if present), model confidence, materiality, and horizon.
- **Uncertainty:** ambiguity or contradiction flags. These make the article ineligible to add direction under the current feature policy.
- **Symbol candidates:** symbols returned by the article model. They are not, by themselves, verified issuer-level impact.
- **Mind/strategy use:** candidate-history annotation when enabled; zero strategy weight today.
- **Evidence:** model rationale, source URL, publication and analysis timestamps, risks, and reported figures.

The UI must not translate article sentiment into a buy/sell instruction, target, expected return, or claim that a stock will move.

## Research and validation gate before strategy use

Published financial-text research finds useful information in news, but effects vary with sector, attention, horizon, and target variable; news tone can relate to volatility or turnover without producing a robust return signal. LLM-based historical sentiment also has a specific look-ahead risk when the model's training data overlaps the backtest period. Therefore, strategy use requires point-in-time news and analysis availability, survivorship-aware symbols, licensed source history, calibrated labels, and a walk-forward comparison against an otherwise identical no-news baseline after costs.

References:

- Zhang et al., [Distillation of News Flow into Analysis of Stock Reactions](https://arxiv.org/abs/2009.10392).
- [Assessing Look-Ahead Bias in Stock Return Predictions Generated by GPT Sentiment Analysis](https://arxiv.org/abs/2309.17322).

## Evidence limits

A successful source fetch proves retrieval only. A customer feed count or an `ANALYZED` label does not prove full source coverage, valid issuer attribution, an active production Gemini provider, or incremental strategy performance. Those need separate runtime and out-of-time evidence.
