from __future__ import annotations

from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest
from pydantic import ValidationError
from test_india_independent_holdout import HOLDOUT_DAYS, _export, _target

from aitrading_intelligence.ai_evidence_source import NewsAiEvidenceExport
from aitrading_intelligence.event_source import NewsResearchExport
from aitrading_intelligence.india_financial_nlp_corpus import (
    IndiaFinancialNlpCorpusConfig,
    IndiaFinancialNlpCorpusReport,
    LicensedNewsText,
    build_india_financial_nlp_corpus,
    java_news_content_hash,
)

IST = ZoneInfo("Asia/Kolkata")
MODEL = "gemini-2.5-flash"


def _at(index: int, hour: int, minute: int) -> datetime:
    return datetime.combine(
        HOLDOUT_DAYS[index], time(hour, minute), tzinfo=IST
    )


def _article(source_id: str, available_at: datetime, *,
             symbol: str = "RELIANCE", title: str = "Earnings") -> dict:
    return {
        "sourceId": source_id,
        "source": "official-synthetic-news",
        "publisher": "Synthetic Official Fixture",
        "sourceUrl": "https://example.com/official/notice-" + source_id,
        "publishedAt": available_at - timedelta(minutes=1),
        "availableAt": available_at,
        "contentHash": java_news_content_hash(title, "Quarterly results reported"),
        "linkedSymbols": [symbol],
    }


def _news(events: list[dict]) -> NewsResearchExport:
    return NewsResearchExport.model_validate({
        "contractVersion": "news-events.v1",
        "generatedAt": datetime(2026, 11, 3, 12, tzinfo=UTC),
        "fromInclusive": _at(0, 0, 0),
        "toExclusive": _at(1, 0, 0) + timedelta(days=1),
        "dataCutoff": max(item["availableAt"] for item in events),
        "providerSources": ["official-synthetic-news"],
        "events": events,
    })


def _licensed(source_id: str, available_at: datetime, *,
              title: str = "Earnings", content: str = "Quarterly results reported"):
    return LicensedNewsText(
        source_id=source_id, title=title, content=content,
        captured_at=available_at + timedelta(minutes=1),
        declared_research_rights_reference="synthetic-fixture-evaluation-only",
    )


def _advisory(source_id: str, news_at: datetime, produced_at: datetime):
    return {
        "analysisId": "analysis-" + source_id,
        "sourceId": source_id,
        "provider": "GEMINI", "model": MODEL,
        "providerRequestId": "synthetic-request-" + source_id,
        "sourceAvailableAt": news_at,
        "evidenceAvailableAt": produced_at,
        "promptHash": "a" * 64, "requestFingerprint": "b" * 64,
        "responseFingerprint": "c" * 64,
        "sentiment": "POSITIVE", "directionScore": 0.6,
        "category": "EARNINGS", "confidence": 0.7,
        "materiality": 0.8, "horizon": "INTRADAY",
        "novelty": 0.2, "contradiction": False,
        "ambiguity": False, "impactedSymbols": ["RELIANCE"],
        "inputTokens": 10, "outputTokens": 10, "totalTokens": 20,
    }


def _ai(events: list[dict]) -> NewsAiEvidenceExport:
    return NewsAiEvidenceExport.model_validate({
        "contractVersion": "news-ai-evidence.v1",
        "generatedAt": datetime(2026, 11, 3, 12, tzinfo=UTC),
        "fromInclusive": _at(0, 0, 0),
        "toExclusive": _at(1, 0, 0) + timedelta(days=1),
        "dataCutoff": max(item["evidenceAvailableAt"] for item in events),
        "providerSources": ["GEMINI:" + MODEL],
        "evidence": events,
    })


def _fixture():
    first = _at(0, 9, 19)
    second = _at(1, 9, 22)
    news = _news([_article("news-a", first), _article("news-b", second)])
    ai = _ai([
        _advisory("news-a", first, _at(0, 9, 20)),
        _advisory("news-b", second, _at(1, 9, 30)),
    ])
    texts = [_licensed("news-a", first), _licensed("news-b", second)]
    return news, ai, _export(HOLDOUT_DAYS), _target(), texts


def _run(*, news=None, ai=None, market=None, target=None, texts=None, config=None):
    n, a, m, t, x = _fixture()
    return build_india_financial_nlp_corpus(
        news or n,
        ai if ai is not None else a,
        market or m,
        target or t,
        x if texts is None else texts,
        config=config,
    )


