from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.catboost_model import (
    CatBoostTrainingConfig,
    train_catboost_challenger,
)
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.lightgbm_model import (
    LightGBMTrainingConfig,
    train_lightgbm_challenger,
)
from aitrading_intelligence.model_fair_comparison import (
    FairComparisonConfig,
    ModelFairComparisonReport,
    evaluate_model_families,
)
from aitrading_intelligence.model_registry import (
    register_gradient_boosting_model_lab_entry,
)
from aitrading_intelligence.pytorch_target import (
    ForwardReturnTargetSpec,
    build_labeled_examples,
)


def _series(length: int = 71) -> list[FeatureRow]:
    closes = [100.0]
    for index in range(length - 1):
        direction = 1.0 if index % 2 == 0 else -1.0
        closes.append(closes[-1] * (1.0 + (0.01 * direction)))

    rows: list[FeatureRow] = []
    start = datetime(2026, 1, 1, 3, 45, tzinfo=UTC)
    for index, close in enumerate(closes):
        direction = 1.0 if index % 2 == 0 else -1.0
        as_of = start + timedelta(minutes=5 * index)
        rows.append(
            FeatureRow(
                symbol="RELIANCE",
                as_of=as_of,
                data_cutoff=as_of,
                features={
                    "market.close": close,
                    "market.return_1": direction * 0.01,
                    "market.range_pct": 0.015 + (0.001 * (index % 3)),
                    "market.volume_change_1": direction * (0.20 + 0.01 * (index % 4)),
                    "event.AI_NEWS_EVIDENCE.direction_score": direction * 0.70,
                    "event.AI_NEWS_EVIDENCE.confidence": 0.80,
                    "event.AI_NEWS_EVIDENCE.materiality": 0.75,
                },
                provenance=[f"fixture:{index}"],
                fingerprint=f"{index + 1:064x}",
            )
        )
    return rows


@pytest.fixture(scope="module")
def registry_evidence():
    rows = _series()
    spec = ForwardReturnTargetSpec(
        horizon_bars=1,
        minimum_edge_bps=25,
        round_trip_cost_bps=10,
    )
    examples = build_labeled_examples(rows, spec)
    evaluation_as_of = rows[-1].as_of + timedelta(minutes=1)
    comparison = evaluate_model_families(
        examples,
        spec,
        evaluation_as_of=evaluation_as_of,
        config=FairComparisonConfig(
            fold_count=2,
            test_rows_per_fold=10,
            minimum_train_rows=20,
        ),
        include_pytorch=False,
    )
    lightgbm = train_lightgbm_challenger(
        examples,
        training_as_of=evaluation_as_of,
        config=LightGBMTrainingConfig(
            n_estimators=30,
            num_leaves=7,
            max_depth=4,
            min_child_samples=5,
        ),
    )
    catboost = train_catboost_challenger(
        examples,
        training_as_of=evaluation_as_of,
        config=CatBoostTrainingConfig(iterations=30, depth=4),
    )
    return comparison, lightgbm, catboost, evaluation_as_of


def test_gradient_boosting_models_register_against_exact_fair_comparison(
    registry_evidence,
) -> None:
    comparison, lightgbm, catboost, evaluation_as_of = registry_evidence
    registered_at = evaluation_as_of + timedelta(minutes=1)

    light_entry = register_gradient_boosting_model_lab_entry(
        model_family="LIGHTGBM",
        artifact=lightgbm,
        fair_comparison=comparison,
        code_sha="a" * 40,
        registered_at=registered_at,
    )
    cat_entry = register_gradient_boosting_model_lab_entry(
        model_family="CATBOOST",
        artifact=catboost,
        fair_comparison=comparison,
        code_sha="a" * 40,
        registered_at=registered_at,
    )

    assert light_entry.stage == "RESEARCH_MODEL_LAB"
    assert light_entry.required_next_gate == "V3_05_CALIBRATION"
    assert light_entry.fair_comparison_report_fingerprint == comparison.report_fingerprint
    assert light_entry.artifact_fingerprint == lightgbm.model_fingerprint
    assert cat_entry.artifact_fingerprint == catboost.model_fingerprint
    assert light_entry.fold_membership_fingerprint == cat_entry.fold_membership_fingerprint
    assert light_entry.promotion_allowed is False
    assert light_entry.can_authorize_capital is False
    assert cat_entry.promotion_allowed is False
    assert cat_entry.can_authorize_capital is False


def test_model_lab_registry_separates_reproducibility_from_registration_time(
    registry_evidence,
) -> None:
    comparison, lightgbm, _, evaluation_as_of = registry_evidence
    registered_at = evaluation_as_of + timedelta(minutes=1)
    kwargs = {
        "model_family": "LIGHTGBM",
        "artifact": lightgbm,
        "fair_comparison": comparison,
        "code_sha": "b" * 40,
        "registered_at": registered_at,
    }

    first = register_gradient_boosting_model_lab_entry(**kwargs)
    repeated = register_gradient_boosting_model_lab_entry(**kwargs)
    later_registration = register_gradient_boosting_model_lab_entry(
        **{**kwargs, "registered_at": registered_at + timedelta(minutes=5)}
    )
    changed_code = register_gradient_boosting_model_lab_entry(
        **{**kwargs, "code_sha": "c" * 40}
    )

    assert first.reproducibility_fingerprint == repeated.reproducibility_fingerprint
    assert first.registry_fingerprint == repeated.registry_fingerprint
    assert first.reproducibility_fingerprint == later_registration.reproducibility_fingerprint
    assert first.registry_fingerprint != later_registration.registry_fingerprint
    assert first.reproducibility_fingerprint != changed_code.reproducibility_fingerprint


def test_model_lab_registry_fails_closed_on_family_target_or_time_mismatch(
    registry_evidence,
) -> None:
    comparison, lightgbm, _, evaluation_as_of = registry_evidence
    registered_at = evaluation_as_of + timedelta(minutes=1)

    with pytest.raises(
        ValueError,
        match="model version does not match requested model family",
    ):
        register_gradient_boosting_model_lab_entry(
            model_family="CATBOOST",
            artifact=lightgbm,
            fair_comparison=comparison,
            code_sha="d" * 40,
            registered_at=registered_at,
        )

    mismatched_target: ModelFairComparisonReport = comparison.model_copy(
        update={"target_spec_fingerprint": "f" * 64}
    )
    with pytest.raises(ValueError, match="target lineage does not match"):
        register_gradient_boosting_model_lab_entry(
            model_family="LIGHTGBM",
            artifact=lightgbm,
            fair_comparison=mismatched_target,
            code_sha="d" * 40,
            registered_at=registered_at,
        )

    with pytest.raises(
        ValueError,
        match="evaluation cutoff cannot exceed registration time",
    ):
        register_gradient_boosting_model_lab_entry(
            model_family="LIGHTGBM",
            artifact=lightgbm,
            fair_comparison=comparison,
            code_sha="d" * 40,
            registered_at=evaluation_as_of - timedelta(seconds=1),
        )
