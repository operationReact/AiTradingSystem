from __future__ import annotations

import math
import random
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

SERIES_VERSION = "paired-net-return-series.v1"
REPORT_VERSION = "incremental-economic-value.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class NetReturnSeries(BaseModel):
    """Point-in-time ordered OOS net returns, including zeros for abstained decisions."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["paired-net-return-series.v1"] = SERIES_VERSION
    candidate_id: Annotated[str, Field(min_length=1, max_length=128)]
    model_version_ref: Annotated[str, Field(min_length=1, max_length=128)]
    source_result_fingerprint: Hash64
    target_binding_fingerprint: Hash64
    fold_binding_fingerprint: Hash64
    economic_cost_binding_fingerprint: Hash64
    observation_fingerprints: list[Hash64] = Field(min_length=2, max_length=10_000)
    decision_times: list[datetime] = Field(min_length=2, max_length=10_000)
    net_returns_after_costs: list[float] = Field(min_length=2, max_length=10_000)
    series_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_times")
    @classmethod
    def timestamps_must_be_aware(cls, times: list[datetime]) -> list[datetime]:
        if any(t.tzinfo is None or t.utcoffset() is None for t in times):
            raise ValueError("decision times must be timezone-aware")
        return times

    @model_validator(mode="after")
    def validate_series(self) -> NetReturnSeries:
        count = len(self.observation_fingerprints)
        if len(self.decision_times) != count or len(self.net_returns_after_costs) != count:
            raise ValueError("all net-return series dimensions must be aligned")
        if len(set(self.observation_fingerprints)) != count:
            raise ValueError("observation fingerprints must be unique")
        if any(later <= earlier for earlier, later in
               zip(self.decision_times, self.decision_times[1:], strict=True)):
            raise ValueError("decision times must be strictly chronological")
        if any(not math.isfinite(v) or v < -1.0 or v > 10.0
               for v in self.net_returns_after_costs):
            raise ValueError("net returns must be finite and between -1 and 10")
        if self.series_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"series_fingerprint"})
        ):
            raise ValueError("net-return series fingerprint mismatch")
        return self


class IncrementalValueConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["incremental-value-config.v1"] = "incremental-value-config.v1"
    minimum_observations: Annotated[int, Field(ge=20, le=10_000)] = 60
    block_size: Annotated[int, Field(ge=2, le=100)] = 5
    bootstrap_replicates: Annotated[int, Field(ge=200, le=2_000)] = 500
    bootstrap_seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42
    confidence_level: Annotated[float, Field(ge=0.90, le=0.99)] = 0.95
    minimum_mean_return_uplift: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0001

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class IncrementalValueReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["incremental-economic-value.v1"] = REPORT_VERSION
    baseline_series_fingerprint: Hash64
    challenger_series_fingerprint: Hash64
    config_fingerprint: Hash64
    observation_membership_fingerprint: Hash64
    observation_count: int
    mean_baseline_net_return: float
    mean_challenger_net_return: float
    mean_paired_uplift: float
    bootstrap_lower_bound: float | None
    bootstrap_upper_bound: float | None
    assessment: Literal["PROMISING_RESEARCH", "NOT_IMPROVED", "INCONCLUSIVE"]
    reason_codes: list[str]
    report_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def check_report(self) -> IncrementalValueReport:
        if self.assessment == "PROMISING_RESEARCH" and self.reason_codes:
            raise ValueError("promising result cannot contain failed reasons")
        if self.assessment != "PROMISING_RESEARCH" and not self.reason_codes:
            raise ValueError("non-promising result requires reason codes")
        if self.assessment == "INCONCLUSIVE" and (
            self.bootstrap_lower_bound is not None or self.bootstrap_upper_bound is not None
        ):
            raise ValueError("inconclusive report must not claim bootstrap bounds")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("incremental economic report fingerprint mismatch")
        return self


def create_net_return_series(
    *,
    candidate_id: str,
    model_version_ref: str,
    source_result_fingerprint: str,
    target_binding_fingerprint: str,
    fold_binding_fingerprint: str,
    economic_cost_binding_fingerprint: str,
    observation_fingerprints: list[str],
    decision_times: list[datetime],
    net_returns_after_costs: list[float],
) -> NetReturnSeries:
    payload = {
        "contract_version": SERIES_VERSION,
        "candidate_id": candidate_id,
        "model_version_ref": model_version_ref,
        "source_result_fingerprint": source_result_fingerprint,
        "target_binding_fingerprint": target_binding_fingerprint,
        "fold_binding_fingerprint": fold_binding_fingerprint,
        "economic_cost_binding_fingerprint": economic_cost_binding_fingerprint,
        "observation_fingerprints": observation_fingerprints,
        "decision_times": [t.isoformat() for t in decision_times],
        "net_returns_after_costs": net_returns_after_costs,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    result = NetReturnSeries(**payload, series_fingerprint=canonical_fingerprint(payload))
    return result


def evaluate_incremental_economic_value(
    baseline: NetReturnSeries,
    challenger: NetReturnSeries,
    *,
    config: IncrementalValueConfig | None = None,
) -> IncrementalValueReport:
    """Paired circular-block bootstrap on identical causal OOS observation membership.

    Research-only. Repeated trials, strategy selection and market regime shifts require
    separate robustness gates before interpreting an uplift as reliable trading alpha.
    """
    first = NetReturnSeries.model_validate(baseline.model_dump(mode="python"))
    second = NetReturnSeries.model_validate(challenger.model_dump(mode="python"))
    policy = config or IncrementalValueConfig()
    if first.candidate_id == second.candidate_id:
        raise ValueError("baseline and challenger must be distinct candidates")
    if first.source_result_fingerprint == second.source_result_fingerprint:
        raise ValueError("baseline and challenger cannot share one experiment result")
    for field in (
        "target_binding_fingerprint",
        "fold_binding_fingerprint",
        "economic_cost_binding_fingerprint",
        "observation_fingerprints",
        "decision_times",
    ):
        if getattr(first, field) != getattr(second, field):
            raise ValueError(f"paired economic comparison has mismatched {field}")

    deltas = [
        challenger_return - baseline_return
        for baseline_return, challenger_return in zip(
            first.net_returns_after_costs, second.net_returns_after_costs, strict=True
        )
    ]
    count = len(deltas)
    mean_base = sum(first.net_returns_after_costs) / count
    mean_challenger = sum(second.net_returns_after_costs) / count
    mean_delta = sum(deltas) / count
    reasons: list[str] = []
    lower: float | None = None
    upper: float | None = None
    if count < policy.minimum_observations:
        reasons.append("INSUFFICIENT_CAUSAL_OBSERVATIONS")
    if policy.block_size > count:
        reasons.append("BOOTSTRAP_BLOCK_EXCEEDS_OBSERVATIONS")

    if reasons:
        assessment = "INCONCLUSIVE"
    else:
        rng = random.Random(policy.bootstrap_seed)
        resampled_means = []
        for _ in range(policy.bootstrap_replicates):
            selection: list[float] = []
            while len(selection) < count:
                start = rng.randrange(count)
                selection.extend(
                    deltas[(start + offset) % count] for offset in range(policy.block_size)
                )
            resampled_means.append(sum(selection[:count]) / count)
        resampled_means.sort()
        tail = (1.0 - policy.confidence_level) / 2.0
        lower = resampled_means[int(tail * (len(resampled_means) - 1))]
        upper = resampled_means[int((1.0 - tail) * (len(resampled_means) - 1))]
        if mean_delta <= policy.minimum_mean_return_uplift:
            reasons.append("NO_MEAN_NET_UPLIFT")
        if lower <= policy.minimum_mean_return_uplift:
            reasons.append("LOWER_CONFIDENCE_BOUND_NOT_POSITIVE")
        assessment = "NOT_IMPROVED" if reasons else "PROMISING_RESEARCH"

    payload = {
        "contract_version": REPORT_VERSION,
        "baseline_series_fingerprint": first.series_fingerprint,
        "challenger_series_fingerprint": second.series_fingerprint,
        "config_fingerprint": policy.fingerprint,
        "observation_membership_fingerprint": canonical_fingerprint({
            "observation_fingerprints": first.observation_fingerprints,
            "decision_times": [t.isoformat() for t in first.decision_times],
        }),
        "observation_count": count,
        "mean_baseline_net_return": mean_base,
        "mean_challenger_net_return": mean_challenger,
        "mean_paired_uplift": mean_delta,
        "bootstrap_lower_bound": lower,
        "bootstrap_upper_bound": upper,
        "assessment": assessment,
        "reason_codes": reasons,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IncrementalValueReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )
