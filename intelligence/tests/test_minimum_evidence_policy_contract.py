import pytest
from pydantic import ValidationError

from aitrading_intelligence.minimum_evidence_policy import (
    MANDATORY_REQUIREMENTS,
    MinimumEvidencePolicyConfigV1,
    ProductionCandidateEvidenceReportV1,
    build_production_candidate_evidence_report,
    evaluate_minimum_evidence_policy,
)


def test_v3_08_7_requires_all_six_validation_families() -> None:
    assert MANDATORY_REQUIREMENTS == (
        "PURGED_WALK_FORWARD",
        "CPCV",
        "DEPENDENCE_BOOTSTRAP",
        "OVERFIT_DIAGNOSTICS",
        "TRANSACTION_COST_STRESS",
        "STABILITY_REPORT",
    )

    report = evaluate_minimum_evidence_policy(
        purged_walk_forward=None,
        cpcv=None,
        monte_carlo=None,
        monte_carlo_config=None,
        overfit_diagnostics=None,
        transaction_cost_stress=None,
        stability_report=None,
    )

    assert report.state == "INCONCLUSIVE"
    assert report.mandatory_check_count == 6
    assert report.satisfied_check_count == 0
    assert all(item.state == "INCONCLUSIVE" for item in report.checks)
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False

    with pytest.raises(ValueError, match="requires every mandatory V3-08 evidence check"):
        build_production_candidate_evidence_report(report)


def test_default_policy_keeps_material_sample_floors_explicit() -> None:
    config = MinimumEvidencePolicyConfigV1()

    assert config.minimum_purged_folds == 3
    assert config.minimum_source_target_count == 60
    assert config.minimum_source_decision_buckets == 60
    assert config.minimum_cpcv_splits == 10
    assert config.minimum_monte_carlo_oos_trades == 20
    assert config.minimum_overfit_candidates == 3
    assert config.minimum_overfit_observations == 60
    assert config.minimum_cost_stress_scenarios == 9
    assert config.minimum_stability_observations == 60
    assert config.minimum_stability_evaluated_buckets_per_dimension == 2
    assert config.require_monte_carlo_governance_candidate is True


def test_candidate_evidence_contract_cannot_omit_mandatory_tests() -> None:
    with pytest.raises(ValidationError):
        ProductionCandidateEvidenceReportV1(
            minimum_evidence_report_fingerprint="1" * 64,
            mandatory_requirements=["PURGED_WALK_FORWARD"],
            mandatory_check_fingerprints=["2" * 64],
            mandatory_evidence_fingerprints=["3" * 64],
            report_fingerprint="4" * 64,
        )
