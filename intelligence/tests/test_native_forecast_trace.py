from __future__ import annotations

import importlib.util

import pytest
from pydantic import ValidationError
from test_cross_model_causal_cohort import _case
from test_india_bounded_tft import _run as tft_run
from test_india_independent_holdout import TRAIN_DAYS, _export
from test_india_patch_transformer import _run as patch_run
from test_india_time_mixer import _run as mixer_run

from aitrading_intelligence.cross_model_causal_cohort import (
    bind_common_causal_tournament_cohort,
)
from aitrading_intelligence.india_causal_forecast_baseline import _causal_rows
from aitrading_intelligence.native_forecast_trace import (
    NativeForecastDecisionTrace,
    build_native_forecast_trace,
)


def _baseline_trace():
    parent, rows, target = _case()
    calibration = _causal_rows(_export(TRAIN_DAYS), lookback=8, horizon=1)[-32:]
    cohort = bind_common_causal_tournament_cohort(parent, rows, target)
    predictions = [row.predicted_forward_return for row in calibration]
    holdout_predictions = [row.predicted_forward_return for row in rows]
    from aitrading_intelligence.india_causal_forecast_baseline import _calibration_quantile

    bound = _calibration_quantile([
        abs(row.observed_forward_return - prediction)
        for row, prediction in zip(calibration, predictions, strict=True)
    ], 0.9)
    decision = [
        prediction - bound > target.positive_threshold_return
        for prediction in holdout_predictions
    ]
    net = [
        row.observed_forward_return - target.round_trip_cost_bps / 10_000
        if eligible else 0.0
        for row, eligible in zip(rows, decision, strict=True)
    ]
    result = build_native_forecast_trace(
        candidate_id="baseline",
        model_version_ref="v4-04-baseline",
        model_state_fingerprint="a" * 64,
        causal_baseline_report_fingerprint=parent.report_fingerprint,
        cohort=cohort,
        target=target,
        interval_coverage=0.90,
        calibration_rows=calibration,
        calibration_predictions=predictions,
        holdout_rows=rows,
        holdout_predictions=holdout_predictions,
        holdout_net_returns_after_costs=net,
        reported_trade_count=sum(decision),
    )
    return result


def test_native_trace_replays_every_decision_with_no_model_authority() -> None:
    trace = _baseline_trace()
    assert NativeForecastDecisionTrace.model_validate_json(
        trace.model_dump_json()
    ) == trace
    assert len(trace.holdout_rows) == len(trace.holdout_net_returns_after_costs)
    assert len(trace.calibration_rows) == 32
    assert trace.cohort.target_binding_fingerprint == trace.target.fingerprint
    assert trace.source_market_data_independently_verified is False
    assert trace.actual_model_checkpoint_independently_verified is False
    assert trace.preregistration_independently_verified is False
    assert trace.eligible_for_model_promotion is False
    assert trace.can_authorize_capital is False


def test_native_trace_rejects_after_cost_or_calibration_tampering() -> None:
    trace = _baseline_trace()
    base = trace.model_dump(mode="json")
    base["holdout_net_returns_after_costs"][0] += 0.01
    with pytest.raises(ValidationError, match="net returns contradict forecasts"):
        NativeForecastDecisionTrace.model_validate(base)
    base = trace.model_dump(mode="json")
    base["calibration_predictions"][0] += 0.01
    with pytest.raises(ValidationError, match="calibration interval|fingerprint mismatch"):
        NativeForecastDecisionTrace.model_validate(base)
    base = trace.model_dump(mode="json")
    base["reported_trade_count"] += 1
    with pytest.raises(ValidationError, match="trade count"):
        NativeForecastDecisionTrace.model_validate(base)


def test_native_trace_rejects_misordered_observations_and_status_forgery() -> None:
    trace = _baseline_trace()
    base = trace.model_dump(mode="json")
    base["holdout_rows"][0], base["holdout_rows"][1] = (
        base["holdout_rows"][1], base["holdout_rows"][0]
    )
    with pytest.raises(ValidationError, match="holdout membership|decision identities"):
        NativeForecastDecisionTrace.model_validate(base)
    base = trace.model_dump(mode="json")
    base["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        NativeForecastDecisionTrace.model_validate(base)


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Neural model training is in the pinned CPU PyTorch CI job",
)
@pytest.mark.parametrize("runner", [patch_run, mixer_run, tft_run])
def test_actual_cpu_model_report_contains_native_calibration_and_returns(runner) -> None:
    report = runner()
    trace = report.native_trace
    assert trace is not None
    assert trace.model_state_fingerprint == report.model_state_fingerprint
    assert trace.target.fingerprint == report.target_spec_fingerprint
    assert trace.cohort.holdout_export_fingerprint == (
        report.holdout_export_fingerprint
    )
    assert trace.calibration_rows[-1].label_available_at < (
        trace.holdout_rows[0].decision_at
    )
    assert trace.holdout_net_returns_after_costs
    assert NativeForecastDecisionTrace.model_validate_json(
        trace.model_dump_json()
    ) == trace
    assert report.can_authorize_capital is False
    assert trace.can_authorize_capital is False


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Neural model training is in the pinned CPU PyTorch CI job",
)
def test_model_report_and_embedded_trace_cannot_disagree() -> None:
    from aitrading_intelligence.india_patch_transformer import IndiaPatchForecastReport

    report = patch_run()
    payload = report.model_dump(mode="json")
    payload["native_trace"]["model_state_fingerprint"] = "f" * 64
    with pytest.raises(ValidationError):
        IndiaPatchForecastReport.model_validate(payload)
    payload = report.model_dump(mode="json")
    payload["native_trace"]["holdout_net_returns_after_costs"][0] += 0.01
    with pytest.raises(ValidationError):
        IndiaPatchForecastReport.model_validate(payload)
