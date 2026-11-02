from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.model_monitoring import (
    ModelMonitoringConfig,
    MonitoringObservation,
    evaluate_model_monitoring,
)

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)
REGISTRY = "a" * 64


def _window(
    *,
    prefix: str,
    start: datetime,
    count: int = 50,
    negative_probability: float = 0.10,
    positive_probability: float = 0.90,
    registry_fingerprint: str = REGISTRY,
) -> list[MonitoringObservation]:
    rows: list[MonitoringObservation] = []
    offset = 1 if prefix == "cur" else 10_000
    for index in range(count):
        positive = index % 2 == 1
        rows.append(
            MonitoringObservation(
                trade_id=f"{prefix}-{index}",
                observed_at=start + timedelta(minutes=index),
                registry_fingerprint=registry_fingerprint,
                outcome_record_fingerprint=f"{offset + index:064x}",
                predicted_probability=(
                    positive_probability if positive else negative_probability
                ),
                actual_positive=positive,
                market_regime="TREND_UP",
            )
        )
    return rows


def _config() -> ModelMonitoringConfig:
    return ModelMonitoringConfig(
        minimum_reference_records=50,
        minimum_current_records=50,
    )


def test_stable_monitoring_report_is_deterministic_and_non_authorizing() -> None:
    reference = _window(prefix="ref", start=START)
    current = _window(prefix="cur", start=START + timedelta(days=1))

    first = evaluate_model_monitoring(
        reference=list(reversed(reference)),
        current=list(reversed(current)),
        config=_config(),
    )
    second = evaluate_model_monitoring(
        reference=reference,
        current=current,
        config=_config(),
    )

    assert first == second
    assert first.assessment == "STABLE"
    assert first.reason_codes == ["MONITORING_WITHIN_ACCEPTED_THRESHOLDS"]
    assert first.required_next_gate == "V2_09_CONTINUE_PAPER_MONITORING"
    assert first.candidate_training_allowed is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_mild_calibration_regression_enters_watch_without_retraining() -> None:
    reference = _window(
        prefix="ref",
        start=START,
        negative_probability=0.15,
        positive_probability=0.85,
    )
    current = _window(
        prefix="cur",
        start=START + timedelta(days=1),
        negative_probability=0.19,
        positive_probability=0.81,
    )

    report = evaluate_model_monitoring(
        reference=reference,
        current=current,
        config=_config(),
    )

    assert report.assessment == "WATCH"
    assert "BRIER_REGRESSION_WATCH" in report.reason_codes
    assert report.candidate_training_allowed is False
    assert report.promotion_allowed is False


def test_severe_probability_and_calibration_drift_recommends_candidate_retraining() -> None:
    reference = _window(prefix="ref", start=START)
    current = _window(
        prefix="cur",
        start=START + timedelta(days=1),
        negative_probability=0.35,
        positive_probability=0.65,
    )

    report = evaluate_model_monitoring(
        reference=reference,
        current=current,
        config=_config(),
    )

    assert report.assessment == "RETRAINING_RECOMMENDED"
    assert report.required_next_gate == "V2_09_CANDIDATE_RETRAINING"
    assert report.candidate_training_allowed is True
    assert any(code.endswith("_SEVERE") for code in report.reason_codes)
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_monitoring_rejects_insufficient_windows() -> None:
    with pytest.raises(ValueError, match="reference monitoring window has insufficient records"):
        evaluate_model_monitoring(
            reference=_window(prefix="ref", start=START, count=49),
            current=_window(prefix="cur", start=START + timedelta(days=1)),
            config=_config(),
        )


def test_monitoring_rejects_mixed_registry_lineage() -> None:
    reference = _window(prefix="ref", start=START)
    current = _window(
        prefix="cur",
        start=START + timedelta(days=1),
        registry_fingerprint="b" * 64,
    )

    with pytest.raises(ValueError, match="exactly one registered model"):
        evaluate_model_monitoring(
            reference=reference,
            current=current,
            config=_config(),
        )


def test_monitoring_rejects_overlapping_trade_ids() -> None:
    reference = _window(prefix="ref", start=START)
    current = _window(prefix="cur", start=START + timedelta(days=1))
    current[0] = current[0].model_copy(update={"trade_id": reference[0].trade_id})

    with pytest.raises(ValueError, match="duplicate or overlapping trade ids"):
        evaluate_model_monitoring(
            reference=reference,
            current=current,
            config=_config(),
        )


def test_monitoring_rejects_non_chronological_windows() -> None:
    reference = _window(prefix="ref", start=START + timedelta(days=1))
    current = _window(prefix="cur", start=START)

    with pytest.raises(ValueError, match="reference window must end before"):
        evaluate_model_monitoring(
            reference=reference,
            current=current,
            config=_config(),
        )
