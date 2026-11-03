from __future__ import annotations

from datetime import timedelta

import pytest
from pydantic import ValidationError
from test_india_independent_holdout import (
    HOLDOUT_DAYS,
    TRAIN_DAYS,
    _export,
    _fee_book,
    _target,
)

from aitrading_intelligence.india_causal_forecast_baseline import (
    CausalForecastBaselineReport,
    CausalForecastConfig,
    CausalForecastRow,
    _causal_rows,
    evaluate_india_causal_forecast_baseline,
)
from aitrading_intelligence.market_source import MarketResearchExport


def _research(*, training=None, holdout=None, target=None, config=None):
    return evaluate_india_causal_forecast_baseline(
        training or _export(TRAIN_DAYS),
        holdout or _export(HOLDOUT_DAYS),
        _fee_book(),
        target or _target(),
        config=config,
    )


def test_forecasting_baseline_is_deterministic_and_zero_capital() -> None:
    first = _research()
    repeated = _research()
    assert first == repeated
    assert first.status == "RESEARCH_FORECAST_METRICS_ONLY"
    assert first.calibration_rows >= 50
    assert first.independent_holdout_rows >= 50
    assert first.independent_holdout_sessions == 2
    assert first.calibration_absolute_error_quantile is not None
    assert first.holdout_mae is not None
    assert first.holdout_rmse is not None
    assert 0 <= first.holdout_interval_coverage <= 1
    assert first.holdout_average_interval_width == pytest.approx(
        first.calibration_absolute_error_quantile * 2
    )
    assert first.assessed_round_trip_cost_bps == 25
    assert first.trade_count >= 0
    assert first.external_market_source_verified is False
    assert first.holdout_pre_registration_verified is False
    assert first.competing_model_improvement_verified is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_every_forecast_only_uses_data_within_its_own_market_session() -> None:
    rows = _causal_rows(_export(HOLDOUT_DAYS), lookback=8, horizon=1)
    assert len(rows) == 2 * (45 - 8 - 1)
    assert {row.session_date for row in rows} == {
        day.isoformat() for day in HOLDOUT_DAYS
    }
    assert all(
        row.decision_at.date() == row.label_available_at.date()
        for row in rows
    )
    assert all(row.label_available_at > row.decision_at for row in rows)


def test_future_label_mutation_cannot_change_earlier_forecast_or_calibration() -> None:
    original = _export(HOLDOUT_DAYS)
    before = _causal_rows(original, lookback=8, horizon=1)
    first = before[0]
    payload = original.model_dump(mode="json", by_alias=True)
    # The next bar after the first forecast decision is a future target.
    payload["candles"][9]["close"] *= 1.01
    payload["candles"][9]["high"] = max(
        payload["candles"][9]["open"], payload["candles"][9]["close"]
    ) * 1.001
    payload["candles"][9]["low"] = min(
        payload["candles"][9]["open"], payload["candles"][9]["close"]
    ) * 0.999
    changed = MarketResearchExport.model_validate(payload)
    after = _causal_rows(changed, lookback=8, horizon=1)
    assert first.predicted_forward_return == after[0].predicted_forward_return
    assert first.history_fingerprint == after[0].history_fingerprint
    assert first.observed_forward_return != after[0].observed_forward_return

    base = _research()
    altered = _research(holdout=changed)
    assert base.calibration_absolute_error_quantile == (
        altered.calibration_absolute_error_quantile
    )
    assert base.train_export_fingerprint == altered.train_export_fingerprint
    assert base.holdout_export_fingerprint != altered.holdout_export_fingerprint


def test_single_holdout_session_is_inconclusive_not_backtested_as_evidence() -> None:
    report = _research(holdout=_export((HOLDOUT_DAYS[0],)))
    assert report.status == "INCONCLUSIVE"
    assert report.reason_codes
    assert report.calibration_absolute_error_quantile is None
    assert report.holdout_mae is None
    assert report.trade_count == 0
    assert report.net_return_after_assumed_costs == 0
    assert report.promotion_allowed is False


def test_high_lookback_with_insufficient_data_fails_closed() -> None:
    report = _research(config=CausalForecastConfig(
        lookback_returns=40,
        minimum_calibration_rows=25,
        minimum_holdout_rows=30,
    ))
    assert report.status == "INCONCLUSIVE"
    assert "INSUFFICIENT_TRAIN_ONLY_CALIBRATION_ROWS" in report.reason_codes
    assert "INSUFFICIENT_HOLDOUT_FORECAST_ROWS" in report.reason_codes
    assert report.holdout_interval_coverage is None


def test_india_fee_and_source_gates_are_inherited_from_parent_holdout() -> None:
    with pytest.raises(ValueError, match="understates configured India fees"):
        _research(target=_target(cost_bps=1))
    altered = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    altered["symbolToken"] = "wrong-symbol"
    with pytest.raises(ValueError, match="same cash-equity instrument"):
        _research(holdout=MarketResearchExport.model_validate(altered))


def test_forecast_row_and_report_fingerprints_cannot_be_rewritten() -> None:
    report = _research()
    changed = report.model_dump(mode="json")
    changed["holdout_rmse"] = 0.42
    with pytest.raises(ValidationError, match="forecast baseline report fingerprint"):
        CausalForecastBaselineReport.model_validate(changed)

    source = _causal_rows(_export(HOLDOUT_DAYS), lookback=8, horizon=1)[0]
    changed_row = source.model_dump(mode="json")
    changed_row["predicted_forward_return"] += 0.02
    with pytest.raises(ValidationError, match="forecast row fingerprint"):
        CausalForecastRow.model_validate(changed_row)


def test_unsupported_future_target_across_session_is_rejected() -> None:
    row = _causal_rows(_export(HOLDOUT_DAYS), lookback=8, horizon=1)[-1]
    changed = row.model_dump(mode="json")
    changed["label_available_at"] = (
        row.label_available_at + timedelta(days=1)
    ).isoformat()
    with pytest.raises(ValidationError, match="cannot cross India trading sessions"):
        CausalForecastRow.model_validate(changed)


def test_conformal_quantile_is_only_derived_from_training_residuals() -> None:
    from aitrading_intelligence.india_causal_forecast_baseline import _calibration_quantile

    assert _calibration_quantile([0.01] * 39 + [0.1], 0.90) == 0.01
    with pytest.raises(ValueError, match="insufficient calibration"):
        _calibration_quantile([0.01] * 4, 0.95)
