# News Analyzer ↔ Mintick Mind integration (GitHub #1809)

Status legend: **IMPLEMENTED** (code exists in repo) · **TESTED** (a test exercises it) ·
**DEPLOYED** (running in an environment) · **RUNTIME VERIFIED** (observed working on real data) ·
**GAP** (missing or not connected) · **BLOCKED** (cannot be verified with available inputs).

This document is the PR 1 inventory. It describes `dev` at `be5a8ee`. Nothing here is
production evidence: no real news feed has been observed by this work.

## 1. As-is sequence (code-accurate)

```
Operator (X-API-Key)
  └─ POST /api/news/articles ──► NewsController.ingest (news/NewsController.java:49)
        └─ NewsService.ingest (news/NewsService.java:83)
             INSERT news_articles … ON CONFLICT (url_hash) DO NOTHING      [TESTED: NewsApiIT]
  └─ POST /api/news/articles/{id}/analyze
        └─ NewsAnalysisService.analyze (news/NewsAnalysisService.java:77)
             provider = props.provider()  (default NONE → UNCONFIGURED)  [TESTED: unit]
             persist → INSERT news_analyses(status ANALYZED|FAILED, impacted_symbols JSON)
             source_available_at := article.ingestedAt   (line 181)      [see G3]

RSS job (only when app.news.ingestion.enabled=true; default false)
  NewsIngestionJob.ingest (news/NewsIngestionJob.java:53)
    RssFeedClient → RssFeedParser → NewsRepository.insert
    → INSERT news_item … ON CONFLICT (content_hash)   (linked_symbols = watchlist match)
    → optional GeminiNewsSummarizer → news_summary                    [TESTED: unit]
  NOTE: this path never writes news_articles or news_analyses.          [G1]

Scanner (research scan, scanner/ResearchScanService.java:83)
  for each active cash-equity instrument: candles → quality gate → ScannerEngine
  candidate → scans.saveCandidate(...)  (ResearchScanService.java:143)
    → research_scan_candidate (signal_candle_time, data_cutoff_at, reasons, fingerprint)
  NO news is read at any point.                                         [G2]

Mind decision history (mind/MindDecisionHistoryService.java:55)
  SELECT research_scan_candidate JOIN research_scan_run WHERE status='SUCCEEDED'
  → Decision record (no news field)                                     [G2]
  → GET /api/customer/mind/v1/decision-history (CustomerMindDecisionHistoryController)

Mind replay / paper cycle (mind/MindStrategyReplayService, mind/MindPaperCycleService)
  No reference to news. Replay has no as-of news join.                  [G4]
```

## 2. Interface inventory

| Interface | Producer / consumer | Storage | Auth | Retry / idempotency | Status |
|---|---|---|---|---|---|
| Article ingest | operator → `NewsService.ingest` | `news_articles` (V3) | X-API-Key | `url_hash` unique; duplicate ignored | IMPLEMENTED, TESTED |
| Analysis | operator → `NewsAnalysisService.analyze` | `news_analyses` (V3, V44, V45) | X-API-Key | `inFlight` guard; retries `props.maxAttempts`; append-only rows | IMPLEMENTED, TESTED (unit) |
| RSS ingest | scheduler → `NewsIngestionJob` | `news_item`, `news_summary` (V12) | none (outbound) | `content_hash` unique; disabled by default | IMPLEMENTED, TESTED (unit); default OFF |
| Source health | `NewsSourceHealthRepository` | V46 | X-API-Key | per-run record | IMPLEMENTED, TESTED |
| Scan candidate | `ResearchScanService` → `ResearchScanRepository.saveCandidate` | `research_scan_candidate` (V13) | internal | fingerprint unique per run | IMPLEMENTED, TESTED |
| Decision history | `MindDecisionHistoryService.inspect` | reads V13 tables | customer key | read-only, `mind.decision-history.v1` | IMPLEMENTED, TESTED |
| Replay | `MindStrategyReplayService` | candle + custody | — | — | IMPLEMENTED, no news input |

## 3. Gap register

