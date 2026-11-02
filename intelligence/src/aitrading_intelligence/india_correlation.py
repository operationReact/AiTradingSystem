from __future__ import annotations

import math
import re
from collections import defaultdict
from collections.abc import Iterable, Sequence
from datetime import datetime
from itertools import combinations
from statistics import fmean, median, pstdev
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_context import IndiaContextObservation, IndiaUniverseSnapshot

INDIA_CORRELATION_CONTRACT_VERSION = "india-correlation-state.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Symbol = Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class IndiaCorrelationConfig(BaseModel):
    """Versioned policy for causal, time-aligned pairwise return correlation."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    window_observations: Annotated[int, Field(ge=5, le=500)] = 20
    minimum_pair_observations: Annotated[int, Field(ge=3, le=500)] = 5
    minimum_market_symbols: Annotated[int, Field(ge=3, le=10_000)] = 3
    minimum_sector_symbols: Annotated[int, Field(ge=2, le=10_000)] = 2
    high_correlation_threshold: Annotated[float, Field(ge=-1.0, le=1.0)] = 0.70
    zero_variance_epsilon: Annotated[float, Field(gt=0.0, le=0.01)] = 1e-12

    @model_validator(mode="after")
    def validate_config(self) -> IndiaCorrelationConfig:
        if self.minimum_pair_observations > self.window_observations:
            raise ValueError(
                "minimum_pair_observations cannot exceed window_observations"
            )
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class IndiaReturnObservation(BaseModel):
    """One instrument return known to the system at a specific availability time."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    exchange: Literal["NSE", "BSE"] = "NSE"
    symbol: Symbol
    event_at: datetime
    available_at: datetime
    return_value: float
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

    @field_validator("return_value")
    @classmethod
    def return_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("return_value must be finite")
        return value

    @model_validator(mode="after")
    def validate_observation(self) -> IndiaReturnObservation:
        if self.available_at < self.event_at:
            raise ValueError("available_at cannot precede event_at")
        if len(set(self.quality_flags)) != len(self.quality_flags):
            raise ValueError("quality_flags must be unique")
        return self


class PairCorrelationEvidence(BaseModel):
    """Deterministic pair-level evidence retained for correlation-state provenance."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    left_symbol: Symbol
    right_symbol: Symbol
    common_observations: Annotated[int, Field(ge=3, le=500)]
    correlation: Annotated[float, Field(ge=-1.0, le=1.0)]
    data_cutoff: datetime
    source_fingerprints: list[Hash64] = Field(min_length=2, max_length=1_000)
    pair_fingerprint: Hash64

    @field_validator("data_cutoff")
    @classmethod
    def cutoff_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @model_validator(mode="after")
    def validate_pair(self) -> PairCorrelationEvidence:
        if self.left_symbol >= self.right_symbol:
            raise ValueError("pair symbols must be strictly ordered")
        if len(set(self.source_fingerprints)) != len(self.source_fingerprints):
            raise ValueError("pair source fingerprints must be unique")
        return self


class CorrelationAggregate(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    symbol_count: Annotated[int, Field(ge=2)]
    pair_count: Annotated[int, Field(ge=1)]
    mean_pairwise_correlation: Annotated[float, Field(ge=-1.0, le=1.0)]
    median_pairwise_correlation: Annotated[float, Field(ge=-1.0, le=1.0)]
    correlation_dispersion: Annotated[float, Field(ge=0.0, le=2.0)]
    high_correlation_pair_fraction: Annotated[float, Field(ge=0.0, le=1.0)]


class IndiaCorrelationSnapshot(BaseModel):
    """Research-only market and sector correlation state at one decision time."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-correlation-state.v1"] = (
        INDIA_CORRELATION_CONTRACT_VERSION
    )
    exchange: Literal["NSE", "BSE"]
    as_of: datetime
    data_cutoff: datetime
    universe_snapshot_fingerprint: Hash64
    config_fingerprint: Hash64
    market: CorrelationAggregate
    sectors: dict[str, CorrelationAggregate] = Field(default_factory=dict, max_length=256)
    pair_evidence: list[PairCorrelationEvidence] = Field(min_length=1, max_length=100_000)
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_CONTEXT_ONLY"] = "RESEARCH_CONTEXT_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> IndiaCorrelationSnapshot:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        if list(self.sectors) != sorted(self.sectors):
            raise ValueError("sector aggregates must be deterministically ordered")
        pair_ids = [item.pair_fingerprint for item in self.pair_evidence]
        if len(set(pair_ids)) != len(pair_ids):
            raise ValueError("pair evidence fingerprints must be unique")
        return self

    def to_context_observations(self) -> list[IndiaContextObservation]:
        """Expose correlation state through the canonical India context contract."""

        suffix = f"{self.config_window_label}"
        values = {
            f"breadth.{self.exchange}.avg_pairwise_corr_{suffix}": (
                self.market.mean_pairwise_correlation
            ),
            f"breadth.{self.exchange}.median_pairwise_corr_{suffix}": (
                self.market.median_pairwise_correlation
            ),
            f"breadth.{self.exchange}.pairwise_corr_dispersion_{suffix}": (
                self.market.correlation_dispersion
            ),
            f"breadth.{self.exchange}.high_corr_pair_fraction_{suffix}": (
                self.market.high_correlation_pair_fraction
            ),
        }
        for sector, aggregate in self.sectors.items():
            token = _context_token(sector)
            values[f"sector.{token}.avg_pairwise_corr_{suffix}"] = (
                aggregate.mean_pairwise_correlation
            )
            values[f"sector.{token}.high_corr_pair_fraction_{suffix}"] = (
                aggregate.high_correlation_pair_fraction
            )

        observations: list[IndiaContextObservation] = []
        for context_key, value in sorted(values.items()):
            source_id = canonical_fingerprint(
                {
                    "correlation_snapshot": self.snapshot_fingerprint,
                    "context_key": context_key,
                }
            )
            observations.append(
                IndiaContextObservation(
                    context_key=context_key,
                    event_at=self.data_cutoff,
                    available_at=self.data_cutoff,
                    value=value,
                    source=INDIA_CORRELATION_CONTRACT_VERSION,
                    source_id=source_id,
                )
            )
        return observations

    @property
    def config_window_label(self) -> str:
        # The exact window lives in config_fingerprint; the context key uses an explicit
        # stable semantic label so downstream feature names remain versioned and readable.
        return "aligned"


