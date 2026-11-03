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

from aitrading_intelligence.india_chronos2_zero_shot import (
    Chronos2ResearchConfig,
    Chronos2ResearchReport,
    _forecast_one,
    evaluate_chronos2_zero_shot,
)
from aitrading_intelligence.market_source import MarketResearchExport

PIN = "a" * 40


class FakeChronosPipeline:
    """Simulated inference, NEVER evidence of official Amazon checkpoint execution."""

    def __init__(self, quantiles: tuple[float, float, float] = (0.0, 0.005, 0.010)):
        self.quantiles = quantiles
        self.calls: list[list[dict]] = []

    def predict_df(self, frame, *, prediction_length, quantile_levels,
                   id_column, timestamp_column, target):
        assert prediction_length == 1
        assert quantile_levels == [0.1, 0.5, 0.9]
        assert (id_column, timestamp_column, target) == ("item_id", "timestamp", "target")
        assert all(row["target"] == row["target"] for row in frame)
        assert all(
            later["timestamp"] - before["timestamp"] == timedelta(minutes=5)
            for before, later in zip(frame[:-1], frame[1:], strict=True)
        )
        self.calls.append(frame)
        lo, mid, hi = self.quantiles
        return [{"0.1": lo, "0.5": mid, "0.9": hi}]


def _run(*, config=None, pipeline=None, training=None, holdout=None, target=None):
    pipe = pipeline if pipeline is not None else FakeChronosPipeline()
    report = evaluate_chronos2_zero_shot(
        training or _export(TRAIN_DAYS),
        holdout or _export(HOLDOUT_DAYS),
        _fee_book(),
        target or _target(),
        config=config or Chronos2ResearchConfig(
            checkpoint_revision=PIN,
        ),
        pipeline=pipe,
        frame_factory=lambda rows: rows,
    )
    return report, pipe


def test_fake_adapter_runs_same_independent_holdout_and_blocks_capital() -> None:
    first, pipe = _run()
    second, _ = _run()
    assert first == second
    assert first.status == "RESEARCH_COMPARISON_ONLY"
    assert first.adapter_invocation_completed is True
    assert first.checkpoint_integrity_independently_verified is False
    assert first.holdout_rows >= 60
    assert first.calibrated_rows == 16
    assert len(pipe.calls) == first.calibrated_rows + first.holdout_rows
    assert first.paired_economic_assessment in {
        "INCONCLUSIVE", "NOT_IMPROVED", "PROMISING_RESEARCH"
    }
    assert first.paired_economic_report_fingerprint is not None
    assert first.native_quantile_forecasts_fingerprint is not None
    assert first.baseline_metrics_fingerprint != first.chronos_metrics_fingerprint
    assert first.market_source_independently_verified is False
    assert first.independent_holdout_preregistration_verified is False
    assert first.checkpoint_commercial_review_complete is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_holdout_future_candle_does_not_enter_first_forecast_context() -> None:
    original = _export(HOLDOUT_DAYS)
    one, pipe = _run(holdout=original)
    first_context = pipe.calls[one.calibrated_rows]
    payload = original.model_dump(mode="json", by_alias=True)
    # Decision index 8, label at 9. Modify only future OHLC values and keep valid bounds.
    payload["candles"][9]["close"] *= 1.01
    payload["candles"][9]["high"] = max(
        payload["candles"][9]["open"], payload["candles"][9]["close"]
    ) * 1.001
    payload["candles"][9]["low"] = min(
        payload["candles"][9]["open"], payload["candles"][9]["close"]
    ) * 0.999
    two, modified = _run(holdout=MarketResearchExport.model_validate(payload))
    assert first_context == modified.calls[two.calibrated_rows]
    assert one.train_calibration_membership_fingerprint == (
        two.train_calibration_membership_fingerprint
    )
    assert one.holdout_export_fingerprint != two.holdout_export_fingerprint


def test_bad_quantiles_and_wrong_forecast_shapes_fail_closed() -> None:
    for quantiles in ((0.3, 0.1, 0.4), (float("nan"), 0.1, 0.2), (0, 0.1, 2.1)):
        with pytest.raises(ValueError, match="quantiles|bounds"):
            _run(pipeline=FakeChronosPipeline(quantiles))
    original = FakeChronosPipeline()

    class Broken(FakeChronosPipeline):
        def predict_df(self, *args, **kwargs):
            return []

    with pytest.raises(ValueError, match="exactly one next-bar quantile"):
        _run(pipeline=Broken())
    assert original.calls == []


def test_insufficient_calibration_or_holdout_never_invokes_checkpoint() -> None:
    cfg = Chronos2ResearchConfig(
        checkpoint_revision=PIN, minimum_calibration_rows=120,
    )
    report, fake = _run(config=cfg)
    assert report.status == "INCONCLUSIVE"
    assert report.adapter_invocation_completed is False
    assert report.paired_economic_report_fingerprint is None
    assert not fake.calls
    assert report.can_authorize_capital is False


def test_cost_and_source_mismatch_still_checked_by_java_export_parent() -> None:
    with pytest.raises(ValueError, match="understates configured India fees"):
        _run(target=_target(cost_bps=1))
    payload = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    payload["symbolToken"] = "unknown-token"
    with pytest.raises(ValueError, match="same cash-equity instrument"):
        _run(holdout=MarketResearchExport.model_validate(payload))


def test_rejects_nonpinned_revision_and_rewritten_evidence() -> None:
    with pytest.raises(ValidationError):
        Chronos2ResearchConfig(checkpoint_revision="latest")
    report, _ = _run()
    changed = report.model_dump(mode="json")
    changed["holdout_rows"] += 1
    with pytest.raises(ValidationError, match="report fingerprint mismatch"):
        Chronos2ResearchReport.model_validate(changed)


def test_direct_adapter_quantiles_keep_only_past_five_minute_context() -> None:
    pipe = FakeChronosPipeline()
    decision = _export(HOLDOUT_DAYS).candles[8].available_at
    out = _forecast_one(
        pipe, lambda rows: rows, [0.01] * 8, decision_at=decision,
    )
    assert out == (0.0, 0.005, 0.010)
    assert len(pipe.calls[0]) == 8
    assert pipe.calls[0][-1]["timestamp"] == decision
    assert pipe.calls[0][0]["timestamp"] == decision - timedelta(minutes=35)
