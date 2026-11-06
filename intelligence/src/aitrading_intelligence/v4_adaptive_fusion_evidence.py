"""V4-06.2 — research-only adaptive-fusion evidence over immutable V3 reports.

V3's static champion is NEVER replaced here. This compares the inherited
static/adaptive policies on the same resolved hypothetical outcome cohorts,
but source rights, latency, live PAPER and broker acceptance remain unverified.
"""
from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .fingerprints import canonical_fingerprint
from .fusion_policy_shadow_evaluation import FusionPolicyShadowEvaluationReport
from .v4_decision_fusion_baseline import (
    FusionBaselineFreezeConfig,
    V4DecisionFusionBaselineV1,
    freeze_v4_static_fusion_baseline,
)

CONTRACT_VERSION = "v4-adaptive-fusion-evidence.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
WindowAssessment = Literal["RESEARCH_UPLIFT", "CHAMPION_PRESERVED", "INCONCLUSIVE"]
OverallAssessment = Literal[
    "OFFLINE_RESEARCH_CANDIDATE",
    "STATIC_CHAMPION_PRESERVED",
    "INSUFFICIENT_EVIDENCE",
]


class AdaptiveFusionEvidenceConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_independent_windows: Annotated[int, Field(ge=2, le=50)] = 3
    minimum_resolved_requests: Annotated[int, Field(ge=2, le=100_000)] = 50
    minimum_window_challenger_trades: Annotated[int, Field(ge=1, le=100_000)] = 8
    minimum_challenger_coverage: Annotated[float, Field(gt=0.0, le=1.0)] = 0.05
    minimum_window_expectancy_uplift_bps: Annotated[
        float, Field(gt=0.0, le=500.0)
    ] = 1.0
    maximum_window_drawdown_regression: Annotated[
        float, Field(ge=0.0, le=1.0)
    ] = 0.0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class AdaptiveFusionWindowV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evaluated_at: datetime
    source_v3_report_fingerprint: Hash64
    resolved_requests: Annotated[int, Field(ge=1)]
    excluded_unresolved_requests: Annotated[int, Field(ge=0)]
    static_enter_count: Annotated[int, Field(ge=0)]
    challenger_enter_count: Annotated[int, Field(ge=0)]
    static_non_enter_count: Annotated[int, Field(ge=0)]
    challenger_non_enter_count: Annotated[int, Field(ge=0)]
    challenger_coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    expectancy_uplift_after_assumed_cost_bps: float | None
    worst_drawdown_regression: float
    assessment: WindowAssessment
    reason_codes: list[str] = Field(min_length=1, max_length=10)


class V4AdaptiveFusionEvidenceV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-adaptive-fusion-evidence.v1"] = CONTRACT_VERSION
    assessed_at: datetime
    config_fingerprint: Hash64
    source_v4_baseline_fingerprint: Hash64
    source_v3_report_fingerprints: list[Hash64] = Field(min_length=1, max_length=50)
    windows: list[AdaptiveFusionWindowV1] = Field(min_length=1, max_length=50)
    resolved_requests: Annotated[int, Field(ge=1)]
    research_uplift_windows: Annotated[int, Field(ge=0)]
    champion_preserved_windows: Annotated[int, Field(ge=0)]
    inconclusive_windows: Annotated[int, Field(ge=0)]
    assessment: OverallAssessment
    reason_codes: list[str] = Field(min_length=1, max_length=15)
    non_enter_breakdown: Literal["WAIT_VS_ABSTAIN_UNAVAILABLE"] = (
        "WAIT_VS_ABSTAIN_UNAVAILABLE"
    )
    latency_status: Literal["NOT_MEASURED"] = "NOT_MEASURED"
    source_custody: Literal["UNVERIFIED"] = "UNVERIFIED"
    real_paper_shadow_status: Literal["NOT_VALIDATED"] = "NOT_VALIDATED"
    champion_policy_changed: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    evidence_fingerprint: Hash64

    @field_validator("assessed_at")
    @classmethod
    def cutoff_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("assessed_at must be timezone-aware")
        return value


