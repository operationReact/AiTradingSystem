from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.shadow_governance import (
    ShadowEvaluationConfig,
    ShadowPredictionInput,
    build_shadow_prediction,
    evaluate_shadow_challenger,
)

START = datetime(2026, 1, 7, 4, 0, tzinfo=UTC)
CHAMPION = "a" * 64
CHALLENGER = "b" * 64


def _hash(value: int) -> str:
    return f"{value:064x}"


def _record(index: int, *, challenger_better: bool = True):
    positive = index % 2 == 0
    # Keep champion probabilities in separate calibration buckets. Using 0.55/0.45 puts
    # both classes in the same bucket and can make ECE look artificially perfect.
    champion_probability = 0.60 if positive else 0.40
    if challenger_better:
        challenger_probability = 0.80 if positive else 0.20
    else:
        challenger_probability = 0.30 if positive else 0.70
    prediction_at = START + timedelta(minutes=index)
    return build_shadow_prediction(
        ShadowPredictionInput(
            trade_id=f"paper-{index}",
            prediction_at=prediction_at,
            outcome_available_at=prediction_at + timedelta(minutes=10),
            outcome_record_fingerprint=_hash(index + 1),
            champion_registry_fingerprint=CHAMPION,
            challenger_registry_fingerprint=CHALLENGER,
            champion_probability=champion_probability,
            challenger_probability=challenger_probability,
            actual_positive=positive,
        )
    )


def test_shadow_challenger_can_only_advance_to_explicit_review() -> None:
    records = [_record(index) for index in range(30)]

    evidence = evaluate_shadow_challenger(records)
    reordered = evaluate_shadow_challenger(list(reversed(records)))

    assert evidence.evidence_fingerprint == reordered.evidence_fingerprint
    assert evidence.assessment == "CHALLENGER_ELIGIBLE_FOR_GOVERNANCE_REVIEW"
    assert evidence.required_next_gate == "V2_09_EXPLICIT_GOVERNANCE_DECISION"
    assert evidence.challenger.brier_score < evidence.champion.brier_score
    assert evidence.challenger.log_loss < evidence.champion.log_loss
    assert evidence.challenger.expected_calibration_error < evidence.champion.expected_calibration_error
    assert evidence.promotion_allowed is False
    assert evidence.can_authorize_capital is False


def test_shadow_comparison_keeps_champion_when_challenger_is_worse() -> None:
    records = [_record(index, challenger_better=False) for index in range(30)]

    evidence = evaluate_shadow_challenger(records)

    assert evidence.assessment == "CHAMPION_HOLDS"
    assert evidence.required_next_gate == "V2_09_CONTINUE_SHADOW"
    assert evidence.challenger.brier_score > evidence.champion.brier_score


def test_shadow_comparison_rejects_weak_or_mixed_lineage() -> None:
    records = [_record(index) for index in range(30)]

    with pytest.raises(ValueError, match="not enough PAPER shadow records"):
        evaluate_shadow_challenger(
            records[:20],
            config=ShadowEvaluationConfig(minimum_records=30),
        )

    mixed = records[-1].model_copy(update={"challenger_registry_fingerprint": "c" * 64})
    with pytest.raises(ValueError, match="one champion and one challenger"):
        evaluate_shadow_challenger([*records[:-1], mixed])


def test_shadow_input_rejects_same_model_and_noncausal_outcome() -> None:
    prediction_at = START
    kwargs = {
        "trade_id": "paper-x",
        "prediction_at": prediction_at,
        "outcome_available_at": prediction_at + timedelta(minutes=1),
        "outcome_record_fingerprint": _hash(999),
        "champion_registry_fingerprint": CHAMPION,
        "challenger_registry_fingerprint": CHALLENGER,
        "champion_probability": 0.5,
        "challenger_probability": 0.6,
        "actual_positive": True,
    }

    with pytest.raises(ValueError, match="must differ"):
        ShadowPredictionInput(**{**kwargs, "challenger_registry_fingerprint": CHAMPION})

    with pytest.raises(ValueError, match="after prediction time"):
        ShadowPredictionInput(**{**kwargs, "outcome_available_at": prediction_at})
