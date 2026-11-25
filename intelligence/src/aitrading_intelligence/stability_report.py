from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from statistics import mean, median, stdev
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .regime import REGIME_LABELS, RegimeLabel

STABILITY_REPORT_VERSION = "stability-report.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
StabilityState = Literal["EVALUATED", "INCONCLUSIVE"]
StabilityDimension = Literal["REGIME", "LIQUIDITY", "SECTOR"]
LiquidityBucket = Literal["LOW", "MEDIUM", "HIGH"]
LIQUIDITY_BUCKETS: tuple[LiquidityBucket, ...] = ("LOW", "MEDIUM", "HIGH")


class StabilityReportConfigV1(BaseModel):
    """Minimum-evidence policy local to descriptive stability buckets."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    report_version: Literal["stability-report.v1"] = STABILITY_REPORT_VERSION
    minimum_bucket_observations: Annotated[int, Field(ge=2, le=100_000)] = 10
    minimum_evaluated_buckets_per_dimension: Annotated[int, Field(ge=2, le=10)] = 2
    low_liquidity_max_percentile: Annotated[float, Field(gt=0.0, lt=1.0)] = 1.0 / 3.0
    medium_liquidity_max_percentile: Annotated[float, Field(gt=0.0, lt=1.0)] = 2.0 / 3.0

    @model_validator(mode="after")
    def validate_liquidity_boundaries(self) -> StabilityReportConfigV1:
        if self.low_liquidity_max_percentile >= self.medium_liquidity_max_percentile:
            raise ValueError("liquidity percentile boundaries must be strictly increasing")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class StabilityObservationV1(BaseModel):
    """One after-cost research return with exact regime/liquidity/sector lineage."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    report_version: Literal["stability-report.v1"] = STABILITY_REPORT_VERSION
    decision_at: datetime
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    net_return: float
    regime: RegimeLabel
    liquidity_percentile: Annotated[float, Field(gt=0.0, le=1.0)]
    sector: Annotated[str, Field(min_length=1, max_length=160)]
    source_return_fingerprint: Hash64
    source_regime_snapshot_fingerprint: Hash64
    source_sector_liquidity_score_fingerprint: Hash64
    source_liquidity_evidence_fingerprint: Hash64
    observation_fingerprint: Hash64
    status: Literal["RESEARCH_STABILITY_OBSERVATION_ONLY"] = (
        "RESEARCH_STABILITY_OBSERVATION_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_at")
    @classmethod
    def decision_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("stability observation decision_at must be timezone-aware")
        return value

    @field_validator("net_return")
    @classmethod
    def net_return_must_be_valid(cls, value: float) -> float:
        if not math.isfinite(value) or value <= -1.0:
            raise ValueError("stability net_return must be finite and greater than -100%")
        return value

    @field_validator("sector")
    @classmethod
    def sector_must_be_canonical_text(cls, value: str) -> str:
        if value != value.strip():
            raise ValueError("stability sector cannot contain surrounding whitespace")
        return value

    @model_validator(mode="after")
    def validate_observation(self) -> StabilityObservationV1:
        if self.observation_fingerprint != canonical_fingerprint(_observation_payload(self)):
            raise ValueError("stability observation fingerprint mismatch")
        return self