def compare_v4_adaptive_fusion(
    reports: Sequence[FusionPolicyShadowEvaluationReport],
    *,
    baseline: V4DecisionFusionBaselineV1,
    assessed_at: datetime,
    config: AdaptiveFusionEvidenceConfig | None = None,
    baseline_config: FusionBaselineFreezeConfig | None = None,
) -> V4AdaptiveFusionEvidenceV1:
    """An offline, multi-window research filter, not a promotion decision.

    Rebuild and match the exact V4-06.1 frozen baseline before consuming report
    metrics. Only windows at/before assessed_at participate; V4-06.1 enforces
    report fingerprint, config parity and disjoint request/outcome membership.
    """
    cfg = config or AdaptiveFusionEvidenceConfig()
    rebuilt = freeze_v4_static_fusion_baseline(
        reports, assessed_at=assessed_at, config=baseline_config
    )
    if rebuilt.model_dump(mode="json") != baseline.model_dump(mode="json"):
        raise ValueError("provided V4 baseline does not match the frozen source cohort")

    eligible = sorted(
        (r for r in reports if r.evaluation_as_of <= assessed_at),
        key=lambda r: (r.evaluation_as_of, r.report_fingerprint),
    )
    windows: list[AdaptiveFusionWindowV1] = []
    for report in eligible:
        if report.static_champion.policy != "STATIC_45_35_20":
            raise ValueError("static champion identity differs from inherited V3")
        if report.guarded_challenger.policy != "ADAPTIVE_DISAGREEMENT_GUARDED":
            raise ValueError("adaptive challenger identity differs from inherited V3")
        resolved = report.resolved_request_count
        static = report.static_champion
        challenger = report.guarded_challenger
        if static.trade_count > resolved or challenger.trade_count > resolved:
            raise ValueError("V3 policy enters exceed resolved request count")
        # Recompute the coverage, refusing inconsistent imported research metrics.
        coverage = challenger.trade_count / resolved
        if abs(coverage - challenger.coverage) > 1e-9:
            raise ValueError("V3 challenger coverage does not reconcile")
        if abs(static.trade_count / resolved - static.coverage) > 1e-9:
            raise ValueError("V3 static coverage does not reconcile")

        uplift = report.expectancy_uplift_bps
        dd_regression = report.maximum_drawdown_change
        if (
            uplift is not None
            and static.expectancy_bps is not None
            and challenger.expectancy_bps is not None
            and abs(uplift - (challenger.expectancy_bps - static.expectancy_bps)) > 1e-7
        ):
            raise ValueError("V3 reported expectancy uplift does not reconcile")
        if abs(dd_regression - (
            challenger.maximum_drawdown - static.maximum_drawdown
        )) > 1e-9:
            raise ValueError("V3 drawdown change does not reconcile")

        reasons: list[str] = []
        if challenger.trade_count < cfg.minimum_window_challenger_trades:
            reasons.append("INSUFFICIENT_WINDOW_CHALLENGER_TRADES")
        if coverage < cfg.minimum_challenger_coverage:
            reasons.append("INSUFFICIENT_WINDOW_CHALLENGER_COVERAGE")
        if uplift is None:
            reasons.append("NO_COMPARABLE_EXPECTANCY")
        if report.assessment == "INSUFFICIENT_EVIDENCE":
            reasons.append("V3_WINDOW_INCONCLUSIVE")

        if reasons:
            assessment: WindowAssessment = "INCONCLUSIVE"
        elif (
            uplift is not None
            and uplift >= cfg.minimum_window_expectancy_uplift_bps
            and dd_regression <= cfg.maximum_window_drawdown_regression
            and report.assessment == "CHALLENGER_EVIDENCE_SUPERIOR"
        ):
            assessment = "RESEARCH_UPLIFT"
            reasons.append("HYPOTHETICAL_AFTER_COST_UPLIFT_ONLY")
        else:
            assessment = "CHAMPION_PRESERVED"
            reasons.append("CHALLENGER_DID_NOT_PASS_EVERY_WINDOW_GATE")

        windows.append(AdaptiveFusionWindowV1(
            evaluated_at=report.evaluation_as_of,
            source_v3_report_fingerprint=report.report_fingerprint,
            resolved_requests=resolved,
            excluded_unresolved_requests=report.unresolved_request_count,
            static_enter_count=static.trade_count,
            challenger_enter_count=challenger.trade_count,
            static_non_enter_count=resolved - static.trade_count,
            challenger_non_enter_count=resolved - challenger.trade_count,
            challenger_coverage=coverage,
            expectancy_uplift_after_assumed_cost_bps=uplift,
            worst_drawdown_regression=dd_regression,
            assessment=assessment,
            reason_codes=sorted(reasons),
        ))

    superior = sum(w.assessment == "RESEARCH_UPLIFT" for w in windows)
    preserved = sum(w.assessment == "CHAMPION_PRESERVED" for w in windows)
    inconclusive = sum(w.assessment == "INCONCLUSIVE" for w in windows)
    resolved_total = sum(w.resolved_requests for w in windows)
    overall_reasons = [
        "INDEPENDENT_INDIA_SOURCE_CUSTODY_NOT_VERIFIED",
        "REAL_PAPER_SHADOW_AND_BROKER_RECONCILIATION_PENDING",
        "LATENCY_AND_WAIT_ABSTAIN_BREAKDOWN_NOT_MEASURED",
    ]
    insufficient = (
        len(windows) < cfg.minimum_independent_windows
        or resolved_total < cfg.minimum_resolved_requests
        or inconclusive > 0
    )
    if len(windows) < cfg.minimum_independent_windows:
        overall_reasons.append("INSUFFICIENT_INDEPENDENT_WINDOWS")
    if resolved_total < cfg.minimum_resolved_requests:
        overall_reasons.append("INSUFFICIENT_RESOLVED_REQUESTS")
    if inconclusive:
        overall_reasons.append("INCONCLUSIVE_SOURCE_WINDOWS")
    if insufficient:
        assessment: OverallAssessment = "INSUFFICIENT_EVIDENCE"
    elif preserved:
        assessment = "STATIC_CHAMPION_PRESERVED"
        overall_reasons.append("NOT_ALL_WINDOWS_SHOW_RESEARCH_UPLIFT")
    else:
        assessment = "OFFLINE_RESEARCH_CANDIDATE"
        overall_reasons.append("HYPOTHETICAL_UPLIFT_REQUIRES_REAL_PROSPECTIVE_ACCEPTANCE")

    payload = {
        "contract_version": CONTRACT_VERSION,
        "assessed_at": assessed_at.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "source_v4_baseline_fingerprint": rebuilt.evidence_fingerprint,
        "source_v3_report_fingerprints": [r.report_fingerprint for r in eligible],
        "windows": [w.model_dump(mode="json") for w in windows],
        "resolved_requests": resolved_total,
        "research_uplift_windows": superior,
        "champion_preserved_windows": preserved,
        "inconclusive_windows": inconclusive,
        "assessment": assessment,
        "reason_codes": sorted(overall_reasons),
        "non_enter_breakdown": "WAIT_VS_ABSTAIN_UNAVAILABLE",
        "latency_status": "NOT_MEASURED",
        "source_custody": "UNVERIFIED",
        "real_paper_shadow_status": "NOT_VALIDATED",
        "champion_policy_changed": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return V4AdaptiveFusionEvidenceV1(
        **payload, evidence_fingerprint=canonical_fingerprint(payload)
    )
