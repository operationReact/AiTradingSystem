from datetime import UTC, datetime, timedelta

import lightgbm as lgb
import pytest

from aitrading_intelligence.baseline import DEFAULT_BASELINE_FEATURES
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.lightgbm_model import (
    LightGBMTrainingConfig,
    train_lightgbm_challenger,
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


def _examples():
    spec = ForwardReturnTargetSpec(
        horizon_bars=1,
        minimum_edge_bps=25,
        round_trip_cost_bps=10,
    )
    return build_labeled_examples(_series(), spec), spec


def _config() -> LightGBMTrainingConfig:
    return LightGBMTrainingConfig(
        n_estimators=60,
        learning_rate=0.05,
        num_leaves=7,
        max_depth=4,
        min_child_samples=5,
        seed=42,
    )


def test_training_is_reproducible_purged_and_never_promoted() -> None:
    examples, spec = _examples()
    config = _config()

    first = train_lightgbm_challenger(examples, config=config)
    second = train_lightgbm_challenger(list(reversed(examples)), config=config)

    assert first == second
    assert first.model_version == "lightgbm-binary.v1"
    assert first.lightgbm_version == lgb.__version__
    assert first.feature_names == list(DEFAULT_BASELINE_FEATURES)
    assert first.target_spec_fingerprint == spec.fingerprint
    assert first.training_config_fingerprint == config.fingerprint
    assert first.train_end < first.validation_start
    assert first.train_rows >= 20
    assert first.validation_rows >= 10
    assert len(first.training_example_fingerprints) == first.train_rows
    assert len(first.validation_example_fingerprints) == first.validation_rows
    assert len(first.validation_probabilities_fingerprint) == 64
    assert len(first.model_fingerprint) == 64
    assert first.status == "CHALLENGER_MODEL_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_validation_metrics_are_bounded_and_serialized_booster_reloads() -> None:
    examples, _ = _examples()
    artifact = train_lightgbm_challenger(examples, config=_config())

    assert 0.0 <= artifact.validation.roc_auc <= 1.0
    assert 0.0 <= artifact.validation.brier_score <= 1.0
    assert artifact.validation.log_loss >= 0.0
    assert artifact.validation.rows == artifact.validation_rows
    assert artifact.validation.positive_rows >= 1
    assert artifact.validation.negative_rows >= 1

    booster = lgb.Booster(model_str=artifact.booster_model)
    assert booster.num_feature() == len(DEFAULT_BASELINE_FEATURES)
    assert booster.feature_name() == list(DEFAULT_BASELINE_FEATURES)


def test_training_rejects_inconsistent_target_specs() -> None:
    rows = _series(70)
    first = build_labeled_examples(rows, ForwardReturnTargetSpec(horizon_bars=1))
    second = build_labeled_examples(rows, ForwardReturnTargetSpec(horizon_bars=2))
    mixed = [*first[:30], *second[30:50]]

    with pytest.raises(ValueError, match="same target specification"):
        train_lightgbm_challenger(mixed, config=_config())


def test_training_requires_sufficient_labeled_history() -> None:
    rows = _series(35)
    examples = build_labeled_examples(rows, ForwardReturnTargetSpec(horizon_bars=1))

    with pytest.raises(ValueError, match="at least 40 labeled examples"):
        train_lightgbm_challenger(examples, config=_config())


def test_training_rejects_non_finite_features() -> None:
    examples, _ = _examples()
    corrupted = examples[0].model_copy(
        update={
            "features": {
                **examples[0].features,
                "market.return_1": float("nan"),
            }
        }
    )

    with pytest.raises(ValueError, match="feature values must be finite"):
        train_lightgbm_challenger([corrupted, *examples[1:]], config=_config())
