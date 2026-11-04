from __future__ import annotations

import importlib.util

import pytest
from pydantic import ValidationError
from test_india_bounded_tft import _run as tft_run
from test_india_patch_transformer import _run as patch_run
from test_india_time_mixer import _run as mixer_run

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_patch_transformer import PatchForecastConfig
from aitrading_intelligence.native_forecast_tournament import (
    NativeForecastTournamentSubmission,
    build_native_forecast_tournament_submission,
)
from aitrading_intelligence.structured_alpha_tournament import (
    screen_structured_alpha_tournament,
)

CPU_TORCH = pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Actual bounded model CPU training requires the pinned PyTorch workflow",
)


def test_inconclusive_model_has_no_native_evidence_to_submit() -> None:
    incomplete = patch_run(config=PatchForecastConfig(
        lookback_returns=40, patch_size=5
    ))
    assert incomplete.native_trace is None
    with pytest.raises(ValueError, match="inconclusive model without native trace"):
        build_native_forecast_tournament_submission(incomplete)


@CPU_TORCH
@pytest.mark.parametrize("runner", [patch_run, mixer_run, tft_run])
def test_actual_trained_model_generates_directional_tournament_evidence(runner) -> None:
    model = runner()
    artifact = build_native_forecast_tournament_submission(model)
    repeated = build_native_forecast_tournament_submission(model)
    assert artifact == repeated
    assert artifact.native_model_report.report_fingerprint == model.report_fingerprint
    assert artifact.directional.calibration.source_result_fingerprint == (
        model.report_fingerprint
    )
    assert artifact.directional.candidate.returns.source_result_fingerprint == (
        model.report_fingerprint
    )
    assert artifact.directional.calibration.heldout_forecast_input_fingerprint == (
        canonical_fingerprint(model.native_trace.holdout_predictions)
    )
    assert artifact.directional.calibration.holdout_fitted_or_tuned is False
    assert artifact.independently_attested_binary_execution is False
    assert artifact.independently_authenticated_indian_market_source is False
    assert artifact.externally_preregistered_trial_verified is False
    assert artifact.can_authorize_capital is False
    assert NativeForecastTournamentSubmission.model_validate_json(
        artifact.model_dump_json()
    ) == artifact


@CPU_TORCH
def test_actual_cpu_model_results_reach_existing_declared_family_tournament() -> None:
    records = [
        build_native_forecast_tournament_submission(runner())
        for runner in (patch_run, mixer_run, tft_run)
    ]
    first, *challengers = [r.directional.candidate for r in records]
    assert len({item.returns.source_result_fingerprint
                for item in [first, *challengers]}) == 3
    result = screen_structured_alpha_tournament(first, challengers)
    assert result.registered_challenger_count == 2
    assert result.observation_count == len(first.returns.observation_fingerprints)
    assert result.familywise_declared_tests_adjusted is True
    assert result.independent_indian_market_validation_complete is False
    assert result.can_authorize_capital is False
    assert result.promotion_allowed is False


@CPU_TORCH
def test_rewriting_directional_return_or_calibration_is_detected() -> None:
    original = build_native_forecast_tournament_submission(patch_run())
    tampered = original.model_dump(mode="json")
    tampered["directional"]["candidate"]["returns"]["net_returns_after_costs"][0] += (
        0.003
    )
    with pytest.raises(ValidationError):
        NativeForecastTournamentSubmission.model_validate(tampered)
    tampered = original.model_dump(mode="json")
    tampered["directional"]["calibration"]["fitted_forecast_slope"] += 1
    with pytest.raises(ValidationError):
        NativeForecastTournamentSubmission.model_validate(tampered)
    tampered = original.model_dump(mode="json")
    tampered["native_model_report"]["native_trace"]["calibration_predictions"][0] += 0.1
    with pytest.raises(ValidationError):
        NativeForecastTournamentSubmission.model_validate(tampered)


@CPU_TORCH
def test_native_research_submission_cannot_upgrade_to_live_trading() -> None:
    original = build_native_forecast_tournament_submission(tft_run())
    forged = original.model_dump(mode="json")
    forged["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        NativeForecastTournamentSubmission.model_validate(forged)
    forged = original.model_dump(mode="json")
    forged["independently_attested_binary_execution"] = True
    with pytest.raises(ValidationError):
        NativeForecastTournamentSubmission.model_validate(forged)
