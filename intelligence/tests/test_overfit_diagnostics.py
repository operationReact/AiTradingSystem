import math
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.overfit_diagnostics import (
    CandidateReturnSeriesV1,
    OverfitDiagnosticsConfigV1,
    build_candidate_return_series,
    evaluate_overfit_diagnostics,
)

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)


def _candidates(length: int = 60):
    decision_times = [START + timedelta(minutes=5 * index) for index in range(length)]
    return [
        build_candidate_return_series(
            "alpha",
            f"{1:064x}",
            decision_times,
            [0.0030 + (0.0100 * math.sin(index * 0.37)) for index in range(length)],
        ),
        build_candidate_return_series(
            "beta",
            f"{2:064x}",
            decision_times,
            [
                0.0020 + (0.0090 * math.sin((index * 0.51) + 0.30))
                for index in range(length)
            ],
        ),
        build_candidate_return_series(
            "gamma",
            f"{3:064x}",
            decision_times,
            [0.0010 + (0.0080 * math.cos(index * 0.29)) for index in range(length)],
        ),
        build_candidate_return_series(
            "delta",
            f"{4:064x}",
            decision_times,
            [
                0.0005 + (0.0120 * math.sin((index * 0.77) + 0.15))
                for index in range(length)
            ],
        ),
    ]


def test_pbo_and_deflated_sharpe_are_deterministic_and_research_only() -> None:
    candidates = _candidates()
    config = OverfitDiagnosticsConfigV1(independent_trial_count=4)

    first = evaluate_overfit_diagnostics(candidates, config=config)
    second = evaluate_overfit_diagnostics(list(reversed(candidates)), config=config)

    assert first == second
    assert first.state == "EVALUATED"
    assert first.reason_codes == []
    assert first.candidate_count == 4
    assert first.observation_count == 60
    assert first.pbo.state == "EVALUATED"
    assert first.pbo.combination_count == math.comb(6, 3)
    assert first.pbo.evaluated_split_count == first.pbo.combination_count
    assert first.pbo.pbo is not None
    assert 0.0 <= first.pbo.pbo <= 1.0
    assert first.deflated_sharpe.state == "EVALUATED"
    assert first.deflated_sharpe.dsr_probability is not None
    assert 0.0 <= first.deflated_sharpe.dsr_probability <= 1.0
    assert first.deflated_sharpe.independent_trial_count == 4
    assert first.status == "RESEARCH_OVERFIT_DIAGNOSTICS_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_insufficient_history_is_explicitly_inconclusive() -> None:
    report = evaluate_overfit_diagnostics(
        _candidates(30),
        config=OverfitDiagnosticsConfigV1(independent_trial_count=4),
    )

    assert report.state == "INCONCLUSIVE"
    assert report.reason_codes == ["INSUFFICIENT_RETURN_HISTORY"]
    assert report.pbo.state == "INCONCLUSIVE"
    assert report.pbo.pbo is None
    assert report.pbo.splits == []
    assert report.deflated_sharpe.state == "INCONCLUSIVE"
    assert report.deflated_sharpe.dsr_probability is None
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_deflated_sharpe_requires_explicit_independent_trial_count() -> None:
    report = evaluate_overfit_diagnostics(_candidates())

    assert report.state == "INCONCLUSIVE"
    assert report.pbo.state == "EVALUATED"
    assert report.pbo.pbo is not None
    assert report.deflated_sharpe.state == "INCONCLUSIVE"
    assert report.deflated_sharpe.reason_codes == ["INDEPENDENT_TRIAL_COUNT_REQUIRED"]
    assert report.deflated_sharpe.dsr_probability is None
    assert report.reason_codes == ["INDEPENDENT_TRIAL_COUNT_REQUIRED"]


def test_pbo_does_not_silently_truncate_non_divisible_history() -> None:
    report = evaluate_overfit_diagnostics(
        _candidates(62),
        config=OverfitDiagnosticsConfigV1(independent_trial_count=4),
    )

    assert report.state == "INCONCLUSIVE"
    assert report.pbo.state == "INCONCLUSIVE"
    assert report.pbo.reason_codes == ["OBSERVATIONS_NOT_DIVISIBLE_BY_PBO_PARTITIONS"]
    assert report.pbo.pbo is None
    assert report.deflated_sharpe.state == "EVALUATED"
    assert report.deflated_sharpe.dsr_probability is not None


def test_unaligned_candidate_observations_are_inconclusive() -> None:
    candidates = _candidates()
    shifted_times = [value + timedelta(minutes=1) for value in candidates[-1].decision_times]
    shifted = build_candidate_return_series(
        candidates[-1].candidate_id,
        candidates[-1].source_fingerprint,
        shifted_times,
        candidates[-1].net_returns,
    )

    report = evaluate_overfit_diagnostics(
        [*candidates[:-1], shifted],
        config=OverfitDiagnosticsConfigV1(independent_trial_count=4),
    )

    assert report.state == "INCONCLUSIVE"
    assert report.reason_codes == ["UNALIGNED_CANDIDATE_OBSERVATIONS"]
    assert report.pbo.pbo is None
    assert report.deflated_sharpe.dsr_probability is None


def test_candidate_series_rejects_tampered_fingerprint() -> None:
    candidate = _candidates()[0]
    payload = candidate.model_dump(mode="python")
    payload["series_fingerprint"] = "0" * 64

    with pytest.raises(ValidationError, match="candidate return series fingerprint mismatch"):
        CandidateReturnSeriesV1.model_validate(payload)
