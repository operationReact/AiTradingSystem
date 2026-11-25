from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.learning_dataset import LearningDatasetManifest
from aitrading_intelligence.outcome_learning import PredictionSnapshot, RiskSnapshot
from aitrading_intelligence.paper_learning_bridge import (
    PaperClosedTradeExport,
    PaperLearningEvidence,
    bridge_paper_closed_trades,
)
from aitrading_intelligence.paper_learning_soak import (
    PaperLearningSoakConfig,
    evaluate_paper_learning_soak,
)

START = datetime(2026, 11, 2, 3, 45, tzinfo=UTC)
CAPTURE_END = START + timedelta(hours=6)
CODE_SHA = "1" * 40


def _uuid(namespace: int, index: int) -> UUID:
    return UUID(int=(namespace << 96) + index + 1)


def _export_and_evidence(count: int = 100):
    trades = []
    evidence = []
    for index in range(count):
        opened_at = START + timedelta(minutes=2 * index + 1)
        closed_at = opened_at + timedelta(minutes=1)
        allocation_id = _uuid(1, index)
        lot_id = _uuid(2, index)
        entry_order_id = _uuid(3, index)
        entry_fill_id = _uuid(4, index)
        exit_order_id = _uuid(5, index)
        exit_fill_id = _uuid(6, index)
        trades.append(
            {
                "allocationId": str(allocation_id),
                "lotId": str(lot_id),
                "entryOrderId": str(entry_order_id),
                "entryFillId": str(entry_fill_id),
                "exitOrderId": str(exit_order_id),
                "exitFillId": str(exit_fill_id),
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
                "allocatedRealizedPnl": "165.00",
                "exitReason": "TARGET",
                "openedAt": opened_at.isoformat(),
                "closedAt": closed_at.isoformat(),
            }
        )
        evidence.append(
            PaperLearningEvidence(
                entryOrderId=entry_order_id,
                entryFillId=entry_fill_id,
                symbol="RELIANCE",
                prediction=PredictionSnapshot(
                    decision_at=opened_at - timedelta(seconds=2),
                    feature_row_fingerprint=f"{1000 + index:064x}",
                    opportunity_fingerprint=f"{2000 + index:064x}",
                    model_name="pytorch-challenger",
                    model_version="pytorch-challenger.v1",
                    model_artifact_fingerprint="a" * 64,
                    feature_schema_version="features.v2",
                    probability_up=0.72,
                    confidence=0.81,
                ),
                risk=RiskSnapshot(
                    decided_at=opened_at - timedelta(seconds=1),
                    decision_fingerprint=f"{3000 + index:064x}",
                    policy_version="risk-policy.v2",
                    reason_codes=["MAX_LOSS_OK", "EXPOSURE_OK"],
                    max_loss_amount=1250.0,
                ),
                maeReturn=-0.006,
                mfeReturn=0.024,
                marketRegime="TREND_UP",
            )
        )
    export = PaperClosedTradeExport.model_validate(
        {
            "contractVersion": "paper-closed-trade.v1",
            "generatedAt": (CAPTURE_END - timedelta(minutes=1)).isoformat(),
            "trades": trades,
        }
    )
    return export, evidence


def _dataset(records) -> LearningDatasetManifest:
    ordered = sorted(
        records,
        key=lambda item: (
            item.dataset_eligible_at,
            item.trade_id,
            item.learning_fingerprint,
        ),
    )
    return LearningDatasetManifest(
        dataset_version="paper-soak-2026-11-02",
        created_at=CAPTURE_END,
        data_cutoff=max(item.dataset_eligible_at for item in ordered),
        code_sha=CODE_SHA,
        row_count=len(ordered),
        dataset_fingerprint=canonical_fingerprint(
            [item.model_dump(mode="json") for item in ordered]
        ),
        source_versions={
            "paper_closed_trade": "paper-closed-trade.v1",
            "learning_record": "trade-learning-record.v1",
        },
    )


def _materialize(count: int = 100):
    export, evidence = _export_and_evidence(count)
    records = bridge_paper_closed_trades(export, evidence)
    return export, records, _dataset(records)


