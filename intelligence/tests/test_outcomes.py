from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest
from pydantic import ValidationError

from aitrading_intelligence.outcomes import (
    TradeOutcomeInput,
    build_outcome_dataset,
    build_trade_outcome,
)

ENTRY_AT = datetime(2026, 10, 30, 4, 0, tzinfo=UTC)
ENTRY_ORDER = UUID("00000000-0000-0000-0000-000000000101")
EXIT_ORDER = UUID("00000000-0000-0000-0000-000000000102")
ENTRY_RISK = UUID("00000000-0000-0000-0000-000000000201")
EXIT_RISK = UUID("00000000-0000-0000-0000-000000000202")
ENTRY_FILL = UUID("00000000-0000-0000-0000-000000000301")
EXIT_FILL = UUID("00000000-0000-0000-0000-000000000302")


def _source(*, exit_minutes: int = 30, evidence_offset_minutes: int = -1) -> TradeOutcomeInput:
    return TradeOutcomeInput(
        entry_order_id=ENTRY_ORDER,
        exit_order_id=EXIT_ORDER,
        entry_risk_decision_id=ENTRY_RISK,
        exit_risk_decision_id=EXIT_RISK,
        candidate_id=41,
        entry_fill_ids=[ENTRY_FILL],
        exit_fill_ids=[EXIT_FILL],
        symbol="reliance",
        product_type="INTRADAY",
        execution_origin="AUTOMATED",
        entry_side="BUY",
        exit_side="SELL",
        quantity=10,
        entry_price=100.0,
        exit_price=103.0,
        entry_at=ENTRY_AT,
        exit_at=ENTRY_AT + timedelta(minutes=exit_minutes),
        gross_pnl=30.0,
        fee_amount=2.0,
        slippage_amount=1.0,
        realized_pnl=27.0,
        mae_return=-0.008,
        mfe_return=0.041,
        exit_reason="TARGET",
        market_regime="TREND_UP",
        evidence_cutoff=ENTRY_AT + timedelta(minutes=evidence_offset_minutes),
        feature_row_fingerprint="1" * 64,
        model_artifact_fingerprint="2" * 64,
        fusion_opportunity_fingerprint="3" * 64,
        gemini_evidence_fingerprints=["5" * 64, "4" * 64],
        risk_policy_fingerprints=["7" * 64, "6" * 64],
        provenance=["paper-fill:exit", "paper-fill:entry"],
    )


def test_trade_outcome_is_deterministic_normalized_and_non_executable() -> None:
    first = build_trade_outcome(_source())
    second = build_trade_outcome(_source())

    assert first == second
    assert first.symbol == "RELIANCE"
    assert first.net_return == pytest.approx(0.027)
    assert first.gemini_evidence_fingerprints == ["4" * 64, "5" * 64]
    assert first.risk_policy_fingerprints == ["6" * 64, "7" * 64]
    assert first.provenance == ["paper-fill:entry", "paper-fill:exit"]
    assert len(first.record_fingerprint) == 64
    assert first.status == "EVALUATION_EVIDENCE_ONLY"
    assert first.retraining_triggered is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_outcome_rejects_future_evidence_and_invalid_round_trip() -> None:
    with pytest.raises(ValidationError, match="evidence_cutoff"):
        _source(evidence_offset_minutes=1)

    with pytest.raises(ValidationError, match="opposite"):
        _source().model_copy(update={"exit_side": "BUY"}, revalidate_instances="always")


def test_outcome_rejects_invalid_excursion_and_duplicate_fill_lineage() -> None:
    payload = _source().model_dump()
    payload["mae_return"] = 0.01
    with pytest.raises(ValidationError, match="less than or equal to 0"):
        TradeOutcomeInput(**payload)

    payload = _source().model_dump()
    payload["entry_fill_ids"] = [ENTRY_FILL, ENTRY_FILL]
    with pytest.raises(ValidationError, match="fill ids must be unique"):
        TradeOutcomeInput(**payload)


def test_outcome_dataset_is_order_invariant_and_does_not_trigger_training() -> None:
    first = build_trade_outcome(_source())
    payload = _source(exit_minutes=45).model_dump()
    payload["exit_order_id"] = UUID("00000000-0000-0000-0000-000000000103")
    payload["exit_fill_ids"] = [UUID("00000000-0000-0000-0000-000000000303")]
    second = build_trade_outcome(TradeOutcomeInput(**payload))

    dataset = build_outcome_dataset([second, first])
    reversed_dataset = build_outcome_dataset([first, second])

    assert dataset.dataset_fingerprint == reversed_dataset.dataset_fingerprint
    assert dataset.records == [first, second]
    assert dataset.record_count == 2
    assert dataset.status == "BATCH_RESEARCH_INPUT_ONLY"
    assert dataset.retraining_triggered is False
    assert dataset.promotion_allowed is False
    assert dataset.can_authorize_capital is False


def test_outcome_dataset_rejects_duplicate_records() -> None:
    record = build_trade_outcome(_source())

    with pytest.raises(ValueError, match="unique"):
        build_outcome_dataset([record, record])
