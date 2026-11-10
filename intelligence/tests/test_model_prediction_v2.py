import importlib.util
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.baseline import (
    DEFAULT_BASELINE_FEATURES,
    train_logistic_baseline,
)
from aitrading_intelligence.catboost_model import (
    CatBoostTrainingConfig,
    train_catboost_challenger,
)
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.lightgbm_model import (
    LightGBMTrainingConfig,
    train_lightgbm_challenger,
)
from aitrading_intelligence.model_prediction_v2 import (
    MAX_ATTRIBUTION_FEATURES,
    ModelPredictionV2,
    adapt_catboost_prediction,
    adapt_lightgbm_prediction,
    adapt_logistic_prediction,
    adapt_pytorch_prediction,
    _feature_vector,
)
from aitrading_intelligence.pytorch_target import (
    ForwardReturnTargetSpec,
    build_labeled_examples,
)


def _series(length: int = 65) -> list[FeatureRow]:
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


def _target_spec() -> ForwardReturnTargetSpec:
    return ForwardReturnTargetSpec(
        horizon_bars=1,
        minimum_edge_bps=25,
        round_trip_cost_bps=10,
    )


def test_logistic_prediction_v2_preserves_target_lineage_and_attribution() -> None:
    rows = _series()
    spec = _target_spec()
    examples = build_labeled_examples(rows, spec)
    artifact = train_logistic_baseline(
        rows[: len(examples)],
        [example.label for example in examples],
    )

    first = adapt_logistic_prediction(
        rows[-1],
        artifact,
        target_definition_fingerprint=spec.fingerprint,
    )
    second = adapt_logistic_prediction(
        rows[-1],
        artifact,
        target_definition_fingerprint=spec.fingerprint,
    )

    assert first.contract_version == "model-prediction.v2"
    assert first.model_family == "LOGISTIC"
    assert first.target_definition_fingerprint == spec.fingerprint
    assert first.feature_row_fingerprint == rows[-1].fingerprint
    assert first.attribution_status == "AVAILABLE"
    assert first.attribution_summary
    assert first.raw_confidence is not None
    assert first.inference_fingerprint == second.inference_fingerprint
    assert first.raw_probability == second.raw_probability
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_tree_models_attach_bounded_native_attribution_without_fabricated_confidence() -> None:
    rows = _series()
    spec = _target_spec()
    examples = build_labeled_examples(rows, spec)
    lightgbm = train_lightgbm_challenger(
        examples,
        config=LightGBMTrainingConfig(
            n_estimators=30,
            num_leaves=7,
            max_depth=4,
            min_child_samples=5,
        ),
    )
    catboost = train_catboost_challenger(
        examples,
        config=CatBoostTrainingConfig(iterations=30, depth=4),
    )

    light_prediction = adapt_lightgbm_prediction(rows[-1], lightgbm)
    repeated_light_prediction = adapt_lightgbm_prediction(rows[-1], lightgbm)
    cat_prediction = adapt_catboost_prediction(rows[-1], catboost)
    repeated_cat_prediction = adapt_catboost_prediction(rows[-1], catboost)

    for prediction, family in (
        (light_prediction, "LIGHTGBM"),
        (cat_prediction, "CATBOOST"),
    ):
        assert prediction.contract_version == "model-prediction.v2"
        assert prediction.model_family == family
        assert prediction.target_definition_fingerprint == spec.fingerprint
        assert 0.0 <= prediction.raw_probability <= 1.0
        assert -1.0 <= prediction.raw_score <= 1.0
        assert prediction.raw_confidence is None
        assert prediction.attribution_status == "AVAILABLE"
        assert prediction.attribution_summary
        assert len(prediction.attribution_summary) <= MAX_ATTRIBUTION_FEATURES
        assert set(prediction.attribution_summary) <= set(DEFAULT_BASELINE_FEATURES)
        assert all(
            value == pytest.approx(value)
            for value in prediction.attribution_summary.values()
        )
        assert prediction.inference_latency_micros >= 0
        assert prediction.promotion_allowed is False
        assert prediction.can_authorize_capital is False

    # Latency is telemetry rather than semantic model/input identity, while native
    # attribution is deterministic semantic evidence and is included in the fingerprint.
    assert light_prediction.attribution_summary == repeated_light_prediction.attribution_summary
    assert cat_prediction.attribution_summary == repeated_cat_prediction.attribution_summary
    assert light_prediction.inference_fingerprint == repeated_light_prediction.inference_fingerprint
    assert cat_prediction.inference_fingerprint == repeated_cat_prediction.inference_fingerprint


