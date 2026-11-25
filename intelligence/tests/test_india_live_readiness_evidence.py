from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_live_readiness_evidence import (
    CustodyReceipt,
    EvidencePreflightPolicy,
    IndiaEvidenceIntake,
    IndiaEvidencePreflightReport,
    LicenceEvidence,
    LiveIntent,
    PaperShadowSession,
    evaluate_india_evidence_preflight,
)

AT = datetime(2026, 11, 4, 12, tzinfo=UTC)
HASH = "a" * 64
SECOND = "b" * 64
THIRD = "c" * 64
KINDS = (
    "NEWS_CAPTURE", "MARKET_CAPTURE", "INDIA_FEE_SCHEDULE",
    "BROKER_STATIC_IP", "BROKER_ALGO_COMPLIANCE",
    "RISK_KILL_SWITCH", "BROKER_RECONCILIATION",
)


def _license(kind: str, *, rights: list[str] | None = None,
             until: datetime | None = None):
    required = (
        ["ARTICLE_TEXT", "COMMERCIAL_PRODUCT", "DERIVED_FEATURES"]
        if kind == "INDIAN_NEWS"
        else ["COMMERCIAL_PRODUCT", "HISTORICAL_BACKTEST", "INTRADAY_FIVE_MINUTE"]
    )
    return LicenceEvidence(
        data_class=kind,
        provider="Original Synthetic Issuer",
        agreement_reference=f"https://example.org/{kind.lower()}/agreement",
        agreement_sha256=HASH,
        rights=required if rights is None else rights,
        valid_from=AT - timedelta(days=2),
        valid_until=until or AT + timedelta(days=2),
        captured_at=AT - timedelta(days=1),
    )


def _receipts():
    return [
        CustodyReceipt(
            kind=kind,
            document_reference=f"https://example.org/audit/{kind.lower()}",
            document_sha256=SECOND,
            observed_at=AT - timedelta(hours=1),
        )
        for kind in KINDS
    ]


def _session(mode: str, *, origin: str = "RUNTIME_EXPORT",
             source: str = HASH, unresolved: int = 0,
             broker_orders: int = 0, decisions: int = 10):
    return PaperShadowSession(
        mode=mode,
        capture_origin=origin,
        started_at=AT - timedelta(hours=2),
        completed_at=AT - timedelta(minutes=1),
        market_export_fingerprint=source,
        news_export_fingerprint=HASH,
        decision_cohort_fingerprint=THIRD,
        model_registry_fingerprint=SECOND,
        recorded_decisions=decisions,
        resolved_outcomes=decisions,
        simulated_fills=2 if mode == "PAPER" else 0,
        actual_broker_orders=broker_orders,
        unresolved_unknowns=unresolved,
    )


def _intake(*, licence=True, receipts=True, sessions=True, approved=True):
    return IndiaEvidenceIntake(
        assessment_at=AT,
        licences=(
            [_license("INDIAN_NEWS"), _license("INDIAN_EQUITY_MARKET")]
            if licence else []
        ),
        custody_receipts=_receipts() if receipts else [],
        sessions=[_session("PAPER"), _session("SHADOW")] if sessions else [],
        owner_live_intent=LiveIntent(
            requested=approved,
            requested_at=(AT - timedelta(minutes=10)) if approved else None,
        ),
    )


def _policy():
    return EvidencePreflightPolicy(
        minimum_paper_decisions=10,
        minimum_shadow_decisions=10,
        minimum_soak_hours=1,
    )


def _replace(intake, **updates):
    values = intake.model_dump(mode="python")
    values.update(updates)
    return IndiaEvidenceIntake.model_validate(values)


def test_empty_intake_is_incomplete_and_never_promotes_live():
    report = evaluate_india_evidence_preflight(
        IndiaEvidenceIntake(assessment_at=AT)
    )
    assert report.assessment == "EVIDENCE_INCOMPLETE"
    assert "MISSING_INDIAN_NEWS_AGREEMENT" in report.reason_codes
    assert "MISSING_INDIAN_EQUITY_MARKET_AGREEMENT" in report.reason_codes
    assert "MISSING_PAPER_SESSION" in report.reason_codes
    assert "MISSING_SHADOW_SESSION" in report.reason_codes
    assert not report.live_order_execution_enabled
    assert not report.can_authorize_capital