def test_source_bound_indian_news_corpus_never_leaks_late_gemini_evidence() -> None:
    first = _run()
    assert first == _run()
    assert first.status == "CAUSAL_RESEARCH_CORPUS_ONLY"
    assert len(first.cases) == 2
    one, two = first.cases
    assert one.advisory_status == "CAUSALLY_AVAILABLE"
    assert one.advisory_direction_score == 0.6
    assert one.news_available_at <= one.decision_at < one.label_available_at
    assert two.advisory_status == "MISSING_OR_LATE"
    assert two.advisory_analysis_id is None
    assert two.advisory_direction_score is None
    assert two.advisory_evidence_available_at is None
    assert all(case.target_symbol == "RELIANCE-EQ" for case in first.cases)
    assert first.independent_news_licensing_verified is False
    assert first.independent_market_source_verified is False
    assert first.prospectively_frozen_holdout_verified is False
    assert first.financial_nlp_model_benchmark_completed is False
    assert first.can_authorize_capital is False
    assert IndiaFinancialNlpCorpusReport.model_validate_json(
        first.model_dump_json()
    ) == first


def test_java_news_hash_matches_normalized_html_and_whitespace() -> None:
    assert java_news_content_hash("<b>Revenue &amp; profit</b>", " A  B ") == (
        java_news_content_hash("revenue & profit", "a b")
    )
    with pytest.raises(ValueError, match="does not match Java source contentHash"):
        n, a, m, t, x = _fixture()
        wrong = list(x)
        wrong[0] = _licensed("news-a", n.events[0].available_at, title="Changed title")
        build_india_financial_nlp_corpus(n, a, m, t, wrong)


def test_news_without_declared_text_rights_is_excluded_not_silently_scored() -> None:
    corpus = _run(texts=[])
    assert corpus.status == "INCONCLUSIVE"
    assert not corpus.cases
    assert corpus.excluded == {
        "news-a": "NO_DECLARED_RESEARCH_TEXT_RIGHTS",
        "news-b": "NO_DECLARED_RESEARCH_TEXT_RIGHTS",
    }
    assert "INSUFFICIENT_CAUSAL_LICENCED_NEWS_CASES" in corpus.reason_codes


def test_source_and_ai_causality_mismatch_fails_closed() -> None:
    n, ai, market, target, texts = _fixture()
    payload = ai.model_dump(mode="json", by_alias=True)
    payload["evidence"][0]["sourceAvailableAt"] = (
        n.events[0].available_at + timedelta(seconds=1)
    ).isoformat()
    with pytest.raises(ValueError, match="source availability differs"):
        build_india_financial_nlp_corpus(
            n, NewsAiEvidenceExport.model_validate(payload), market, target, texts
        )
    unknown = list(texts)
    unknown.append(_licensed("unknown-news", _at(0, 9, 19)))
    with pytest.raises(ValueError, match="not present in authoritative news"):
        _run(texts=unknown)


def test_non_cash_equity_or_wrong_horizon_cannot_be_benchmarked() -> None:
    with pytest.raises(ValueError, match="horizon does not match"):
        _run(config=IndiaFinancialNlpCorpusConfig(horizon_bars=2))
    payload = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    payload["interval"] = "ONE_MINUTE"
    from aitrading_intelligence.market_source import MarketResearchExport
    with pytest.raises(ValueError, match="five-minute cash-equity"):
        _run(market=MarketResearchExport.model_validate(payload))


def test_horizon_does_not_cross_market_session_or_reuse_missing_candles() -> None:
    at = _at(0, 12, 59)
    n = _news([_article("late-news", at)])
    corpus = _run(news=n, texts=[_licensed("late-news", at)], ai=_ai([]))
    assert corpus.status == "INCONCLUSIVE"
    assert corpus.excluded == {"late-news": "SESSION_BOUNDARY_OR_MISSING_CANDLES"}


def test_corpus_is_tamper_evident_and_no_order_is_authorized() -> None:
    output = _run()
    forged = output.model_dump(mode="json")
    forged["cases"][0]["advisory_status"] = "MISSING_OR_LATE"
    with pytest.raises(ValidationError):
        IndiaFinancialNlpCorpusReport.model_validate(forged)
    forged = output.model_dump(mode="json")
    forged["cases"][0]["trading_order_authorized"] = True
    with pytest.raises(ValidationError):
        IndiaFinancialNlpCorpusReport.model_validate(forged)