def test_prediction_contract_rejects_future_data_cutoff() -> None:
    rows = _series()
    spec = _target_spec()
    examples = build_labeled_examples(rows, spec)
    artifact = train_logistic_baseline(
        rows[: len(examples)],
        [example.label for example in examples],
    )
    future_row = rows[-1].model_copy(
        update={"data_cutoff": rows[-1].as_of + timedelta(seconds=1)}
    )

    with pytest.raises(ValueError, match="data_cutoff cannot exceed as_of"):
        adapt_logistic_prediction(
            future_row,
            artifact,
            target_definition_fingerprint=spec.fingerprint,
        )


def test_prediction_contract_rejects_probability_score_mismatch() -> None:
    as_of = datetime(2026, 1, 1, 10, 0, tzinfo=UTC)
    with pytest.raises(ValidationError, match="raw_score must equal"):
        ModelPredictionV2(
            symbol="RELIANCE",
            as_of=as_of,
            data_cutoff=as_of,
            model_family="LIGHTGBM",
            model_name="LGBMClassifier",
            model_version="lightgbm-binary.v1",
            model_artifact_fingerprint="a" * 64,
            target_definition_fingerprint="b" * 64,
            raw_probability=0.75,
            raw_score=0.10,
            raw_confidence=None,
            feature_row_fingerprint="c" * 64,
            attribution_status="NOT_ATTACHED",
            inference_latency_micros=10,
            inference_fingerprint="d" * 64,
        )


def test_pytorch_adapter_uses_same_contract_when_optional_dependency_is_available() -> None:
    if importlib.util.find_spec("torch") is None:
        pytest.skip("PyTorch is installed through the separate CPU research requirements")

    from aitrading_intelligence.pytorch_model import (
        TorchTrainingConfig,
        train_torch_challenger,
    )

    rows = _series()
    spec = _target_spec()
    examples = build_labeled_examples(rows, spec)
    artifact = train_torch_challenger(
        examples,
        config=TorchTrainingConfig(hidden_dim=8, epochs=30, learning_rate=0.05, seed=7),
    )

    prediction = adapt_pytorch_prediction(rows[-1], artifact)

    assert prediction.model_family == "PYTORCH"
    assert prediction.target_definition_fingerprint == spec.fingerprint
    assert prediction.raw_confidence is not None
    assert prediction.attribution_status == "AVAILABLE"
    assert prediction.attribution_summary
    assert prediction.promotion_allowed is False
    assert prediction.can_authorize_capital is False


def test_missing_required_model_inputs_are_not_silently_zero_imputed() -> None:
    """A model trained on a feature must not infer when that source is absent."""
    row = _series(3)[-1]
    # Both an unlicensed filing and missing NLP evidence must fail closed,
    # rather than be treated as two real observed 0.0 feature values.
    with pytest.raises(ValueError, match="missing trained model features"):
        _feature_vector(row, [
            "market.return_1",
            "fundamental.roce",
            "event.NEWS.materiality",
        ])
    # An explicitly observed zero remains valid and distinguishable.
    vector = _feature_vector(row, ["market.return_1", "market.close"])
    assert len(vector) == 2


def test_feature_vector_rejects_duplicate_or_empty_artifact_schema() -> None:
    row = _series(3)[-1]
    with pytest.raises(ValueError, match="non-empty and unique"):
        _feature_vector(row, [])
    with pytest.raises(ValueError, match="non-empty and unique"):
        _feature_vector(row, ["market.close", "market.close"])