| ID | Gap | Evidence | Impact | Planned fix |
|---|---|---|---|---|
| **G1** | Two disconnected news stores: analyzer (`news_articles`/`news_analyses`) is fed only by manual POST; RSS job feeds `news_item` only. No automated path feeds analysis. | `NewsService.ingest` callers; `NewsIngestionJob` writes `NewsRepository.insert` only | Analysis cannot run on scheduled news | PR 2: reuse analyzer store; do not add a third RSS scraper. Automated feed into `news_articles` is BLOCKED on a rights-approved source list. |
| **G2** | Scanner and Mind decision history have no news input or output. | `ResearchScanService.run` has no news reference; `Decision` record has no news field | Mind cannot show news impact | PR 2: as-of news context persisted on each candidate (shadow annotation). |
| **G3** | `news_analyses.source_available_at` = article *ingest* time, not analysis-availability time. Using it for as-of joins would allow a later analysis to be used for an earlier signal. | `NewsAnalysisService.java:181` | Lookahead risk if used as availability | PR 1/2: availability := `news_analyses.created_at` (immutable, append-only). `source_available_at` kept as-is (historical data). |
| **G4** | Replay / walk-forward have no as-of news join. | `MindStrategyReplayService` has no news reference | Backtests cannot test news value | PR 3: shadow as-of join with no-lookahead tests. |
| **G5** | LLM `impactedSymbols` are used without verification against instrument master. | `NewsAnalysisService.SYSTEM_PROMPT` asks for symbols; stored raw in `impacted_symbols VARCHAR(500)` | Hallucinated mapping could attach news to the wrong stock | PR 2: a symbol counts only when it equals the scanned instrument's own base symbol (cash equity, exchange-scoped). Otherwise `EXCLUDED_UNVERIFIED_INSTRUMENT`. |
| **G6** | `news_analyses` is append-only but `NewsService.findArticle` returns the most recent analysis, which can be newer than a signal. | `NewsService.ARTICLE_SELECT` LATERAL `ORDER BY created_at DESC LIMIT 1` | As-of must pick the latest analysis with `created_at <= asOf` | PR 2: `DISTINCT ON (article_id)` with `created_at <= :asOf`. |
| **G7** | No real news data has been observed. Ingestion defaults OFF; provider defaults `NONE`. | `application.yml` `NEWS_INGESTION_ENABLED:false`, `NEWS_ANALYSIS_PROVIDER:NONE` | Any news-driven result is synthetic test data | Report BLOCKED for real-news runtime evidence. |
| **G8** | Flutter Mintick Mind has no news UI. | `apps/trading_client/lib/src/features/mintick_mind/` has no news model | UI cannot show impact | PR 4. |
| **G9** | Source rights, retention and per-source licence fields are not recorded on `news_articles`. | V3 schema | Cannot prove a source may be used for research | Recorded as BLOCKED; no rights claim is made by this work. |

## 4. Target contract: `news.context.v1`

Produced by `news/feature/NewsFeaturePolicy` (PR 1) and `news/feature/NewsFeatureService` (PR 2).

- An item contributes at `asOf` only if `published_at <= asOf` **and** the analysis row `created_at <= asOf`.
- Lookback is bounded to 30 days. Validity by horizon: INTRADAY 1 day, MULTI_DAY 5 days, LONGER_TERM 30 days, UNKNOWN 1 day (conservative).
- Inclusion needs: verified symbol, `ANALYZED` status, not expired, confidence ≥ 0.50, materiality and direction present, no contradiction and no ambiguity.
- Every excluded item carries one reason code: `EXCLUDED_PUBLISHED_AFTER_AS_OF`, `EXCLUDED_ANALYSIS_NOT_YET_AVAILABLE`, `EXCLUDED_UNVERIFIED_INSTRUMENT`, `EXCLUDED_ANALYSIS_FAILED`, `EXCLUDED_EXPIRED`, `EXCLUDED_LOW_CONFIDENCE`, `EXCLUDED_UNCERTAIN`.
- Net direction is the confidence × materiality weighted mean over included items only. It is `null` with no included evidence. Unknown never becomes positive or negative.
- The direction label is evidence direction (POSITIVE / NEGATIVE / NEUTRAL / UNKNOWN). It is not a trade recommendation.
- Uncertainty: `UNKNOWN` (no evidence), `LOW`, `MEDIUM` (some uncertain items excluded), `HIGH` (only uncertain or none included).

## 5. Rollout and safety

- News never changes candidacy, score, risk, sizing or order placement. In PR 2 it is an annotation on the candidate. Its role is research/shadow only.
- A feature flag `app.news.mind-context.enabled` (default `false`) gates writing the annotation. Defaults keep production behaviour unchanged.
- Historical candidates recorded before the migration have no news context. The UI must show "not recorded", not "no news".
- Server risk, authorization, instrument identity, expiry and idempotency gates are untouched. No LLM output is used to place or approve an order.

## 6. Evidence status at PR 1

- Policy unit tests: `NewsFeaturePolicyTest` (15 tests) — TESTED (Maven, surefire).
- Flutter, real-news, Android/iOS and live evidence: not part of PR 1. See each PR's description for its status.
