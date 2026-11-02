from __future__ import annotations

import math
from collections import defaultdict
from collections.abc import Iterable
from datetime import datetime
from statistics import fmean, median, pstdev
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_context import (
    IndiaContextObservation,
    IndiaUniverseSnapshot,
)

INDIA_CROSS_SECTION_CONTRACT_VERSION = "india-cross-section.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Symbol = Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class IndiaCrossSectionConfig(BaseModel):
    """Versioned acceptance policy for a causal cross-sectional snapshot."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_members: Annotated[int, Field(ge=3, le=10_000)] = 3
    minimum_coverage_fraction: Annotated[float, Field(gt=0.0, le=1.0)] = 0.60
    unchanged_return_epsilon: Annotated[float, Field(ge=0.0, le=0.01)] = 1e-12

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class IndiaCrossSectionMemberObservation(BaseModel):
    """Point-in-time per-instrument inputs used to derive breadth/liquidity context."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    exchange: Literal["NSE", "BSE"] = "NSE"
    symbol: Symbol
    event_at: datetime
    available_at: datetime
    return_1: float
    adv_notional: Annotated[float, Field(ge=0.0)]
    spread_bps: Annotated[float, Field(ge=0.0, le=10_000.0)]
    relative_volume: Annotated[float, Field(ge=0.0)]
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

    @field_validator("return_1", "adv_notional", "spread_bps", "relative_volume")
    @classmethod
    def values_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("cross-sectional values must be finite")
        return value

    @model_validator(mode="after")
    def validate_observation(self) -> IndiaCrossSectionMemberObservation:
        if self.available_at < self.event_at:
            raise ValueError("available_at cannot precede event_at")
        if len(set(self.quality_flags)) != len(self.quality_flags):
            raise ValueError("quality_flags must be unique")
        return self