def test_even_fully_staged_approved_intake_is_not_independent_evidence():
    intake = _intake()
    first = evaluate_india_evidence_preflight(intake, policy=_policy())
    second = evaluate_india_evidence_preflight(intake, policy=_policy())
    assert first == second
    assert first.assessment == "EXTERNAL_REVIEW_REQUIRED"
    assert first.rights_documents_staged
    assert first.custody_documents_staged
    assert first.paper_runtime_technical_preflight
    assert first.shadow_runtime_technical_preflight
    assert first.owner_live_review_requested
    assert "NEWS_AND_MARKET_RIGHTS_REQUIRE_EXTERNAL_REVIEW" in first.reason_codes
    assert "BROKER_AND_EXCHANGE_COMPLIANCE_REQUIRE_EXTERNAL_REVIEW" in first.reason_codes
    assert "PAPER_SHADOW_RUNTIME_REQUIRES_INDEPENDENT_ACCEPTANCE" in first.reason_codes
    assert first.licensed_news_independently_approved is False
    assert first.licensed_market_data_independently_approved is False
    assert first.broker_exchange_compliance_independently_approved is False
    assert first.paper_shadow_independently_accepted is False
    assert first.live_order_execution_enabled is False
    assert first.can_authorize_capital is False
    assert IndiaEvidencePreflightReport.model_validate_json(
        first.model_dump_json()
    ) == first


def test_user_live_approval_is_review_intent_not_order_authority():
    approved = evaluate_india_evidence_preflight(_intake(), policy=_policy())
    unapproved = evaluate_india_evidence_preflight(
        _intake(approved=False), policy=_policy()
    )
    assert approved.owner_live_review_requested
    assert not unapproved.owner_live_review_requested
    assert not approved.live_order_execution_enabled
    assert not unapproved.live_order_execution_enabled
    with pytest.raises(ValidationError, match="matching timestamp"):
        LiveIntent(requested=True)


def test_undocumented_missing_rights_and_expired_agreement_fail_closed():
    item = _intake()
    report = evaluate_india_evidence_preflight(
        _replace(item, licences=[_license("INDIAN_NEWS")]), policy=_policy()
    )
    assert "MISSING_INDIAN_EQUITY_MARKET_AGREEMENT" in report.reason_codes
    assert not report.rights_documents_staged
    report = evaluate_india_evidence_preflight(
        _replace(item, licences=[
            _license("INDIAN_NEWS", rights=["COMMERCIAL_PRODUCT"]),
            _license("INDIAN_EQUITY_MARKET"),
        ]), policy=_policy()
    )
    assert "INDIAN_NEWS_PERMITTED_USE_INCOMPLETE" in report.reason_codes
    report = evaluate_india_evidence_preflight(
        _replace(item, licences=[
            _license("INDIAN_NEWS", until=AT - timedelta(minutes=1)),
            _license("INDIAN_EQUITY_MARKET"),
        ]), policy=_policy()
    )
    assert "INDIAN_NEWS_AGREEMENT_INACTIVE" in report.reason_codes


def test_missing_static_ip_or_algo_approval_dossier_is_not_staged():
    intake = _intake()
    report = evaluate_india_evidence_preflight(
        _replace(intake, custody_receipts=[
            receipt for receipt in intake.custody_receipts
            if receipt.kind != "BROKER_STATIC_IP"
        ]), policy=_policy()
    )
    assert "MISSING_BROKER_STATIC_IP_RECEIPT" in report.reason_codes
    assert not report.custody_documents_staged


def test_synthetic_fixtures_never_count_as_runtime_paper_or_shadow():
    intake = _intake()
    sessions = [
        _session("PAPER", origin="CI_SYNTHETIC"),
        _session("SHADOW", origin="CI_SYNTHETIC"),
    ]
    report = evaluate_india_evidence_preflight(
        _replace(intake, sessions=sessions), policy=_policy()
    )
    assert "PAPER_SYNTHETIC_FIXTURE_ONLY" in report.reason_codes
    assert "SHADOW_SYNTHETIC_FIXTURE_ONLY" in report.reason_codes
    assert not report.paper_runtime_technical_preflight
    assert not report.shadow_runtime_technical_preflight
    assert report.assessment == "EVIDENCE_INCOMPLETE"


