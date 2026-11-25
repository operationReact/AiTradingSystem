from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .selective_evaluation import SelectivePolicyEvaluationReport, _validate_decision
from .selective_policy import SelectiveDecisionV1

SELECTIVE_PRESENTATION_VERSION = "selective-presentation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
PresentationState = Literal["CANDIDATE", "ABSTAIN"]
ProbabilitySemantics = Literal["CALIBRATED_EVENT_PROBABILITY", "WITHHELD"]


class SelectivePresentationRequest(BaseModel):
    """Exact research evidence required to derive customer-safe probability semantics."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    decision: SelectiveDecisionV1
    evaluation: SelectivePolicyEvaluationReport


class SelectivePresentationV1(BaseModel):
    """Research-only UI/API projection; never order or capital authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["selective-presentation.v1"] = SELECTIVE_PRESENTATION_VERSION
    symbol: Annotated[str, Field(min_length=1, max_length=64)]
    presentation_as_of: datetime
    state: PresentationState
    calibrated_probability: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    probability_semantics: ProbabilitySemantics
    uncertainty_score: Annotated[float, Field(ge=0.0, le=1.0)]
    uncertainty_level: Literal["LOW", "MEDIUM", "HIGH"]
    evidence_quality: Literal["COMPLETE", "DEGRADED", "INSUFFICIENT"]
    evaluation_assessment: Literal[
        "QUALITY_PRESERVED_OR_IMPROVED",
        "QUALITY_REGRESSED",
        "INSUFFICIENT_EVIDENCE",
    ]
    reason_codes: list[str] = Field(min_length=1, max_length=96)
    source_decision_fingerprint: Hash64
    source_evaluation_fingerprint: Hash64
    status: Literal["RESEARCH_PRESENTATION_ONLY"] = "RESEARCH_PRESENTATION_ONLY"
    presentation_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("presentation_as_of")
    @classmethod
    def presentation_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("presentation_as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def presentation_semantics_must_fail_closed(self) -> SelectivePresentationV1:
        if self.state == "CANDIDATE":
            if self.calibrated_probability is None:
                raise ValueError("candidate presentation requires calibrated probability")
            if self.probability_semantics != "CALIBRATED_EVENT_PROBABILITY":
                raise ValueError("candidate probability must be explicitly calibrated")
            if self.evaluation_assessment != "QUALITY_PRESERVED_OR_IMPROVED":
                raise ValueError(
                    "candidate presentation requires preserved/improved selective quality"
                )
            if self.evidence_quality != "COMPLETE":
                raise ValueError("candidate presentation requires complete evidence")
        else:
            if self.calibrated_probability is not None:
                raise ValueError("abstain presentation must withhold probability")
            if self.probability_semantics != "WITHHELD":
                raise ValueError("abstain presentation must mark probability withheld")
        return self


def build_selective_presentation(
    decision: SelectiveDecisionV1,
    evaluation: SelectivePolicyEvaluationReport,
) -> SelectivePresentationV1:
    """Project research evidence into truthful customer-facing probability/uncertainty semantics."""

    validated_decision = _validate_decision(decision)
    validated_evaluation = _validate_evaluation(evaluation)

    if (
        validated_decision.decision_fingerprint
        not in validated_evaluation.source_decision_fingerprints
    ):
        raise ValueError("selective evaluation does not cover the supplied decision")
    if validated_decision.decision_as_of > validated_evaluation.evaluation_as_of:
        raise ValueError("selective evaluation cannot predate the supplied decision")

    probability_eligible = (
        validated_decision.policy_decision == "TRADE"
        and validated_decision.state == "CANDIDATE"
        and validated_decision.calibrated_probability is not None
        and validated_decision.evidence_quality == "COMPLETE"
        and validated_evaluation.assessment == "QUALITY_PRESERVED_OR_IMPROVED"
        and validated_evaluation.required_next_gate == "V3_05_API_UI_SEMANTICS"
    )

    reasons = set(validated_decision.reason_codes)
    reasons.update(validated_evaluation.reason_codes)

    if probability_eligible:
        state: PresentationState = "CANDIDATE"
        probability = validated_decision.calibrated_probability
        probability_semantics: ProbabilitySemantics = "CALIBRATED_EVENT_PROBABILITY"
        reasons.add("CALIBRATED_PROBABILITY_EVIDENCE_ELIGIBLE")
    else:
        state = "ABSTAIN"
        probability = None
        probability_semantics = "WITHHELD"
        if validated_decision.policy_decision != "TRADE":
            reasons.add("SELECTIVE_DECISION_ABSTAINED")
        if validated_decision.calibrated_probability is None:
            reasons.add("CALIBRATED_PROBABILITY_UNAVAILABLE")
        if validated_decision.evidence_quality != "COMPLETE":
            reasons.add("SELECTIVE_EVIDENCE_NOT_COMPLETE")
        if validated_evaluation.assessment == "QUALITY_REGRESSED":
            reasons.add("SELECTIVE_EVALUATION_QUALITY_REGRESSED")
        elif validated_evaluation.assessment == "INSUFFICIENT_EVIDENCE":
            reasons.add("SELECTIVE_EVALUATION_INSUFFICIENT_EVIDENCE")
        if validated_evaluation.required_next_gate != "V3_05_API_UI_SEMANTICS":
            reasons.add("SELECTIVE_EVALUATION_NOT_UI_ELIGIBLE")

    payload = {
        "contract_version": SELECTIVE_PRESENTATION_VERSION,
        "symbol": validated_decision.symbol,
        "presentation_as_of": validated_evaluation.evaluation_as_of.isoformat(),
        "state": state,
        "calibrated_probability": probability,
        "probability_semantics": probability_semantics,
        "uncertainty_score": validated_decision.uncertainty_score,
        "uncertainty_level": validated_decision.uncertainty_level,
        "evidence_quality": validated_decision.evidence_quality,
        "evaluation_assessment": validated_evaluation.assessment,
        "reason_codes": sorted(reasons),
        "source_decision_fingerprint": validated_decision.decision_fingerprint,
        "source_evaluation_fingerprint": validated_evaluation.report_fingerprint,
        "status": "RESEARCH_PRESENTATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return SelectivePresentationV1(
        **payload,
        presentation_fingerprint=canonical_fingerprint(payload),
    )


def _validate_evaluation(
    item: SelectivePolicyEvaluationReport,
) -> SelectivePolicyEvaluationReport:
    validated = SelectivePolicyEvaluationReport.model_validate(item.model_dump(mode="python"))
    payload = validated.model_dump(mode="python", exclude={"report_fingerprint"})
    payload["evaluation_as_of"] = validated.evaluation_as_of.isoformat()
    if validated.report_fingerprint != canonical_fingerprint(payload):
        raise ValueError("selective evaluation report fingerprint mismatch")
    if validated.promotion_allowed or validated.can_authorize_capital:
        raise ValueError("selective evaluation cannot carry promotion or capital authority")
    return validated