class StabilityBucketV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    dimension: StabilityDimension
    bucket: Annotated[str, Field(min_length=1, max_length=160)]
    state: StabilityState
    reason_codes: list[str]
    observation_count: Annotated[int, Field(ge=0)]
    source_observation_fingerprints: list[Hash64]
    mean_net_return: float | None = None
    median_net_return: float | None = None
    net_return_standard_deviation: Annotated[float | None, Field(ge=0.0)] = None
    positive_return_fraction: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    worst_net_return: float | None = None
    best_net_return: float | None = None
    bucket_fingerprint: Hash64

    @field_validator(
        "mean_net_return",
        "median_net_return",
        "net_return_standard_deviation",
        "positive_return_fraction",
        "worst_net_return",
        "best_net_return",
    )
    @classmethod
    def optional_metrics_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("stability bucket metrics must be finite")
        return value

    @model_validator(mode="after")
    def validate_bucket(self) -> StabilityBucketV1:
        metrics = (
            self.mean_net_return,
            self.median_net_return,
            self.net_return_standard_deviation,
            self.positive_return_fraction,
            self.worst_net_return,
            self.best_net_return,
        )
        if self.observation_count != len(self.source_observation_fingerprints):
            raise ValueError("stability bucket observation count mismatch")
        if self.state == "EVALUATED":
            if self.reason_codes or any(value is None for value in metrics):
                raise ValueError("evaluated stability bucket requires complete metrics")
        elif not self.reason_codes or any(value is not None for value in metrics):
            raise ValueError("inconclusive stability bucket cannot expose metrics")
        if self.bucket_fingerprint != canonical_fingerprint(_bucket_payload(self)):
            raise ValueError("stability bucket fingerprint mismatch")
        return self


class StabilityDimensionReportV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    dimension: StabilityDimension
    state: StabilityState
    reason_codes: list[str]
    observation_count: Annotated[int, Field(ge=0)]
    bucket_count: Annotated[int, Field(ge=0)]
    evaluated_bucket_count: Annotated[int, Field(ge=0)]
    inconclusive_bucket_count: Annotated[int, Field(ge=0)]
    buckets: list[StabilityBucketV1]
    minimum_bucket_mean_net_return: float | None = None
    maximum_bucket_mean_net_return: float | None = None
    bucket_mean_return_spread: Annotated[float | None, Field(ge=0.0)] = None
    dimension_fingerprint: Hash64

    @field_validator(
        "minimum_bucket_mean_net_return",
        "maximum_bucket_mean_net_return",
        "bucket_mean_return_spread",
    )
    @classmethod
    def summary_metrics_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("stability dimension metrics must be finite")
        return value

    @model_validator(mode="after")
    def validate_dimension(self) -> StabilityDimensionReportV1:
        if self.bucket_count != len(self.buckets):
            raise ValueError("stability dimension bucket_count mismatch")
        evaluated = sum(item.state == "EVALUATED" for item in self.buckets)
        if self.evaluated_bucket_count != evaluated:
            raise ValueError("stability dimension evaluated bucket count mismatch")
        if self.inconclusive_bucket_count != self.bucket_count - evaluated:
            raise ValueError("stability dimension inconclusive bucket count mismatch")
        if self.observation_count != sum(item.observation_count for item in self.buckets):
            raise ValueError("stability dimension observations must partition the source")
        metrics = (
            self.minimum_bucket_mean_net_return,
            self.maximum_bucket_mean_net_return,
            self.bucket_mean_return_spread,
        )
        if self.state == "EVALUATED":
            if self.reason_codes or any(value is None for value in metrics):
                raise ValueError("evaluated stability dimension requires summary metrics")
        elif not self.reason_codes or any(value is not None for value in metrics):
            raise ValueError("inconclusive stability dimension cannot expose summary metrics")
        if self.dimension_fingerprint != canonical_fingerprint(_dimension_payload(self)):
            raise ValueError("stability dimension fingerprint mismatch")
        return self


class StabilityReportV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    report_version: Literal["stability-report.v1"] = STABILITY_REPORT_VERSION
    state: StabilityState
    reason_codes: list[str]
    config_fingerprint: Hash64
    observation_count: Annotated[int, Field(ge=0)]
    source_observation_fingerprints: list[Hash64]
    regime: StabilityDimensionReportV1
    liquidity: StabilityDimensionReportV1
    sector: StabilityDimensionReportV1
    report_fingerprint: Hash64
    status: Literal["RESEARCH_STABILITY_REPORT_ONLY"] = "RESEARCH_STABILITY_REPORT_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_report(self) -> StabilityReportV1:
        if self.observation_count != len(self.source_observation_fingerprints):
            raise ValueError("stability report observation count mismatch")
        dimensions = (self.regime, self.liquidity, self.sector)
        if any(item.observation_count != self.observation_count for item in dimensions):
            raise ValueError("each stability dimension must partition all observations")
        expected_state: StabilityState = (
            "EVALUATED" if all(item.state == "EVALUATED" for item in dimensions) else "INCONCLUSIVE"
        )
        if self.state != expected_state:
            raise ValueError("stability report state must reflect every dimension")
        if self.state == "EVALUATED" and self.reason_codes:
            raise ValueError("evaluated stability report cannot contain reason codes")
        if self.state == "INCONCLUSIVE" and not self.reason_codes:
            raise ValueError("inconclusive stability report requires reason codes")
        if self.report_fingerprint != canonical_fingerprint(_report_payload(self)):
            raise ValueError("stability report fingerprint mismatch")
        return self


