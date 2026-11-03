from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .fusion_policy_shadow_evaluation import FusionPolicyShadowEvaluationReport

LEARNED_META_MODEL_READINESS_VERSION = "learned-meta-model-readiness.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Readiness = Literal["RESEARCH_TRAINING_ELIGIBLE", "NOT_READY"]


class LearnedMetaModelReadinessConfig(BaseModel):
    """Strict evidence floors before learned fusion research may even begin."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["learned-meta-model-readiness.v1"] = (
        LEARNED_META_MODEL_READINESS_VERSION
    )
    minimum_independent_oos_windows: Annotated[int, Field(ge=2, le=100)] = 3
    minimum_total_resolved_requests: Annotated[int, Field(ge=2, le=1_000_000)] = 90
    minimum_total_challenger_trades: Annotated[int, Field(ge=1, le=1_000_000)] = 24

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class LearnedMetaModelReadinessV1(BaseModel):
    """Research-readiness evidence only; it never trains, enables or promotes a model."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["learned-meta-model-readiness.v1"] = (
        LEARNED_META_MODEL_READINESS_VERSION
    )
    assessed_at: datetime
    config_fingerprint: Hash64
    eligible_report_count: Annotated[int, Field(ge=1)]
    superior_report_count: Annotated[int, Field(ge=0)]
    total_resolved_requests: Annotated[int, Field(ge=1)]
    total_challenger_trades: Annotated[int, Field(ge=0)]
    distinct_request_count: Annotated[int, Field(ge=1)]
    distinct_outcome_count: Annotated[int, Field(ge=1)]
    target_spec_fingerprint: Hash64 | None = None
    shadow_evaluation_config_fingerprint: Hash64 | None = None
    challenger_base_policy_config_fingerprint: Hash64 | None = None
    challenger_disagreement_config_fingerprint: Hash64 | None = None
    independent_oos_windows: bool
    comparable_evidence: bool
    all_windows_superior: bool
    readiness: Readiness
    reason_codes: list[str] = Field(min_length=1, max_length=32)
    source_report_fingerprints: list[Hash64] = Field(min_length=1, max_length=100)
    source_request_fingerprints: list[Hash64] = Field(min_length=1, max_length=1_000_000)
    source_outcome_fingerprints: list[Hash64] = Field(min_length=1, max_length=1_000_000)
    required_next_gate: Literal[
        "LEARNED_META_MODEL_RESEARCH_DESIGN",
        "CONTINUE_NON_LEAKING_OOS_EVIDENCE",
    ]
    readiness_fingerprint: Hash64
    learned_policy_enabled: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    status: Literal["RESEARCH_READINESS_ONLY"] = "RESEARCH_READINESS_ONLY"

    @field_validator("assessed_at")
    @classmethod
    def assessed_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("readiness assessed_at must be timezone-aware")
        return value

    @model_validator(mode="after")
    def readiness_semantics_must_fail_closed(self) -> LearnedMetaModelReadinessV1:
        if self.superior_report_count > self.eligible_report_count:
            raise ValueError("superior report count cannot exceed eligible report count")
        if self.distinct_request_count != len(set(self.source_request_fingerprints)):
            raise ValueError("distinct request count must match unique source requests")
        if self.distinct_outcome_count != len(set(self.source_outcome_fingerprints)):
            raise ValueError("distinct outcome count must match unique source outcomes")
        if len(set(self.source_report_fingerprints)) != len(self.source_report_fingerprints):
            raise ValueError("source shadow reports must be unique")
        ready = self.readiness == "RESEARCH_TRAINING_ELIGIBLE"
        if ready:
            if not self.independent_oos_windows:
                raise ValueError("training eligibility requires independent OOS windows")
            if not self.comparable_evidence:
                raise ValueError("training eligibility requires comparable shadow evidence")
            if not self.all_windows_superior:
                raise ValueError("training eligibility requires superior evidence in every window")
            if self.required_next_gate != "LEARNED_META_MODEL_RESEARCH_DESIGN":
                raise ValueError("eligible readiness must advance only to research design")
        elif self.required_next_gate != "CONTINUE_NON_LEAKING_OOS_EVIDENCE":
            raise ValueError("not-ready evidence must continue OOS evidence collection")
        return self


