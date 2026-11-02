from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .datasets import FundamentalSnapshot

FUNDAMENTAL_EXPORT_CONTRACT = "fundamentals-history.v1"


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class ResearchFundamentalSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    period_end: datetime = Field(alias="periodEnd")
    available_at: datetime = Field(alias="availableAt")
    source_id: Annotated[str, Field(min_length=1, max_length=200)] = Field(alias="sourceId")
    source: Annotated[str, Field(min_length=1, max_length=80)]
    metrics: dict[str, float] = Field(default_factory=dict, max_length=256)

    @field_validator("period_end", "available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_timing(self) -> ResearchFundamentalSnapshot:
        if self.period_end > self.available_at:
            raise ValueError("periodEnd cannot be after availableAt")
        return self


class FundamentalResearchExport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    contract_version: Literal["fundamentals-history.v1"] = Field(alias="contractVersion")
    generated_at: datetime = Field(alias="generatedAt")
    from_inclusive: datetime = Field(alias="fromInclusive")
    to_exclusive: datetime = Field(alias="toExclusive")
    data_cutoff: datetime | None = Field(alias="dataCutoff")
    provider_sources: list[str] = Field(alias="providerSources", max_length=32)
    snapshots: list[ResearchFundamentalSnapshot] = Field(max_length=20_000)

    @field_validator("generated_at", "from_inclusive", "to_exclusive", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_export(self) -> FundamentalResearchExport:
        if self.from_inclusive >= self.to_exclusive:
            raise ValueError("fromInclusive must be before toExclusive")

        identities = [
            (snapshot.symbol, snapshot.period_end, snapshot.source_id)
            for snapshot in self.snapshots
        ]
        if len(identities) != len(set(identities)):
            raise ValueError("duplicate fundamental snapshot identities are not allowed")

        for snapshot in self.snapshots:
            if not self.from_inclusive <= snapshot.available_at < self.to_exclusive:
                raise ValueError("availableAt is outside the export window")
            if snapshot.available_at > self.generated_at:
                raise ValueError("availableAt cannot be after generatedAt")

        expected_cutoff = max(
            (snapshot.available_at for snapshot in self.snapshots), default=None
        )
        if self.data_cutoff != expected_cutoff:
            raise ValueError("dataCutoff must equal the latest availableAt")

        row_sources = {snapshot.source for snapshot in self.snapshots}
        if not row_sources.issubset(set(self.provider_sources)):
            raise ValueError("providerSources must include every fundamental source")
        return self

    def to_fundamental_snapshots(self) -> list[FundamentalSnapshot]:
        return [
            FundamentalSnapshot(
                symbol=snapshot.symbol,
                observed_at=snapshot.available_at,
                period_end=snapshot.period_end,
                source_id=snapshot.source_id,
                source=snapshot.source,
                metrics=dict(sorted(snapshot.metrics.items())),
            )
            for snapshot in sorted(
                self.snapshots,
                key=lambda item: (
                    item.available_at,
                    item.symbol,
                    item.period_end,
                    item.source_id,
                ),
            )
        ]


def load_fundamental_export(path: Path) -> FundamentalResearchExport:
    return FundamentalResearchExport.model_validate_json(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = __import__("argparse").ArgumentParser(
        description="Validate a point-in-time fundamental research export"
    )
    parser.add_argument("export_json", type=Path)
    args = parser.parse_args()
    export = load_fundamental_export(args.export_json)
    print(json.dumps(export.model_dump(mode="json", by_alias=True), sort_keys=True))


if __name__ == "__main__":
    main()