def _observation_payload(observation: StabilityObservationV1) -> dict[str, object]:
    return {
        "report_version": STABILITY_REPORT_VERSION,
        "decision_at": observation.decision_at.isoformat(),
        "symbol": observation.symbol,
        "net_return": observation.net_return,
        "regime": observation.regime,
        "liquidity_percentile": observation.liquidity_percentile,
        "sector": observation.sector,
        "source_return_fingerprint": observation.source_return_fingerprint,
        "source_regime_snapshot_fingerprint": observation.source_regime_snapshot_fingerprint,
        "source_sector_liquidity_score_fingerprint": (
            observation.source_sector_liquidity_score_fingerprint
        ),
        "source_liquidity_evidence_fingerprint": (
            observation.source_liquidity_evidence_fingerprint
        ),
        "status": "RESEARCH_STABILITY_OBSERVATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def build_stability_observation(
    *,
    decision_at: datetime,
    symbol: str,
    net_return: float,
    regime: RegimeLabel,
    liquidity_percentile: float,
    sector: str,
    source_return_fingerprint: str,
    source_regime_snapshot_fingerprint: str,
    source_sector_liquidity_score_fingerprint: str,
    source_liquidity_evidence_fingerprint: str,
) -> StabilityObservationV1:
    payload = {
        "report_version": STABILITY_REPORT_VERSION,
        "decision_at": decision_at.isoformat(),
        "symbol": symbol,
        "net_return": net_return,
        "regime": regime,
        "liquidity_percentile": liquidity_percentile,
        "sector": sector,
        "source_return_fingerprint": source_return_fingerprint,
        "source_regime_snapshot_fingerprint": source_regime_snapshot_fingerprint,
        "source_sector_liquidity_score_fingerprint": (
            source_sector_liquidity_score_fingerprint
        ),
        "source_liquidity_evidence_fingerprint": source_liquidity_evidence_fingerprint,
        "status": "RESEARCH_STABILITY_OBSERVATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return StabilityObservationV1(
        decision_at=decision_at,
        symbol=symbol,
        net_return=net_return,
        regime=regime,
        liquidity_percentile=liquidity_percentile,
        sector=sector,
        source_return_fingerprint=source_return_fingerprint,
        source_regime_snapshot_fingerprint=source_regime_snapshot_fingerprint,
        source_sector_liquidity_score_fingerprint=(
            source_sector_liquidity_score_fingerprint
        ),
        source_liquidity_evidence_fingerprint=source_liquidity_evidence_fingerprint,
        observation_fingerprint=canonical_fingerprint(payload),
    )


def _liquidity_bucket(
    percentile: float,
    config: StabilityReportConfigV1,
) -> LiquidityBucket:
    if percentile <= config.low_liquidity_max_percentile:
        return "LOW"
    if percentile <= config.medium_liquidity_max_percentile:
        return "MEDIUM"
    return "HIGH"


def _bucket_payload(bucket: StabilityBucketV1) -> dict[str, object]:
    return {
        "dimension": bucket.dimension,
        "bucket": bucket.bucket,
        "state": bucket.state,
        "reason_codes": bucket.reason_codes,
        "observation_count": bucket.observation_count,
        "source_observation_fingerprints": bucket.source_observation_fingerprints,
        "mean_net_return": bucket.mean_net_return,
        "median_net_return": bucket.median_net_return,
        "net_return_standard_deviation": bucket.net_return_standard_deviation,
        "positive_return_fraction": bucket.positive_return_fraction,
        "worst_net_return": bucket.worst_net_return,
        "best_net_return": bucket.best_net_return,
    }


