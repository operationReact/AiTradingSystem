from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest
from pydantic import ValidationError

from aitrading_intelligence.outcome_learning import (
    GeminiSnapshot,
    PredictionSnapshot,
    RiskSnapshot,
)
from aitrading_intelligence.paper_learning_bridge import (
    PaperClosedTradeExport,
    PaperLearningBridgeError,
    PaperLearningEvidence,
    bridge_paper_closed_trades,
)

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)
ALLOCATION_ID = UUID("10000000-0000-0000-0000-000000000001")
LOT_ID = UUID("20000000-0000-0000-0000-000000000001")
ENTRY_ORDER_ID = UUID("30000000-0000-0000-0000-000000000001")
ENTRY_FILL_ID = UUID("40000000-0000-0000-0000-000000000001")
EXIT_ORDER_ID = UUID("50000000-0000-0000-0000-000000000001")
EXIT_FILL_ID = UUID("60000000-0000-0000-0000-000000000001")


def _export_payload(*, realized_pnl: str = "165.00") -> dict:
    return {
        "contractVersion": "paper-closed-trade.v1",
        "generatedAt": (START + timedelta(minutes=21)).isoformat(),
        "trades": [
            {
                "allocationId": str(ALLOCATION_ID),
                "lotId": str(LOT_ID),
                "entryOrderId": str(ENTRY_ORDER_ID),
                "entryFillId": str(ENTRY_FILL_ID),
                "exitOrderId": str(EXIT_ORDER_ID),
                "exitFillId": str(EXIT_FILL_ID),
                "instrumentId": 2885,
                "exchange": "NSE",
                "symbol": "RELIANCE",
                "productType": "INTRADAY",
                "executionOrigin": "AUTOMATED",
                "entrySide": "BUY",
                "quantity": 100,
                "expectedEntryPrice": "100.00",
                "entryPrice": "100.10",
                "exitPrice": "102.00",
                "allocatedEntryFeeAmount": "10.00",
                "allocatedExitFeeAmount": "15.00",
                "allocatedEntrySlippageAmount": "10.00",
                "allocatedExitSlippageAmount": "5.00",
                "allocatedRealizedPnl": realized_pnl,
                "exitReason": "TARGET",
                "openedAt": (START + timedelta(seconds=2)).isoformat(),
                "closedAt": (START + timedelta(minutes=20)).isoformat(),
            }
        ],
    }


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


def _risk() -> RiskSnapshot:
    return RiskSnapshot(
        decided_at=START + timedelta(seconds=1),
        decision_fingerprint="7" * 64,
        policy_version="risk-policy.v2",
        reason_codes=["MAX_LOSS_OK", "EXPOSURE_OK"],
        max_loss_amount=1250.0,
    )


def _gemini() -> GeminiSnapshot:
    return GeminiSnapshot(
        generated_at=START - timedelta(seconds=30),
        evidence_fingerprint="4" * 64,
        model="gemini-structured-evidence.v1",
        request_fingerprint="5" * 64,
        response_fingerprint="6" * 64,
        source_ids=["sebi:announcement:1"],
        direction_score=0.4,
        confidence=0.76,
        materiality=0.68,
    )


def _evidence() -> PaperLearningEvidence:
    return PaperLearningEvidence(
        entryOrderId=ENTRY_ORDER_ID,
        entryFillId=ENTRY_FILL_ID,
        symbol="RELIANCE",
        prediction=_prediction(),
        risk=_risk(),
        gemini=_gemini(),
        maeReturn=-0.006,
        mfeReturn=0.024,
        marketRegime="TREND_UP",
    )


def _export(*, realized_pnl: str = "165.00") -> PaperClosedTradeExport:
    return PaperClosedTradeExport.model_validate(_export_payload(realized_pnl=realized_pnl))


def test_bridge_binds_exact_java_lineage_to_real_evidence_deterministically() -> None:
    first = bridge_paper_closed_trades(_export(), [_evidence()])
    second = bridge_paper_closed_trades(_export(), [_evidence()])

    assert first == second
    assert len(first) == 1
    record = first[0]
    assert record.trade_id == str(ALLOCATION_ID)
    assert record.execution.order_id == str(ENTRY_ORDER_ID)
    assert record.execution.position_id == str(LOT_ID)
    assert record.execution.trading_mode == "PAPER"
    assert record.execution.execution_mode == "AUTOMATED"
    assert record.execution.total_fees == pytest.approx(25.0)
    assert record.outcome.realized_pnl == pytest.approx(165.0)
    assert record.outcome.mae_return == pytest.approx(-0.006)
    assert record.outcome.mfe_return == pytest.approx(0.024)
    assert record.outcome.market_regime == "TREND_UP"
    assert record.status == "EVALUATION_EVIDENCE_ONLY"
    assert record.promotion_allowed is False
    assert record.can_authorize_capital is False


def test_bridge_accepts_real_trade_without_optional_gemini_evidence() -> None:
    evidence = _evidence().model_copy(update={"gemini": None})

    record = bridge_paper_closed_trades(_export(), [evidence])[0]

    assert record.gemini is None
    assert not any(item.startswith("gemini:") for item in record.provenance)


def test_bridge_fails_closed_when_required_evidence_is_missing() -> None:
    with pytest.raises(PaperLearningBridgeError, match="missing required learning evidence"):
        bridge_paper_closed_trades(_export(), [])


def test_bridge_rejects_duplicate_entry_evidence() -> None:
    evidence = _evidence()

    with pytest.raises(PaperLearningBridgeError, match="duplicate learning evidence"):
        bridge_paper_closed_trades(_export(), [evidence, evidence])


def test_bridge_rejects_symbol_mismatch_instead_of_guessing_linkage() -> None:
    evidence = _evidence().model_copy(update={"symbol": "TCS"})

    with pytest.raises(PaperLearningBridgeError, match="symbol does not match"):
        bridge_paper_closed_trades(_export(), [evidence])


def test_bridge_rejects_unreconciled_authoritative_realized_pnl() -> None:
    with pytest.raises(PaperLearningBridgeError, match="realized P&L does not reconcile"):
        bridge_paper_closed_trades(_export(realized_pnl="166.00"), [_evidence()])


def test_export_contract_rejects_unknown_version() -> None:
    payload = _export_payload()
    payload["contractVersion"] = "paper-closed-trade.v2"

    with pytest.raises(ValidationError):
        PaperClosedTradeExport.model_validate(payload)


def test_export_contract_rejects_unknown_execution_origin() -> None:
    payload = _export_payload()
    payload["trades"][0]["executionOrigin"] = "BROKER"

    with pytest.raises(ValidationError):
        PaperClosedTradeExport.model_validate(payload)
