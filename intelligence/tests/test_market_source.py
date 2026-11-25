import json
from datetime import UTC, datetime
from uuid import UUID

import pyarrow.parquet as pq
import pytest

from aitrading_intelligence.market_source import MarketResearchExport, build_market_dataset


def export_payload() -> dict:
    return {
        "contractVersion": "market-history.v1",
        "generatedAt": "2026-11-02T12:00:00Z",
        "instrumentId": 1,
        "exchange": "NSE",
        "tradingSymbol": "RELIANCE-EQ",
        "symbolToken": "2885",
        "catalogueVersion": "12345678-1234-1234-1234-123456789abc",
        "interval": "THREE_MINUTE",
        "fromInclusive": "2026-11-02T00:00:00Z",
        "toExclusive": "2026-11-03T00:00:00Z",
        "dataCutoff": "2026-11-02T03:51:00Z",
        "providerSources": ["ANGEL_ONE_SMARTAPI"],
        "candles": [
            {
                "candleTime": "2026-11-02T03:45:00Z",
                "availableAt": "2026-11-02T03:48:00Z",
                "open": 2948.0,
                "high": 2952.0,
                "low": 2946.0,
                "close": 2950.0,
                "volume": 1000,
                "source": "ANGEL_ONE_SMARTAPI",
                "fetchedAt": "2026-11-02T11:00:00Z",
            },
            {
                "candleTime": "2026-11-02T03:48:00Z",
                "availableAt": "2026-11-02T03:51:00Z",
                "open": 2950.0,
                "high": 2956.0,
                "low": 2949.0,
                "close": 2955.0,
                "volume": 1250,
                "source": "ANGEL_ONE_SMARTAPI",
                "fetchedAt": "2026-11-02T11:00:00Z",
            },
        ],
    }


def test_java_export_maps_event_time_to_safe_feature_availability() -> None:
    export = MarketResearchExport.model_validate(export_payload())

    bars = export.to_market_bars()

    assert [bar.interval for bar in bars] == ["3m", "3m"]
    assert bars[0].event_at == datetime(2026, 11, 2, 3, 45, tzinfo=UTC)
    assert bars[0].observed_at == datetime(2026, 11, 2, 3, 48, tzinfo=UTC)
    assert bars[0].ingested_at == datetime(2026, 11, 2, 11, 0, tzinfo=UTC)


def test_market_export_builds_reproducible_parquet_with_source_lineage(tmp_path) -> None:
    export = MarketResearchExport.model_validate(export_payload())

    manifest = build_market_dataset(
        export,
        output_dir=tmp_path,
        dataset_version="reliance-3m-2026-11-02-v1",
        code_sha="a" * 40,
    )

    assert manifest.row_count == 2
    assert manifest.data_cutoff == datetime(2026, 11, 2, 3, 51, tzinfo=UTC)
    assert manifest.source_versions["marketExportContract"] == "market-history.v1"
    assert manifest.source_versions["instrumentCatalogue"] == str(
        UUID("12345678-1234-1234-1234-123456789abc")
    )
    table = pq.read_table(tmp_path / "features.parquet")
    assert table.num_rows == 2
    second_features = json.loads(table.column("features_json")[1].as_py())
    assert second_features["market.close"] == 2955.0
    assert second_features["market.return_1"] == pytest.approx(2955.0 / 2950.0 - 1.0)


def test_market_export_rejects_cutoff_that_includes_unavailable_future_bar() -> None:
    payload = export_payload()
    payload["candles"][1]["availableAt"] = "2026-11-02T12:01:00Z"
    payload["dataCutoff"] = "2026-11-02T12:01:00Z"

    with pytest.raises(ValueError, match="availableAt cannot be after generatedAt"):
        MarketResearchExport.model_validate(payload)


def test_market_export_rejects_unknown_provider_lineage() -> None:
    payload = export_payload()
    payload["providerSources"] = []

    with pytest.raises(ValueError, match="providerSources"):
        MarketResearchExport.model_validate(payload)