class IndiaCrossSectionSnapshot(BaseModel):
    """Research-only causal breadth, dispersion and liquidity evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-cross-section.v1"] = INDIA_CROSS_SECTION_CONTRACT_VERSION
    exchange: Literal["NSE", "BSE"]
    as_of: datetime
    data_cutoff: datetime
    universe_snapshot_fingerprint: Hash64
    config_fingerprint: Hash64
    expected_members: Annotated[int, Field(ge=1)]
    covered_members: Annotated[int, Field(ge=1)]
    coverage_fraction: Annotated[float, Field(gt=0.0, le=1.0)]
    advancing_members: Annotated[int, Field(ge=0)]
    declining_members: Annotated[int, Field(ge=0)]
    unchanged_members: Annotated[int, Field(ge=0)]
    advance_fraction: Annotated[float, Field(ge=0.0, le=1.0)]
    decline_fraction: Annotated[float, Field(ge=0.0, le=1.0)]
    unchanged_fraction: Annotated[float, Field(ge=0.0, le=1.0)]
    net_breadth: Annotated[float, Field(ge=-1.0, le=1.0)]
    return_mean: float
    return_dispersion: Annotated[float, Field(ge=0.0)]
    median_adv_notional: Annotated[float, Field(ge=0.0)]
    median_spread_bps: Annotated[float, Field(ge=0.0)]
    median_relative_volume: Annotated[float, Field(ge=0.0)]
    sector_return_mean: dict[str, float] = Field(default_factory=dict, max_length=256)
    member_source_fingerprints: list[Hash64] = Field(min_length=1, max_length=10_000)
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_CONTEXT_ONLY"] = "RESEARCH_CONTEXT_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @field_validator(
        "return_mean",
        "return_dispersion",
        "median_adv_notional",
        "median_spread_bps",
        "median_relative_volume",
    )
    @classmethod
    def metrics_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("cross-sectional metrics must be finite")
        return value

    @field_validator("sector_return_mean")
    @classmethod
    def sector_metrics_must_be_finite(cls, value: dict[str, float]) -> dict[str, float]:
        if any(not math.isfinite(metric) for metric in value.values()):
            raise ValueError("sector return metrics must be finite")
        if list(value) != sorted(value):
            raise ValueError("sector_return_mean must be deterministically ordered")
        return value

    @model_validator(mode="after")
    def validate_snapshot(self) -> IndiaCrossSectionSnapshot:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        if self.covered_members > self.expected_members:
            raise ValueError("covered_members cannot exceed expected_members")
        breadth_total = (
            self.advancing_members
            + self.declining_members
            + self.unchanged_members
        )
        if breadth_total != self.covered_members:
            raise ValueError("breadth counts must equal covered_members")
        if len(self.member_source_fingerprints) != self.covered_members:
            raise ValueError("member source fingerprints must match covered_members")
        if len(set(self.member_source_fingerprints)) != len(self.member_source_fingerprints):
            raise ValueError("member source fingerprints must be unique")
        return self

    def to_context_observations(self) -> list[IndiaContextObservation]:
        """Expose aggregate evidence through the canonical India context contract."""

        metric_values = {
            f"breadth.{self.exchange}.advance_fraction": self.advance_fraction,
            f"breadth.{self.exchange}.decline_fraction": self.decline_fraction,
            f"breadth.{self.exchange}.net_breadth": self.net_breadth,
            f"breadth.{self.exchange}.return_mean": self.return_mean,
            f"breadth.{self.exchange}.return_dispersion": self.return_dispersion,
            f"liquidity.{self.exchange}.median_adv_notional": self.median_adv_notional,
            f"liquidity.{self.exchange}.median_relative_volume": self.median_relative_volume,
            f"liquidity.{self.exchange}.median_spread_bps": self.median_spread_bps,
        }
        observations: list[IndiaContextObservation] = []
        for context_key, value in sorted(metric_values.items()):
            source_id = canonical_fingerprint(
                {
                    "cross_section_snapshot": self.snapshot_fingerprint,
                    "context_key": context_key,
                }
            )
            observations.append(
                IndiaContextObservation(
                    context_key=context_key,
                    event_at=self.data_cutoff,
                    available_at=self.data_cutoff,
                    value=value,
                    source=INDIA_CROSS_SECTION_CONTRACT_VERSION,
                    source_id=source_id,
                )
            )
        return observations


def _source_fingerprint(observation: IndiaCrossSectionMemberObservation) -> str:
    return canonical_fingerprint(observation.model_dump(mode="json"))


def build_india_cross_section_snapshot(
    *,
    universe: IndiaUniverseSnapshot,
    as_of: datetime,
    observations: Iterable[IndiaCrossSectionMemberObservation],
    exchange: Literal["NSE", "BSE"] = "NSE",
    config: IndiaCrossSectionConfig | None = None,
) -> IndiaCrossSectionSnapshot:
    """Derive breadth/dispersion/liquidity using only evidence available by decision time."""

    _require_aware(as_of)
    config = config or IndiaCrossSectionConfig()
    if universe.as_of != as_of:
        raise ValueError("universe snapshot must be built for the same decision time")
    if universe.data_cutoff > as_of:
        raise ValueError("universe data cutoff cannot exceed decision time")

    members = {
        member.symbol: member
        for member in universe.members
        if member.exchange == exchange
    }
    if len(members) < config.minimum_members:
        raise ValueError("universe has fewer members than the configured minimum")

    latest: dict[str, IndiaCrossSectionMemberObservation] = {}
    for observation in observations:
        if observation.exchange != exchange:
            continue
        if observation.symbol not in members:
            continue
        if observation.available_at > as_of:
            continue
        current = latest.get(observation.symbol)
        if current is None or (
            observation.available_at,
            observation.event_at,
            observation.source_id,
        ) > (
            current.available_at,
            current.event_at,
            current.source_id,
        ):
            latest[observation.symbol] = observation

    if len(latest) < config.minimum_members:
        raise ValueError("causal cross-section has fewer observations than the configured minimum")

    expected_members = len(members)
    covered_members = len(latest)
    coverage_fraction = covered_members / expected_members
    if coverage_fraction < config.minimum_coverage_fraction:
        raise ValueError("cross-sectional coverage is below the configured minimum")

    ordered_symbols = sorted(latest)
    ordered = [latest[symbol] for symbol in ordered_symbols]
    returns = [observation.return_1 for observation in ordered]
    epsilon = config.unchanged_return_epsilon
    advancing = sum(value > epsilon for value in returns)
    declining = sum(value < -epsilon for value in returns)
    unchanged = covered_members - advancing - declining

    sector_returns: dict[str, list[float]] = defaultdict(list)
    for symbol in ordered_symbols:
        sector_returns[members[symbol].sector].append(latest[symbol].return_1)
    sector_return_mean = {
        sector: fmean(sector_returns[sector])
        for sector in sorted(sector_returns)
    }

    source_fingerprints = [_source_fingerprint(observation) for observation in ordered]
    cutoff = max(
        [universe.data_cutoff]
        + [observation.available_at for observation in ordered]
    )
    payload = {
        "contract_version": INDIA_CROSS_SECTION_CONTRACT_VERSION,
        "exchange": exchange,
        "as_of": as_of.isoformat(),
        "data_cutoff": cutoff.isoformat(),
        "universe_snapshot_fingerprint": universe.snapshot_fingerprint,
        "config_fingerprint": config.fingerprint,
        "expected_members": expected_members,
        "covered_members": covered_members,
        "coverage_fraction": coverage_fraction,
        "advancing_members": advancing,
        "declining_members": declining,
        "unchanged_members": unchanged,
        "advance_fraction": advancing / covered_members,
        "decline_fraction": declining / covered_members,
        "unchanged_fraction": unchanged / covered_members,
        "net_breadth": (advancing - declining) / covered_members,
        "return_mean": fmean(returns),
        "return_dispersion": pstdev(returns),
        "median_adv_notional": median(observation.adv_notional for observation in ordered),
        "median_spread_bps": median(observation.spread_bps for observation in ordered),
        "median_relative_volume": median(observation.relative_volume for observation in ordered),
        "sector_return_mean": sector_return_mean,
        "member_source_fingerprints": source_fingerprints,
        "status": "RESEARCH_CONTEXT_ONLY",
        "can_authorize_capital": False,
    }
    return IndiaCrossSectionSnapshot(
        **payload,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
