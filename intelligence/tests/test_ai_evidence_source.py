from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from aitrading_intelligence.ai_evidence_source import NewsAiEvidenceExport


def _dt(hour: int, minute: int, second: int = 0) -> datetime:
    return datetime(2026, 10, 1, hour, minute, second, tzinfo=UTC)


def _payload() -> dict:
    return {
        "contractVersion": "news-ai-evidence.v1",
        "generatedAt": _dt(10, 0),
        "fromInclusive": _dt(9, 15),
        "toExclusive": _dt(9, 18),
        "dataCutoff": _dt(9, 16, 50),
        "providerSources": ["GEMINI:gemini-2.5-flash"],
        "evidence": [
            {
                "analysisId": "analysis-1",
                "sourceId": "news-1",
                "provider": "GEMINI",
                "model": "gemini-2.5-flash",
                "providerRequestId": "interaction-1",
                "sourceAvailableAt": _dt(9, 16, 30),
                "evidenceAvailableAt": _dt(9, 16, 50),
                "promptHash": "a" * 64,
                "requestFingerprint": "b" * 64,
                "responseFingerprint": "c" * 64,
                "sentiment": "POSITIVE",
                "directionScore": 0.55,
                "category": "EARNINGS",
                "confidence": 0.82,
                "materiality": 0.77,
                "horizon": "MULTI_DAY",
                "novelty": 0.64,
                "contradiction": False,
                "ambiguity": True,
                "impactedSymbols": ["RELIANCE"],
                "inputTokens": 120,
                "outputTokens": 60,
                "totalTokens": 180,
            }
        ],
    }


def test_ai_evidence_uses_generation_time_as_observed_at_and_preserves_lineage() -> None:
    export = NewsAiEvidenceExport.model_validate(_payload())

    records = export.to_event_records()

    assert len(records) == 1
    record = records[0]
    assert record.symbol == "RELIANCE"
    assert record.event_type == "AI_NEWS_EVIDENCE"
    assert record.observed_at == _dt(9, 16, 50)
    assert record.effective_at == _dt(9, 16, 30)
    assert record.numeric_features["direction_score"] == 0.55
    assert record.numeric_features["sentiment_positive"] == 1.0
    assert record.numeric_features["horizon_multi_day"] == 1.0
    assert "news:news-1" in record.lineage_ids
    assert "model:gemini-2.5-flash" in record.lineage_ids
    assert "provider-request:interaction-1" in record.lineage_ids
    assert "request:" + "b" * 64 in record.lineage_ids


def test_rejects_backdated_ai_evidence_and_bad_token_accounting() -> None:
    backdated = _payload()
    backdated["evidence"][0]["evidenceAvailableAt"] = _dt(9, 16, 20)
    backdated["dataCutoff"] = _dt(9, 16, 20)
    with pytest.raises(ValidationError, match="sourceAvailableAt cannot be after evidenceAvailableAt"):
        NewsAiEvidenceExport.model_validate(backdated)

    bad_usage = _payload()
    bad_usage["evidence"][0]["totalTokens"] = 100
    with pytest.raises(ValidationError, match="totalTokens"):
        NewsAiEvidenceExport.model_validate(bad_usage)
