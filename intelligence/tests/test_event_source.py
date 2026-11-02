from __future__ import annotations

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from aitrading_intelligence.event_source import NewsResearchExport

UTC = timezone.utc


def _payload() -> dict:
    return {
        "contractVersion": "news-events.v1",
        "generatedAt": "2026-10-01T10:10:00Z",
        "fromInclusive": "2026-10-01T00:00:00Z",
        "toExclusive": "2026-10-02T00:00:00Z",
        "dataCutoff": "2026-10-01T10:04:00Z",
        "providerSources": ["economic-times", "moneycontrol"],
        "events": [
            {
                "sourceId": "11111111-1111-1111-1111-111111111111",
                "source": "moneycontrol",
                "publisher": "Moneycontrol",
                "sourceUrl": "https://example.com/reliance",
                "publishedAt": "2026-10-01T09:00:00Z",
                "availableAt": "2026-10-01T09:02:00Z",
                "contentHash": "abc123",
                "linkedSymbols": ["RELIANCE"],
            },
            {
                "sourceId": "22222222-2222-2222-2222-222222222222",
                "source": "economic-times",
                "publisher": "Economic Times",
                "sourceUrl": "https://example.com/it",
                "publishedAt": "2026-10-01T10:00:00Z",
                "availableAt": "2026-10-01T10:04:00Z",
                "contentHash": "def456",
                "linkedSymbols": ["TCS", "INFY"],
            },
        ],
    }


def test_normalizes_each_symbol_without_using_future_or_ai_fields() -> None:
    export = NewsResearchExport.model_validate(_payload())

    records = export.to_event_records()

    assert [(record.symbol, record.source) for record in records] == [
        ("RELIANCE", "moneycontrol"),
        ("INFY", "economic-times"),
        ("TCS", "economic-times"),
    ]
    assert records[0].observed_at == datetime(2026, 10, 1, 9, 2, tzinfo=UTC)
    assert records[0].effective_at == datetime(2026, 10, 1, 9, 0, tzinfo=UTC)
    assert records[0].numeric_features == {"present": 1.0}
    assert all("sentiment" not in record.numeric_features for record in records)


def test_rejects_event_that_was_not_yet_available() -> None:
    payload = _payload()
    payload["events"][0]["availableAt"] = "2026-10-01T08:59:00Z"

    with pytest.raises(ValidationError, match="availableAt cannot be before publishedAt"):
        NewsResearchExport.model_validate(payload)


def test_rejects_incorrect_cutoff_or_missing_provider_lineage() -> None:
    bad_cutoff = _payload()
    bad_cutoff["dataCutoff"] = "2026-10-01T09:02:00Z"
    with pytest.raises(ValidationError, match="dataCutoff"):
        NewsResearchExport.model_validate(bad_cutoff)

    missing_provider = _payload()
    missing_provider["providerSources"] = ["moneycontrol"]
    with pytest.raises(ValidationError, match="providerSources"):
        NewsResearchExport.model_validate(missing_provider)
