from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .incremental_economic_value import (
    IncrementalValueConfig,
    NetReturnSeries,
    evaluate_incremental_economic_value,
)
from .model_diversity import (
    ModelPredictionSeries,
    build_model_diversity_report,
)

REPORT_VERSION = "joint-diversity-economic-gate.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
JointStatus = Literal["ADDITIVE_RESEARCH_ONLY", "NON_ADDITIVE", "INCONCLUSIVE"]


class JointDiversityEconomicReport(BaseModel):
    """Single paired challenger research screen, never production selection evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["joint-diversity-economic-gate.v1"] = REPORT_VERSION
    baseline_candidate_id: str
    challenger_candidate_id: str
    baseline_source_result_fingerprint: Hash64
    challenger_source_result_fingerprint: Hash64
    prediction_diversity_report_fingerprint: Hash64
    paired_economic_report_fingerprint: Hash64
    observation_count: Annotated[int, Field(ge=2)]
    correlation_defined: bool
    redundancy_risk: bool
    economic_assessment: Literal["PROMISING_RESEARCH", "NOT_IMPROVED", "INCONCLUSIVE"]
    mean_paired_net_uplift: float
    status: JointStatus
    reason_codes: list[str]
    # Selecting over many trials needs a separate multiplicity / independent holdout gate.
    multiple_testing_adjusted: Literal[False] = False
    independent_indian_market_validation_complete: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_report(self) -> JointDiversityEconomicReport:
        if self.status == "ADDITIVE_RESEARCH_ONLY" and self.reason_codes:
            raise ValueError("additive research screening cannot contain failure reasons")
        if self.status != "ADDITIVE_RESEARCH_ONLY" and not self.reason_codes:
            raise ValueError("non-additive or inconclusive screening requires reasons")
        if self.status == "ADDITIVE_RESEARCH_ONLY" and (
            not self.correlation_defined
            or self.redundancy_risk
            or self.economic_assessment != "PROMISING_RESEARCH"
        ):
            raise ValueError("additive research screening requires both independent gates")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("joint diversity-economic fingerprint mismatch")
        return self


def evaluate_joint_diversity_economic_gate(
    baseline_prediction: ModelPredictionSeries,
    challenger_prediction: ModelPredictionSeries,
    baseline_returns: NetReturnSeries,
    challenger_returns: NetReturnSeries,
    *,
    economic_config: IncrementalValueConfig | None = None,
) -> JointDiversityEconomicReport:
    """Combine aligned predictions with paired OOS *after-cost* economic evidence.

    Strictly a two-candidate, research-only pre-screen. The same observed OOS rows
    must underlie both prediction/diversity and economic comparisons. This does
    not correct for repeated trials, data snooping, independent India validation,
    PAPER/shadow runs, or trading costs omitted from upstream inputs.
    """
    bp = ModelPredictionSeries.model_validate(baseline_prediction.model_dump(mode="python"))
    cp = ModelPredictionSeries.model_validate(challenger_prediction.model_dump(mode="python"))
    br = NetReturnSeries.model_validate(baseline_returns.model_dump(mode="python"))
    cr = NetReturnSeries.model_validate(challenger_returns.model_dump(mode="python"))

    if bp.candidate_id == cp.candidate_id:
        raise ValueError("joint gate requires two distinct candidates")
    for predictions, returns in ((bp, br), (cp, cr)):
        for field in (
            "candidate_id",
            "model_version_ref",
            "source_result_fingerprint",
            "observation_fingerprints",
        ):
            if getattr(predictions, field) != getattr(returns, field):
                raise ValueError(f"prediction/net-return evidence mismatches {field}")

    diversity = build_model_diversity_report([bp, cp])
    economic = evaluate_incremental_economic_value(
        br, cr, config=economic_config
    )
    pair = diversity.pairs[0]
    reasons: list[str] = []
    if not pair.correlation_defined:
        reasons.append("DIVERSITY_CORRELATION_UNDEFINED")
    elif pair.redundancy_risk:
        reasons.append("PROBABILITY_SIGNAL_REDUNDANT")
    if economic.assessment != "PROMISING_RESEARCH":
        reasons.extend(f"ECONOMIC_{code}" for code in economic.reason_codes)

    if not pair.correlation_defined or economic.assessment == "INCONCLUSIVE":
        status: JointStatus = "INCONCLUSIVE"
    elif reasons:
        status = "NON_ADDITIVE"
    else:
        status = "ADDITIVE_RESEARCH_ONLY"

    payload = {
        "contract_version": REPORT_VERSION,
        "baseline_candidate_id": bp.candidate_id,
        "challenger_candidate_id": cp.candidate_id,
        "baseline_source_result_fingerprint": bp.source_result_fingerprint,
        "challenger_source_result_fingerprint": cp.source_result_fingerprint,
        "prediction_diversity_report_fingerprint": diversity.report_fingerprint,
        "paired_economic_report_fingerprint": economic.report_fingerprint,
        "observation_count": economic.observation_count,
        "correlation_defined": pair.correlation_defined,
        "redundancy_risk": pair.redundancy_risk,
        "economic_assessment": economic.assessment,
        "mean_paired_net_uplift": economic.mean_paired_uplift,
        "status": status,
        "reason_codes": sorted(set(reasons)),
        "multiple_testing_adjusted": False,
        "independent_indian_market_validation_complete": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return JointDiversityEconomicReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )
