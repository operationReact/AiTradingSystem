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

from aitrading_intelligence.india_bounded_tft import (
    BoundedTftConfig,
    BoundedTftReport,
    evaluate_bounded_tft,
)
from aitrading_intelligence.market_source import MarketResearchExport


def _run(*, config=None, train=None, holdout=None, target=None):
    return evaluate_bounded_tft(
        train or _export(TRAIN_DAYS),
        holdout or _export(HOLDOUT_DAYS),
        _fee_book(),
        target or _target(),
        config=config,
    )


def test_tft_uses_a_bounded_frozen_research_configuration() -> None:
    with pytest.raises(ValidationError):
        BoundedTftConfig(hidden_dim=256)
    with pytest.raises(ValidationError):
        BoundedTftConfig(epochs=100)
    with pytest.raises(ValidationError):
        BoundedTftConfig(lookback_returns=100)


def test_inconclusive_evidence_prevents_training_and_authority() -> None:
    report = _run(config=BoundedTftConfig(lookback_returns=40))
    assert report.status == "INCONCLUSIVE"
    assert "INSUFFICIENT_PURGED_FIT_ROWS" in report.reason_codes
    assert "INSUFFICIENT_UNTOUCHED_CALIBRATION_ROWS" in report.reason_codes
    assert "INSUFFICIENT_INDEPENDENT_HOLDOUT_ROWS" in report.reason_codes
    assert report.model_state_fingerprint is None
    assert report.candidate_metrics is None
    assert report.paired_economic_report_fingerprint is None
    assert report.can_authorize_capital is False
    assert report.model_promotion_allowed is False


def test_parent_india_fee_and_instrument_validation_remains_authoritative() -> None:
    with pytest.raises(ValueError, match="understates configured India fees"):
        _run(target=_target(cost_bps=1))
    alternate = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    alternate["symbolToken"] = "unknown-instrument"
    with pytest.raises(ValueError, match="same cash-equity instrument"):
        _run(holdout=MarketResearchExport.model_validate(alternate))


def test_missing_five_minute_context_or_forward_target_is_rejected() -> None:
    source = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    candle = source["candles"][10]
    candle["candleTime"] = (
        datetime.fromisoformat(candle["candleTime"]) + timedelta(minutes=1)
    ).isoformat()
    candle["availableAt"] = (
        datetime.fromisoformat(candle["availableAt"]) + timedelta(minutes=1)
    ).isoformat()
    with pytest.raises(ValueError, match="contiguous five-minute bars"):
        _run(holdout=MarketResearchExport.model_validate(source))


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="CPU TFT quantile training executes in dedicated pinned PyTorch workflow",
)
def test_tft_cpu_training_and_quantile_report_are_deterministic() -> None:
    first = _run()
    second = _run()
    assert first == second
    assert first.status == "RESEARCH_COMPARISON_ONLY"
    assert first.fit_rows >= 25
    assert first.calibration_rows >= 16
    assert first.independent_holdout_rows >= 60
    assert first.model_state_fingerprint is not None
    assert first.quantile_forecasts_fingerprint is not None
    assert first.baseline_metrics is not None
    assert first.candidate_metrics is not None
    assert 0 <= first.native_quantile_interval_coverage <= 1
    assert 0 <= first.candidate_metrics.interval_coverage <= 1
    assert first.paired_economic_assessment in {
        "PROMISING_RESEARCH", "NOT_IMPROVED", "INCONCLUSIVE",
    }
    assert first.paired_economic_report_fingerprint is not None
    assert first.official_tft_implementation is False
    assert first.independently_authenticated_market_data is False
    assert first.independently_preregistered_holdout is False
    assert first.economically_proven_in_indian_markets is False
    assert first.model_promotion_allowed is False
    assert first.can_authorize_capital is False


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="CPU TFT quantile training executes in dedicated pinned PyTorch workflow",
)
def test_tft_future_holdout_label_cannot_modify_training_or_calibration() -> None:
    before = _run()
    mutated = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    mutated["candles"][9]["close"] *= 1.01
    mutated["candles"][9]["high"] = max(
        mutated["candles"][9]["open"], mutated["candles"][9]["close"]
    ) * 1.001
    mutated["candles"][9]["low"] = min(
        mutated["candles"][9]["open"], mutated["candles"][9]["close"]
    ) * 0.999
    after = _run(holdout=MarketResearchExport.model_validate(mutated))
    assert before.model_state_fingerprint == after.model_state_fingerprint
    assert before.fit_membership_fingerprint == after.fit_membership_fingerprint
    assert before.calibration_membership_fingerprint == (
        after.calibration_membership_fingerprint
    )
    assert before.training_export_fingerprint == after.training_export_fingerprint
    assert before.holdout_export_fingerprint != after.holdout_export_fingerprint
    assert before.independent_holdout_membership_fingerprint != (
        after.independent_holdout_membership_fingerprint
    )


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="CPU TFT quantile training executes in dedicated pinned PyTorch workflow",
)
def test_tft_evidence_tampering_and_capital_promotions_are_rejected() -> None:
    result = _run()
    modified = result.model_dump(mode="json")
    modified["candidate_metrics"]["mae"] *= 0.5
    with pytest.raises(ValidationError, match="TFT research report fingerprint mismatch"):
        BoundedTftReport.model_validate(modified)
    modified = result.model_dump(mode="json")
    modified["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        BoundedTftReport.model_validate(modified)
    modified = result.model_dump(mode="json")
    modified["official_tft_implementation"] = True
    with pytest.raises(ValidationError):
        BoundedTftReport.model_validate(modified)
