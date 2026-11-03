from __future__ import annotations

import importlib.util
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

from aitrading_intelligence.india_patch_transformer import (
    IndiaPatchForecastReport,
    PatchForecastConfig,
    _return_windows,
    evaluate_bounded_patch_forecast,
)
from aitrading_intelligence.market_source import MarketResearchExport


def _run(*, config=None, train=None, holdout=None, target=None):
    return evaluate_bounded_patch_forecast(
        train or _export(TRAIN_DAYS),
        holdout or _export(HOLDOUT_DAYS),
        _fee_book(),
        target or _target(),
        config=config,
    )


def test_patch_adapter_requires_bounded_compatible_patches() -> None:
    with pytest.raises(ValidationError, match="whole non-overlapping patches"):
        PatchForecastConfig(lookback_returns=9, patch_size=4)
    with pytest.raises(ValidationError, match="attention heads must divide"):
        PatchForecastConfig(embedding_dim=10, attention_heads=4)


def test_patch_inputs_are_strictly_session_local_and_match_baseline_count() -> None:
    windows = _return_windows(_export(HOLDOUT_DAYS), lookback=8, horizon=1)
    assert len(windows) == 2 * (45 - 8 - 1)
    assert all(len(history) == 8 for history in windows)


def test_patch_rejects_gaps_in_five_minute_history() -> None:
    original = _export(HOLDOUT_DAYS)
    payload = original.model_dump(mode="json", by_alias=True)
    # Shift a single already-available candle within the declared session,
    # causing an invalid 6/4-minute interval without changing report cutoff.
    row = payload["candles"][10]
    from datetime import datetime

    row["candleTime"] = (
        datetime.fromisoformat(row["candleTime"]) + timedelta(minutes=1)
    ).isoformat()
    row["availableAt"] = (
        datetime.fromisoformat(row["availableAt"]) + timedelta(minutes=1)
    ).isoformat()
    shifted = MarketResearchExport.model_validate(payload)
    with pytest.raises(ValueError, match="contiguous five-minute bars"):
        _run(holdout=shifted)


def test_insufficient_fit_or_calibration_fails_closed_before_importing_torch() -> None:
    report = _run(config=PatchForecastConfig(lookback_returns=40, patch_size=5))
    assert report.status == "INCONCLUSIVE"
    assert report.baseline is None and report.challenger is None
    assert report.paired_economic_report_fingerprint is None
    assert report.model_state_fingerprint is None
    assert report.same_independent_holdout_compared is False
    assert report.promotion_allowed is False and report.can_authorize_capital is False
    assert "INSUFFICIENT_PURGED_FIT_ROWS" in report.reason_codes
    assert "INSUFFICIENT_UNTOUCHED_CALIBRATION_ROWS" in report.reason_codes


def test_market_fee_and_source_gates_remain_authoritative() -> None:
    with pytest.raises(ValueError, match="understates configured India fees"):
        _run(target=_target(cost_bps=1))
    changed = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    changed["symbolToken"] = "invalid-other-instrument"
    with pytest.raises(ValueError, match="same cash-equity instrument"):
        _run(holdout=MarketResearchExport.model_validate(changed))


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Pinned CPU torch runs in dedicated PyTorch model-lab workflow",
)
def test_bounded_patch_model_is_deterministic_and_research_only() -> None:
    first = _run()
    repeated = _run()

    assert first == repeated
    assert first.status in {
        "RESEARCH_CANDIDATE_ONLY", "NO_ECONOMIC_UPLIFT", "INCONCLUSIVE"
    }
    assert first.baseline is not None and first.challenger is not None
    assert first.same_independent_holdout_compared is True
    assert first.fit_rows >= 25
    assert first.calibration_rows >= 16
    assert first.holdout_rows >= 60
    assert first.model_state_fingerprint is not None
    assert first.paired_economic_report_fingerprint is not None
    assert first.paired_economic_assessment in {
        "PROMISING_RESEARCH", "NOT_IMPROVED", "INCONCLUSIVE"
    }
    assert first.baseline.mae >= 0 and first.challenger.rmse >= 0
    assert 0 <= first.challenger.interval_coverage <= 1
    assert first.external_market_source_verified is False
    assert first.holdout_pre_registration_verified is False
    assert first.model_licence_production_approved is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Pinned CPU torch runs in dedicated PyTorch model-lab workflow",
)
def test_neural_report_hash_is_tamper_evident() -> None:
    result = _run()
    changed = result.model_dump(mode="json")
    changed["fit_rows"] += 1
    with pytest.raises(ValidationError, match="patch forecast report fingerprint"):
        IndiaPatchForecastReport.model_validate(changed)


@pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Pinned CPU torch runs in dedicated PyTorch model-lab workflow",
)
def test_neural_weights_are_never_promoted_by_apparent_research_value() -> None:
    result = _run()
    with pytest.raises(ValidationError):
        IndiaPatchForecastReport.model_validate({
            **result.model_dump(mode="json"),
            "can_authorize_capital": True,
        })
    with pytest.raises(ValidationError):
        IndiaPatchForecastReport.model_validate({
            **result.model_dump(mode="json"),
            "external_market_source_verified": True,
        })
