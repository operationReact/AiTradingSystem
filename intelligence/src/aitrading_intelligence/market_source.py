from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .datasets import MarketBar
from .exports import export_feature_dataset
from .features import build_feature_row

MARKET_EXPORT_CONTRACT = "market-history.v1"

INTERVALS = {
    "ONE_MINUTE": "1m",
    "THREE_MINUTE": "3m",
    "FIVE_MINUTE": "5m",
    "TEN_MINUTE": "10m",
    "FIFTEEN_MINUTE": "15m",
    "THIRTY_MINUTE": "30m",
    "ONE_HOUR": "1h",
    "ONE_DAY": "1d",
}


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class ResearchCandle(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    candle_time: datetime = Field(alias="candleTime")
    available_at: datetime = Field(alias="availableAt")
    open: Annotated[float, Field(gt=0)]
    high: Annotated[float, Field(gt=0)]
    low: Annotated[float, Field(gt=0)]
    close: Annotated[float, Field(gt=0)]
    volume: Annotated[float, Field(ge=0)]
    source: Annotated[str, Field(min_length=1, max_length=80)]
    fetched_at: datetime = Field(alias="fetchedAt")

    @field_validator("candle_time", "available_at", "fetched_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_timing(self) -> ResearchCandle:
        if self.available_at < self.candle_time:
            raise ValueError("availableAt cannot be before candleTime")
        return self


class MarketResearchExport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    contract_version: Literal["market-history.v1"] = Field(alias="contractVersion")
    generated_at: datetime = Field(alias="generatedAt")
    instrument_id: Annotated[int, Field(gt=0)] = Field(alias="instrumentId")
    exchange: Annotated[str, Field(min_length=1, max_length=20)]
    trading_symbol: Annotated[str, Field(min_length=1, max_length=64)] = Field(alias="tradingSymbol")
    symbol_token: Annotated[str, Field(min_length=1, max_length=64)] = Field(alias="symbolToken")
    catalogue_version: UUID = Field(alias="catalogueVersion")
    interval: Literal[
        "ONE_MINUTE",
        "THREE_MINUTE",
        "FIVE_MINUTE",
        "TEN_MINUTE",
        "FIFTEEN_MINUTE",
        "THIRTY_MINUTE",
        "ONE_HOUR",
        "ONE_DAY",
    ]
    from_inclusive: datetime = Field(alias="fromInclusive")
    to_exclusive: datetime = Field(alias="toExclusive")
    data_cutoff: datetime | None = Field(alias="dataCutoff")
    provider_sources: list[str] = Field(alias="providerSources", max_length=16)
    candles: list[ResearchCandle] = Field(max_length=20_000)

    @field_validator("generated_at", "from_inclusive", "to_exclusive", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_export(self) -> MarketResearchExport:
        if self.from_inclusive >= self.to_exclusive:
            raise ValueError("fromInclusive must be before toExclusive")
        event_times = [candle.candle_time for candle in self.candles]
        if len(event_times) != len(set(event_times)):
            raise ValueError("duplicate candleTime values are not allowed")
        for candle in self.candles:
            if not self.from_inclusive <= candle.candle_time < self.to_exclusive:
                raise ValueError("candleTime is outside the export window")
            if candle.available_at > self.generated_at:
                raise ValueError("availableAt cannot be after generatedAt")
        expected_cutoff = max((candle.available_at for candle in self.candles), default=None)
        if self.data_cutoff != expected_cutoff:
            raise ValueError("dataCutoff must equal the latest availableAt")
        row_sources = {candle.source for candle in self.candles}
        if not row_sources.issubset(set(self.provider_sources)):
            raise ValueError("providerSources must include every candle source")
        return self

    def to_market_bars(self) -> list[MarketBar]:
        interval = INTERVALS[self.interval]
        return [
            MarketBar(
                symbol=self.trading_symbol,
                interval=interval,
                observed_at=candle.available_at,
                event_at=candle.candle_time,
                ingested_at=candle.fetched_at,
                open=candle.open,
                high=candle.high,
                low=candle.low,
                close=candle.close,
                volume=candle.volume,
                source=candle.source,
            )
            for candle in sorted(self.candles, key=lambda item: item.available_at)
        ]


def build_market_dataset(
    export: MarketResearchExport,
    *,
    output_dir: Path,
    dataset_version: str,
    code_sha: str,
):
    bars = export.to_market_bars()
    if not bars:
        raise ValueError("market export contains no candles")

    rows = []
    previous: MarketBar | None = None
    for bar in bars:
        window = [bar] if previous is None else [previous, bar]
        rows.append(
            build_feature_row(
                symbol=bar.symbol,
                as_of=bar.observed_at,
                bars=window,
            )
        )
        previous = bar

    return export_feature_dataset(
        rows,
        output_dir=output_dir,
        dataset_version=dataset_version,
        code_sha=code_sha,
        source_versions={
            "marketExportContract": export.contract_version,
            "instrumentCatalogue": str(export.catalogue_version),
            "providers": ",".join(export.provider_sources),
            "interval": export.interval,
        },
    )


def load_market_export(path: Path) -> MarketResearchExport:
    return MarketResearchExport.model_validate_json(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a point-in-time research dataset from Java market history")
    parser.add_argument("export_json", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--dataset-version", required=True)
    parser.add_argument("--code-sha", required=True)
    args = parser.parse_args()

    manifest = build_market_dataset(
        load_market_export(args.export_json),
        output_dir=args.output_dir,
        dataset_version=args.dataset_version,
        code_sha=args.code_sha,
    )
    print(json.dumps(manifest.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
