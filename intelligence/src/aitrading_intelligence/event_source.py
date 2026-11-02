from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .datasets import EventRecord

NEWS_EVENT_EXPORT_CONTRACT = "news-events.v1"


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class ResearchNewsEvent(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    source_id: Annotated[str, Field(min_length=1, max_length=200)] = Field(alias="sourceId")
    source: Annotated[str, Field(min_length=1, max_length=80)]
    publisher: Annotated[str, Field(min_length=1, max_length=160)]
    source_url: Annotated[str, Field(min_length=1, max_length=2048)] = Field(alias="sourceUrl")
    published_at: datetime | None = Field(alias="publishedAt")
    available_at: datetime = Field(alias="availableAt")
    content_hash: Annotated[str, Field(min_length=1, max_length=128)] = Field(alias="contentHash")
    linked_symbols: list[Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]] = Field(
        alias="linkedSymbols", min_length=1, max_length=64
    )

    @field_validator("published_at", "available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_timing(self) -> ResearchNewsEvent:
        if self.published_at is not None and self.available_at < self.published_at:
            raise ValueError("availableAt cannot be before publishedAt")
        if len(self.linked_symbols) != len(set(self.linked_symbols)):
            raise ValueError("linkedSymbols cannot contain duplicates")
        return self


class NewsResearchExport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    contract_version: Literal["news-events.v1"] = Field(alias="contractVersion")
    generated_at: datetime = Field(alias="generatedAt")
    from_inclusive: datetime = Field(alias="fromInclusive")
    to_exclusive: datetime = Field(alias="toExclusive")
    data_cutoff: datetime | None = Field(alias="dataCutoff")
    provider_sources: list[str] = Field(alias="providerSources", max_length=64)
    events: list[ResearchNewsEvent] = Field(max_length=20_000)

    @field_validator("generated_at", "from_inclusive", "to_exclusive", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_export(self) -> NewsResearchExport:
        if self.from_inclusive >= self.to_exclusive:
            raise ValueError("fromInclusive must be before toExclusive")
        ids = [event.source_id for event in self.events]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate sourceId values are not allowed")
        for event in self.events:
            if not self.from_inclusive <= event.available_at < self.to_exclusive:
                raise ValueError("availableAt is outside the export window")
            if event.available_at > self.generated_at:
                raise ValueError("availableAt cannot be after generatedAt")
        expected_cutoff = max((event.available_at for event in self.events), default=None)
        if self.data_cutoff != expected_cutoff:
            raise ValueError("dataCutoff must equal the latest availableAt")
        event_sources = {event.source for event in self.events}
        if not event_sources.issubset(set(self.provider_sources)):
            raise ValueError("providerSources must include every event source")
        return self

    def to_event_records(self) -> list[EventRecord]:
        records: list[EventRecord] = []
        for event in sorted(self.events, key=lambda item: (item.available_at, item.source_id)):
            effective_at = event.published_at or event.available_at
            for symbol in sorted(event.linked_symbols):
                records.append(
                    EventRecord(
                        symbol=symbol,
                        event_type="NEWS",
                        observed_at=event.available_at,
                        effective_at=effective_at,
                        source_id=event.source_id,
                        source=event.source,
                        numeric_features={"present": 1.0},
                    )
                )
        return records


def load_news_export(path: Path) -> NewsResearchExport:
    return NewsResearchExport.model_validate_json(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate and normalize Java point-in-time news research exports"
    )
    parser.add_argument("export_json", type=Path)
    args = parser.parse_args()

    records = load_news_export(args.export_json).to_event_records()
    print(json.dumps([record.model_dump(mode="json") for record in records], sort_keys=True))


if __name__ == "__main__":
    main()
