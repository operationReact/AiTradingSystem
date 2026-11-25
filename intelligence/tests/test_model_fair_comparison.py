import importlib.util
from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.model_fair_comparison import (
    FairComparisonConfig,
    evaluate_model_families,
)
from aitrading_intelligence.pytorch_target import (
    ForwardReturnTargetSpec,
    build_labeled_examples,
)


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
                provenance=[f"fair-comparison-fixture:{index}"],
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


def _config() -> FairComparisonConfig:
    return FairComparisonConfig(
        fold_count=3,
        test_rows_per_fold=10,
        minimum_train_rows=20,
        calibration_bins=5,
        seed=42,
    )


def test_comparison_uses_identical_purged_folds_and_economic_metrics() -> None:
    spec = _target_spec()
    examples = build_labeled_examples(_series(), spec)
    evaluation_as_of = examples[-1].target_observed_at

    report = evaluate_model_families(
        examples,
        spec,
        evaluation_as_of=evaluation_as_of,
        config=_config(),
        include_pytorch=False,
    )

    assert report.comparison_version == "model-fair-comparison.v1"
    assert report.included_pytorch is False
    assert {model.model_family for model in report.models} == {
        "LOGISTIC",
        "HIST_TREE",
        "LIGHTGBM",
        "CATBOOST",
    }
    assert len(report.folds) == 3
    assert len({model.fold_membership_fingerprint for model in report.models}) == 1
    assert set(report.economic_ranking) == {model.model_family for model in report.models}
    for fold in report.folds:
        assert fold.train_end < fold.test_start
        assert fold.train_rows >= 20
        assert fold.test_rows == 10
    for model in report.models:
        assert 0.0 <= model.roc_auc <= 1.0
        assert 0.0 <= model.brier_score <= 1.0
        assert model.log_loss >= 0.0
        assert 0.0 <= model.expected_calibration_error <= 1.0
        assert 0.0 <= model.trade_rate <= 1.0
        assert 0.0 <= model.max_drawdown <= 1.0
        assert model.promotion_allowed is False
        assert model.can_authorize_capital is False
    assert report.status == "RESEARCH_COMPARISON_ONLY"
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_comparison_is_reproducible_and_input_order_invariant() -> None:
    spec = _target_spec()
    examples = build_labeled_examples(_series(), spec)
    evaluation_as_of = examples[-1].target_observed_at

    first = evaluate_model_families(
        examples,
        spec,
        evaluation_as_of=evaluation_as_of,
        config=_config(),
        include_pytorch=False,
    )
    second = evaluate_model_families(
        list(reversed(examples)),
        spec,
        evaluation_as_of=evaluation_as_of,
        config=_config(),
        include_pytorch=False,
    )

    assert first == second
    assert first.report_fingerprint == second.report_fingerprint


def test_future_archive_growth_cannot_rewrite_historical_comparison() -> None:
    spec = _target_spec()
    historical = build_labeled_examples(_series(90), spec)
    extended = build_labeled_examples(_series(120), spec)
    evaluation_as_of = historical[-1].target_observed_at

    before_growth = evaluate_model_families(
        historical,
        spec,
        evaluation_as_of=evaluation_as_of,
        config=_config(),
        include_pytorch=False,
    )
    after_growth = evaluate_model_families(
        extended,
        spec,
        evaluation_as_of=evaluation_as_of,
        config=_config(),
        include_pytorch=False,
    )

    assert before_growth == after_growth


def test_comparison_rejects_mixed_target_specifications() -> None:
    rows = _series(100)
    first_spec = _target_spec()
    second_spec = ForwardReturnTargetSpec(
        horizon_bars=2,
        minimum_edge_bps=25,
        round_trip_cost_bps=10,
    )
    first = build_labeled_examples(rows, first_spec)
    second = build_labeled_examples(rows, second_spec)
    mixed = [*first[:45], *second[45:80]]

    with pytest.raises(ValueError, match="requested target specification"):
        evaluate_model_families(
            mixed,
            first_spec,
            evaluation_as_of=mixed[-1].target_observed_at,
            config=_config(),
            include_pytorch=False,
        )


def test_pytorch_runs_on_the_same_folds_when_cpu_dependency_is_installed() -> None:
    if importlib.util.find_spec("torch") is None:
        pytest.skip("targeted model-lab CI installs the separate PyTorch CPU requirement")

    spec = _target_spec()
    examples = build_labeled_examples(_series(), spec)
    report = evaluate_model_families(
        examples,
        spec,
        evaluation_as_of=examples[-1].target_observed_at,
        config=_config(),
        include_pytorch=True,
    )

    assert report.included_pytorch is True
    assert {model.model_family for model in report.models} == {
        "LOGISTIC",
        "HIST_TREE",
        "LIGHTGBM",
        "CATBOOST",
        "PYTORCH",
    }
    assert len({model.fold_membership_fingerprint for model in report.models}) == 1
    pytorch = next(model for model in report.models if model.model_family == "PYTORCH")
    assert pytorch.evaluated_folds == len(report.folds)
    assert pytorch.evaluated_rows == sum(fold.test_rows for fold in report.folds)
    assert pytorch.promotion_allowed is False
    assert pytorch.can_authorize_capital is False
