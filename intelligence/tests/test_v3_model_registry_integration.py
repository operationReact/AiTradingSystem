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
    evaluate_model_families,
)
from aitrading_intelligence.model_registry import (
    build_model_registry_snapshot,
    register_catboost_model_lab_entry,
    register_lightgbm_model_lab_entry,
)
from aitrading_intelligence.pytorch_target import (
    ForwardReturnTargetSpec,
    build_labeled_examples,
)

CODE_SHA = "a" * 40


def _series(length: int = 90) -> list[FeatureRow]:
    closes = [100.0]
    rows: list[FeatureRow] = []
    start = datetime(2026, 1, 1, 3, 45, tzinfo=UTC)
    for index in range(length):
        if index > 0:
            direction = 1.0 if (index % 4) in (1, 2) else -1.0
            magnitude = 0.012 if index % 5 else 0.007
            closes.append(closes[-1] * (1.0 + (direction * magnitude)))
        close = closes[index]
        direction = 1.0 if (index % 4) in (1, 2) else -1.0
        as_of = start + timedelta(minutes=5 * index)
        rows.append(
            FeatureRow(
                symbol="RELIANCE",
                as_of=as_of,
                data_cutoff=as_of,
                features={
                    "market.close": close,
                    "market.return_1": direction * (0.008 + (0.001 * (index % 3))),
                    "market.range_pct": 0.012 + (0.001 * (index % 4)),
                    "market.volume_change_1": direction * (0.15 + 0.02 * (index % 5)),
                    "event.AI_NEWS_EVIDENCE.direction_score": direction * 0.65,
                    "event.AI_NEWS_EVIDENCE.confidence": 0.70 + (0.02 * (index % 4)),
                    "event.AI_NEWS_EVIDENCE.materiality": 0.68 + (0.01 * (index % 5)),
                },
                provenance=[f"registry-fixture:{index}"],
                fingerprint=f"{index + 1:064x}",
            )
        )
    return rows


@pytest.fixture(scope="module")
def registry_evidence():
    target_spec = ForwardReturnTargetSpec(
        horizon_bars=1,
        minimum_edge_bps=25,
        round_trip_cost_bps=10,
    )
    examples = build_labeled_examples(_series(), target_spec)
    config = FairComparisonConfig(
        fold_count=3,
        test_rows_per_fold=10,
        minimum_train_rows=20,
        calibration_bins=5,
        seed=42,
    )
    evaluation_as_of = examples[-1].target_observed_at
    comparison = evaluate_model_families(
        examples,
        target_spec,
        evaluation_as_of=evaluation_as_of,
        config=config,
        include_pytorch=False,
    )
    lightgbm = train_lightgbm_challenger(
        examples,
        config=LightGBMTrainingConfig(seed=config.seed),
    )
    catboost = train_catboost_challenger(
        examples,
        config=CatBoostTrainingConfig(seed=config.seed),
    )
    return config, comparison, lightgbm, catboost


def test_v3_model_lab_entries_bind_exact_fair_comparison_lineage(registry_evidence) -> None:
    config, comparison, lightgbm, catboost = registry_evidence
    registered_at = comparison.evaluation_as_of + timedelta(minutes=1)

    light_entry = register_lightgbm_model_lab_entry(
        artifact=lightgbm,
        comparison=comparison,
        comparison_config=config,
        code_sha=CODE_SHA,
        registered_at=registered_at,
    )
    cat_entry = register_catboost_model_lab_entry(
        artifact=catboost,
        comparison=comparison,
        comparison_config=config,
        code_sha=CODE_SHA,
        registered_at=registered_at,
    )

    assert light_entry.model_family == "LIGHTGBM"
    assert cat_entry.model_family == "CATBOOST"
    assert light_entry.evaluation_report_fingerprint == comparison.report_fingerprint
    assert cat_entry.evaluation_report_fingerprint == comparison.report_fingerprint
    assert light_entry.target_spec_fingerprint == comparison.target_spec_fingerprint
    assert cat_entry.target_spec_fingerprint == comparison.target_spec_fingerprint
    assert light_entry.stage == "V3_MODEL_LAB_REGISTERED"
    assert cat_entry.stage == "V3_MODEL_LAB_REGISTERED"
    assert light_entry.required_next_gate == "V3_05_CALIBRATION"
    assert cat_entry.required_next_gate == "V3_05_CALIBRATION"
    assert light_entry.promotion_allowed is False
    assert cat_entry.promotion_allowed is False
    assert light_entry.can_authorize_capital is False
    assert cat_entry.can_authorize_capital is False


def test_registry_snapshot_is_order_invariant_and_future_registration_invariant(
    registry_evidence,
) -> None:
    config, comparison, lightgbm, catboost = registry_evidence
    registered_at = comparison.evaluation_as_of + timedelta(minutes=1)
    future_registered_at = registered_at + timedelta(days=1)
    light_entry = register_lightgbm_model_lab_entry(
        artifact=lightgbm,
        comparison=comparison,
        comparison_config=config,
        code_sha=CODE_SHA,
        registered_at=registered_at,
    )
    cat_entry = register_catboost_model_lab_entry(
        artifact=catboost,
        comparison=comparison,
        comparison_config=config,
        code_sha=CODE_SHA,
        registered_at=registered_at,
    )
    future_cat_entry = register_catboost_model_lab_entry(
        artifact=catboost,
        comparison=comparison,
        comparison_config=config,
        code_sha="b" * 40,
        registered_at=future_registered_at,
    )

    first = build_model_registry_snapshot(
        [light_entry, cat_entry],
        registry_as_of=registered_at,
    )
    reversed_input = build_model_registry_snapshot(
        [cat_entry, light_entry],
        registry_as_of=registered_at,
    )
    after_future_growth = build_model_registry_snapshot(
        [light_entry, cat_entry, future_cat_entry],
        registry_as_of=registered_at,
    )

    assert first == reversed_input
    assert first == after_future_growth
    assert first.snapshot_fingerprint == reversed_input.snapshot_fingerprint
    assert first.snapshot_fingerprint == after_future_growth.snapshot_fingerprint
    assert [entry.model_family for entry in first.entries] == ["CATBOOST", "LIGHTGBM"]
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_registry_rejects_artifact_config_not_used_by_fair_comparison(registry_evidence) -> None:
    config, comparison, _, _ = registry_evidence
    target_spec = ForwardReturnTargetSpec(
        horizon_bars=1,
        minimum_edge_bps=25,
        round_trip_cost_bps=10,
    )
    examples = build_labeled_examples(_series(), target_spec)
    mismatched_artifact = train_lightgbm_challenger(
        examples,
        config=LightGBMTrainingConfig(n_estimators=60, seed=config.seed),
    )

    with pytest.raises(ValueError, match="training config does not match"):
        register_lightgbm_model_lab_entry(
            artifact=mismatched_artifact,
            comparison=comparison,
            comparison_config=config,
            code_sha=CODE_SHA,
            registered_at=comparison.evaluation_as_of + timedelta(minutes=1),
        )


def test_registry_rejects_target_lineage_mismatch(registry_evidence) -> None:
    config, comparison, lightgbm, _ = registry_evidence
    mismatched = lightgbm.model_copy(update={"target_spec_fingerprint": "f" * 64})

    with pytest.raises(ValueError, match="target does not match"):
        register_lightgbm_model_lab_entry(
            artifact=mismatched,
            comparison=comparison,
            comparison_config=config,
            code_sha=CODE_SHA,
            registered_at=comparison.evaluation_as_of + timedelta(minutes=1),
        )
