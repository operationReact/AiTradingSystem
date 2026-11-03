from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .combinatorial_purged_cv import CombinatorialPurgedCvPlanV1
from .fingerprints import canonical_fingerprint
from .monte_carlo import MONTE_CARLO_VERSION, MonteCarloConfig, MonteCarloEvidence
from .overfit_diagnostics import OverfitDiagnosticsReportV1
from .purged_walk_forward import PurgedWalkForwardPlanV1
from .stability_report import StabilityReportV1
from .transaction_cost_stress import TransactionCostStressReportV1

MINIMUM_EVIDENCE_POLICY_VERSION = "minimum-evidence-policy.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Requirement = Literal[
    "PURGED_WALK_FORWARD",
    "CPCV",
    "DEPENDENCE_BOOTSTRAP",
    "OVERFIT_DIAGNOSTICS",
    "TRANSACTION_COST_STRESS",
    "STABILITY_REPORT",
]
CheckState = Literal["SATISFIED", "INCONCLUSIVE"]
PolicyState = Literal["PRODUCTION_CANDIDATE", "INCONCLUSIVE"]

MANDATORY_REQUIREMENTS: tuple[Requirement, ...] = (
    "PURGED_WALK_FORWARD",
    "CPCV",
    "DEPENDENCE_BOOTSTRAP",
    "OVERFIT_DIAGNOSTICS",
    "TRANSACTION_COST_STRESS",
    "STABILITY_REPORT",
)


