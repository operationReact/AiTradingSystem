"""V4-06.1 immutable research snapshot of the inherited V3 fusion baseline.

This is NOT an execution policy or a verified Indian-market model tournament.
It reuses V3's exact static 45/35/20 champion and guarded adaptive challenger
evidence; it cannot enable any model, broker trade, or promotion.
"""
from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .fingerprints import canonical_fingerprint
from .fusion_policy_shadow_evaluation import (
    FusionPolicyShadowEvaluationReport,
    FusionPolicyShadowPoint,
)

CONTRACT_VERSION = "v4-decision-fusion-baseline.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class FusionBaselineFreezeConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_independent_windows: Annotated[int, Field(ge=2, le=50)] = 2
    minimum_resolved_requests: Annotated[int, Field(ge=2, le=100_000)] = 30

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class PolicyResearchAggregate(BaseModel):
    """Non-compounded, after-assumed-cost V3 metrics; not account P&L."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy: Literal["STATIC_45_35_20", "ADAPTIVE_DISAGREEMENT_GUARDED"]
    trades: Annotated[int, Field(ge=0)]
    wins: Annotated[int, Field(ge=0)]
    resolved_request_coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    sum_net_return: float
    mean_net_return_bps: float | None
    worst_window_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]

    @field_validator("resolved_request_coverage", "sum_net_return", "mean_net_return_bps",
                     "worst_window_drawdown")
    @classmethod
    def finite_metrics_only(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("non-finite tournament metric")
        return value


class V4DecisionFusionBaselineV1(BaseModel):
    """A frozen, unverified evidence comparison, never promotion authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-decision-fusion-baseline.v1"] = CONTRACT_VERSION
    assessed_at: datetime
    config_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    v3_shadow_config_fingerprint: Hash64
    v3_base_policy_config_fingerprint: Hash64
    v3_disagreement_config_fingerprint: Hash64
    evaluated_windows: Annotated[int, Field(ge=1, le=50)]
    resolved_requests: Annotated[int, Field(ge=1)]
    unresolved_requests: Annotated[int, Field(ge=0)]
    static_champion: PolicyResearchAggregate
    adaptive_challenger: PolicyResearchAggregate
    source_report_fingerprints: list[Hash64] = Field(min_length=1, max_length=50)
    source_request_fingerprints: list[Hash64] = Field(min_length=1, max_length=100_000)
    source_outcome_fingerprints: list[Hash64] = Field(min_length=1, max_length=100_000)
    assessment: Literal["OFFLINE_UNVERIFIED", "INSUFFICIENT_SAMPLE"]
    reason_codes: list[str] = Field(min_length=1, max_length=12)
    latency_evidence: Literal["NOT_MEASURED"] = "NOT_MEASURED"
    external_source_custody_verified: Literal[False] = False
    paper_shadow_runtime_validated: Literal[False] = False
    model_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    evidence_fingerprint: Hash64

    @field_validator("assessed_at")
    @classmethod
    def aware_cutoff_only(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("assessed_at must be timezone-aware")
        return value


def freeze_v4_static_fusion_baseline(
    reports: Sequence[FusionPolicyShadowEvaluationReport],
    *,
    assessed_at: datetime,
    config: FusionBaselineFreezeConfig | None = None,
) -> V4DecisionFusionBaselineV1:
    """Freeze only comparable, independent, already-resolved V3 OOS windows.

    A V3 superiority assessment is never upgraded into genuine trading alpha:
    market-data custody, latency, prospective validation and PAPER soak are
    separate gates. Later reports cannot rewrite an earlier snapshot.
    """
    cfg = config or FusionBaselineFreezeConfig()
    if assessed_at.tzinfo is None or assessed_at.utcoffset() is None:
        raise ValueError("assessed_at must be timezone-aware")
    if not reports:
        raise ValueError("no inherited V3 fusion reports supplied")

    eligible = sorted(
        (report for report in reports if report.evaluation_as_of <= assessed_at),
        key=lambda report: (report.evaluation_as_of, report.report_fingerprint),
    )
    if not eligible:
        raise ValueError("no V3 reports precede the assessment cutoff")
    if len(eligible) > 50:
        raise ValueError("too many V3 reports for one bounded research freeze")

    validated: list[FusionPolicyShadowEvaluationReport] = []
    report_ids: set[str] = set()
    window_times: set[datetime] = set()
    request_ids: set[str] = set()
    outcome_ids: set[str] = set()
    comparisons: set[tuple[str, str, str, str]] = set()
    for report in eligible:
        checked = FusionPolicyShadowEvaluationReport.model_validate(
            report.model_dump(mode="python")
        )
        payload = checked.model_dump(mode="python", exclude={"report_fingerprint"})
        payload["evaluation_as_of"] = checked.evaluation_as_of.isoformat()
        if canonical_fingerprint(payload) != checked.report_fingerprint:
            raise ValueError("V3 source report fingerprint does not match its contents")
        if checked.promotion_allowed or checked.can_authorize_capital:
            raise ValueError("V3 report cannot claim trading authority")
        if checked.report_fingerprint in report_ids or checked.evaluation_as_of in window_times:
            raise ValueError("duplicate report or OOS evaluation time")
        if request_ids.intersection(checked.source_request_fingerprints):
            raise ValueError("overlapping request cohort is not an independent OOS window")
        if outcome_ids.intersection(checked.source_outcome_fingerprints):
            raise ValueError("overlapping outcome cohort is not an independent OOS window")
        report_ids.add(checked.report_fingerprint)
        window_times.add(checked.evaluation_as_of)
        request_ids.update(checked.source_request_fingerprints)
        outcome_ids.update(checked.source_outcome_fingerprints)
        comparisons.add((
            checked.target_spec_fingerprint,
            checked.config_fingerprint,
            checked.challenger_base_policy_config_fingerprint,
            checked.challenger_disagreement_config_fingerprint,
        ))
        validated.append(checked)

    if len(comparisons) != 1:
        raise ValueError("V3 windows must share one target, cost and policy configuration")
    target, shadow_cfg, base_cfg, disagreement_cfg = next(iter(comparisons))
    resolved = sum(r.resolved_request_count for r in validated)
    unresolved = sum(r.unresolved_request_count for r in validated)
    reasons = [
        "INDEPENDENT_MARKET_SOURCE_CUSTODY_UNVERIFIED",
        "PROSPECTIVE_PAPER_SHADOW_VALIDATION_PENDING",
        "POLICY_LATENCY_NOT_MEASURED",
    ]
    if len(validated) < cfg.minimum_independent_windows:
        reasons.append("INSUFFICIENT_INDEPENDENT_OOS_WINDOWS")
    if resolved < cfg.minimum_resolved_requests:
        reasons.append("INSUFFICIENT_RESOLVED_REQUESTS")

    def aggregate(
        policy: Literal["STATIC_45_35_20", "ADAPTIVE_DISAGREEMENT_GUARDED"],
        points: list[FusionPolicyShadowPoint],
    ) -> PolicyResearchAggregate:
        if any(point.policy != policy for point in points):
            raise ValueError("inherited V3 policy identity mismatch")
        trades = sum(point.trade_count for point in points)
        wins = sum(point.winning_trades for point in points)
        total_return = sum(point.total_net_return for point in points)
        return PolicyResearchAggregate(
            policy=policy,
            trades=trades,
            wins=wins,
            resolved_request_coverage=trades / resolved,
            sum_net_return=total_return,
            mean_net_return_bps=10_000.0 * total_return / trades if trades else None,
            worst_window_drawdown=max(point.maximum_drawdown for point in points),
        )

    payload = {
        "contract_version": CONTRACT_VERSION,
        "assessed_at": assessed_at.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "target_spec_fingerprint": target,
        "v3_shadow_config_fingerprint": shadow_cfg,
        "v3_base_policy_config_fingerprint": base_cfg,
        "v3_disagreement_config_fingerprint": disagreement_cfg,
        "evaluated_windows": len(validated),
        "resolved_requests": resolved,
        "unresolved_requests": unresolved,
        "static_champion": aggregate(
            "STATIC_45_35_20", [r.static_champion for r in validated]
        ).model_dump(mode="json"),
        "adaptive_challenger": aggregate(
            "ADAPTIVE_DISAGREEMENT_GUARDED", [r.guarded_challenger for r in validated]
        ).model_dump(mode="json"),
        "source_report_fingerprints": [r.report_fingerprint for r in validated],
        "source_request_fingerprints": sorted(request_ids),
        "source_outcome_fingerprints": sorted(outcome_ids),
        "assessment": (
            "INSUFFICIENT_SAMPLE" if len(reasons) > 3 else "OFFLINE_UNVERIFIED"
        ),
        "reason_codes": sorted(reasons),
        "latency_evidence": "NOT_MEASURED",
        "external_source_custody_verified": False,
        "paper_shadow_runtime_validated": False,
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return V4DecisionFusionBaselineV1(
        **payload, evidence_fingerprint=canonical_fingerprint(payload)
    )
