from __future__ import annotations

import math
from collections.abc import Iterable
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

INDIA_UNIVERSE_CONTRACT_VERSION = "india-universe-snapshot.v1"
INDIA_CONTEXT_CONTRACT_VERSION = "india-market-context.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Symbol = Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
ContextKey = Annotated[
    str,
    Field(pattern=r"^(benchmark|sector|volatility|breadth|liquidity)\.[A-Z0-9&._-]+\.[a-z0-9_.-]+$"),
]


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class IndiaUniverseMembership(BaseModel):
    """Point-in-time membership evidence for one tradable Indian instrument."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    instrument_id: Annotated[str, Field(min_length=1, max_length=120)]
    exchange: Literal["NSE", "BSE"] = "NSE"
    symbol: Symbol
    series: Annotated[str, Field(min_length=1, max_length=24)] = "EQ"
    sector: Annotated[str, Field(min_length=1, max_length=120)]
    index_memberships: list[Annotated[str, Field(min_length=1, max_length=120)]] = Field(
        default_factory=list,
        max_length=32,
    )
    effective_from: datetime
    effective_to: datetime | None = None
    available_at: datetime
    source: Annotated[str, Field(min_length=1, max_length=80)]
    source_id: Annotated[str, Field(min_length=1, max_length=240)]

    @field_validator("effective_from", "effective_to", "available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _require_aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_membership(self) -> IndiaUniverseMembership:
        if self.effective_to is not None and self.effective_to <= self.effective_from:
            raise ValueError("effective_to must be after effective_from")
        if len(set(self.index_memberships)) != len(self.index_memberships):
            raise ValueError("index_memberships must be unique")
        return self


class IndiaUniverseSnapshot(BaseModel):
    """Causal universe snapshot. It is research evidence and never capital authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-universe-snapshot.v1"] = INDIA_UNIVERSE_CONTRACT_VERSION
    universe_id: Annotated[str, Field(min_length=1, max_length=120)]
    as_of: datetime
    data_cutoff: datetime
    members: list[IndiaUniverseMembership] = Field(min_length=1, max_length=10_000)
    source_versions: dict[str, str] = Field(default_factory=dict, max_length=64)
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_CONTEXT_ONLY"] = "RESEARCH_CONTEXT_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> IndiaUniverseSnapshot:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        identities = [(member.exchange, member.instrument_id) for member in self.members]
        if len(set(identities)) != len(identities):
            raise ValueError("snapshot members must have unique exchange/instrument identities")
        ordered = sorted(
            identities,
            key=lambda item: (item[0], item[1]),
        )
        if identities != ordered:
            raise ValueError("snapshot members must be deterministically ordered")
        return self


class IndiaContextObservation(BaseModel):
    """One point-in-time market-context observation such as NIFTY, sector or India VIX."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    context_key: ContextKey
    event_at: datetime
    available_at: datetime
    value: float
    source: Annotated[str, Field(min_length=1, max_length=80)]
    source_id: Annotated[str, Field(min_length=1, max_length=240)]
    quality_flags: list[Annotated[str, Field(min_length=1, max_length=80)]] = Field(
        default_factory=list,
        max_length=32,
    )

    @field_validator("event_at", "available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @field_validator("value")
    @classmethod
    def value_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("context value must be finite")
        return value

    @model_validator(mode="after")
    def validate_observation(self) -> IndiaContextObservation:
        if self.available_at < self.event_at:
            raise ValueError("available_at cannot precede event_at")
        if len(set(self.quality_flags)) != len(self.quality_flags):
            raise ValueError("quality_flags must be unique")
        return self


class IndiaMarketContextSnapshot(BaseModel):
    """Latest causally available India benchmark/sector/VIX/breadth/liquidity context."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-market-context.v1"] = INDIA_CONTEXT_CONTRACT_VERSION
    as_of: datetime
    data_cutoff: datetime
    metrics: dict[ContextKey, float] = Field(min_length=1, max_length=512)
    provenance: dict[ContextKey, str] = Field(min_length=1, max_length=512)
    quality_flags: dict[ContextKey, list[str]] = Field(default_factory=dict, max_length=512)
    source_versions: dict[str, str] = Field(default_factory=dict, max_length=64)
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_CONTEXT_ONLY"] = "RESEARCH_CONTEXT_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> IndiaMarketContextSnapshot:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        if set(self.metrics) != set(self.provenance):
            raise ValueError("every context metric must have matching provenance")
        if not set(self.quality_flags).issubset(self.metrics):
            raise ValueError("quality_flags cannot reference an absent metric")
        if any(not math.isfinite(value) for value in self.metrics.values()):
            raise ValueError("context metrics must be finite")
        if list(self.metrics) != sorted(self.metrics):
            raise ValueError("context metrics must be deterministically ordered")
        return self


def build_india_universe_snapshot(
    *,
    universe_id: str,
    as_of: datetime,
    memberships: Iterable[IndiaUniverseMembership],
    source_versions: dict[str, str] | None = None,
) -> IndiaUniverseSnapshot:
    """Build the universe using only membership evidence available by ``as_of``."""

    _require_aware(as_of)
    latest: dict[tuple[str, str], IndiaUniverseMembership] = {}
    for membership in memberships:
        if membership.available_at > as_of:
            continue
        if membership.effective_from > as_of:
            continue
        if membership.effective_to is not None and as_of >= membership.effective_to:
            continue
        identity = (membership.exchange, membership.instrument_id)
        current = latest.get(identity)
        if current is None or (
            membership.available_at,
            membership.source_id,
        ) > (
            current.available_at,
            current.source_id,
        ):
            latest[identity] = membership

    if not latest:
        raise ValueError("no causally available active universe members")

    ordered_members = [latest[identity] for identity in sorted(latest)]
    cutoff = max(member.available_at for member in ordered_members)
    versions = dict(sorted((source_versions or {}).items()))
    payload = {
        "contract_version": INDIA_UNIVERSE_CONTRACT_VERSION,
        "universe_id": universe_id,
        "as_of": as_of.isoformat(),
        "data_cutoff": cutoff.isoformat(),
        "members": [member.model_dump(mode="json") for member in ordered_members],
        "source_versions": versions,
        "status": "RESEARCH_CONTEXT_ONLY",
        "can_authorize_capital": False,
    }
    return IndiaUniverseSnapshot(
        universe_id=universe_id,
        as_of=as_of,
        data_cutoff=cutoff,
        members=ordered_members,
        source_versions=versions,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )


def build_india_market_context_snapshot(
    *,
    as_of: datetime,
    observations: Iterable[IndiaContextObservation],
    source_versions: dict[str, str] | None = None,
) -> IndiaMarketContextSnapshot:
    """Select the latest version of each context metric that was available by ``as_of``."""

    _require_aware(as_of)
    latest: dict[str, IndiaContextObservation] = {}
    for observation in observations:
        if observation.available_at > as_of:
            continue
        current = latest.get(observation.context_key)
        if current is None or (
            observation.available_at,
            observation.event_at,
            observation.source_id,
        ) > (
            current.available_at,
            current.event_at,
            current.source_id,
        ):
            latest[observation.context_key] = observation

    if not latest:
        raise ValueError("no causally available India market context observations")

    keys = sorted(latest)
    metrics = {key: latest[key].value for key in keys}
    provenance = {
        key: f"{latest[key].source}:{latest[key].source_id}"
        for key in keys
    }
    quality_flags = {
        key: list(latest[key].quality_flags)
        for key in keys
        if latest[key].quality_flags
    }
    cutoff = max(latest[key].available_at for key in keys)
    versions = dict(sorted((source_versions or {}).items()))
    payload = {
        "contract_version": INDIA_CONTEXT_CONTRACT_VERSION,
        "as_of": as_of.isoformat(),
        "data_cutoff": cutoff.isoformat(),
        "metrics": metrics,
        "provenance": provenance,
        "quality_flags": quality_flags,
        "source_versions": versions,
        "status": "RESEARCH_CONTEXT_ONLY",
        "can_authorize_capital": False,
    }
    return IndiaMarketContextSnapshot(
        as_of=as_of,
        data_cutoff=cutoff,
        metrics=metrics,
        provenance=provenance,
        quality_flags=quality_flags,
        source_versions=versions,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
