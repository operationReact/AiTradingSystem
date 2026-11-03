from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest
from pydantic import ValidationError
from test_india_independent_holdout import (
    HOLDOUT_DAYS,
    TRAIN_DAYS,
    _export,
    _fee_book,
    _run,
    _target,
)

from aitrading_intelligence.india_fee_tax import (
    build_india_fee_tax_book,
    build_india_fee_tax_schedule,
)
from aitrading_intelligence.india_independent_holdout import IndiaHoldoutConfig
from aitrading_intelligence.india_market_provenance import (
    IndiaHoldoutRegistration,
    IndiaMarketProvenanceReport,
    SourceCustodyDeclaration,
    audit_india_market_holdout,
    register_holdout_design,
)
from aitrading_intelligence.market_source import MarketResearchExport
from aitrading_intelligence.pytorch_target import ForwardReturnTargetSpec

REGISTERED = datetime(2026, 11, 3, 13, tzinfo=UTC)
EVALUATION = REGISTERED + timedelta(hours=1)


def _inputs():
    training = _export(TRAIN_DAYS)
    holdout = _export(HOLDOUT_DAYS)
    book = _fee_book()
    target = _target()
    config = IndiaHoldoutConfig()
    registration = register_holdout_design(
        training, holdout, book, target, config,
        registered_at=REGISTERED,
        candidate_families=["CATBOOST", "LIGHTGBM", "PYTORCH"],
    )
    declarations = [
        SourceCustodyDeclaration(
            export_role=role,
            export_fingerprint=registration.training_export_fingerprint
            if role == "TRAINING" else registration.holdout_export_fingerprint,
            declared_provider="ANGEL_ONE_SMARTAPI",
            raw_capture_fingerprint=character * 64,
            acquisition_reference=f"test-only-capture-{role.lower()}",
            licence_reference="test-only-license-not-legally-verified",
            recorded_at=REGISTERED + timedelta(minutes=5),
        )
        for role, character in (("TRAINING", "a"), ("HOLDOUT", "b"))
    ]
    return {
        "registration": registration,
        "training": training,
        "holdout": holdout,
        "fee_book": book,
        "target": target,
        "config": config,
        "evaluation_started_at": EVALUATION,
        "declarations": declarations,
    }


def test_consistent_market_data_is_still_unverified_research() -> None:
    inputs = _inputs()
    result = audit_india_market_holdout(**inputs)

    assert result.assessment == "CONSISTENT_BUT_UNVERIFIED"
    assert result.reason_codes == []
    assert result.training_candles == 90
    assert result.holdout_candles == 90
    assert result.data_source_independently_verified is False
    assert result.preregistration_independently_timestamped is False
    assert result.market_fee_schedule_independently_verified is False
    assert result.can_authorize_capital is False
    assert result.promotion_allowed is False
    assert result.report_fingerprint == audit_india_market_holdout(**inputs).report_fingerprint


def test_cannot_promote_registration_or_preflight_with_tampered_hash() -> None:
    inputs = _inputs()
    result = audit_india_market_holdout(**inputs)
    with pytest.raises(ValidationError, match="provenance report fingerprint"):
        IndiaMarketProvenanceReport.model_validate({
            **result.model_dump(mode="json"), "report_fingerprint": "f" * 64
        })
    with pytest.raises(ValidationError, match="pre-registration fingerprint"):
        IndiaHoldoutRegistration.model_validate({
            **inputs["registration"].model_dump(mode="json"),
            "registration_fingerprint": "d" * 64,
        })
    with pytest.raises(ValidationError):
        IndiaMarketProvenanceReport.model_validate({
            **result.model_dump(mode="json"), "data_source_independently_verified": True
        })


def test_missing_external_custody_declarations_fail_closed() -> None:
    inputs = _inputs()
    inputs["declarations"] = None

    result = audit_india_market_holdout(**inputs)
    assert result.assessment == "INCONCLUSIVE"
    assert "TWO_SOURCE_CUSTODY_DECLARATIONS_REQUIRED" in result.reason_codes


def test_changed_holdout_rows_fail_registration_hash_check() -> None:
    inputs = _inputs()
    payload = inputs["holdout"].model_dump(mode="json", by_alias=True)
    payload["candles"][2]["volume"] += 100
    inputs["holdout"] = MarketResearchExport.model_validate(payload)

    result = audit_india_market_holdout(**inputs)
    assert "HOLDOUT_EXPORT_CHANGED" in result.reason_codes
    assert "HOLDOUT_CUSTODY_EXPORT_HASH_MISMATCH" in result.reason_codes


