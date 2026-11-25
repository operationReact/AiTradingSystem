from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .fingerprints import canonical_fingerprint
from .learning_dataset import LearningDatasetManifest
from .outcome_learning import TradeLearningRecord
from .paper_learning_bridge import PAPER_CLOSED_TRADE_VERSION, PaperClosedTradeExport

PAPER_LEARNING_SOAK_VERSION = "paper-learning-soak.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
CaptureOrigin = Literal["CI_FIXTURE", "RUNTIME_PAPER"]
Assessment = Literal[
    "CI_FIXTURE_ONLY",
    "INSUFFICIENT_RUNTIME_EVIDENCE",
    "RUNTIME_SOAK_REJECTED",
    "RUNTIME_SOAK_ACCEPTED",
]


class PaperLearningSoakConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_runtime_closed_trades: Annotated[int, Field(ge=20, le=100_000)] = 100
    minimum_capture_seconds: Annotated[int, Field(ge=60, le=2_592_000)] = 21_600
    maximum_bridge_failures: Annotated[int, Field(ge=0, le=10_000)] = 0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class PaperLearningSoakReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    soak_version: Literal["paper-learning-soak.v1"] = PAPER_LEARNING_SOAK_VERSION
    config_fingerprint: Hash64
    capture_origin: CaptureOrigin
    capture_started_at: datetime
    capture_completed_at: datetime
    capture_seconds: Annotated[int, Field(gt=0)]
    source_export_contract_version: Literal["paper-closed-trade.v1"] = (
        PAPER_CLOSED_TRADE_VERSION
    )
    source_export_fingerprint: Hash64
    source_export_generated_at: datetime
    observed_closed_trades: Annotated[int, Field(gt=0)]
    learning_records: Annotated[int, Field(gt=0)]
    learning_dataset_fingerprint: Hash64
    bridge_failure_count: Annotated[int, Field(ge=0)]
    assessment: Assessment
    reason_codes: list[Annotated[str, Field(min_length=1, max_length=160)]]
    required_next_gate: Literal[
        "V2_09_CAPTURE_REAL_RUNTIME_PAPER",
        "V2_09_EXPLICIT_RUNTIME_ACCEPTANCE_REVIEW",
    ]
    runtime_acceptance_met: bool
    report_fingerprint: Hash64
    status: Literal["PAPER_LEARNING_SOAK_EVIDENCE_ONLY"] = (
        "PAPER_LEARNING_SOAK_EVIDENCE_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator(
        "capture_started_at",
        "capture_completed_at",
        "source_export_generated_at",
    )
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("PAPER soak timestamps must be timezone-aware")
        return value


def evaluate_paper_learning_soak(
    *,
    export: PaperClosedTradeExport,
    records: list[TradeLearningRecord],
    learning_dataset: LearningDatasetManifest,
    capture_origin: CaptureOrigin,
    capture_started_at: datetime,
    capture_completed_at: datetime,
    bridge_failure_count: int = 0,
    config: PaperLearningSoakConfig | None = None,
) -> PaperLearningSoakReport:
    config = config or PaperLearningSoakConfig()
    _require_aware(capture_started_at, "capture_started_at")
    _require_aware(capture_completed_at, "capture_completed_at")
    if capture_completed_at <= capture_started_at:
        raise ValueError("capture_completed_at must be after capture_started_at")
    if bridge_failure_count < 0:
        raise ValueError("bridge_failure_count cannot be negative")
    if not (capture_started_at <= export.generated_at <= capture_completed_at):
        raise ValueError("source export must be generated inside the capture window")

    captured_trades = [
        trade
        for trade in export.trades
        if capture_started_at <= trade.closed_at <= capture_completed_at
    ]
    captured_records = [
        record
        for record in records
        if capture_started_at <= record.dataset_eligible_at <= capture_completed_at
    ]
    if not captured_trades or not captured_records:
        raise ValueError("PAPER soak requires closed trades and learning records in the window")

    _validate_exact_runtime_lineage(captured_trades, captured_records)
    _validate_learning_dataset(captured_records, learning_dataset)

    capture_seconds = int((capture_completed_at - capture_started_at).total_seconds())
    reasons: list[str] = []
    if capture_origin == "CI_FIXTURE":
        assessment: Assessment = "CI_FIXTURE_ONLY"
        reasons.append("CI_FIXTURE_CANNOT_PROVE_RUNTIME")
    elif bridge_failure_count > config.maximum_bridge_failures:
        assessment = "RUNTIME_SOAK_REJECTED"
        reasons.append("BRIDGE_FAILURES_PRESENT")
    else:
        if len(captured_trades) < config.minimum_runtime_closed_trades:
            reasons.append("INSUFFICIENT_RUNTIME_CLOSED_TRADES")
        if capture_seconds < config.minimum_capture_seconds:
            reasons.append("INSUFFICIENT_RUNTIME_CAPTURE_DURATION")
        assessment = (
            "INSUFFICIENT_RUNTIME_EVIDENCE"
            if reasons
            else "RUNTIME_SOAK_ACCEPTED"
        )
        if not reasons:
            reasons.append("RUNTIME_PAPER_LINEAGE_AND_DATASET_ACCEPTED")

    runtime_acceptance_met = assessment == "RUNTIME_SOAK_ACCEPTED"
    required_next_gate = (
        "V2_09_EXPLICIT_RUNTIME_ACCEPTANCE_REVIEW"
        if runtime_acceptance_met
        else "V2_09_CAPTURE_REAL_RUNTIME_PAPER"
    )
    source_export_fingerprint = canonical_fingerprint(
        export.model_dump(mode="json", by_alias=True)
    )
    payload = {
        "soak_version": PAPER_LEARNING_SOAK_VERSION,
        "config_fingerprint": config.fingerprint,
        "capture_origin": capture_origin,
        "capture_started_at": capture_started_at.isoformat(),
        "capture_completed_at": capture_completed_at.isoformat(),
        "capture_seconds": capture_seconds,
        "source_export_contract_version": PAPER_CLOSED_TRADE_VERSION,
        "source_export_fingerprint": source_export_fingerprint,
        "source_export_generated_at": export.generated_at.isoformat(),
        "observed_closed_trades": len(captured_trades),
        "learning_records": len(captured_records),
        "learning_dataset_fingerprint": learning_dataset.dataset_fingerprint,
        "bridge_failure_count": bridge_failure_count,
        "assessment": assessment,
        "reason_codes": reasons,
        "required_next_gate": required_next_gate,
        "runtime_acceptance_met": runtime_acceptance_met,
        "status": "PAPER_LEARNING_SOAK_EVIDENCE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return PaperLearningSoakReport(
        config_fingerprint=config.fingerprint,
        capture_origin=capture_origin,
        capture_started_at=capture_started_at,
        capture_completed_at=capture_completed_at,
        capture_seconds=capture_seconds,
        source_export_fingerprint=source_export_fingerprint,
        source_export_generated_at=export.generated_at,
        observed_closed_trades=len(captured_trades),
        learning_records=len(captured_records),
        learning_dataset_fingerprint=learning_dataset.dataset_fingerprint,
        bridge_failure_count=bridge_failure_count,
        assessment=assessment,
        reason_codes=reasons,
        required_next_gate=required_next_gate,
        runtime_acceptance_met=runtime_acceptance_met,
        report_fingerprint=canonical_fingerprint(payload),
    )


