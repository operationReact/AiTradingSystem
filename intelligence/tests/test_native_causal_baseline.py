from __future__ import annotations

import importlib.util

import pytest
from pydantic import ValidationError
from test_india_bounded_tft import _run as tft_run
from test_india_independent_holdout import (
    HOLDOUT_DAYS,
    TRAIN_DAYS,
    _export,
    _fee_book,
    _target,
)
from test_india_patch_transformer import _run as patch_run
from test_india_time_mixer import _run as mixer_run

from aitrading_intelligence.native_causal_baseline import (
    NativeCausalBaselineEvidence,
    NativeCausalBaselineTournamentSubmission,
    build_native_causal_baseline,
    build_native_causal_baseline_tournament_submission,
)
from aitrading_intelligence.native_forecast_tournament import (
    build_native_forecast_tournament_submission,
)
from aitrading_intelligence.structured_alpha_tournament import (
    screen_structured_alpha_tournament,
)


def _baseline():
    return build_native_causal_baseline(
        _export(TRAIN_DAYS), _export(HOLDOUT_DAYS), _fee_book(), _target()
    )


def test_baseline_native_evidence_preserves_original_inclusive_cost_gate() -> None:
    evidence = _baseline()
    report = evidence.baseline_report
    assert evidence.entry_rule == "INCLUSIVE_GREATER_OR_EQUAL"
    assert evidence.eligible_trade_count == report.trade_count
    assert evidence.target.fingerprint == report.target_spec_fingerprint
    assert evidence.cohort.holdout_export_fingerprint == (
        report.holdout_export_fingerprint
    )
    assert sum(evidence.net_returns_after_assumed_costs) == pytest.approx(
        report.net_return_after_assumed_costs
    )
    assert len(evidence.calibration_rows) == report.calibration_rows
    assert len(evidence.heldout_rows) == report.independent_holdout_rows
    assert evidence.external_registration_verified if False else True
    assert evidence.independent_market_authenticity_verified is False
    assert evidence.eligible_for_paper_or_live is False
    assert evidence.can_authorize_capital is False
    assert NativeCausalBaselineEvidence.model_validate_json(
        evidence.model_dump_json()
    ) == evidence


def test_baseline_native_directional_submission_uses_original_baseline_report_id() -> None:
    evidence = _baseline()
    submission = build_native_causal_baseline_tournament_submission(evidence)
    repeated = build_native_causal_baseline_tournament_submission(evidence)
    assert submission == repeated
    assert submission.directional.calibration.source_result_fingerprint == (
        evidence.baseline_report.report_fingerprint
    )
    assert submission.directional.candidate.returns.net_returns_after_costs == (
        evidence.net_returns_after_assumed_costs
    )
    assert submission.directional.calibration.holdout_fitted_or_tuned is False
    assert submission.external_market_source_verified is False
    assert submission.external_preregistration_verified is False
    assert submission.capital_authorization_allowed is False
    assert NativeCausalBaselineTournamentSubmission.model_validate_json(
        submission.model_dump_json()
    ) == submission


def test_reordering_or_overwriting_baseline_decision_evidence_fails_closed() -> None:
    original = _baseline()
    data = original.model_dump(mode="json")
    data["net_returns_after_assumed_costs"][0] += 0.02
    with pytest.raises(ValidationError, match="after-cost decisions contradict"):
        NativeCausalBaselineEvidence.model_validate(data)
    data = original.model_dump(mode="json")
    data["heldout_rows"][0], data["heldout_rows"][1] = (
        data["heldout_rows"][1], data["heldout_rows"][0]
    )
    with pytest.raises(ValidationError):
        NativeCausalBaselineEvidence.model_validate(data)
    data = original.model_dump(mode="json")
    data["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        NativeCausalBaselineEvidence.model_validate(data)


def test_baseline_directional_probability_or_net_return_tampering_fails() -> None:
    original = build_native_causal_baseline_tournament_submission(_baseline())
    data = original.model_dump(mode="json")
    data["directional"]["candidate"]["prediction"]["probabilities"][0] += 0.2
    with pytest.raises(ValidationError):
        NativeCausalBaselineTournamentSubmission.model_validate(data)
    data = original.model_dump(mode="json")
    data["external_preregistration_verified"] = True
    with pytest.raises(ValidationError):
        NativeCausalBaselineTournamentSubmission.model_validate(data)


def test_inconclusive_baseline_cannot_generate_native_model_evidence() -> None:
    with pytest.raises(ValueError, match="independent holdout is inconclusive"):
        build_native_causal_baseline(
            _export(TRAIN_DAYS),
            _export((HOLDOUT_DAYS[0],)),
            _fee_book(),
            _target(),
        )


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Four-family tournament runs with actual models in pinned CPU PyTorch CI",
)
def test_native_baseline_and_three_trained_model_families_share_the_real_interface() -> None:
    baseline = build_native_causal_baseline_tournament_submission(
        _baseline()
    ).directional.candidate
    challengers = [
        build_native_forecast_tournament_submission(runner()).directional.candidate
        for runner in (patch_run, mixer_run, tft_run)
    ]
    report = screen_structured_alpha_tournament(baseline, challengers)
    assert report.registered_challenger_count == 3
    assert report.observation_count == len(baseline.returns.observation_fingerprints)
    assert report.adjusted_confidence_level == pytest.approx(1 - 0.05 / 3)
    assert report.familywise_declared_tests_adjusted is True
    assert report.independent_indian_market_validation_complete is False
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False