def _context_token(value: str) -> str:
    token = re.sub(r"[^A-Z0-9&._-]+", "_", value.upper()).strip("_")
    return token or "UNKNOWN"


def _source_fingerprint(observation: IndiaReturnObservation) -> str:
    return canonical_fingerprint(observation.model_dump(mode="json"))


def _latest_by_symbol_event(
    observations: Iterable[IndiaReturnObservation],
    *,
    as_of: datetime,
    exchange: Literal["NSE", "BSE"],
    allowed_symbols: set[str],
) -> dict[str, dict[datetime, IndiaReturnObservation]]:
    latest: dict[tuple[str, datetime], IndiaReturnObservation] = {}
    for observation in observations:
        if observation.exchange != exchange:
            continue
        if observation.symbol not in allowed_symbols:
            continue
        if observation.available_at > as_of:
            continue
        key = (observation.symbol, observation.event_at)
        current = latest.get(key)
        if current is None or (
            observation.available_at,
            observation.source_id,
        ) > (
            current.available_at,
            current.source_id,
        ):
            latest[key] = observation

    by_symbol: dict[str, dict[datetime, IndiaReturnObservation]] = defaultdict(dict)
    for (symbol, event_at), observation in latest.items():
        by_symbol[symbol][event_at] = observation
    return dict(by_symbol)


def _pearson(values_a: Sequence[float], values_b: Sequence[float], epsilon: float) -> float | None:
    mean_a = fmean(values_a)
    mean_b = fmean(values_b)
    centered_a = [value - mean_a for value in values_a]
    centered_b = [value - mean_b for value in values_b]
    sum_sq_a = sum(value * value for value in centered_a)
    sum_sq_b = sum(value * value for value in centered_b)
    if sum_sq_a <= epsilon or sum_sq_b <= epsilon:
        return None
    covariance = sum(
        left * right for left, right in zip(centered_a, centered_b, strict=True)
    )
    correlation = covariance / math.sqrt(sum_sq_a * sum_sq_b)
    return max(-1.0, min(1.0, correlation))


def _pair_evidence(
    left_symbol: str,
    right_symbol: str,
    series: dict[str, dict[datetime, IndiaReturnObservation]],
    config: IndiaCorrelationConfig,
) -> PairCorrelationEvidence | None:
    common = sorted(set(series[left_symbol]) & set(series[right_symbol]))
    if len(common) < config.minimum_pair_observations:
        return None
    selected = common[-config.window_observations :]
    left = [series[left_symbol][timestamp] for timestamp in selected]
    right = [series[right_symbol][timestamp] for timestamp in selected]
    correlation = _pearson(
        [item.return_value for item in left],
        [item.return_value for item in right],
        config.zero_variance_epsilon,
    )
    if correlation is None:
        return None

    source_fingerprints = sorted(
        {
            *(_source_fingerprint(item) for item in left),
            *(_source_fingerprint(item) for item in right),
        }
    )
    cutoff = max(item.available_at for item in [*left, *right])
    payload = {
        "left_symbol": left_symbol,
        "right_symbol": right_symbol,
        "common_observations": len(selected),
        "correlation": correlation,
        "data_cutoff": cutoff.isoformat(),
        "source_fingerprints": source_fingerprints,
    }
    return PairCorrelationEvidence(
        **payload,
        data_cutoff=cutoff,
        pair_fingerprint=canonical_fingerprint(payload),
    )


