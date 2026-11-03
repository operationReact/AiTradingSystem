from __future__ import annotations

import hashlib
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.incremental_economic_value import (
    IncrementalValueConfig,
    create_net_return_series,
)
from aitrading_intelligence.model_diversity import build_prediction_series
from aitrading_intelligence.structured_alpha_tournament import (
    StructuredAlphaTournamentReport,
    TournamentCandidate,
    screen_structured_alpha_tournament,
)


def _sha(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _candidate(
    name: str,
    mean_return: float,
    *,
    count: int = 100,
    probabilities: list[float] | None = None,
    times_offset: int = 0,
) -> TournamentCandidate:
    observations = [_sha(f"oos-row-{i}") for i in range(count)]
    available = probabilities or [
        0.2 + 0.6 * ((i * (7 if name == "lightgbm" else 13)) % 17) / 16
        for i in range(count)
    ]
    source = _sha(f"experiment-result-{name}")
    return TournamentCandidate(
        prediction=build_prediction_series(
            candidate_id=name,
            model_version_ref=f"{name}-v1",
            source_result_fingerprint=source,
            observation_fingerprints=observations,
            probabilities=available,
        ),
        returns=create_net_return_series(
            candidate_id=name,
            model_version_ref=f"{name}-v1",
            source_result_fingerprint=source,
            target_binding_fingerprint=_sha("point-in-time-target"),
            fold_binding_fingerprint=_sha("purged-indian-folds"),
            economic_cost_binding_fingerprint=_sha("india-fees-and-slippage"),
            observation_fingerprints=observations,
            decision_times=[
                datetime(2025, 1, 1, 4, 0, tzinfo=UTC)
                + timedelta(minutes=5 * i + times_offset)
                for i in range(count)
            ],
            net_returns_after_costs=[mean_return] * count,
        ),
    )


def _baseline(count: int = 100) -> TournamentCandidate:
    probabilities = [0.2 + 0.6 * (i % 5) / 4 for i in range(count)]
    return _candidate("catboost", 0.001, count=count, probabilities=probabilities)


def test_multiple_candidates_get_corrected_confidence_and_no_capital_authority() -> None:
    base = _baseline()
    lightgbm = _candidate("lightgbm", 0.005)
    pytorch = _candidate("pytorch", 0.003)
    report = screen_structured_alpha_tournament(base, [pytorch, lightgbm])
    reversed_report = screen_structured_alpha_tournament(base, [lightgbm, pytorch])

    assert report.adjusted_confidence_level == pytest.approx(0.975)
    assert report.adjusted_confidence_level > IncrementalValueConfig().confidence_level
    assert report.original_economic_config_fingerprint != (
        report.corrected_economic_config_fingerprint
    )
    assert report.status == "RESEARCH_SHORTLIST_ONLY"
    assert set(report.shortlisted_candidate_ids) == {"lightgbm", "pytorch"}
    assert report.report_fingerprint == reversed_report.report_fingerprint
    assert report.familywise_declared_tests_adjusted is True
    assert report.independent_indian_market_validation_complete is False
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_redundant_challenger_is_not_double_counted() -> None:
    base = _baseline()
    lightgbm = _candidate("lightgbm", 0.005)
    duplicate = _candidate(
        "near_duplicate", 0.004,
        probabilities=[min(1.0, v + 0.01) for v in lightgbm.prediction.probabilities],
    )
    report = screen_structured_alpha_tournament(base, [duplicate, lightgbm])

    assert report.shortlisted_candidate_ids == ["lightgbm"]
    rejected = next(d for d in report.decisions if d.candidate_id == "near_duplicate")
    assert rejected.decision == "REJECTED"
    assert "REDUNDANT_WITH_lightgbm" in rejected.reason_codes


def test_no_economic_uplift_cannot_enter_research_shortlist() -> None:
    report = screen_structured_alpha_tournament(
        _baseline(), [_candidate("lightgbm", 0.001)]
    )
    assert report.status == "NO_ADDITIVE_RESEARCH_CANDIDATES"
    assert report.shortlisted_candidate_ids == []
    assert report.decisions[0].decision == "REJECTED"


def test_insufficient_observations_result_in_inconclusive_evidence() -> None:
    report = screen_structured_alpha_tournament(
        _baseline(count=30), [_candidate("lightgbm", 0.005, count=30)]
    )
    assert report.status == "INCONCLUSIVE"
    assert report.decisions[0].decision == "INCONCLUSIVE"
    assert report.decisions[0].adjusted_lower_bound is None


def test_cross_candidate_time_lineage_must_match() -> None:
    with pytest.raises(ValueError, match="decision_times"):
        screen_structured_alpha_tournament(
            _baseline(), [_candidate("lightgbm", 0.005, times_offset=5)]
        )


def test_duplicate_candidate_identity_cannot_be_registered_twice() -> None:
    same = _candidate("lightgbm", 0.005)
    with pytest.raises(ValueError, match="candidate identities must be unique"):
        screen_structured_alpha_tournament(_baseline(), [same, same])


def test_unsupported_multiple_trial_adjustment_fails_closed() -> None:
    challengers = [
        _candidate(f"model-{i}", 0.003 + 0.001 * i)
        for i in range(5)
    ]
    with pytest.raises(ValueError, match="Bonferroni confidence range"):
        screen_structured_alpha_tournament(
            _baseline(),
            challengers,
            economic_config=IncrementalValueConfig(confidence_level=0.99),
        )


def test_report_fingerprint_makes_research_shortlist_tamper_evident() -> None:
    report = screen_structured_alpha_tournament(
        _baseline(), [_candidate("lightgbm", 0.005)]
    )
    with pytest.raises(ValidationError, match="tournament report fingerprint mismatch"):
        StructuredAlphaTournamentReport.model_validate(
            {**report.model_dump(), "report_fingerprint": _sha("tampered")}
        )
