from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.outcome_learning import (
    ExecutionSnapshot,
    GeminiSnapshot,
    PredictionSnapshot,
    RiskSnapshot,
    build_trade_learning_record,
)

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)


def _prediction() -> PredictionSnapshot:
    return PredictionSnapshot(
        decision_at=START,
        feature_row_fingerprint="1" * 64,
        opportunity_fingerprint="2" * 64,
        model_name="pytorch-challenger",
        model_version="pytorch-challenger.v1",
        model_artifact_fingerprint="3" * 64,
        feature_schema_version="features.v2",
        probability_up=0.72,
        confidence=0.81,
    )


def _gemini() -> GeminiSnapshot:
    return GeminiSnapshot(
        generated_at=START - timedelta(seconds=30),
        evidence_fingerprint="4" * 64,
        model="gemini-structured-evidence.v1",
        request_fingerprint="5" * 64,
        response_fingerprint="6" * 64,
        source_ids=["sebi:announcement:1", "news:provider:2"],
        direction_score=0.40,
        confidence=0.76,
        materiality=0.68,
    )


def _risk() -> RiskSnapshot:
    return RiskSnapshot(
        decided_at=START + timedelta(seconds=1),
        decision_fingerprint="7" * 64,
        policy_version="risk-policy.v2",
        reason_codes=["MAX_LOSS_OK", "EXPOSURE_OK"],
        max_loss_amount=1250.0,
    )


def _execution(*, exit_price: float = 102.0, side: str = "BUY") -> ExecutionSnapshot:
    return ExecutionSnapshot(
        order_id="paper-order-1",
        position_id="paper-position-1",
        trading_mode="PAPER",
        execution_mode="AUTOMATED",
        side=side,
        quantity=100,
        expected_entry_price=100.0,
        entry_price=100.10,
        exit_price=exit_price,
        opened_at=START + timedelta(seconds=2),
        closed_at=START + timedelta(minutes=20),
        total_fees=25.0,
        execution_fingerprint="8" * 64,
    )


def _build(*, exit_price: float = 102.0):
    return build_trade_learning_record(
        trade_id="paper-trade-1",
        symbol="RELIANCE",
        prediction=_prediction(),
        gemini=_gemini(),
        risk=_risk(),
        execution=_execution(exit_price=exit_price),
        mae_return=-0.006,
        mfe_return=0.024,
        exit_reason="TARGET",
        market_regime="TREND_UP",
    )


def test_learning_record_is_deterministic_and_research_only() -> None:
    first = _build()
    second = _build()

    assert first == second
    assert first.learning_fingerprint == second.learning_fingerprint
    assert first.status == "EVALUATION_EVIDENCE_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False
    assert first.dataset_eligible_at == first.execution.closed_at
    assert first.provenance == [
        "feature:" + ("1" * 64),
        "opportunity:" + ("2" * 64),
        "model:" + ("3" * 64),
        "risk:" + ("7" * 64),
        "execution:" + ("8" * 64),
        "gemini:" + ("4" * 64),
    ]


def test_learning_record_normalizes_pnl_return_slippage_and_holding_time() -> None:
    record = _build()

    assert record.outcome.gross_pnl == pytest.approx(190.0)
    assert record.outcome.realized_pnl == pytest.approx(165.0)
    assert record.outcome.realized_return == pytest.approx(165.0 / 10_010.0)
    assert record.outcome.slippage_bps == pytest.approx(10.0)
    assert record.outcome.holding_seconds == 1198
    assert record.outcome.mae_return == pytest.approx(-0.006)
    assert record.outcome.mfe_return == pytest.approx(0.024)


def test_learning_fingerprint_changes_when_realized_outcome_changes() -> None:
    winner = _build(exit_price=102.0)
    loser = _build(exit_price=99.0)

    assert winner.learning_fingerprint != loser.learning_fingerprint
    assert winner.outcome.realized_pnl > 0.0
    assert loser.outcome.realized_pnl < 0.0


def test_learning_record_rejects_post_decision_gemini_evidence() -> None:
    future_gemini = _gemini().model_copy(
        update={"generated_at": START + timedelta(seconds=1)}
    )

    with pytest.raises(ValueError, match="Gemini evidence generated after decision time"):
        build_trade_learning_record(
            trade_id="paper-trade-1",
            symbol="RELIANCE",
            prediction=_prediction(),
            gemini=future_gemini,
            risk=_risk(),
            execution=_execution(),
            mae_return=-0.006,
            mfe_return=0.024,
            exit_reason="TARGET",
            market_regime="TREND_UP",
        )


def test_learning_record_rejects_risk_decision_after_execution_open() -> None:
    late_risk = _risk().model_copy(
        update={"decided_at": START + timedelta(seconds=3)}
    )

    with pytest.raises(ValueError, match="execution cannot open before the risk decision"):
        build_trade_learning_record(
            trade_id="paper-trade-1",
            symbol="RELIANCE",
            prediction=_prediction(),
            gemini=_gemini(),
            risk=late_risk,
            execution=_execution(),
            mae_return=-0.006,
            mfe_return=0.024,
            exit_reason="TARGET",
            market_regime="TREND_UP",
        )


def test_learning_record_rejects_invalid_excursion_signs() -> None:
    with pytest.raises(ValueError):
        build_trade_learning_record(
            trade_id="paper-trade-1",
            symbol="RELIANCE",
            prediction=_prediction(),
            gemini=_gemini(),
            risk=_risk(),
            execution=_execution(),
            mae_return=0.001,
            mfe_return=0.024,
            exit_reason="TARGET",
            market_regime="TREND_UP",
        )
