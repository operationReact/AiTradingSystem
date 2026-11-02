from datetime import UTC, datetime, timedelta

import pyarrow.parquet as pq
import pytest

from aitrading_intelligence.learning_dataset import export_learning_dataset
from aitrading_intelligence.outcome_learning import (
    ExecutionSnapshot,
    PredictionSnapshot,
    RiskSnapshot,
    build_trade_learning_record,
)

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)


def _record(index: int, exit_price: float):
    decision_at = START + timedelta(minutes=index * 30)
    prediction = PredictionSnapshot(
        decision_at=decision_at,
        feature_row_fingerprint=f"{100 + index:064x}",
        opportunity_fingerprint=f"{200 + index:064x}",
        model_name="pytorch-challenger",
        model_version="pytorch-challenger.v1",
        model_artifact_fingerprint=f"{300 + index:064x}",
        feature_schema_version="features.v2",
        probability_up=0.70,
        confidence=0.80,
    )
    risk = RiskSnapshot(
        decided_at=decision_at + timedelta(seconds=1),
        decision_fingerprint=f"{400 + index:064x}",
        policy_version="risk-policy.v2",
        reason_codes=["EXPOSURE_OK"],
        max_loss_amount=1000.0,
    )
    execution = ExecutionSnapshot(
        order_id=f"order-{index}",
        position_id=f"position-{index}",
        trading_mode="PAPER",
        execution_mode="AUTOMATED",
        side="BUY",
        quantity=10,
        expected_entry_price=100.0,
        entry_price=100.0,
        exit_price=exit_price,
        opened_at=decision_at + timedelta(seconds=2),
        closed_at=decision_at + timedelta(minutes=20),
        total_fees=5.0,
        execution_fingerprint=f"{500 + index:064x}",
    )
    return build_trade_learning_record(
        trade_id=f"trade-{index}",
        symbol="RELIANCE",
        prediction=prediction,
        risk=risk,
        execution=execution,
        mae_return=-0.01,
        mfe_return=0.02,
        exit_reason="TARGET" if exit_price > 100.0 else "STOP_LOSS",
        market_regime="TREND_UP",
    )


def test_learning_dataset_export_is_reproducible_and_versioned(tmp_path) -> None:
    records = [_record(0, 102.0), _record(1, 99.0)]

    first = export_learning_dataset(
        records,
        output_dir=tmp_path / "first",
        dataset_version="learning-2026-11-02",
        code_sha="abcdef1234567",
        source_versions={"risk": "risk-policy.v2"},
    )
    second = export_learning_dataset(
        list(reversed(records)),
        output_dir=tmp_path / "second",
        dataset_version="learning-2026-11-02",
        code_sha="abcdef1234567",
        source_versions={"risk": "risk-policy.v2"},
    )

    assert first.dataset_fingerprint == second.dataset_fingerprint
    assert first.row_count == 2
    assert first.data_cutoff == records[1].dataset_eligible_at
    assert first.status == "RESEARCH_DATASET_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False

    table = pq.read_table(tmp_path / "first" / "trade_learning.parquet")
    assert table.num_rows == 2
    assert table.schema.metadata[b"learning_dataset_schema_version"] == b"trade-learning-dataset.v1"
    assert set(table.column_names) >= {
        "trade_id",
        "learning_fingerprint",
        "realized_pnl",
        "mae_return",
        "mfe_return",
        "slippage_bps",
        "market_regime",
    }


def test_learning_dataset_rejects_duplicate_trade_ids(tmp_path) -> None:
    first = _record(0, 102.0)
    duplicate = _record(1, 101.0).model_copy(update={"trade_id": first.trade_id})

    with pytest.raises(ValueError, match="unique trade_id"):
        export_learning_dataset(
            [first, duplicate],
            output_dir=tmp_path,
            dataset_version="learning-test",
            code_sha="abcdef1234567",
        )


def test_learning_dataset_rejects_empty_input(tmp_path) -> None:
    with pytest.raises(ValueError, match="empty learning dataset"):
        export_learning_dataset(
            [],
            output_dir=tmp_path,
            dataset_version="learning-test",
            code_sha="abcdef1234567",
        )