def _evaluate_bucket(
    dimension: StabilityDimension,
    bucket_name: str,
    observations: Sequence[StabilityObservationV1],
    config: StabilityReportConfigV1,
) -> StabilityBucketV1:
    fingerprints = [item.observation_fingerprint for item in observations]
    if len(observations) < config.minimum_bucket_observations:
        payload = {
            "dimension": dimension,
            "bucket": bucket_name,
            "state": "INCONCLUSIVE",
            "reason_codes": ["INSUFFICIENT_BUCKET_OBSERVATIONS"],
            "observation_count": len(observations),
            "source_observation_fingerprints": fingerprints,
            "mean_net_return": None,
            "median_net_return": None,
            "net_return_standard_deviation": None,
            "positive_return_fraction": None,
            "worst_net_return": None,
            "best_net_return": None,
        }
        return StabilityBucketV1(
            **payload,
            bucket_fingerprint=canonical_fingerprint(payload),
        )

    returns = [item.net_return for item in observations]
    payload = {
        "dimension": dimension,
        "bucket": bucket_name,
        "state": "EVALUATED",
        "reason_codes": [],
        "observation_count": len(observations),
        "source_observation_fingerprints": fingerprints,
        "mean_net_return": mean(returns),
        "median_net_return": median(returns),
        "net_return_standard_deviation": stdev(returns),
        "positive_return_fraction": sum(value > 0.0 for value in returns) / len(returns),
        "worst_net_return": min(returns),
        "best_net_return": max(returns),
    }
    return StabilityBucketV1(
        **payload,
        bucket_fingerprint=canonical_fingerprint(payload),
    )


def _dimension_payload(report: StabilityDimensionReportV1) -> dict[str, object]:
    return {
        "dimension": report.dimension,
        "state": report.state,
        "reason_codes": report.reason_codes,
        "observation_count": report.observation_count,
        "bucket_count": report.bucket_count,
        "evaluated_bucket_count": report.evaluated_bucket_count,
        "inconclusive_bucket_count": report.inconclusive_bucket_count,
        "buckets": [item.model_dump(mode="json") for item in report.buckets],
        "minimum_bucket_mean_net_return": report.minimum_bucket_mean_net_return,
        "maximum_bucket_mean_net_return": report.maximum_bucket_mean_net_return,
        "bucket_mean_return_spread": report.bucket_mean_return_spread,
    }


def _build_dimension(
    dimension: StabilityDimension,
    bucket_names: Sequence[str],
    bucketed_observations: dict[str, list[StabilityObservationV1]],
    observation_count: int,
    config: StabilityReportConfigV1,
) -> StabilityDimensionReportV1:
    buckets = [
        _evaluate_bucket(
            dimension,
            bucket_name,
            bucketed_observations.get(bucket_name, []),
            config,
        )
        for bucket_name in bucket_names
    ]
    evaluated = [item for item in buckets if item.state == "EVALUATED"]
    enough_buckets = len(evaluated) >= config.minimum_evaluated_buckets_per_dimension
    if enough_buckets:
        bucket_means = [
            float(item.mean_net_return)
            for item in evaluated
            if item.mean_net_return is not None
        ]
        state: StabilityState = "EVALUATED"
        reasons: list[str] = []
        minimum_mean = min(bucket_means)
        maximum_mean = max(bucket_means)
        spread = maximum_mean - minimum_mean
    else:
        state = "INCONCLUSIVE"
        reasons = ["INSUFFICIENT_EVALUATED_BUCKETS"]
        minimum_mean = None
        maximum_mean = None
        spread = None

    payload = {
        "dimension": dimension,
        "state": state,
        "reason_codes": reasons,
        "observation_count": observation_count,
        "bucket_count": len(buckets),
        "evaluated_bucket_count": len(evaluated),
        "inconclusive_bucket_count": len(buckets) - len(evaluated),
        "buckets": [item.model_dump(mode="json") for item in buckets],
        "minimum_bucket_mean_net_return": minimum_mean,
        "maximum_bucket_mean_net_return": maximum_mean,
        "bucket_mean_return_spread": spread,
    }
    return StabilityDimensionReportV1(
        **payload,
        dimension_fingerprint=canonical_fingerprint(payload),
    )


