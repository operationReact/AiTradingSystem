from __future__ import annotations

import importlib.util
from datetime import datetime, timedelta

import pytest
from pydantic import ValidationError
from test_india_independent_holdout import (
    HOLDOUT_DAYS,
    TRAIN_DAYS,
    _export,
    _fee_book,
    _target,
)

from aitrading_intelligence.india_time_mixer import (
    TimeMixerResearchConfig,
    TimeMixerResearchReport,
    evaluate_bounded_time_mixer,
)
from aitrading_intelligence.market_source import MarketResearchExport


def _run(*, config=None, train=None, holdout=None, target=None):
    return evaluate_bounded_time_mixer(
        train or _export(TRAIN_DAYS),
        holdout or _export(HOLDOUT_DAYS),
        _fee_book(),
        target or _target(),
        config=config,
    )


def test_time_mixer_config_rejects_incompatible_pooled_scales() -> None:
    with pytest.raises(ValidationError, match="lookback must divide"):
        TimeMixerResearchConfig(lookback_returns=9, downsample_factor=2)


def test_insufficient_train_calibration_and_holdout_fails_before_torch() -> None:
    report = _run(config=TimeMixerResearchConfig(
        lookback_returns=40, downsample_factor=4
    ))
    assert report.status == "INCONCLUSIVE"
    assert report.model_state_fingerprint is None
    assert report.time_mixer_metrics is None
    assert report.paired_economic_report_fingerprint is None
    assert "INSUFFICIENT_PURGED_TRAINING_ROWS" in report.reason_codes
    assert "INSUFFICIENT_UNTOUCHED_CALIBRATION_ROWS" in report.reason_codes
    assert "INSUFFICIENT_INDEPENDENT_HOLDOUT_ROWS" in report.reason_codes
    assert report.can_authorize_capital is False


def test_existing_india_market_cost_and_instrument_gates_are_preserved() -> None:
    with pytest.raises(ValueError, match="understates configured India fees"):
        _run(target=_target(cost_bps=1))
    shifted = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    shifted["symbolToken"] = "99999999"
    with pytest.raises(ValueError, match="same cash-equity instrument"):
        _run(holdout=MarketResearchExport.model_validate(shifted))


def test_five_minute_gaps_in_history_or_future_target_are_blocked() -> None:
    original = _export(HOLDOUT_DAYS)
    payload = original.model_dump(mode="json", by_alias=True)
    one = payload["candles"][10]
    one["candleTime"] = (
        datetime.fromisoformat(one["candleTime"]) + timedelta(minutes=1)
    ).isoformat()
    one["availableAt"] = (
        datetime.fromisoformat(one["availableAt"]) + timedelta(minutes=1)
    ).isoformat()
    with pytest.raises(ValueError, match="contiguous five-minute"):
        _run(holdout=MarketResearchExport.model_validate(payload))


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Actual bounded CPU model is exercised in dedicated PyTorch CI",
)
def test_original_time_mixer_trains_deterministically_on_purged_cohort() -> None:
    first = _run()
    again = _run()
    assert first == again
    assert first.status == "RESEARCH_COMPARISON_ONLY"
    assert first.fit_rows >= 25
    assert first.calibration_rows >= 16
    assert first.holdout_rows >= 60
    assert first.model_state_fingerprint is not None
    assert first.causal_baseline_metrics is not None
    assert first.time_mixer_metrics is not None
    assert first.paired_economic_report_fingerprint is not None
    assert first.paired_economic_assessment in {
        "PROMISING_RESEARCH", "NOT_IMPROVED", "INCONCLUSIVE"
    }
    assert first.causal_baseline_metrics.mae >= 0
    assert first.time_mixer_metrics.rmse >= 0
    assert 0 <= first.time_mixer_metrics.interval_coverage <= 1
    assert first.upstream_timemixer_weights_used is False
    assert first.authentic_market_source_verified is False
    assert first.preregistered_holdout_verified is False
    assert first.genuine_market_economic_uplift_verified is False
    assert first.production_model_approved is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Actual bounded CPU model is exercised in dedicated PyTorch CI",
)
def test_tampering_metrics_or_capital_status_is_rejected() -> None:
    report = _run()
    record = report.model_dump(mode="json")
    record["fit_rows"] += 1
    with pytest.raises(ValidationError, match="research report fingerprint mismatch"):
        TimeMixerResearchReport.model_validate(record)
    record = report.model_dump(mode="json")
    record["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        TimeMixerResearchReport.model_validate(record)


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Actual bounded CPU model is exercised in dedicated PyTorch CI",
)
def test_holdout_future_price_changes_do_not_refit_the_model() -> None:
    first = _run()
    original = _export(HOLDOUT_DAYS)
    changed = original.model_dump(mode="json", by_alias=True)
    changed["candles"][9]["close"] *= 1.01
    changed["candles"][9]["high"] = max(
        changed["candles"][9]["open"], changed["candles"][9]["close"]
    ) * 1.001
    changed["candles"][9]["low"] = min(
        changed["candles"][9]["open"], changed["candles"][9]["close"]
    ) * 0.999
    after = _run(holdout=MarketResearchExport.model_validate(changed))
    assert first.model_state_fingerprint == after.model_state_fingerprint
    assert first.fit_example_membership_fingerprint == (
        after.fit_example_membership_fingerprint
    )
    assert first.calibration_example_membership_fingerprint == (
        after.calibration_example_membership_fingerprint
    )
    assert first.holdout_export_fingerprint != after.holdout_export_fingerprint
    assert first.holdout_example_membership_fingerprint != (
        after.holdout_example_membership_fingerprint
    )
