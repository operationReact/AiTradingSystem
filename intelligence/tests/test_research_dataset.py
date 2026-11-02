from datetime import UTC, datetime
from uuid import UUID

import pyarrow.parquet as pq

from aitrading_intelligence.event_source import NewsResearchExport
from aitrading_intelligence.fundamental_source import FundamentalResearchExport
from aitrading_intelligence.market_source import MarketResearchExport
from aitrading_intelligence.research_dataset import build_research_dataset


def _dt(hour: int, minute: int, second: int = 0) -> datetime:
    return datetime(2026, 10, 1, hour, minute, second, tzinfo=UTC)


def _market_export() -> MarketResearchExport:
    return MarketResearchExport.model_validate(
        {
            "contractVersion": "market-history.v1",
            "generatedAt": _dt(10, 0),
            "instrumentId": 101,
            "exchange": "NSE",
            "tradingSymbol": "RELIANCE",
            "symbolToken": "2885",
            "catalogueVersion": "11111111-1111-1111-1111-111111111111",
            "interval": "ONE_MINUTE",
            "fromInclusive": _dt(9, 15),
            "toExclusive": _dt(9, 18),
            "dataCutoff": _dt(9, 17, 5),
            "providerSources": ["angel-one"],
            "candles": [
                {
                    "candleTime": _dt(9, 16),
                    "availableAt": _dt(9, 16, 5),
                    "open": 100.0,
                    "high": 102.0,
                    "low": 99.0,
                    "close": 101.0,
                    "volume": 1000.0,
                    "source": "angel-one",
                    "fetchedAt": _dt(9, 16, 5),
                },
                {
                    "candleTime": _dt(9, 17),
                    "availableAt": _dt(9, 17, 5),
                    "open": 101.0,
                    "high": 104.0,
                    "low": 100.0,
                    "close": 103.0,
                    "volume": 1400.0,
                    "source": "angel-one",
                    "fetchedAt": _dt(9, 17, 5),
                },
            ],
        }
    )


def _news_export() -> NewsResearchExport:
    return NewsResearchExport.model_validate(
        {
            "contractVersion": "news-events.v1",
            "generatedAt": _dt(10, 0),
            "fromInclusive": _dt(9, 15),
            "toExclusive": _dt(9, 18),
            "dataCutoff": _dt(9, 16, 30),
            "providerSources": ["exchange-news"],
            "events": [
                {
                    "sourceId": "news-1",
                    "source": "exchange-news",
                    "publisher": "Exchange News",
                    "sourceUrl": "https://example.test/news-1",
                    "publishedAt": _dt(9, 16, 20),
                    "availableAt": _dt(9, 16, 30),
                    "contentHash": "abc123",
                    "linkedSymbols": ["RELIANCE"],
                }
            ],
        }
    )


def _fundamental_export() -> FundamentalResearchExport:
    return FundamentalResearchExport.model_validate(
        {
            "contractVersion": "fundamentals-history.v1",
            "generatedAt": _dt(10, 0),
            "fromInclusive": _dt(9, 15),
            "toExclusive": _dt(9, 18),
            "dataCutoff": _dt(9, 16, 45),
            "providerSources": ["exchange-filings"],
            "snapshots": [
                {
                    "symbol": "RELIANCE",
                    "periodEnd": datetime(2026, 9, 30, tzinfo=UTC),
                    "availableAt": _dt(9, 16, 45),
                    "sourceId": "filing-q2",
                    "source": "exchange-filings",
                    "metrics": {"eps": 41.2},
                }
            ],
        }
    )


def test_combined_dataset_excludes_future_evidence_and_preserves_lineage(tmp_path) -> None:
    output = tmp_path / "dataset"
    manifest = build_research_dataset(
        market=_market_export(),
        news=_news_export(),
        fundamentals=_fundamental_export(),
        output_dir=output,
        dataset_version="research-2026-10-01",
        code_sha="a" * 40,
    )

    table = pq.read_table(output / "features.parquet").to_pylist()

    assert manifest.row_count == 2
    assert manifest.data_cutoff == _dt(9, 17, 5)
    assert manifest.source_versions["newsExportContract"] == "news-events.v1"
    assert manifest.source_versions["fundamentalExportContract"] == "fundamentals-history.v1"

    first_features = table[0]["features_json"]
    second_features = table[1]["features_json"]
    assert "event.NEWS.present" not in first_features
    assert "fundamental.eps" not in first_features
    assert '"event.NEWS.present":1.0' in second_features
    assert '"fundamental.eps":41.2' in second_features

    first_provenance = table[0]["provenance_json"]
    second_provenance = table[1]["provenance_json"]
    assert "news-1" not in first_provenance
    assert "filing-q2" not in first_provenance
    assert "news-1" in second_provenance
    assert "filing-q2" in second_provenance


def test_same_inputs_reproduce_same_dataset_fingerprint(tmp_path) -> None:
    kwargs = {
        "market": _market_export(),
        "news": _news_export(),
        "fundamentals": _fundamental_export(),
        "dataset_version": "research-2026-10-01",
        "code_sha": "b" * 40,
    }

    first = build_research_dataset(output_dir=tmp_path / "one", **kwargs)
    second = build_research_dataset(output_dir=tmp_path / "two", **kwargs)

    assert first.fingerprint == second.fingerprint
    assert first.row_count == second.row_count == 2
    assert first.source_versions == second.source_versions
    assert UUID(first.source_versions["instrumentCatalogue"]) == UUID(
        "11111111-1111-1111-1111-111111111111"
    )