def test_ci_fixture_can_validate_contract_but_never_runtime_acceptance() -> None:
    export, records, dataset = _materialize()

    report = evaluate_paper_learning_soak(
        export=export,
        records=records,
        learning_dataset=dataset,
        capture_origin="CI_FIXTURE",
        capture_started_at=START,
        capture_completed_at=CAPTURE_END,
    )

    assert report.assessment == "CI_FIXTURE_ONLY"
    assert report.runtime_acceptance_met is False
    assert report.required_next_gate == "V2_09_CAPTURE_REAL_RUNTIME_PAPER"
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_runtime_paper_accepts_only_after_duration_and_trade_thresholds() -> None:
    export, records, dataset = _materialize()

    first = evaluate_paper_learning_soak(
        export=export,
        records=records,
        learning_dataset=dataset,
        capture_origin="RUNTIME_PAPER",
        capture_started_at=START,
        capture_completed_at=CAPTURE_END,
    )
    second = evaluate_paper_learning_soak(
        export=export,
        records=records,
        learning_dataset=dataset,
        capture_origin="RUNTIME_PAPER",
        capture_started_at=START,
        capture_completed_at=CAPTURE_END,
    )

    assert first == second
    assert first.assessment == "RUNTIME_SOAK_ACCEPTED"
    assert first.runtime_acceptance_met is True
    assert first.observed_closed_trades == 100
    assert first.learning_records == 100
    assert first.required_next_gate == "V2_09_EXPLICIT_RUNTIME_ACCEPTANCE_REVIEW"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_runtime_paper_remains_insufficient_below_trade_threshold() -> None:
    export, records, dataset = _materialize(20)

    report = evaluate_paper_learning_soak(
        export=export,
        records=records,
        learning_dataset=dataset,
        capture_origin="RUNTIME_PAPER",
        capture_started_at=START,
        capture_completed_at=CAPTURE_END,
    )

    assert report.assessment == "INSUFFICIENT_RUNTIME_EVIDENCE"
    assert report.runtime_acceptance_met is False
    assert "INSUFFICIENT_RUNTIME_CLOSED_TRADES" in report.reason_codes


def test_runtime_paper_remains_insufficient_below_duration_threshold() -> None:
    export, records, dataset = _materialize()
    config = PaperLearningSoakConfig(minimum_capture_seconds=21_600)

    report = evaluate_paper_learning_soak(
        export=export,
        records=records,
        learning_dataset=dataset,
        capture_origin="RUNTIME_PAPER",
        capture_started_at=START,
        capture_completed_at=START + timedelta(hours=5, minutes=59),
        config=config,
    )

    assert report.assessment == "INSUFFICIENT_RUNTIME_EVIDENCE"
    assert "INSUFFICIENT_RUNTIME_CAPTURE_DURATION" in report.reason_codes


def test_bridge_failures_block_runtime_acceptance() -> None:
    export, records, dataset = _materialize()

    report = evaluate_paper_learning_soak(
        export=export,
        records=records,
        learning_dataset=dataset,
        capture_origin="RUNTIME_PAPER",
        capture_started_at=START,
        capture_completed_at=CAPTURE_END,
        bridge_failure_count=1,
    )

    assert report.assessment == "RUNTIME_SOAK_REJECTED"
    assert report.runtime_acceptance_met is False
    assert report.reason_codes == ["BRIDGE_FAILURES_PRESENT"]


def test_live_learning_record_is_rejected_from_paper_soak() -> None:
    export, records, dataset = _materialize()
    records[0] = records[0].model_copy(
        update={
            "execution": records[0].execution.model_copy(update={"trading_mode": "LIVE"})
        }
    )

    with pytest.raises(ValueError, match="cannot contain LIVE"):
        evaluate_paper_learning_soak(
            export=export,
            records=records,
            learning_dataset=dataset,
            capture_origin="RUNTIME_PAPER",
            capture_started_at=START,
            capture_completed_at=CAPTURE_END,
        )


def test_missing_learning_record_fails_exact_one_to_one_lineage() -> None:
    export, records, dataset = _materialize()

    with pytest.raises(ValueError, match="map one-to-one"):
        evaluate_paper_learning_soak(
            export=export,
            records=records[:-1],
            learning_dataset=dataset,
            capture_origin="RUNTIME_PAPER",
            capture_started_at=START,
            capture_completed_at=CAPTURE_END,
        )


def test_learning_dataset_fingerprint_must_match_captured_records() -> None:
    export, records, dataset = _materialize()
    mismatched = dataset.model_copy(update={"dataset_fingerprint": "f" * 64})

    with pytest.raises(ValueError, match="fingerprint does not match"):
        evaluate_paper_learning_soak(
            export=export,
            records=records,
            learning_dataset=mismatched,
            capture_origin="RUNTIME_PAPER",
            capture_started_at=START,
            capture_completed_at=CAPTURE_END,
        )
