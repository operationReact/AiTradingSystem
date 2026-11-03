from __future__ import annotations

import hashlib
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.incremental_economic_value import (
    IncrementalValueConfig,
    IncrementalValueReport,
    NetReturnSeries,
    create_net_return_series,
    evaluate_incremental_economic_value,
)


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _series(
    name: str,
    returns: list[float],
    *,
    observation_shift: int = 0,
    date_shift_minutes: int = 0,
    result_name: str | None = None,
) -> NetReturnSeries:
    start = datetime(2025, 1, 1, 4, 0, tzinfo=UTC)
    return create_net_return_series(
        candidate_id=name,
        model_version_ref=f"{name}-v1",
        source_result_fingerprint=_hash(result_name or f"result-{name}"),
        target_binding_fingerprint=_hash("target"),
        fold_binding_fingerprint=_hash("folds"),
        economic_cost_binding_fingerprint=_hash("india-fees"),
        observation_fingerprints=[
            _hash(f"observation-{i + observation_shift}") for i in range(len(returns))
        ],
        decision_times=[
            start + timedelta(minutes=5 * i + date_shift_minutes)
            for i in range(len(returns))
        ],
        net_returns_after_costs=returns,
    )


def test_positive_paired_net_value_is_only_promising_research() -> None:
    baseline = _series("catboost", [0.001] * 100)
    challenger = _series("lightgbm", [0.004] * 100)

    report = evaluate_incremental_economic_value(baseline, challenger)

    assert report.assessment == "PROMISING_RESEARCH"
    assert report.mean_paired_uplift == pytest.approx(0.003)
    assert report.bootstrap_lower_bound is not None
    assert report.bootstrap_lower_bound > 0
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_no_uplift_or_negative_uplift_cannot_pass() -> None:
    baseline = _series("catboost", [0.001] * 100)
    same = _series("lightgbm", [0.001] * 100)

    report = evaluate_incremental_economic_value(baseline, same)

    assert report.assessment == "NOT_IMPROVED"
    assert "NO_MEAN_NET_UPLIFT" in report.reason_codes
    assert "LOWER_CONFIDENCE_BOUND_NOT_POSITIVE" in report.reason_codes


def test_inconclusive_samples_have_no_bootstrap_bounds() -> None:
    report = evaluate_incremental_economic_value(
        _series("catboost", [0.001] * 20),
        _series("lightgbm", [0.020] * 20),
    )
    assert report.assessment == "INCONCLUSIVE"
    assert report.bootstrap_lower_bound is None
    assert report.bootstrap_upper_bound is None
    assert "INSUFFICIENT_CAUSAL_OBSERVATIONS" in report.reason_codes


def test_mismatched_ordered_observation_membership_fails_closed() -> None:
    baseline = _series("catboost", [0.001] * 100)
    challenger = _series("lightgbm", [0.002] * 100, observation_shift=1)
    with pytest.raises(ValueError, match="observation_fingerprints"):
        evaluate_incremental_economic_value(baseline, challenger)


def test_mismatched_decision_times_fails_closed() -> None:
    baseline = _series("catboost", [0.001] * 100)
    challenger = _series("lightgbm", [0.002] * 100, date_shift_minutes=5)
    with pytest.raises(ValueError, match="decision_times"):
        evaluate_incremental_economic_value(baseline, challenger)


def test_research_decisions_are_deterministic_given_seed_and_config() -> None:
    base = _series("catboost", [0.001] * 100)
    challenger = _series("lightgbm", [0.002 if i % 3 else -0.001 for i in range(100)])
    first = evaluate_incremental_economic_value(base, challenger)
    second = evaluate_incremental_economic_value(base, challenger)
    assert first.report_fingerprint == second.report_fingerprint

    different_config = evaluate_incremental_economic_value(
        base, challenger, config=IncrementalValueConfig(bootstrap_seed=9)
    )
    assert first.config_fingerprint != different_config.config_fingerprint


def test_tampered_series_or_report_is_rejected() -> None:
    base = _series("catboost", [0.001] * 100)
    challenger = _series("lightgbm", [0.002] * 100)
    with pytest.raises(ValidationError, match="fingerprint mismatch"):
        NetReturnSeries.model_validate(
            {**base.model_dump(), "series_fingerprint": _hash("tampered")}
        )
    report = evaluate_incremental_economic_value(base, challenger)
    with pytest.raises(ValidationError, match="fingerprint mismatch"):
        IncrementalValueReport.model_validate(
            {**report.model_dump(), "report_fingerprint": _hash("tampered")}
        )


def test_duplicate_source_result_fails_closed() -> None:
    base = _series("catboost", [0.001] * 100, result_name="shared")
    challenger = _series("lightgbm", [0.003] * 100, result_name="shared")
    with pytest.raises(ValueError, match="cannot share one experiment result"):
        evaluate_incremental_economic_value(base, challenger)


def test_reordered_timestamps_are_rejected() -> None:
    baseline = _series("catboost", [0.001] * 100)
    with pytest.raises(ValidationError, match="strictly chronological"):
        create_net_return_series(
            candidate_id="bad-series",
            model_version_ref="v1",
            source_result_fingerprint=_hash("different"),
            target_binding_fingerprint=_hash("target"),
            fold_binding_fingerprint=_hash("folds"),
            economic_cost_binding_fingerprint=_hash("india-fees"),
            observation_fingerprints=baseline.observation_fingerprints,
            decision_times=list(reversed(baseline.decision_times)),
            net_returns_after_costs=baseline.net_returns_after_costs,
        )