def test_unresolved_fills_soak_and_cohort_gaps_block_paper_shadow():
    intake = _intake()
    report = evaluate_india_evidence_preflight(
        _replace(intake, sessions=[
            _session("PAPER", unresolved=1),
            _session("SHADOW"),
        ]), policy=_policy()
    )
    assert "PAPER_INTEGRITY_OR_SAFETY_FAILURE" in report.reason_codes
    assert not report.paper_runtime_technical_preflight
    report = evaluate_india_evidence_preflight(
        _replace(intake, sessions=[
            _session("PAPER"), _session("SHADOW", source=SECOND),
        ]), policy=_policy()
    )
    assert "PAPER_SHADOW_COHORT_OR_WINDOW_MISMATCH" in report.reason_codes
    assert not report.paper_runtime_technical_preflight
    assert not report.shadow_runtime_technical_preflight
    report = evaluate_india_evidence_preflight(
        intake, policy=EvidencePreflightPolicy(
            minimum_paper_decisions=11,
            minimum_shadow_decisions=11,
            minimum_soak_hours=3,
        )
    )
    assert "PAPER_INSUFFICIENT_DECISIONS" in report.reason_codes
    assert "SHADOW_INSUFFICIENT_SOAK" in report.reason_codes


def test_no_actual_broker_order_is_permitted_inside_paper_shadow_evidence():
    with pytest.raises(ValidationError, match="zero real broker orders"):
        _session("PAPER", broker_orders=1)
    with pytest.raises(ValidationError, match="zero real broker orders"):
        _session("SHADOW", broker_orders=1)
    values = _session("SHADOW").model_dump()
    values["simulated_fills"] = 1
    with pytest.raises(ValidationError, match="cannot produce simulated order fills"):
        PaperShadowSession.model_validate(values)


def test_source_provenance_must_not_include_credentials_or_weaken_contract():
    with pytest.raises(ValidationError, match="without credentials"):
        LicenceEvidence.model_validate({
            **_license("INDIAN_NEWS").model_dump(mode="json"),
            "agreement_reference": "https://token:secret@example.org/license",
        })
    with pytest.raises(ValidationError):
        LicenceEvidence.model_validate({
            **_license("INDIAN_NEWS").model_dump(mode="json"),
            "externally_verified_rights": True,
        })
    with pytest.raises(ValidationError):
        CustodyReceipt.model_validate({
            **_receipts()[0].model_dump(mode="json"),
            "independently_verified": True,
        })


def test_duplicates_future_evidence_and_naive_dates_rejected():
    intake = _intake()
    with pytest.raises(ValidationError, match="duplicate rights"):
        _replace(intake, licences=[_license("INDIAN_NEWS"), _license("INDIAN_NEWS")])
    with pytest.raises(ValidationError, match="duplicate custody"):
        _replace(intake, custody_receipts=[_receipts()[0], _receipts()[0]])
    with pytest.raises(ValidationError, match="evidence cannot be recorded after"):
        _replace(intake, custody_receipts=[
            CustodyReceipt(
                kind="NEWS_CAPTURE",
                document_reference="https://example.org/future",
                document_sha256=HASH,
                observed_at=AT + timedelta(hours=1),
            ),
        ])
    with pytest.raises(ValidationError, match="timezone-aware"):
        IndiaEvidenceIntake(assessment_at=datetime(2026, 11, 4))


def test_report_metric_claims_and_live_toggle_fail_closed_on_replay():
    original = evaluate_india_evidence_preflight(_intake(), policy=_policy())
    modified = original.model_dump(mode="json")
    modified["live_order_execution_enabled"] = True
    with pytest.raises(ValidationError):
        IndiaEvidencePreflightReport.model_validate(modified)
    modified = original.model_dump(mode="json")
    modified["report_fingerprint"] = "f" * 64
    with pytest.raises(ValidationError, match="fingerprint mismatch"):
        IndiaEvidencePreflightReport.model_validate(modified)
    modified = original.model_dump(mode="json")
    modified["reason_codes"] = ["MADE_UP"]
    modified["report_fingerprint"] = canonical_fingerprint({
        key: value for key, value in modified.items()
        if key != "report_fingerprint"
    })
    # The document is still a non-authoritative preflight, never a green light.
    result = IndiaEvidencePreflightReport.model_validate(modified)
    assert result.live_order_execution_enabled is False
    assert result.can_authorize_capital is False


def test_unknown_or_reconciliation_events_cannot_disappear_from_report():
    intake = _intake()
    values = _session("PAPER").model_dump()
    values["reconciliation_errors"] = 1
    values["stale_market_data_decisions"] = 1
    report = evaluate_india_evidence_preflight(
        _replace(intake, sessions=[
            PaperShadowSession.model_validate(values), _session("SHADOW"),
        ]), policy=_policy()
    )
    assert "PAPER_INTEGRITY_OR_SAFETY_FAILURE" in report.reason_codes
    assert report.paper_runtime_technical_preflight is False
