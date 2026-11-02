from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.outcomes import (
    PriceObservation,
    TradeOutcomeInput,
    build_outcome_dataset_manifest,
    build_trade_outcome_record,
)

START = datetime(2026, 1, 5, 4, 0, tzinfo=UTC)


def _hash(value: int) -> str:
    return f"{value:064x}"


def _source(*, trade_id: str = "paper-1", offset_minutes: int = 0) -> TradeOutcomeInput:
    decision_at = START + timedelta(minutes=offset_minutes)
    entry_at = decision_at + timedelta(minutes=1)
    exit_at = entry_at + timedelta(minutes=10)
    return TradeOutcomeInput(
        trade_id=trade_id,
        symbol="RELIANCE",
        side="LONG",
        trading_mode="PAPER",
        execution_mode="AUTOMATED",
        decision_at=decision_at,
        data_cutoff=decision_at,
        entry_at=entry_at,
        exit_at=exit_at,
        outcome_available_at=exit_at + timedelta(seconds=10),
        decision_price=100.0,
        entry_price=101.0,
        exit_price=105.0,
        quantity=10,
        realized_pnl=40.0,
        exit_reason="TARGET",
        market_regime="TREND_UP",
        feature_schema_version="features.v1",
        feature_row_fingerprint=_hash(1 + offset_minutes),
        opportunity_fingerprint=_hash(101 + offset_minutes),
        model_fingerprint=_hash(201),
        inference_fingerprint=_hash(301 + offset_minutes),
        gemini_evidence_fingerprints=[_hash(401), _hash(402), _hash(401)],
        risk_decision_fingerprint=_hash(501 + offset_minutes),
        order_intent_fingerprint=_hash(601 + offset_minutes),
        execution_fingerprint=_hash(701 + offset_minutes),
        price_path=[
            PriceObservation(observed_at=entry_at + timedelta(minutes=8), price=106.0),
            PriceObservation(observed_at=entry_at + timedelta(minutes=2), price=98.0),
            PriceObservation(observed_at=entry_at + timedelta(minutes=5), price=102.0),
        ],
    )


def test_outcome_normalizes_path_slippage_and_is_order_invariant() -> None:
    source = _source()
    first = build_trade_outcome_record(source)
    second = build_trade_outcome_record(
        source.model_copy(update={"price_path": list(reversed(source.price_path))})
    )

    assert first.record_fingerprint == second.record_fingerprint
    assert first.price_path_fingerprint == second.price_path_fingerprint
    assert first.entry_slippage_bps == pytest.approx(100.0)
    assert first.mae_return == pytest.approx((98.0 / 101.0) - 1.0)
    assert first.mfe_return == pytest.approx((106.0 / 101.0) - 1.0)
    assert first.realized_return == pytest.approx(40.0 / 1010.0)
    assert first.gemini_evidence_fingerprints == [_hash(401), _hash(402)]
    assert first.status == "EVALUATION_EVIDENCE_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_outcome_rejects_future_cutoff_and_price_path_outside_holding_window() -> None:
    source = _source()
    with pytest.raises(ValueError, match="data_cutoff"):
        source.model_copy(update={"data_cutoff": source.decision_at + timedelta(seconds=1)})

    with pytest.raises(ValueError, match="holding window"):
        TradeOutcomeInput(
            **source.model_dump(exclude={"price_path"}),
            price_path=[
                PriceObservation(
                    observed_at=source.entry_at - timedelta(seconds=1),
                    price=100.0,
                )
            ],
        )


def test_outcome_dataset_is_versioned_deterministic_and_schema_pure() -> None:
    first = build_trade_outcome_record(_source(trade_id="paper-1"))
    second = build_trade_outcome_record(_source(trade_id="paper-2", offset_minutes=30))
    generated_at = second.outcome_available_at + timedelta(minutes=1)

    manifest = build_outcome_dataset_manifest([second, first], generated_at=generated_at)
    reordered = build_outcome_dataset_manifest([first, second], generated_at=generated_at)

    assert manifest.dataset_fingerprint == reordered.dataset_fingerprint
    assert manifest.record_count == 2
    assert manifest.feature_schema_version == "features.v1"
    assert manifest.model_fingerprints == [_hash(201)]
    assert manifest.source_cutoff == second.outcome_available_at
    assert manifest.status == "VERSIONED_EVALUATION_DATASET"
    assert manifest.promotion_allowed is False

    incompatible = second.model_copy(update={"feature_schema_version": "features.v2"})
    with pytest.raises(ValueError, match="one feature schema"):
        build_outcome_dataset_manifest([first, incompatible], generated_at=generated_at)