def _report_payload(report: StabilityReportV1) -> dict[str, object]:
    return {
        "report_version": STABILITY_REPORT_VERSION,
        "state": report.state,
        "reason_codes": report.reason_codes,
        "config_fingerprint": report.config_fingerprint,
        "observation_count": report.observation_count,
        "source_observation_fingerprints": report.source_observation_fingerprints,
        "regime": report.regime.model_dump(mode="json"),
        "liquidity": report.liquidity.model_dump(mode="json"),
        "sector": report.sector.model_dump(mode="json"),
        "status": "RESEARCH_STABILITY_REPORT_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def build_stability_report(
    observations: Sequence[StabilityObservationV1],
    *,
    config: StabilityReportConfigV1 | None = None,
) -> StabilityReportV1:
    """Describe return stability across canonical regime, liquidity and sector buckets."""

    cfg = config or StabilityReportConfigV1()
    ordered = sorted(
        (
            StabilityObservationV1.model_validate(item.model_dump(mode="python"))
            for item in observations
        ),
        key=lambda item: (item.decision_at, item.symbol, item.observation_fingerprint),
    )
    observation_fingerprints = [item.observation_fingerprint for item in ordered]
    if len(set(observation_fingerprints)) != len(observation_fingerprints):
        raise ValueError("stability observations must have unique fingerprints")
    source_returns = [item.source_return_fingerprint for item in ordered]
    if len(set(source_returns)) != len(source_returns):
        raise ValueError("stability observations must have unique source returns")

    regime_groups = {
        label: [item for item in ordered if item.regime == label] for label in REGIME_LABELS
    }
    liquidity_groups = {
        label: [
            item
            for item in ordered
            if _liquidity_bucket(item.liquidity_percentile, cfg) == label
        ]
        for label in LIQUIDITY_BUCKETS
    }
    sector_names = sorted({item.sector for item in ordered})
    sector_groups = {
        sector: [item for item in ordered if item.sector == sector] for sector in sector_names
    }

    regime = _build_dimension(
        "REGIME",
        REGIME_LABELS,
        regime_groups,
        len(ordered),
        cfg,
    )
    liquidity = _build_dimension(
        "LIQUIDITY",
        LIQUIDITY_BUCKETS,
        liquidity_groups,
        len(ordered),
        cfg,
    )
    sector = _build_dimension(
        "SECTOR",
        sector_names,
        sector_groups,
        len(ordered),
        cfg,
    )
    dimensions = (regime, liquidity, sector)
    state: StabilityState = (
        "EVALUATED" if all(item.state == "EVALUATED" for item in dimensions) else "INCONCLUSIVE"
    )
    reason_codes = sorted(
        f"{item.dimension}_DIMENSION_INCONCLUSIVE"
        for item in dimensions
        if item.state == "INCONCLUSIVE"
    )
    payload = {
        "report_version": STABILITY_REPORT_VERSION,
        "state": state,
        "reason_codes": reason_codes,
        "config_fingerprint": cfg.fingerprint,
        "observation_count": len(ordered),
        "source_observation_fingerprints": observation_fingerprints,
        "regime": regime.model_dump(mode="json"),
        "liquidity": liquidity.model_dump(mode="json"),
        "sector": sector.model_dump(mode="json"),
        "status": "RESEARCH_STABILITY_REPORT_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return StabilityReportV1(
        state=state,
        reason_codes=reason_codes,
        config_fingerprint=cfg.fingerprint,
        observation_count=len(ordered),
        source_observation_fingerprints=observation_fingerprints,
        regime=regime,
        liquidity=liquidity,
        sector=sector,
        report_fingerprint=canonical_fingerprint(payload),
    )