def _validate_exact_runtime_lineage(trades, records: list[TradeLearningRecord]) -> None:
    trade_by_id = {str(trade.allocation_id): trade for trade in trades}
    record_by_id = {record.trade_id: record for record in records}
    if len(trade_by_id) != len(trades):
        raise ValueError("captured PAPER trades must have unique allocation IDs")
    if len(record_by_id) != len(records):
        raise ValueError("captured learning records must have unique trade IDs")
    if set(trade_by_id) != set(record_by_id):
        raise ValueError("captured PAPER trades and learning records must map one-to-one")

    learning_fingerprints = [record.learning_fingerprint for record in records]
    if len(set(learning_fingerprints)) != len(learning_fingerprints):
        raise ValueError("captured learning fingerprints must be unique")

    for trade_id, trade in trade_by_id.items():
        record = record_by_id[trade_id]
        if record.execution.trading_mode != "PAPER":
            raise ValueError("runtime PAPER soak cannot contain LIVE learning evidence")
        if record.execution.order_id != str(trade.entry_order_id):
            raise ValueError("learning record entry order does not match PAPER allocation")
        if record.execution.position_id != str(trade.lot_id):
            raise ValueError("learning record position does not match PAPER lot")
        if record.execution.closed_at != trade.closed_at:
            raise ValueError("learning record close time does not match PAPER allocation")
        if record.symbol.strip().upper() != trade.symbol.strip().upper():
            raise ValueError("learning record symbol does not match PAPER allocation")
        expected_execution_fingerprint = canonical_fingerprint(
            {
                "contract_version": PAPER_CLOSED_TRADE_VERSION,
                "closed_trade": trade.model_dump(mode="json", by_alias=True),
            }
        )
        if record.execution.execution_fingerprint != expected_execution_fingerprint:
            raise ValueError("learning execution fingerprint does not match PAPER allocation")


def _validate_learning_dataset(
    records: list[TradeLearningRecord],
    manifest: LearningDatasetManifest,
) -> None:
    ordered = sorted(
        records,
        key=lambda item: (
            item.dataset_eligible_at,
            item.trade_id,
            item.learning_fingerprint,
        ),
    )
    expected_fingerprint = canonical_fingerprint(
        [item.model_dump(mode="json") for item in ordered]
    )
    if manifest.row_count != len(ordered):
        raise ValueError("learning dataset row count does not match soak records")
    if manifest.dataset_fingerprint != expected_fingerprint:
        raise ValueError("learning dataset fingerprint does not match soak records")
    expected_cutoff = max(item.dataset_eligible_at for item in ordered)
    if manifest.data_cutoff != expected_cutoff:
        raise ValueError("learning dataset cutoff does not match soak records")


def _require_aware(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{name} must be timezone-aware")