def test_data_window_must_have_valid_indian_market_ohlc() -> None:
    inputs = _inputs()
    payload = inputs["training"].model_dump(mode="json", by_alias=True)
    payload["candles"][0]["high"] = payload["candles"][0]["low"] / 2
    inputs["training"] = MarketResearchExport.model_validate(payload)

    result = audit_india_market_holdout(**inputs)
    assert "TRAIN_OHLC_INCONSISTENT" in result.reason_codes
    assert result.assessment == "INCONCLUSIVE"


def test_registered_at_or_custody_later_than_evaluation_cannot_claim_preflight() -> None:
    inputs = _inputs()
    inputs["evaluation_started_at"] = REGISTERED - timedelta(minutes=5)
    result = audit_india_market_holdout(**inputs)

    assert "NOT_REGISTERED_BEFORE_EVALUATION" in result.reason_codes
    assert "HOLDOUT_CUSTODY_LOGGED_AFTER_EVALUATION" in result.reason_codes
    assert "TRAINING_CUSTODY_LOGGED_AFTER_EVALUATION" in result.reason_codes


def test_fee_source_and_target_drift_are_visible() -> None:
    inputs = _inputs()
    schedule = build_india_fee_tax_schedule(
        schedule_id="changed-fees",
        exchange="NSE",
        product="EQUITY_INTRADAY",
        effective_from=date(2026, 1, 1),
        brokerage_rate_bps=2,
        exchange_transaction_charge_bps=1,
        stt_buy_bps=0,
        stt_sell_bps=1,
        sebi_turnover_charge_bps=0.1,
        stamp_duty_buy_bps=0.3,
        gst_rate_percent=18,
        source_reference="unverified-test-only",
    )
    inputs["fee_book"] = build_india_fee_tax_book([schedule])
    inputs["target"] = ForwardReturnTargetSpec(
        horizon_bars=2, minimum_edge_bps=15, round_trip_cost_bps=40
    )
    result = audit_india_market_holdout(**inputs)

    assert "FEE_BOOK_CHANGED" in result.reason_codes
    assert "TARGET_SPEC_CHANGED" in result.reason_codes


def test_missing_fees_for_market_sessions_are_inconclusive() -> None:
    inputs = _inputs()
    future = build_india_fee_tax_schedule(
        schedule_id="not-yet-effective",
        exchange="NSE", product="EQUITY_INTRADAY",
        effective_from=date(2027, 1, 1),
        brokerage_rate_bps=1, exchange_transaction_charge_bps=1,
        stt_buy_bps=0, stt_sell_bps=1,
        sebi_turnover_charge_bps=0.1,
        stamp_duty_buy_bps=0.3,
        gst_rate_percent=18,
        source_reference="unverified-test-only",
    )
    inputs["fee_book"] = build_india_fee_tax_book([future])
    result = audit_india_market_holdout(**inputs)

    assert "FEE_SCHEDULE_MISSING_FOR_MARKET_SESSION" in result.reason_codes


def test_preflight_binds_actual_holdout_model_report() -> None:
    inputs = _inputs()
    inputs["report"] = _run()
    result = audit_india_market_holdout(**inputs)

    assert result.assessment == "CONSISTENT_BUT_UNVERIFIED"
    report = inputs["report"].model_copy(update={"report_fingerprint": "a" * 64})
    inputs["report"] = report
    with pytest.raises(ValidationError, match="independent holdout report fingerprint"):
        audit_india_market_holdout(**inputs)


def test_registration_must_predeclare_unique_sorted_candidates() -> None:
    inputs = _inputs()
    with pytest.raises(ValueError, match="unique and sorted"):
        register_holdout_design(
            inputs["training"], inputs["holdout"], inputs["fee_book"],
            inputs["target"], inputs["config"],
            registered_at=REGISTERED,
            candidate_families=["LIGHTGBM", "CATBOOST", "LIGHTGBM"],
        )


def test_candle_after_export_generation_is_inconclusive() -> None:
    inputs = _inputs()
    payload = inputs["holdout"].model_dump(mode="json", by_alias=True)
    payload["candles"][0]["fetchedAt"] = (
        datetime.fromisoformat(payload["generatedAt"]) + timedelta(hours=1)
    ).isoformat()
    inputs["holdout"] = MarketResearchExport.model_validate(payload)
    report = audit_india_market_holdout(**inputs)

    assert "HOLDOUT_FETCH_AFTER_EXPORT_GENERATED" in report.reason_codes
