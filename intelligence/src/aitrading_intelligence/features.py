from __future__ import annotations

import math
from collections.abc import Iterable
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .datasets import (
    FEATURE_SCHEMA_VERSION,
    EventRecord,
    FundamentalSnapshot,
    MarketBar,
)
from .fingerprints import canonical_fingerprint


class FeatureRow(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    feature_schema_version: Literal["features.market.v1"] = FEATURE_SCHEMA_VERSION
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    as_of: datetime
    data_cutoff: datetime
    features: dict[str, float] = Field(default_factory=dict, max_length=512)
    provenance: list[str] = Field(default_factory=list, max_length=128)
    fingerprint: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("timestamps must be timezone-aware")
        return value

    @field_validator("features")
    @classmethod
    def features_must_be_finite(cls, value: dict[str, float]) -> dict[str, float]:
        if any(not math.isfinite(number) for number in value.values()):
            raise ValueError("feature values must be finite")
        return value

    @model_validator(mode="after")
    def cutoff_cannot_exceed_as_of(self) -> FeatureRow:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        return self


def _latest_by_observed_at(
    records: Iterable[FundamentalSnapshot],
    as_of: datetime,
) -> FundamentalSnapshot | None:
    eligible = [record for record in records if record.observed_at <= as_of]
    return max(eligible, key=lambda record: record.observed_at, default=None)


def _latest_events_by_type(
    records: Iterable[EventRecord],
    as_of: datetime,
) -> list[EventRecord]:
    latest: dict[str, EventRecord] = {}
    for record in records:
        if record.observed_at > as_of:
            continue
        current = latest.get(record.event_type)
        if current is None or (record.observed_at, record.source_id) > (
            current.observed_at,
            current.source_id,
        ):
            latest[record.event_type] = record
    return [latest[event_type] for event_type in sorted(latest)]


def build_feature_row(
    *,
    symbol: str,
    as_of: datetime,
    bars: Iterable[MarketBar],
    events: Iterable[EventRecord] = (),
    fundamentals: Iterable[FundamentalSnapshot] = (),
) -> FeatureRow:
    if as_of.tzinfo is None or as_of.utcoffset() is None:
        raise ValueError("as_of must be timezone-aware")

    available_bars = sorted(
        (bar for bar in bars if bar.symbol == symbol and bar.observed_at <= as_of),
        key=lambda bar: bar.observed_at,
    )
    if not available_bars:
        raise ValueError("at least one point-in-time market bar is required")

    latest = available_bars[-1]
    previous = available_bars[-2] if len(available_bars) >= 2 else None
    features: dict[str, float] = {
        "market.close": latest.close,
        "market.range_pct": (latest.high - latest.low) / latest.close,
        "market.volume": latest.volume,
    }
    if previous is not None:
        features["market.return_1"] = (latest.close / previous.close) - 1.0
        features["market.volume_change_1"] = (
            (latest.volume / previous.volume) - 1.0 if previous.volume > 0 else 0.0
        )

    provenance = [f"market:{latest.source}:{latest.observed_at.isoformat()}"]

    latest_events = _latest_events_by_type(
        (event for event in events if event.symbol == symbol), as_of
    )
    for event in latest_events:
        for name, value in sorted(event.numeric_features.items()):
            features[f"event.{event.event_type}.{name}"] = value
        provenance.append(f"event:{event.source}:{event.source_id}")
        provenance.extend(f"event-lineage:{lineage}" for lineage in event.lineage_ids)

    latest_fundamental = _latest_by_observed_at(
        (snapshot for snapshot in fundamentals if snapshot.symbol == symbol), as_of
    )
    if latest_fundamental is not None:
        for name, value in sorted(latest_fundamental.metrics.items()):
            features[f"fundamental.{name}"] = value
        provenance.append(
            f"fundamental:{latest_fundamental.source}:{latest_fundamental.source_id}"
        )

    data_cutoff = max(
        [latest.observed_at]
        + [event.observed_at for event in latest_events]
        + ([latest_fundamental.observed_at] if latest_fundamental is not None else [])
    )
    payload = {
        "feature_schema_version": FEATURE_SCHEMA_VERSION,
        "symbol": symbol,
        "as_of": as_of.isoformat(),
        "data_cutoff": data_cutoff.isoformat(),
        "features": features,
        "provenance": provenance,
    }
    return FeatureRow(**payload, fingerprint=canonical_fingerprint(payload))
