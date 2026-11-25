from __future__ import annotations

import hashlib

import pytest
from pydantic import ValidationError

from aitrading_intelligence.model_diversity import (
    ModelDiversityConfig,
    ModelPredictionSeries,
    build_model_diversity_report,
    build_prediction_series,
)


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _observations(count: int = 8) -> list[str]:
    return [_hash(f"observation-{index}") for index in range(count)]


def _series(
    candidate: str,
    probabilities: list[float],
    *,
    observations: list[str] | None = None,
    result_suffix: str | None = None,
) -> ModelPredictionSeries:
    return build_prediction_series(
        candidate_id=candidate,
        model_version_ref=f"model-version-{candidate}",
        source_result_fingerprint=_hash(result_suffix or f"result-{candidate}"),
        observation_fingerprints=observations or _observations(len(probabilities)),
        probabilities=probabilities,
    )


def test_report_is_order_independent_and_flags_redundant_candidates() -> None:
    alpha = _series(
        "alpha",
        [0.10, 0.20, 0.30, 0.40, 0.60, 0.70, 0.80, 0.90],
    )
    near_duplicate = _series(
        "near-duplicate",
        [0.11, 0.21, 0.31, 0.41, 0.59, 0.69, 0.79, 0.89],
    )
    diversifier = _series(
        "diversifier",
        [0.88, 0.72, 0.61, 0.52, 0.48, 0.37, 0.22, 0.12],
    )

    report = build_model_diversity_report([diversifier, near_duplicate, alpha])
    reordered = build_model_diversity_report([alpha, diversifier, near_duplicate])

    assert report.report_fingerprint == reordered.report_fingerprint
    assert [candidate.candidate_id for candidate in report.candidates] == [
        "alpha",
        "diversifier",
        "near-duplicate",
    ]
    assert len(report.pairs) == 3
    assert report.redundant_pair_count == 1
    assert report.undefined_correlation_pair_count == 0
    assert report.status == "REDUNDANCY_RISK"
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False

    redundant = next(
        pair
        for pair in report.pairs
        if {pair.left_candidate_id, pair.right_candidate_id}
        == {"alpha", "near-duplicate"}
    )
    assert redundant.pearson_correlation is not None
    assert redundant.pearson_correlation > 0.99
    assert redundant.signal_disagreement_rate == 0.0
    assert redundant.redundancy_risk is True

    inverse = next(
        pair
        for pair in report.pairs
        if {pair.left_candidate_id, pair.right_candidate_id}
        == {"alpha", "diversifier"}
    )
    assert inverse.pearson_correlation is not None
    assert inverse.pearson_correlation < 0.0
    assert inverse.signal_disagreement_rate > 0.5
    assert inverse.redundancy_risk is False


def test_report_rejects_misaligned_observation_membership() -> None:
    observations = _observations(4)
    alpha = _series("alpha", [0.1, 0.3, 0.7, 0.9], observations=observations)
    misaligned = _series(
        "beta",
        [0.2, 0.4, 0.6, 0.8],
        observations=list(reversed(observations)),
    )

    with pytest.raises(ValueError, match="identical ordered observation fingerprints"):
        build_model_diversity_report([alpha, misaligned])


def test_report_rejects_duplicate_source_result_disguised_as_two_candidates() -> None:
    alpha = _series(
        "alpha",
        [0.1, 0.3, 0.7, 0.9],
        result_suffix="shared-result",
    )
    beta = _series(
        "beta",
        [0.2, 0.4, 0.6, 0.8],
        result_suffix="shared-result",
    )

    with pytest.raises(ValueError, match="cannot duplicate the same experiment result"):
        build_model_diversity_report([alpha, beta])


def test_constant_prediction_vector_is_explicitly_partial_undefined() -> None:
    constant = _series("constant", [0.5, 0.5, 0.5, 0.5])
    variable = _series("variable", [0.1, 0.3, 0.7, 0.9])

    report = build_model_diversity_report([constant, variable])

    assert report.status == "PARTIAL_UNDEFINED"
    assert report.undefined_correlation_pair_count == 1
    assert report.pairs[0].pearson_correlation is None
    assert report.pairs[0].correlation_defined is False
    assert report.pairs[0].redundancy_risk is False


def test_prediction_series_fingerprint_is_tamper_evident() -> None:
    original = _series("alpha", [0.1, 0.3, 0.7, 0.9])

    with pytest.raises(ValidationError, match="prediction_fingerprint"):
        ModelPredictionSeries(
            candidate_id=original.candidate_id,
            model_version_ref=original.model_version_ref,
            source_result_fingerprint=original.source_result_fingerprint,
            observation_fingerprints=original.observation_fingerprints,
            probabilities=[0.1, 0.3, 0.8, 0.9],
            prediction_fingerprint=original.prediction_fingerprint,
            series_fingerprint=original.series_fingerprint,
        )


def test_configured_redundancy_thresholds_are_versioned_in_report_identity() -> None:
    alpha = _series("alpha", [0.1, 0.3, 0.7, 0.9])
    beta = _series("beta", [0.12, 0.32, 0.68, 0.88])

    default_report = build_model_diversity_report([alpha, beta])
    strict_report = build_model_diversity_report(
        [alpha, beta],
        config=ModelDiversityConfig(
            redundancy_correlation_threshold=0.99999,
            redundancy_disagreement_threshold=0.0,
        ),
    )

    assert default_report.config_fingerprint != strict_report.config_fingerprint
    assert default_report.report_fingerprint != strict_report.report_fingerprint
    assert default_report.can_authorize_capital is False
    assert strict_report.can_authorize_capital is False


def test_prediction_builder_rejects_probability_outside_unit_interval() -> None:
    with pytest.raises(ValueError, match="within \[0, 1\]"):
        _series("invalid", [0.1, 1.1])
