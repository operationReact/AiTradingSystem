from __future__ import annotations

import importlib.util
import math

import pytest
from pydantic import ValidationError
from test_india_independent_holdout import (
    HOLDOUT_DAYS,
    TRAIN_DAYS,
    _export,
    _fee_book,
    _target,
)

from aitrading_intelligence.india_independent_holdout import IndiaHoldoutConfig
from aitrading_intelligence.xgboost_benchmark import (
    XGBoostConditionalBenchmarkReport,
    XGBoostResearchConfig,
    XGBoostScreenPolicy,
    evaluate_conditional_xgboost_benchmark,
)

pytestmark = pytest.mark.skipif(
    importlib.util.find_spec("xgboost") is None,
    reason="install optional CPU XGBoost for this benchmark",
)


def _benchmark(*, train=None, holdout=None, target=None, policy=None, model=None):
    return evaluate_conditional_xgboost_benchmark(
        train or _export(TRAIN_DAYS),
        holdout or _export(HOLDOUT_DAYS),
        _fee_book(),
        target or _target(),
        xgboost_config=model,
        screen_policy=policy,
    )


def test_xgboost_trains_on_same_causal_india_holdout_without_authority() -> None:
    report = _benchmark()

    assert report.contract_version == "conditional-xgboost-benchmark.v1"
    assert report.status in {"RESEARCH_CANDIDATE_ONLY", "NOT_ADDITIVE", "INCONCLUSIVE"}
    assert report.joint_screen_report_fingerprint is not None
    assert report.xgboost_runtime_version is not None
    assert report.evaluated_holdout_rows >= 30
    assert report.xgboost_fit_ms is not None
    assert report.xgboost_fit_ms >= 0
    assert report.xgboost_inference_ms_per_1000_rows is not None
    assert math.isfinite(report.xgboost_inference_ms_per_1000_rows)
    assert report.catboost_net_return_after_costs is not None
    assert report.xgboost_net_return_after_costs is not None
    assert report.total_net_uplift_after_costs == pytest.approx(
        report.xgboost_net_return_after_costs - report.catboost_net_return_after_costs
    )
    assert report.external_market_source_verified is False
    assert report.holdout_pre_registration_verified is False
    assert report.multiple_trial_selection_adjusted is False
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_missing_market_sessions_are_inconclusive_without_fitting_xgboost() -> None:
    report = _benchmark(holdout=_export((HOLDOUT_DAYS[0],)))

    assert report.status == "INCONCLUSIVE"
    assert report.joint_screen_report_fingerprint is None
    assert report.xgboost_runtime_version is None
    assert report.xgboost_fit_ms is None
    assert any("INSUFFICIENT_HOLDOUT_SESSIONS" in reason for reason in report.reason_codes)


def test_an_unrealistic_broker_cost_cannot_pass_through_to_xgboost() -> None:
    with pytest.raises(ValueError, match="understates configured India fees"):
        _benchmark(target=_target(cost_bps=1))


def test_unrealistic_latency_budget_blocks_a_research_candidate() -> None:
    report = _benchmark(
        policy=XGBoostScreenPolicy(max_inference_ms_per_1000_rows=0.000001)
    )
    assert report.status in {"INCONCLUSIVE", "NOT_ADDITIVE"}
    assert "INFERENCE_LATENCY_BUDGET_EXCEEDED" in report.reason_codes
    assert report.promotion_allowed is False


def test_large_economic_hurdle_disallows_trivial_uplift() -> None:
    report = _benchmark(
        policy=XGBoostScreenPolicy(minimum_net_uplift_per_decision=0.1)
    )
    assert report.status != "RESEARCH_CANDIDATE_ONLY"
    assert any("ECONOMIC_" in reason for reason in report.reason_codes)


def test_cannot_claim_xgboost_has_proved_an_independent_indian_source() -> None:
    report = _benchmark()
    forged = report.model_dump(mode="json")
    forged["external_market_source_verified"] = True
    with pytest.raises(ValidationError):
        XGBoostConditionalBenchmarkReport.model_validate(forged)


def test_immutable_report_rejects_metric_tampering() -> None:
    report = _benchmark()
    forged = report.model_dump(mode="json")
    forged["xgboost_net_return_after_costs"] += 0.1
    with pytest.raises(ValidationError, match="XGBoost benchmark fingerprint"):
        XGBoostConditionalBenchmarkReport.model_validate(forged)


def test_xgboost_experiment_size_is_bounded() -> None:
    with pytest.raises(ValueError):
        XGBoostResearchConfig(n_estimators=1_000)
    with pytest.raises(ValueError):
        XGBoostResearchConfig(max_depth=10)
    with pytest.raises(ValueError):
        XGBoostScreenPolicy(maximum_brier_regression=0.5)


def test_holdout_policy_is_reused_and_never_weakened() -> None:
    with pytest.raises(ValueError, match="target cost bps understates"):
        evaluate_conditional_xgboost_benchmark(
            _export(TRAIN_DAYS),
            _export(HOLDOUT_DAYS),
            _fee_book(),
            _target(cost_bps=2),
            holdout_config=IndiaHoldoutConfig(
                assumed_round_trip_spread_slippage_bps=5
            ),
        )
