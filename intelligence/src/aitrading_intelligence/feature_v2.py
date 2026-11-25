from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .features import FeatureRow
from .fingerprints import canonical_fingerprint
from .india_context import IndiaMarketContextSnapshot, IndiaUniverseSnapshot

FEATURE_SCHEMA_V2_VERSION = "features.market.v2"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Symbol = Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class FeatureRowV2(BaseModel):
    """Point-in-time feature row bound to exact India universe/context evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    feature_schema_version: Literal["features.market.v2"] = FEATURE_SCHEMA_V2_VERSION
    instrument_id: Annotated[str, Field(min_length=1, max_length=120)]
    exchange: Literal["NSE", "BSE"]
    symbol: Symbol
    sector: Annotated[str, Field(min_length=1, max_length=120)]
    index_memberships: list[Annotated[str, Field(min_length=1, max_length=120)]] = Field(
        default_factory=list,
        max_length=32,
    )
    as_of: datetime
    data_cutoff: datetime
    base_feature_row_fingerprint: Hash64
    universe_snapshot_fingerprint: Hash64
    market_context_snapshot_fingerprint: Hash64
    features: dict[str, float] = Field(min_length=1, max_length=1024)
    missing_features: list[Annotated[str, Field(min_length=1, max_length=240)]] = Field(
        default_factory=list,
        max_length=512,
    )
    quality_flags: list[Annotated[str, Field(min_length=1, max_length=240)]] = Field(
        default_factory=list,
        max_length=512,
    )
    provenance: list[Annotated[str, Field(min_length=1, max_length=320)]] = Field(
        min_length=3,
        max_length=512,
    )
    fingerprint: Hash64
    status: Literal["RESEARCH_FEATURE_ONLY"] = "RESEARCH_FEATURE_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @field_validator("features")
    @classmethod
    def features_must_be_finite_and_sorted(
        cls,
        value: dict[str, float],
    ) -> dict[str, float]:
        if any(not math.isfinite(number) for number in value.values()):
            raise ValueError("feature values must be finite")
        if list(value) != sorted(value):
            raise ValueError("features must be deterministically ordered")
        return value

    @field_validator("index_memberships", "missing_features", "quality_flags", "provenance")
    @classmethod
    def list_fields_must_be_unique(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("list-valued evidence fields must be unique")
        return value

    @model_validator(mode="after")
    def validate_row(self) -> FeatureRowV2:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        if self.index_memberships != sorted(self.index_memberships):
            raise ValueError("index_memberships must be deterministically ordered")
        if self.missing_features != sorted(self.missing_features):
            raise ValueError("missing_features must be deterministically ordered")
        if self.quality_flags != sorted(self.quality_flags):
            raise ValueError("quality_flags must be deterministically ordered")
        if self.provenance != sorted(self.provenance):
            raise ValueError("provenance must be deterministically ordered")
        return self


def build_feature_row_v2(
    *,
    base_row: FeatureRow,
    universe: IndiaUniverseSnapshot,
    context: IndiaMarketContextSnapshot,
    exchange: Literal["NSE", "BSE"] = "NSE",
    expected_context_keys: Sequence[str] = (),
    required_context_keys: Sequence[str] = (),
) -> FeatureRowV2:
    """Enrich a frozen V1 row with exact point-in-time India context evidence."""

    if universe.as_of != base_row.as_of:
        raise ValueError("universe snapshot must match the feature decision time")
    if context.as_of != base_row.as_of:
        raise ValueError("market context snapshot must match the feature decision time")
    if base_row.data_cutoff > base_row.as_of:
        raise ValueError("base feature row cutoff cannot exceed decision time")
    if universe.data_cutoff > base_row.as_of:
        raise ValueError("universe cutoff cannot exceed decision time")
    if context.data_cutoff > base_row.as_of:
        raise ValueError("market context cutoff cannot exceed decision time")

    members = [
        member
        for member in universe.members
        if member.exchange == exchange and member.symbol == base_row.symbol
    ]
    if len(members) != 1:
        raise ValueError("feature symbol must resolve to exactly one point-in-time universe member")
    member = members[0]

    expected = set(expected_context_keys)
    required = set(required_context_keys)
    if len(expected) != len(expected_context_keys):
        raise ValueError("expected_context_keys must be unique")
    if len(required) != len(required_context_keys):
        raise ValueError("required_context_keys must be unique")
    missing_required = sorted(required.difference(context.metrics))
    if missing_required:
        raise ValueError(
            "required context features are missing: " + ",".join(missing_required)
        )

    merged_features = dict(base_row.features)
    for key, value in context.metrics.items():
        merged_key = f"context.{key}"
        if merged_key in merged_features:
            raise ValueError(f"context feature collides with base feature: {merged_key}")
        merged_features[merged_key] = value
    ordered_features = dict(sorted(merged_features.items()))

    missing_features = sorted(
        f"context.{key}" for key in expected.difference(context.metrics)
    )
    quality_flags = sorted(
        f"context.{key}:{flag}"
        for key, flags in context.quality_flags.items()
        for flag in flags
    )
    provenance = sorted(
        {
            f"feature-row-v1:{base_row.fingerprint}",
            f"universe:{universe.snapshot_fingerprint}",
            f"market-context:{context.snapshot_fingerprint}",
            *[f"base:{item}" for item in base_row.provenance],
            *[
                f"context:{key}:{context.provenance[key]}"
                for key in sorted(context.provenance)
            ],
        }
    )
    data_cutoff = max(
        base_row.data_cutoff,
        universe.data_cutoff,
        context.data_cutoff,
    )
    payload = {
        "feature_schema_version": FEATURE_SCHEMA_V2_VERSION,
        "instrument_id": member.instrument_id,
        "exchange": member.exchange,
        "symbol": member.symbol,
        "sector": member.sector,
        "index_memberships": sorted(member.index_memberships),
        "as_of": base_row.as_of.isoformat(),
        "data_cutoff": data_cutoff.isoformat(),
        "base_feature_row_fingerprint": base_row.fingerprint,
        "universe_snapshot_fingerprint": universe.snapshot_fingerprint,
        "market_context_snapshot_fingerprint": context.snapshot_fingerprint,
        "features": ordered_features,
        "missing_features": missing_features,
        "quality_flags": quality_flags,
        "provenance": provenance,
        "status": "RESEARCH_FEATURE_ONLY",
        "can_authorize_capital": False,
    }
    return FeatureRowV2(
        **payload,
        fingerprint=canonical_fingerprint(payload),
    )