def assess_learned_meta_model_readiness(
    reports: Sequence[FusionPolicyShadowEvaluationReport],
    *,
    assessed_at: datetime,
    config: LearnedMetaModelReadinessConfig | None = None,
) -> LearnedMetaModelReadinessV1:
    cfg = config or LearnedMetaModelReadinessConfig()
    if assessed_at.tzinfo is None or assessed_at.utcoffset() is None:
        raise ValueError("assessed_at must be timezone-aware")
    if not reports:
        raise ValueError("at least one shadow evaluation report is required")

    eligible = sorted(
        (report for report in reports if report.evaluation_as_of <= assessed_at),
        key=lambda report: (report.evaluation_as_of, report.report_fingerprint),
    )
    if not eligible:
        raise ValueError("no shadow evaluation reports are eligible at assessed_at")

    validated = [_validate_shadow_report(report) for report in eligible]
    report_fingerprints = [report.report_fingerprint for report in validated]
    if len(set(report_fingerprints)) != len(report_fingerprints):
        raise ValueError("shadow evaluation reports must have unique fingerprints")
    evaluation_times = [report.evaluation_as_of for report in validated]
    if len(set(evaluation_times)) != len(evaluation_times):
        raise ValueError("independent OOS windows require unique evaluation timestamps")

    request_sets = [set(report.source_request_fingerprints) for report in validated]
    outcome_sets = [set(report.source_outcome_fingerprints) for report in validated]
    independent_requests = _pairwise_disjoint(request_sets)
    independent_outcomes = _pairwise_disjoint(outcome_sets)
    independent_oos_windows = independent_requests and independent_outcomes

    target_specs = {report.target_spec_fingerprint for report in validated}
    evaluation_configs = {report.config_fingerprint for report in validated}
    base_policy_configs = {
        report.challenger_base_policy_config_fingerprint for report in validated
    }
    disagreement_configs = {
        report.challenger_disagreement_config_fingerprint for report in validated
    }
    comparable_evidence = (
        len(target_specs) == 1
        and len(evaluation_configs) == 1
        and len(base_policy_configs) == 1
        and len(disagreement_configs) == 1
    )

    superior_count = sum(
        report.assessment == "CHALLENGER_EVIDENCE_SUPERIOR" for report in validated
    )
    all_windows_superior = superior_count == len(validated)
    total_resolved = sum(report.resolved_request_count for report in validated)
    total_challenger_trades = sum(
        report.guarded_challenger.trade_count for report in validated
    )
    all_requests = [
        fingerprint
        for report in validated
        for fingerprint in report.source_request_fingerprints
    ]
    all_outcomes = [
        fingerprint
        for report in validated
        for fingerprint in report.source_outcome_fingerprints
    ]

    reasons: list[str] = []
    if len(validated) < cfg.minimum_independent_oos_windows:
        reasons.append("INSUFFICIENT_INDEPENDENT_OOS_WINDOWS")
    if not independent_oos_windows:
        reasons.append("OOS_EVIDENCE_WINDOWS_OVERLAP")
    if not comparable_evidence:
        reasons.append("OOS_EVIDENCE_NOT_COMPARABLE")
    if not all_windows_superior:
        reasons.append("CHALLENGER_NOT_SUPERIOR_IN_EVERY_OOS_WINDOW")
    if total_resolved < cfg.minimum_total_resolved_requests:
        reasons.append("INSUFFICIENT_TOTAL_RESOLVED_REQUESTS")
    if total_challenger_trades < cfg.minimum_total_challenger_trades:
        reasons.append("INSUFFICIENT_TOTAL_CHALLENGER_TRADES")

    ready = not reasons
    if ready:
        readiness: Readiness = "RESEARCH_TRAINING_ELIGIBLE"
        reasons.append("NON_LEAKING_OOS_EVIDENCE_FLOORS_SATISFIED")
        required_next_gate = "LEARNED_META_MODEL_RESEARCH_DESIGN"
    else:
        readiness = "NOT_READY"
        required_next_gate = "CONTINUE_NON_LEAKING_OOS_EVIDENCE"

    payload = {
        "contract_version": LEARNED_META_MODEL_READINESS_VERSION,
        "assessed_at": assessed_at.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "eligible_report_count": len(validated),
        "superior_report_count": superior_count,
        "total_resolved_requests": total_resolved,
        "total_challenger_trades": total_challenger_trades,
        "distinct_request_count": len(set(all_requests)),
        "distinct_outcome_count": len(set(all_outcomes)),
        "target_spec_fingerprint": next(iter(target_specs)) if len(target_specs) == 1 else None,
        "shadow_evaluation_config_fingerprint": (
            next(iter(evaluation_configs)) if len(evaluation_configs) == 1 else None
        ),
        "challenger_base_policy_config_fingerprint": (
            next(iter(base_policy_configs)) if len(base_policy_configs) == 1 else None
        ),
        "challenger_disagreement_config_fingerprint": (
            next(iter(disagreement_configs)) if len(disagreement_configs) == 1 else None
        ),
        "independent_oos_windows": independent_oos_windows,
        "comparable_evidence": comparable_evidence,
        "all_windows_superior": all_windows_superior,
        "readiness": readiness,
        "reason_codes": sorted(set(reasons)),
        "source_report_fingerprints": report_fingerprints,
        "source_request_fingerprints": sorted(set(all_requests)),
        "source_outcome_fingerprints": sorted(set(all_outcomes)),
        "required_next_gate": required_next_gate,
        "learned_policy_enabled": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
        "status": "RESEARCH_READINESS_ONLY",
    }
    return LearnedMetaModelReadinessV1(
        **payload,
        readiness_fingerprint=canonical_fingerprint(payload),
    )


def _pairwise_disjoint(sets: Sequence[set[str]]) -> bool:
    seen: set[str] = set()
    for values in sets:
        if seen.intersection(values):
            return False
        seen.update(values)
    return True


def _validate_shadow_report(
    item: FusionPolicyShadowEvaluationReport,
) -> FusionPolicyShadowEvaluationReport:
    checked = FusionPolicyShadowEvaluationReport.model_validate(
        item.model_dump(mode="python")
    )
    payload = checked.model_dump(mode="python", exclude={"report_fingerprint"})
    payload["evaluation_as_of"] = checked.evaluation_as_of.isoformat()
    if checked.report_fingerprint != canonical_fingerprint(payload):
        raise ValueError("fusion policy shadow report fingerprint mismatch")
    if checked.promotion_allowed or checked.can_authorize_capital:
        raise ValueError("shadow evaluation cannot carry promotion or capital authority")
    return checked