class MinimumEvidencePolicyConfigV1(BaseModel):
    """Independent minimum-evidence floors for a V3 production candidate."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["minimum-evidence-policy.v1"] = MINIMUM_EVIDENCE_POLICY_VERSION
    minimum_purged_folds: Annotated[int, Field(ge=1, le=10_000)] = 3
    minimum_source_target_count: Annotated[int, Field(ge=1, le=1_000_000)] = 60
    minimum_source_decision_buckets: Annotated[int, Field(ge=1, le=1_000_000)] = 60
    minimum_cpcv_splits: Annotated[int, Field(ge=1, le=10_000)] = 10
    minimum_monte_carlo_oos_trades: Annotated[int, Field(ge=5, le=1_000_000)] = 20
    minimum_overfit_candidates: Annotated[int, Field(ge=2, le=100_000)] = 3
    minimum_overfit_observations: Annotated[int, Field(ge=20, le=1_000_000)] = 60
    minimum_cost_stress_scenarios: Annotated[int, Field(ge=1, le=10_000)] = 9
    minimum_stability_observations: Annotated[int, Field(ge=2, le=1_000_000)] = 60
    minimum_stability_evaluated_buckets_per_dimension: Annotated[
        int,
        Field(ge=2, le=100),
    ] = 2
    require_monte_carlo_governance_candidate: bool = True

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class MinimumEvidenceCheckV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    requirement: Requirement
    state: CheckState
    reason_codes: list[str]
    artifact_fingerprint: Hash64 | None = None
    observed_sample_count: Annotated[int | None, Field(ge=0)] = None
    minimum_required_sample_count: Annotated[int | None, Field(ge=0)] = None
    check_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_check(self) -> MinimumEvidenceCheckV1:
        if self.state == "SATISFIED":
            if self.reason_codes:
                raise ValueError("satisfied minimum-evidence check cannot contain reasons")
            if self.artifact_fingerprint is None:
                raise ValueError("satisfied minimum-evidence check requires artifact fingerprint")
        elif not self.reason_codes:
            raise ValueError("inconclusive minimum-evidence check requires reason codes")
        if self.check_fingerprint != canonical_fingerprint(_check_payload(self)):
            raise ValueError("minimum-evidence check fingerprint mismatch")
        return self


class MinimumEvidencePolicyReportV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["minimum-evidence-policy.v1"] = MINIMUM_EVIDENCE_POLICY_VERSION
    state: PolicyState
    reason_codes: list[str]
    config_fingerprint: Hash64
    mandatory_check_count: Literal[6] = 6
    satisfied_check_count: Annotated[int, Field(ge=0, le=6)]
    checks: list[MinimumEvidenceCheckV1] = Field(min_length=6, max_length=6)
    report_fingerprint: Hash64
    status: Literal["RESEARCH_MINIMUM_EVIDENCE_POLICY_ONLY"] = (
        "RESEARCH_MINIMUM_EVIDENCE_POLICY_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_report(self) -> MinimumEvidencePolicyReportV1:
        if [item.requirement for item in self.checks] != list(MANDATORY_REQUIREMENTS):
            raise ValueError("minimum-evidence checks must use the canonical mandatory order")
        satisfied = sum(item.state == "SATISFIED" for item in self.checks)
        if self.satisfied_check_count != satisfied:
            raise ValueError("minimum-evidence satisfied_check_count mismatch")
        expected_state: PolicyState = (
            "PRODUCTION_CANDIDATE"
            if satisfied == len(MANDATORY_REQUIREMENTS)
            else "INCONCLUSIVE"
        )
        if self.state != expected_state:
            raise ValueError("minimum-evidence policy state must reflect every mandatory check")
        if self.state == "PRODUCTION_CANDIDATE" and self.reason_codes:
            raise ValueError("production-candidate evidence cannot contain policy reasons")
        if self.state == "INCONCLUSIVE" and not self.reason_codes:
            raise ValueError("inconclusive minimum-evidence policy requires reason codes")
        if self.report_fingerprint != canonical_fingerprint(_policy_report_payload(self)):
            raise ValueError("minimum-evidence policy report fingerprint mismatch")
        return self


class ProductionCandidateEvidenceReportV1(BaseModel):
    """Evidence-complete candidate record; still grants no production/capital authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["minimum-evidence-policy.v1"] = MINIMUM_EVIDENCE_POLICY_VERSION
    minimum_evidence_report_fingerprint: Hash64
    mandatory_requirements: list[Requirement] = Field(min_length=6, max_length=6)
    mandatory_check_fingerprints: list[Hash64] = Field(min_length=6, max_length=6)
    mandatory_evidence_fingerprints: list[Hash64] = Field(min_length=6, max_length=6)
    report_fingerprint: Hash64
    status: Literal["PRODUCTION_CANDIDATE_EVIDENCE_ONLY"] = (
        "PRODUCTION_CANDIDATE_EVIDENCE_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_candidate(self) -> ProductionCandidateEvidenceReportV1:
        if self.mandatory_requirements != list(MANDATORY_REQUIREMENTS):
            raise ValueError("production-candidate evidence must contain every mandatory test")
        if len(set(self.mandatory_check_fingerprints)) != len(
            self.mandatory_check_fingerprints
        ):
            raise ValueError("production-candidate check fingerprints must be unique")
        if len(set(self.mandatory_evidence_fingerprints)) != len(
            self.mandatory_evidence_fingerprints
        ):
            raise ValueError("production-candidate evidence fingerprints must be unique")
        if self.report_fingerprint != canonical_fingerprint(_candidate_report_payload(self)):
            raise ValueError("production-candidate evidence report fingerprint mismatch")
        return self


def _check_payload(check: MinimumEvidenceCheckV1) -> dict[str, object]:
    return {
        "requirement": check.requirement,
        "state": check.state,
        "reason_codes": check.reason_codes,
        "artifact_fingerprint": check.artifact_fingerprint,
        "observed_sample_count": check.observed_sample_count,
        "minimum_required_sample_count": check.minimum_required_sample_count,
    }


def _make_check(
    requirement: Requirement,
    *,
    reasons: list[str],
    artifact_fingerprint: str | None,
    observed_sample_count: int | None = None,
    minimum_required_sample_count: int | None = None,
) -> MinimumEvidenceCheckV1:
    state: CheckState = "SATISFIED" if not reasons else "INCONCLUSIVE"
    payload = {
        "requirement": requirement,
        "state": state,
        "reason_codes": sorted(set(reasons)),
        "artifact_fingerprint": artifact_fingerprint if not reasons else artifact_fingerprint,
        "observed_sample_count": observed_sample_count,
        "minimum_required_sample_count": minimum_required_sample_count,
    }
    return MinimumEvidenceCheckV1(
        **payload,
        check_fingerprint=canonical_fingerprint(payload),
    )


def _monte_carlo_payload(evidence: MonteCarloEvidence) -> dict[str, object]:
    return {
        "monte_carlo_version": MONTE_CARLO_VERSION,
        "config_fingerprint": evidence.config_fingerprint,
        "source_walk_forward_fingerprint": evidence.source_walk_forward_fingerprint,
        "source_trade_fingerprints": evidence.source_trade_fingerprints,
        "returns_distribution_fingerprint": evidence.returns_distribution_fingerprint,
        "drawdowns_distribution_fingerprint": evidence.drawdowns_distribution_fingerprint,
        "summary": evidence.summary.model_dump(mode="json"),
        "assessment": evidence.assessment,
        "next_gate": evidence.next_gate,
        "status": "RESEARCH_MONTE_CARLO_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def _policy_report_payload(report: MinimumEvidencePolicyReportV1) -> dict[str, object]:
    return {
        "policy_version": MINIMUM_EVIDENCE_POLICY_VERSION,
        "state": report.state,
        "reason_codes": report.reason_codes,
        "config_fingerprint": report.config_fingerprint,
        "mandatory_check_count": report.mandatory_check_count,
        "satisfied_check_count": report.satisfied_check_count,
        "checks": [item.model_dump(mode="json") for item in report.checks],
        "status": "RESEARCH_MINIMUM_EVIDENCE_POLICY_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def _candidate_report_payload(
    report: ProductionCandidateEvidenceReportV1,
) -> dict[str, object]:
    return {
        "policy_version": MINIMUM_EVIDENCE_POLICY_VERSION,
        "minimum_evidence_report_fingerprint": report.minimum_evidence_report_fingerprint,
        "mandatory_requirements": report.mandatory_requirements,
        "mandatory_check_fingerprints": report.mandatory_check_fingerprints,
        "mandatory_evidence_fingerprints": report.mandatory_evidence_fingerprints,
        "status": "PRODUCTION_CANDIDATE_EVIDENCE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def _purged_check(
    plan: PurgedWalkForwardPlanV1 | None,
    config: MinimumEvidencePolicyConfigV1,
) -> tuple[MinimumEvidenceCheckV1, PurgedWalkForwardPlanV1 | None]:
    if plan is None:
        return (
            _make_check(
                "PURGED_WALK_FORWARD",
                reasons=["MISSING_PURGED_WALK_FORWARD"],
                artifact_fingerprint=None,
                minimum_required_sample_count=config.minimum_source_decision_buckets,
            ),
            None,
        )
    checked = PurgedWalkForwardPlanV1.model_validate(plan.model_dump(mode="python"))
    reasons: list[str] = []
    if checked.fold_count < config.minimum_purged_folds:
        reasons.append("INSUFFICIENT_PURGED_FOLDS")
    if checked.source_target_count < config.minimum_source_target_count:
        reasons.append("INSUFFICIENT_PURGED_SOURCE_TARGETS")
    if checked.source_decision_bucket_count < config.minimum_source_decision_buckets:
        reasons.append("INSUFFICIENT_PURGED_DECISION_BUCKETS")
    return (
        _make_check(
            "PURGED_WALK_FORWARD",
            reasons=reasons,
            artifact_fingerprint=checked.plan_fingerprint,
            observed_sample_count=checked.source_decision_bucket_count,
            minimum_required_sample_count=config.minimum_source_decision_buckets,
        ),
        checked,
    )


def _cpcv_check(
    plan: CombinatorialPurgedCvPlanV1 | None,
    purged: PurgedWalkForwardPlanV1 | None,
    config: MinimumEvidencePolicyConfigV1,
) -> MinimumEvidenceCheckV1:
    if plan is None:
        return _make_check(
            "CPCV",
            reasons=["MISSING_CPCV"],
            artifact_fingerprint=None,
            minimum_required_sample_count=config.minimum_cpcv_splits,
        )
    checked = CombinatorialPurgedCvPlanV1.model_validate(plan.model_dump(mode="python"))
    reasons: list[str] = []
    if checked.split_count < config.minimum_cpcv_splits:
        reasons.append("INSUFFICIENT_CPCV_SPLITS")
    if checked.source_target_count < config.minimum_source_target_count:
        reasons.append("INSUFFICIENT_CPCV_SOURCE_TARGETS")
    if checked.source_decision_bucket_count < config.minimum_source_decision_buckets:
        reasons.append("INSUFFICIENT_CPCV_DECISION_BUCKETS")
    if purged is None:
        reasons.append("PURGED_WALK_FORWARD_REQUIRED_FOR_CPCV_LINEAGE")
    else:
        same_target_archive = (
            checked.target_spec_fingerprint == purged.target_spec_fingerprint
            and checked.label_as_of == purged.label_as_of
            and checked.bar_interval == purged.bar_interval
            and checked.horizon_bars == purged.horizon_bars
            and checked.source_target_fingerprints == purged.source_target_fingerprints
            and checked.source_decision_times == purged.source_decision_times
        )
        if not same_target_archive:
            reasons.append("CPCV_TARGET_ARCHIVE_LINEAGE_MISMATCH")
    return _make_check(
        "CPCV",
        reasons=reasons,
        artifact_fingerprint=checked.plan_fingerprint,
        observed_sample_count=checked.split_count,
        minimum_required_sample_count=config.minimum_cpcv_splits,
    )


def _bootstrap_check(
    evidence: MonteCarloEvidence | None,
    monte_carlo_config: MonteCarloConfig | None,
    config: MinimumEvidencePolicyConfigV1,
) -> MinimumEvidenceCheckV1:
    if evidence is None or monte_carlo_config is None:
        reasons = []
        if evidence is None:
            reasons.append("MISSING_MONTE_CARLO_EVIDENCE")
        if monte_carlo_config is None:
            reasons.append("MISSING_MONTE_CARLO_CONFIG")
        return _make_check(
            "DEPENDENCE_BOOTSTRAP",
            reasons=reasons,
            artifact_fingerprint=None,
            minimum_required_sample_count=config.minimum_monte_carlo_oos_trades,
        )

    checked = MonteCarloEvidence.model_validate(evidence.model_dump(mode="python"))
    checked_config = MonteCarloConfig.model_validate(
        monte_carlo_config.model_dump(mode="python")
    )
    reasons: list[str] = []
    expected_fingerprint = canonical_fingerprint(_monte_carlo_payload(checked))
    integrity_ok = True
    if checked.config_fingerprint != checked_config.fingerprint:
        reasons.append("MONTE_CARLO_CONFIG_FINGERPRINT_MISMATCH")
        integrity_ok = False
    if checked.evidence_fingerprint != expected_fingerprint:
        reasons.append("MONTE_CARLO_EVIDENCE_FINGERPRINT_MISMATCH")
        integrity_ok = False
    if checked.summary.oos_trade_count != len(checked.source_trade_fingerprints):
        reasons.append("MONTE_CARLO_OOS_TRADE_COUNT_MISMATCH")
    if len(set(checked.source_trade_fingerprints)) != len(checked.source_trade_fingerprints):
        reasons.append("MONTE_CARLO_SOURCE_TRADES_NOT_UNIQUE")
    if checked_config.block_size <= 1:
        reasons.append("DEPENDENCE_BOOTSTRAP_BLOCK_SIZE_NOT_PRESERVING")
    if checked.summary.oos_trade_count < config.minimum_monte_carlo_oos_trades:
        reasons.append("INSUFFICIENT_MONTE_CARLO_OOS_TRADES")
    if (
        config.require_monte_carlo_governance_candidate
        and checked.assessment != "V2_09_GOVERNANCE_CANDIDATE"
    ):
        reasons.append("MONTE_CARLO_RESEARCH_REVIEW")
    return _make_check(
        "DEPENDENCE_BOOTSTRAP",
        reasons=reasons,
        artifact_fingerprint=checked.evidence_fingerprint if integrity_ok else None,
        observed_sample_count=checked.summary.oos_trade_count,
        minimum_required_sample_count=config.minimum_monte_carlo_oos_trades,
    )


def _overfit_check(
    report: OverfitDiagnosticsReportV1 | None,
    config: MinimumEvidencePolicyConfigV1,
) -> MinimumEvidenceCheckV1:
    if report is None:
        return _make_check(
            "OVERFIT_DIAGNOSTICS",
            reasons=["MISSING_OVERFIT_DIAGNOSTICS"],
            artifact_fingerprint=None,
            minimum_required_sample_count=config.minimum_overfit_observations,
        )
    checked = OverfitDiagnosticsReportV1.model_validate(report.model_dump(mode="python"))
    reasons: list[str] = []
    if checked.state != "EVALUATED":
        reasons.append("OVERFIT_DIAGNOSTICS_INCONCLUSIVE")
    if checked.candidate_count < config.minimum_overfit_candidates:
        reasons.append("INSUFFICIENT_OVERFIT_CANDIDATES")
    if checked.observation_count < config.minimum_overfit_observations:
        reasons.append("INSUFFICIENT_OVERFIT_OBSERVATIONS")
    if checked.pbo.state != "EVALUATED":
        reasons.append("PBO_INCONCLUSIVE")
    if checked.deflated_sharpe.state != "EVALUATED":
        reasons.append("DEFLATED_SHARPE_INCONCLUSIVE")
    if checked.deflated_sharpe.independent_trial_count is None:
        reasons.append("DEFLATED_SHARPE_INDEPENDENT_TRIAL_COUNT_MISSING")
    return _make_check(
        "OVERFIT_DIAGNOSTICS",
        reasons=reasons,
        artifact_fingerprint=checked.report_fingerprint,
        observed_sample_count=checked.observation_count,
        minimum_required_sample_count=config.minimum_overfit_observations,
    )


def _cost_stress_check(
    report: TransactionCostStressReportV1 | None,
    config: MinimumEvidencePolicyConfigV1,
) -> MinimumEvidenceCheckV1:
    if report is None:
        return _make_check(
            "TRANSACTION_COST_STRESS",
            reasons=["MISSING_TRANSACTION_COST_STRESS"],
            artifact_fingerprint=None,
            minimum_required_sample_count=config.minimum_cost_stress_scenarios,
        )
    checked = TransactionCostStressReportV1.model_validate(report.model_dump(mode="python"))
    reasons: list[str] = []
    if checked.state != "EVALUATED":
        reasons.append("TRANSACTION_COST_STRESS_INCONCLUSIVE")
    if checked.scenario_count < config.minimum_cost_stress_scenarios:
        reasons.append("INSUFFICIENT_COST_STRESS_SCENARIOS")
    if checked.baseline_trade_count <= 0:
        reasons.append("COST_STRESS_HAS_NO_BASELINE_TRADES")
    has_baseline = any(
        item.transaction_cost_multiplier == 1.0 and item.slippage_multiplier == 1.0
        for item in checked.scenarios
    )
    if checked.state == "EVALUATED" and not has_baseline:
        reasons.append("COST_STRESS_BASELINE_SCENARIO_MISSING")
    return _make_check(
        "TRANSACTION_COST_STRESS",
        reasons=reasons,
        artifact_fingerprint=checked.report_fingerprint,
        observed_sample_count=checked.scenario_count,
        minimum_required_sample_count=config.minimum_cost_stress_scenarios,
    )


def _stability_check(
    report: StabilityReportV1 | None,
    config: MinimumEvidencePolicyConfigV1,
) -> MinimumEvidenceCheckV1:
    if report is None:
        return _make_check(
            "STABILITY_REPORT",
            reasons=["MISSING_STABILITY_REPORT"],
            artifact_fingerprint=None,
            minimum_required_sample_count=config.minimum_stability_observations,
        )
    checked = StabilityReportV1.model_validate(report.model_dump(mode="python"))
    reasons: list[str] = []
    if checked.state != "EVALUATED":
        reasons.append("STABILITY_REPORT_INCONCLUSIVE")
    if checked.observation_count < config.minimum_stability_observations:
        reasons.append("INSUFFICIENT_STABILITY_OBSERVATIONS")
    for dimension in (checked.regime, checked.liquidity, checked.sector):
        if dimension.state != "EVALUATED":
            reasons.append(f"{dimension.dimension}_STABILITY_INCONCLUSIVE")
        if (
            dimension.evaluated_bucket_count
            < config.minimum_stability_evaluated_buckets_per_dimension
        ):
            reasons.append(f"INSUFFICIENT_{dimension.dimension}_STABILITY_BUCKETS")
    return _make_check(
        "STABILITY_REPORT",
        reasons=reasons,
        artifact_fingerprint=checked.report_fingerprint,
        observed_sample_count=checked.observation_count,
        minimum_required_sample_count=config.minimum_stability_observations,
    )


def evaluate_minimum_evidence_policy(
    *,
    purged_walk_forward: PurgedWalkForwardPlanV1 | None,
    cpcv: CombinatorialPurgedCvPlanV1 | None,
    monte_carlo: MonteCarloEvidence | None,
    monte_carlo_config: MonteCarloConfig | None,
    overfit_diagnostics: OverfitDiagnosticsReportV1 | None,
    transaction_cost_stress: TransactionCostStressReportV1 | None,
    stability_report: StabilityReportV1 | None,
    config: MinimumEvidencePolicyConfigV1 | None = None,
) -> MinimumEvidencePolicyReportV1:
    """Evaluate every mandatory V3-08 evidence family without granting capital authority."""

    cfg = config or MinimumEvidencePolicyConfigV1()
    purged_check, checked_purged = _purged_check(purged_walk_forward, cfg)
    checks = [
        purged_check,
        _cpcv_check(cpcv, checked_purged, cfg),
        _bootstrap_check(monte_carlo, monte_carlo_config, cfg),
        _overfit_check(overfit_diagnostics, cfg),
        _cost_stress_check(transaction_cost_stress, cfg),
        _stability_check(stability_report, cfg),
    ]
    satisfied = sum(item.state == "SATISFIED" for item in checks)
    state: PolicyState = (
        "PRODUCTION_CANDIDATE"
        if satisfied == len(MANDATORY_REQUIREMENTS)
        else "INCONCLUSIVE"
    )
    reasons = sorted(
        f"{item.requirement}:{reason}"
        for item in checks
        for reason in item.reason_codes
    )
    payload = {
        "policy_version": MINIMUM_EVIDENCE_POLICY_VERSION,
        "state": state,
        "reason_codes": reasons,
        "config_fingerprint": cfg.fingerprint,
        "mandatory_check_count": 6,
        "satisfied_check_count": satisfied,
        "checks": [item.model_dump(mode="json") for item in checks],
        "status": "RESEARCH_MINIMUM_EVIDENCE_POLICY_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return MinimumEvidencePolicyReportV1(
        state=state,
        reason_codes=reasons,
        config_fingerprint=cfg.fingerprint,
        satisfied_check_count=satisfied,
        checks=checks,
        report_fingerprint=canonical_fingerprint(payload),
    )


def build_production_candidate_evidence_report(
    policy_report: MinimumEvidencePolicyReportV1,
) -> ProductionCandidateEvidenceReportV1:
    """Create a candidate record only after all six mandatory evidence checks pass."""

    checked = MinimumEvidencePolicyReportV1.model_validate(
        policy_report.model_dump(mode="python")
    )
    if checked.state != "PRODUCTION_CANDIDATE":
        raise ValueError(
            "production-candidate report requires every mandatory V3-08 evidence check"
        )
    evidence_fingerprints = [
        item.artifact_fingerprint for item in checked.checks if item.artifact_fingerprint
    ]
    if len(evidence_fingerprints) != len(MANDATORY_REQUIREMENTS):
        raise ValueError("production-candidate report is missing mandatory evidence lineage")
    payload = {
        "policy_version": MINIMUM_EVIDENCE_POLICY_VERSION,
        "minimum_evidence_report_fingerprint": checked.report_fingerprint,
        "mandatory_requirements": list(MANDATORY_REQUIREMENTS),
        "mandatory_check_fingerprints": [item.check_fingerprint for item in checked.checks],
        "mandatory_evidence_fingerprints": evidence_fingerprints,
        "status": "PRODUCTION_CANDIDATE_EVIDENCE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ProductionCandidateEvidenceReportV1(
        minimum_evidence_report_fingerprint=checked.report_fingerprint,
        mandatory_requirements=list(MANDATORY_REQUIREMENTS),
        mandatory_check_fingerprints=[item.check_fingerprint for item in checked.checks],
        mandatory_evidence_fingerprints=evidence_fingerprints,
        report_fingerprint=canonical_fingerprint(payload),
    )


__all__ = [
    "MANDATORY_REQUIREMENTS",
    "MINIMUM_EVIDENCE_POLICY_VERSION",
    "MinimumEvidenceCheckV1",
    "MinimumEvidencePolicyConfigV1",
    "MinimumEvidencePolicyReportV1",
    "ProductionCandidateEvidenceReportV1",
    "build_production_candidate_evidence_report",
    "evaluate_minimum_evidence_policy",
]
