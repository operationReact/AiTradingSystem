from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fundamental_source import FundamentalResearchExport


def _dt(hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 10, 1, hour, minute, tzinfo=UTC)


def test_normalizes_fundamentals_using_first_safe_availability() -> None:
    export = FundamentalResearchExport.model_validate(
        {
            "contractVersion": "fundamentals-history.v1",
            "generatedAt": _dt(12),
            "fromInclusive": _dt(9),
            "toExclusive": _dt(13),
            "dataCutoff": _dt(11),
            "providerSources": ["exchange-filings"],
            "snapshots": [
                {
                    "symbol": "RELIANCE",
                    "periodEnd": datetime(2026, 9, 30, tzinfo=UTC),
                    "availableAt": _dt(11),
                    "sourceId": "filing-2026-q2",
                    "source": "exchange-filings",
                    "metrics": {"eps": 41.2, "revenue_growth_yoy": 0.083},
                }
            ],
        }
    )

    rows = export.to_fundamental_snapshots()

    assert len(rows) == 1
    assert rows[0].observed_at == _dt(11)
    assert rows[0].period_end == datetime(2026, 9, 30, tzinfo=UTC)
    assert rows[0].metrics == {"eps": 41.2, "revenue_growth_yoy": 0.083}
    assert rows[0].source_id == "filing-2026-q2"


def test_rejects_future_period_and_incorrect_cutoff() -> None:
    with pytest.raises(ValidationError, match="periodEnd cannot be after availableAt"):
        FundamentalResearchExport.model_validate(
            {
                "contractVersion": "fundamentals-history.v1",
                "generatedAt": _dt(12),
                "fromInclusive": _dt(9),
                "toExclusive": _dt(13),
                "dataCutoff": _dt(10),
                "providerSources": ["exchange-filings"],
                "snapshots": [
                    {
                        "symbol": "TCS",
                        "periodEnd": _dt(11),
                        "availableAt": _dt(10),
                        "sourceId": "bad-filing",
                        "source": "exchange-filings",
                        "metrics": {"eps": 12.0},
                    }
                ],
            }
        )

    with pytest.raises(ValidationError, match="dataCutoff"):
        FundamentalResearchExport.model_validate(
            {
                "contractVersion": "fundamentals-history.v1",
                "generatedAt": _dt(12),
                "fromInclusive": _dt(9),
                "toExclusive": _dt(13),
                "dataCutoff": _dt(9),
                "providerSources": ["exchange-filings"],
                "snapshots": [
                    {
                        "symbol": "TCS",
                        "periodEnd": datetime(2026, 9, 30, tzinfo=UTC),
                        "availableAt": _dt(10),
                        "sourceId": "filing-1",
                        "source": "exchange-filings",
                        "metrics": {"eps": 12.0},
                    }
                ],
            }
        )
