from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)

DATASET_SCHEMA_VERSION = "dataset.v1"
FEATURE_SCHEMA_VERSION = "features.market.v1"


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class MarketBar(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    interval: Literal["1m", "5m", "15m", "30m", "1h", "1d"]
    observed_at: datetime
    open: Annotated[float, Field(gt=0)]
    high: Annotated[float, Field(gt=0)]
    low: Annotated[float, Field(gt=0)]
    close: Annotated[float, Field(gt=0)]
    volume: Annotated[float, Field(ge=0)]
    source: Annotated[str, Field(min_length=1, max_length=80)]

    @field_validator("observed_at")
    @classmethod
    def observed_at_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @model_validator(mode="after")
    def validate_ohlc(self) -> MarketBar:
        if self.low > min(self.open, self.close) or self.high < max(self.open, self.close):
            raise ValueError("OHLC values are inconsistent")
        if self.low > self.high:
            raise ValueError("low cannot exceed high")
        return self


class EventRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    event_type: Annotated[str, Field(min_length=1, max_length=80)]
    observed_at: datetime
    effective_at: datetime
    source_id: Annotated[str, Field(min_length=1, max_length=200)]
    source: Annotated[str, Field(min_length=1, max_length=80)]
    numeric_features: dict[str, float] = Field(default_factory=dict, max_length=128)

    @field_validator("observed_at", "effective_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)


class FundamentalSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    observed_at: datetime
    period_end: datetime
    source_id: Annotated[str, Field(min_length=1, max_length=200)]
    source: Annotated[str, Field(min_length=1, max_length=80)]
    metrics: dict[str, float] = Field(default_factory=dict, max_length=256)

    @field_validator("observed_at", "period_end")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)


class DatasetManifest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    dataset_schema_version: Literal["dataset.v1"] = DATASET_SCHEMA_VERSION
    feature_schema_version: Literal["features.market.v1"] = FEATURE_SCHEMA_VERSION
    dataset_version: Annotated[str, Field(min_length=1, max_length=120)]
    created_at: datetime
    data_cutoff: datetime
    code_sha: Annotated[str, Field(pattern=r"^[a-f0-9]{40}$")]
    source_versions: dict[str, str] = Field(default_factory=dict, max_length=64)
    row_count: Annotated[int, Field(ge=0)]
    fingerprint: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]

    @field_validator("created_at", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)
