from __future__ import annotations

import hashlib
from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError

from aitrading_intelligence.model_fair_comparison import (
    ModelAggregateMetrics,
    ModelFairComparisonReport,
    PurgedFold,
)
from aitrading_intelligence.structured_alpha_baseline import (
    StructuredAlphaBaselineConfig,
    StructuredAlphaBaselineReport,
    evaluate_structured_alpha_baseline,
)


def _sha(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _model(
    family: str,
    net_return: float,
    *,
    trades: int = 30,
    rows: int = 100,
    brier: float = 0.15,
    drawdown: float = 0.08,
    fold_fingerprint: str | None = None,
) -> ModelAggregateMetrics:
    return ModelAggregateMetrics(
        model_family=family,
        model_version=f"{family.lower()}-model.v1",
        evaluated_folds=2,
        evaluated_rows=rows,
        positive_rows=rows // 2,
        negative_rows=rows // 2,
        roc_auc=0.65,
        brier_score=brier,
        log_loss=0.60,
        expected_calibration_error=0.05,
        trade_count=trades,
        trade_rate=trades / rows,
        win_rate_after_costs=0.55,
        total_net_return=net_return,
        mean_net_return_per_trade=net_return / trades,
        max_drawdown=drawdown,
        probability_fingerprint=_sha(f"prob-{family}"),
        fold_membership_fingerprint=fold_fingerprint or _sha("common-folds"),
        metrics_fingerprint=_sha(f"metrics-{family}-{net_return}-{trades}-{rows}"),
    )


def _comparison(
    *,
    catboost_trades: int = 30,
    lightgbm_return: float = 0.14,
    lightgbm_trades: int = 30,
    lightgbm_rows: int = 100,
    lightgbm_brier: float = 0.16,
    lightgbm_drawdown: float = 0.09,
) -> ModelFairComparisonReport:
    start = datetime(2025, 1, 1, tzinfo=timezone.utc)
    folds: list[PurgedFold] = []
    for index in range(2):
        boundary = start + timedelta(days=31 * (index + 1))
        folds.append(
            PurgedFold(
                fold_index=index,
                train_start=start,
                train_end=boundary - timedelta(days=2),
                test_start=boundary,
                test_end=boundary + timedelta(days=2),
                train_rows=20,
                test_rows=50,
                train_example_fingerprints=[
                    _sha(f"train-{index}-{row}") for row in range(20)
                ],
                test_example_fingerprints=[
                    _sha(f"test-{index}-{row}") for row in range(50)
                ],
                fold_fingerprint=_sha(f"fold-{index}"),
            )
        )

    models = [
        _model("LOGISTIC", 0.07),
        _model("HIST_TREE", 0.08),
        _model(
            "LIGHTGBM",
            lightgbm_return,
            trades=lightgbm_trades,
            rows=lightgbm_rows,
            brier=lightgbm_brier,
            drawdown=lightgbm_drawdown,
        ),
        _model("CATBOOST", 0.12, trades=catboost_trades),
        _model("PYTORCH", 0.10),
    ]
    return ModelFairComparisonReport(
        evaluation_as_of=start + timedelta(days=120),
        feature_names=["rsi", "atr"],
        target_spec_fingerprint=_sha("target"),
        comparison_config_fingerprint=_sha("config"),
        eligible_example_count=140,
        folds=folds,
        models=models,
        included_pytorch=True,
        economic_ranking=["LIGHTGBM", "CATBOOST", "PYTORCH", "HIST_TREE", "LOGISTIC"],
        report_fingerprint=_sha("fair-comparison"),
    )


def test_profitable_incremental_lightgbm_is_only_research_leader() -> None:
    report = evaluate_structured_alpha_baseline(_comparison())

    assert report.status == "CHALLENGER_PROMISING"
    assert report.research_leader == "LIGHTGBM"
    assert report.challengers[0].state == "PROMISING"
    assert report.challengers[1].state == "BASELINE_NOT_BEATEN"
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False
    assert report.challengers[0].incremental_net_return == pytest.approx(0.02)


def test_catboost_retained_when_challenger_has_no_incremental_value() -> None:
    report = evaluate_structured_alpha_baseline(_comparison(lightgbm_return=0.11))

    assert report.status == "BASELINE_RETAINED"
    assert report.research_leader == "CATBOOST"
    assert "NO_INCREMENTAL_NET_VALUE" in report.challengers[0].reason_codes


def test_low_baseline_trade_count_fails_closed_without_research_leader() -> None:
    report = evaluate_structured_alpha_baseline(_comparison(catboost_trades=8))

    assert report.status == "INCONCLUSIVE"
    assert report.research_leader is None
    assert "INSUFFICIENT_OOS_TRADES" in report.reason_codes
    assert all(candidate.state == "INCONCLUSIVE" for candidate in report.challengers)


def test_challenger_with_mismatched_sample_count_is_inconclusive() -> None:
    report = evaluate_structured_alpha_baseline(
        _comparison(lightgbm_rows=90, lightgbm_trades=25)
    )

    assert report.status == "INCONCLUSIVE"
    assert report.research_leader is None
    assert "MISMATCHED_EVALUATION_COUNTS" in report.challengers[0].reason_codes


def test_calibration_or_drawdown_regression_blocks_research_challenger() -> None:
    report = evaluate_structured_alpha_baseline(
        _comparison(lightgbm_brier=0.30, lightgbm_drawdown=0.20)
    )

    assert report.status == "BASELINE_RETAINED"
    assert report.research_leader == "CATBOOST"
    assert "CALIBRATION_REGRESSION" in report.challengers[0].reason_codes
    assert "DRAWDOWN_REGRESSION" in report.challengers[0].reason_codes


def test_report_is_deterministic_across_input_model_order() -> None:
    source = _comparison()
    reordered = source.model_copy(update={"models": list(reversed(source.models))})
    first = evaluate_structured_alpha_baseline(source)
    second = evaluate_structured_alpha_baseline(reordered)

    assert first.report_fingerprint == second.report_fingerprint
    assert [item.model_family for item in first.challengers] == [
        "LIGHTGBM",
        "PYTORCH",
    ]


def test_policy_thresholds_are_versioned_and_can_fail_closed() -> None:
    source = _comparison()
    default = evaluate_structured_alpha_baseline(source)
    stricter = evaluate_structured_alpha_baseline(
        source, config=StructuredAlphaBaselineConfig(minimum_oos_rows=120)
    )
    assert default.status == "CHALLENGER_PROMISING"
    assert stricter.status == "INCONCLUSIVE"
    assert default.config_fingerprint != stricter.config_fingerprint
    assert default.report_fingerprint != stricter.report_fingerprint


def test_tampered_report_fingerprint_is_rejected() -> None:
    report = evaluate_structured_alpha_baseline(_comparison())
    with pytest.raises(ValidationError, match="fingerprint mismatch"):
        StructuredAlphaBaselineReport.model_validate(
            {**report.model_dump(), "report_fingerprint": _sha("tampered")}
        )


def test_cannot_use_mismatched_fold_memberships() -> None:
    source = _comparison()
    altered = source.model_copy(
        update={
            "models": [
                model.model_copy(update={"fold_membership_fingerprint": _sha("wrong")})
                if model.model_family == "LIGHTGBM" else model
                for model in source.models
            ]
        }
    )
    with pytest.raises(ValidationError, match="identical fold membership"):
        evaluate_structured_alpha_baseline(altered)
