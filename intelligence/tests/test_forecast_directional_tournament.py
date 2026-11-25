from __future__ import annotations

from datetime import timedelta

import pytest
from pydantic import ValidationError
from test_cross_model_causal_cohort import _case
from test_india_independent_holdout import TRAIN_DAYS, _export

from aitrading_intelligence.cross_model_causal_cohort import (
    bind_common_causal_tournament_cohort,
)
from aitrading_intelligence.forecast_directional_tournament import (
    DirectionalCalibrationRecord,
    ForecastTournamentSubmission,
    build_directional_tournament_submission,
)
from aitrading_intelligence.india_causal_forecast_baseline import _causal_rows
from aitrading_intelligence.structured_alpha_tournament import (
    screen_structured_alpha_tournament,
)


def _args(candidate_id="patch", *, net_returns=None, forecasts=None):
    parent, rows, target = _case()
    cohort = bind_common_causal_tournament_cohort(parent, rows, target)
    earlier = _causal_rows(_export(TRAIN_DAYS), lookback=8, horizon=1)
    available = [row.label_available_at for row in earlier[-32:]]
    expected = [0.005 if i % 2 == 0 else -0.004
                for i in range(len(available))]
    realized = [0.003 if i % 2 == 0 else -0.003
                for i in range(len(available))]
    holdout = forecasts or [
        (0.001 + (i % 5) * 0.0005) * (1 if i % 3 else -1)
        for i in range(len(rows))
    ]
    return cohort, {
        "candidate_id": candidate_id,
        "model_version_ref": f"{candidate_id}-v1",
        "model_source_result_fingerprint": {
            "baseline": "a", "patch": "b", "tft": "c", "timemixer": "d"
        }.get(candidate_id, "e") * 64,
        "calibration_forecasts": expected,
        "calibration_realized_returns": realized,
        "calibration_label_available_times": available,
        "heldout_forecasts": holdout,
        "heldout_net_returns_after_costs": (
            net_returns if net_returns is not None
            else [0.0001 if i % 2 == 0 else 0 for i in range(len(rows))]
        ),
    }


def test_train_only_directional_probability_evidence_is_deterministic() -> None:
    cohort, inputs = _args()
    first = build_directional_tournament_submission(cohort, **inputs)
    second = build_directional_tournament_submission(cohort, **inputs)
    assert first == second
    assert first.calibration.calibration_rows == 32
    assert first.calibration.positive_labels == 16
    assert first.calibration.negative_labels == 16
    assert first.calibration.calibration_in_sample_brier < 0.25
    assert first.calibration.holdout_fitted_or_tuned is False
    assert first.calibration.real_market_probability_calibration_verified is False
    assert first.candidate.prediction.observation_fingerprints == (
        cohort.observation_fingerprints
    )
    assert first.candidate.returns.fold_binding_fingerprint == (
        cohort.fold_binding_fingerprint
    )
    assert len(first.candidate.prediction.probabilities) == len(cohort.decision_times)
    assert all(0 <= value <= 1 for value in first.candidate.prediction.probabilities)
    assert first.independently_verified_model_run is False
    assert first.can_authorize_capital is False


def test_holdout_net_outcomes_cannot_refit_calibrator_or_prediction_series() -> None:
    cohort, inputs = _args()
    first = build_directional_tournament_submission(cohort, **inputs)
    changed = {
        **inputs,
        "heldout_net_returns_after_costs": [0.009] * len(cohort.decision_times),
    }
    second = build_directional_tournament_submission(cohort, **changed)
    assert first.calibration == second.calibration
    assert first.candidate.prediction == second.candidate.prediction
    assert first.candidate.returns.series_fingerprint != (
        second.candidate.returns.series_fingerprint
    )
    assert first.submission_fingerprint != second.submission_fingerprint


def test_future_calibration_labels_are_rejected_even_with_matching_input_lengths() -> None:
    cohort, inputs = _args()
    input_times = list(inputs["calibration_label_available_times"])
    input_times[-1] = cohort.decision_times[0] + timedelta(minutes=5)
    with pytest.raises(ValueError, match="unavailable before holdout"):
        build_directional_tournament_submission(
            cohort, **{**inputs, "calibration_label_available_times": input_times}
        )


def test_calibrator_does_not_accept_constant_forecasts_or_one_label_class() -> None:
    cohort, inputs = _args()
    with pytest.raises(ValueError, match="constant return forecasts"):
        build_directional_tournament_submission(
            cohort,
            **{**inputs, "calibration_forecasts": [0.002] * 32},
        )
    with pytest.raises(ValueError, match="both positive and negative labels"):
        build_directional_tournament_submission(
            cohort,
            **{**inputs, "calibration_realized_returns": [0.01] * 32},
        )


def test_incomplete_or_non_finite_forecast_or_net_return_evidence_fails_closed() -> None:
    cohort, inputs = _args()
    with pytest.raises(ValueError, match="prediction membership"):
        build_directional_tournament_submission(
            cohort,
            **{**inputs, "heldout_forecasts": [0.001]},
        )
    with pytest.raises(ValueError, match="finite signed returns"):
        build_directional_tournament_submission(
            cohort,
            **{**inputs, "heldout_forecasts": [float("nan")] * len(cohort.decision_times)},
        )
    with pytest.raises(ValueError, match="net returns and predictions must align"):
        build_directional_tournament_submission(
            cohort,
            **{**inputs, "heldout_net_returns_after_costs": [0.0]},
        )


def test_three_forecast_family_candidates_are_tournament_contract_compatible() -> None:
    candidate_ids = ["baseline", "patch", "tft"]
    built = []
    for i, name in enumerate(candidate_ids):
        cohort, inputs = _args(
            name,
            forecasts=[
                (0.001 + (j % (5 + i)) * 0.0004) * (
                    1 if j % (3 + i) else -1
                )
                for j in range(72)
            ],
            net_returns=[0.0003 * i if j % 2 == 0 else 0 for j in range(72)],
        )
        built.append(
            build_directional_tournament_submission(cohort, **inputs)
        )
    tournament = screen_structured_alpha_tournament(
        built[0].candidate, [item.candidate for item in built[1:]]
    )
    assert tournament.registered_challenger_count == 2
    assert tournament.observation_count == 72
    assert tournament.familywise_declared_tests_adjusted is True
    assert tournament.independent_indian_market_validation_complete is False
    assert tournament.promotion_allowed is False
    assert tournament.can_authorize_capital is False


def test_calibrator_and_submission_hashes_prevent_rewriting_decisions() -> None:
    cohort, inputs = _args()
    evidence = build_directional_tournament_submission(cohort, **inputs)
    altered = evidence.calibration.model_dump(mode="json")
    altered["fitted_forecast_slope"] += 1.0
    with pytest.raises(ValidationError, match="directional calibration fingerprint"):
        DirectionalCalibrationRecord.model_validate(altered)
    changed = evidence.model_dump(mode="json")
    changed["candidate"]["returns"]["net_returns_after_costs"][0] += 0.1
    with pytest.raises(ValidationError):
        ForecastTournamentSubmission.model_validate(changed)
    changed = evidence.model_dump(mode="json")
    changed["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        ForecastTournamentSubmission.model_validate(changed)