def _aggregate(
    pairs: Sequence[PairCorrelationEvidence],
    *,
    threshold: float,
) -> CorrelationAggregate:
    correlations = [pair.correlation for pair in pairs]
    symbols = {pair.left_symbol for pair in pairs} | {pair.right_symbol for pair in pairs}
    return CorrelationAggregate(
        symbol_count=len(symbols),
        pair_count=len(pairs),
        mean_pairwise_correlation=fmean(correlations),
        median_pairwise_correlation=median(correlations),
        correlation_dispersion=pstdev(correlations),
        high_correlation_pair_fraction=(
            sum(value >= threshold for value in correlations) / len(correlations)
        ),
    )


def build_india_correlation_snapshot(
    *,
    universe: IndiaUniverseSnapshot,
    as_of: datetime,
    observations: Iterable[IndiaReturnObservation],
    exchange: Literal["NSE", "BSE"] = "NSE",
    config: IndiaCorrelationConfig | None = None,
) -> IndiaCorrelationSnapshot:
    """Build pairwise market/sector correlation using only causally available returns."""

    _require_aware(as_of)
    config = config or IndiaCorrelationConfig()
    if universe.as_of != as_of:
        raise ValueError("universe snapshot must be built for the same decision time")
    if universe.data_cutoff > as_of:
        raise ValueError("universe data cutoff cannot exceed decision time")

    members = {
        member.symbol: member for member in universe.members if member.exchange == exchange
    }
    if len(members) < config.minimum_market_symbols:
        raise ValueError("universe has fewer symbols than the configured market minimum")

    series = _latest_by_symbol_event(
        observations,
        as_of=as_of,
        exchange=exchange,
        allowed_symbols=set(members),
    )
    eligible_symbols = sorted(
        symbol
        for symbol, values in series.items()
        if len(values) >= config.minimum_pair_observations
    )
    if len(eligible_symbols) < config.minimum_market_symbols:
        raise ValueError("too few causally observed symbols for market correlation")

    market_pairs = [
        evidence
        for left_symbol, right_symbol in combinations(eligible_symbols, 2)
        if (
            evidence := _pair_evidence(
                left_symbol,
                right_symbol,
                series,
                config,
            )
        )
        is not None
    ]
    market_symbol_count = len(
        {item.left_symbol for item in market_pairs}
        | {item.right_symbol for item in market_pairs}
    )
    if market_symbol_count < config.minimum_market_symbols or not market_pairs:
        raise ValueError("insufficient non-degenerate market pairs for correlation state")

    sector_symbols: dict[str, list[str]] = defaultdict(list)
    for symbol in eligible_symbols:
        sector_symbols[members[symbol].sector].append(symbol)

    sectors: dict[str, CorrelationAggregate] = {}
    for sector in sorted(sector_symbols):
        symbols = sorted(sector_symbols[sector])
        if len(symbols) < config.minimum_sector_symbols:
            continue
        pairs = [
            evidence
            for left_symbol, right_symbol in combinations(symbols, 2)
            if (
                evidence := _pair_evidence(
                    left_symbol,
                    right_symbol,
                    series,
                    config,
                )
            )
            is not None
        ]
        sector_pair_symbols = {
            symbol
            for pair in pairs
            for symbol in (pair.left_symbol, pair.right_symbol)
        }
        if len(sector_pair_symbols) >= config.minimum_sector_symbols and pairs:
            sectors[sector] = _aggregate(
                pairs,
                threshold=config.high_correlation_threshold,
            )

    all_pair_evidence = sorted(
        market_pairs,
        key=lambda item: (item.left_symbol, item.right_symbol, item.pair_fingerprint),
    )
    cutoff = max(
        [universe.data_cutoff] + [item.data_cutoff for item in all_pair_evidence]
    )
    market = _aggregate(
        all_pair_evidence,
        threshold=config.high_correlation_threshold,
    )
    payload = {
        "contract_version": INDIA_CORRELATION_CONTRACT_VERSION,
        "exchange": exchange,
        "as_of": as_of.isoformat(),
        "data_cutoff": cutoff.isoformat(),
        "universe_snapshot_fingerprint": universe.snapshot_fingerprint,
        "config_fingerprint": config.fingerprint,
        "market": market.model_dump(mode="json"),
        "sectors": {
            sector: sectors[sector].model_dump(mode="json") for sector in sorted(sectors)
        },
        "pair_evidence": [item.model_dump(mode="json") for item in all_pair_evidence],
        "status": "RESEARCH_CONTEXT_ONLY",
        "can_authorize_capital": False,
    }
    return IndiaCorrelationSnapshot(
        exchange=exchange,
        as_of=as_of,
        data_cutoff=cutoff,
        universe_snapshot_fingerprint=universe.snapshot_fingerprint,
        config_fingerprint=config.fingerprint,
        market=market,
        sectors={sector: sectors[sector] for sector in sorted(sectors)},
        pair_evidence=all_pair_evidence,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
