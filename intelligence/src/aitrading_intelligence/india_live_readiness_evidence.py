from __future__ import annotations

import argparse
import os
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

VERSION = "india-licensed-evidence-paper-shadow-preflight.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
DataClass = Literal["INDIAN_NEWS", "INDIAN_EQUITY_MARKET"]
UseRight = Literal[
    "COMMERCIAL_PRODUCT", "ARTICLE_TEXT", "DERIVED_FEATURES",
    "INTRADAY_FIVE_MINUTE", "HISTORICAL_BACKTEST",
]
ReceiptKind = Literal[
    "NEWS_CAPTURE", "MARKET_CAPTURE", "INDIA_FEE_SCHEDULE",
    "BROKER_STATIC_IP", "BROKER_ALGO_COMPLIANCE", "RISK_KILL_SWITCH",
    "BROKER_RECONCILIATION",
]
Mode = Literal["PAPER", "SHADOW"]
Origin = Literal["CI_SYNTHETIC", "RUNTIME_EXPORT"]
REQUIRED_RIGHTS: dict[DataClass, set[UseRight]] = {
    "INDIAN_NEWS": {"COMMERCIAL_PRODUCT", "ARTICLE_TEXT", "DERIVED_FEATURES"},
    "INDIAN_EQUITY_MARKET": {
        "COMMERCIAL_PRODUCT", "INTRADAY_FIVE_MINUTE", "HISTORICAL_BACKTEST",
    },
}
REQUIRED_RECEIPTS: tuple[ReceiptKind, ...] = (
    "NEWS_CAPTURE", "MARKET_CAPTURE", "INDIA_FEE_SCHEDULE",
    "BROKER_STATIC_IP", "BROKER_ALGO_COMPLIANCE",
    "RISK_KILL_SWITCH", "BROKER_RECONCILIATION",
)


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("evidence timestamps must be timezone-aware")
    return value


def _https(url: str) -> str:
    parts = urlsplit(url)
    if (
        parts.scheme.lower() != "https" or not parts.hostname
        or parts.username is not None or parts.password is not None
        or parts.fragment
    ):
        raise ValueError("document references must be absolute HTTPS without credentials")
    return url


class LicenceEvidence(BaseModel):
    """Operator-supplied contract metadata; NOT independent permission verification."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    data_class: DataClass
    provider: Annotated[str, Field(min_length=2, max_length=100)]
    agreement_reference: Annotated[str, Field(min_length=12, max_length=1024)]
    agreement_sha256: Hash64
    rights: list[UseRight] = Field(min_length=1, max_length=5)
    valid_from: datetime
    valid_until: datetime
    captured_at: datetime
    externally_verified_rights: Literal[False] = False

    @model_validator(mode="after")
    def validate_document(self) -> LicenceEvidence:
        _https(self.agreement_reference)
        if self.rights != sorted(set(self.rights)):
            raise ValueError("contract use rights must be sorted and unique")
        if not (
            _aware(self.valid_from) < _aware(self.valid_until)
            and _aware(self.captured_at) >= self.valid_from
        ):
            raise ValueError("contract validity and capture chronology are invalid")
        return self


class CustodyReceipt(BaseModel):
    """An immutable digest/reference is not a signature or audited source custody."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    kind: ReceiptKind
    document_reference: Annotated[str, Field(min_length=12, max_length=1024)]
    document_sha256: Hash64
    observed_at: datetime
    independently_verified: Literal[False] = False

    @field_validator("document_reference")
    @classmethod
    def require_https(cls, value: str) -> str:
        return _https(value)

    @field_validator("observed_at")
    @classmethod
    def require_timestamp(cls, value: datetime) -> datetime:
        return _aware(value)


class PaperShadowSession(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    mode: Mode
    capture_origin: Origin
    started_at: datetime
    completed_at: datetime
    market_export_fingerprint: Hash64
    news_export_fingerprint: Hash64
    decision_cohort_fingerprint: Hash64
    model_registry_fingerprint: Hash64
    recorded_decisions: Annotated[int, Field(ge=0, le=1_000_000)]
    resolved_outcomes: Annotated[int, Field(ge=0, le=1_000_000)]
    simulated_fills: Annotated[int, Field(ge=0, le=1_000_000)]
    actual_broker_orders: Annotated[int, Field(ge=0, le=1_000_000)] = 0
    unresolved_unknowns: Annotated[int, Field(ge=0, le=1_000_000)] = 0
    reconciliation_errors: Annotated[int, Field(ge=0, le=1_000_000)] = 0
    risk_policy_breaches: Annotated[int, Field(ge=0, le=1_000_000)] = 0
    stale_market_data_decisions: Annotated[int, Field(ge=0, le=1_000_000)] = 0
    runtime_independently_audited: Literal[False] = False

    @model_validator(mode="after")
    def validate_session(self) -> PaperShadowSession:
        if _aware(self.started_at) >= _aware(self.completed_at):
            raise ValueError("session must have a positive observation window")
        if self.resolved_outcomes > self.recorded_decisions:
            raise ValueError("resolved outcomes cannot exceed decisions")
        if self.mode == "SHADOW" and self.simulated_fills:
            raise ValueError("shadow mode cannot produce simulated order fills")
        # This evidence-only pathway never authorizes or even records a live broker order.
        if self.actual_broker_orders:
            raise ValueError("PAPER/SHADOW evidence must contain zero real broker orders")
        return self


class LiveIntent(BaseModel):
    """Owner request to pursue review, NEVER an authorization token for an order."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    requested: bool = False
    requested_at: datetime | None = None
    scope: Literal["LIVE_READINESS_REVIEW_ONLY"] = "LIVE_READINESS_REVIEW_ONLY"

    @model_validator(mode="after")
    def validate_intent(self) -> LiveIntent:
        if self.requested != (self.requested_at is not None):
            raise ValueError("owner readiness approval must have a matching timestamp")
        if self.requested_at is not None:
            _aware(self.requested_at)
        return self


class EvidencePreflightPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    minimum_paper_decisions: Annotated[int, Field(ge=1, le=1_000_000)] = 100
    minimum_shadow_decisions: Annotated[int, Field(ge=1, le=1_000_000)] = 100
    minimum_soak_hours: Annotated[int, Field(ge=1, le=720)] = 168


class IndiaEvidenceIntake(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    assessment_at: datetime
    licences: list[LicenceEvidence] = Field(default_factory=list, max_length=20)
    custody_receipts: list[CustodyReceipt] = Field(default_factory=list, max_length=30)
    sessions: list[PaperShadowSession] = Field(default_factory=list, max_length=2)
    owner_live_intent: LiveIntent = Field(default_factory=LiveIntent)

    @model_validator(mode="after")
    def validate_intake(self) -> IndiaEvidenceIntake:
        _aware(self.assessment_at)
        if len({item.data_class for item in self.licences}) != len(self.licences):
            raise ValueError("duplicate rights documents require explicit reconciliation")
        if len({item.kind for item in self.custody_receipts}) != len(self.custody_receipts):
            raise ValueError("duplicate custody receipt kinds require separate review")
        if len({session.mode for session in self.sessions}) != len(self.sessions):
            raise ValueError("PAPER/SHADOW sessions must not duplicate modes")
        if any(
            item.captured_at > self.assessment_at for item in self.licences
        ) or any(
            item.observed_at > self.assessment_at for item in self.custody_receipts
        ) or any(
            item.completed_at > self.assessment_at for item in self.sessions
        ):
            raise ValueError("evidence cannot be recorded after assessment time")
        if (
            self.owner_live_intent.requested_at is not None
            and self.owner_live_intent.requested_at > self.assessment_at
        ):
            raise ValueError("owner approval cannot come from the future")
        return self


class IndiaEvidencePreflightReport(BaseModel):
    """Tamper-evident intake result; permanent fail-closed production boundary."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    contract_version: Literal["india-licensed-evidence-paper-shadow-preflight.v1"] = VERSION
    intake_fingerprint: Hash64
    policy_fingerprint: Hash64
    rights_documents_staged: bool
    custody_documents_staged: bool
    paper_runtime_technical_preflight: bool
    shadow_runtime_technical_preflight: bool
    owner_live_review_requested: bool
    assessment: Literal["EXTERNAL_REVIEW_REQUIRED", "EVIDENCE_INCOMPLETE"]
    reason_codes: list[str] = Field(min_length=1)
    licensed_news_independently_approved: Literal[False] = False
    licensed_market_data_independently_approved: Literal[False] = False
    broker_exchange_compliance_independently_approved: Literal[False] = False
    paper_shadow_independently_accepted: Literal[False] = False
    live_order_execution_enabled: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def check_report(self) -> IndiaEvidencePreflightReport:
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("preflight reasons must be sorted and unique")
        if self.assessment == "EXTERNAL_REVIEW_REQUIRED" and not (
            self.rights_documents_staged and self.custody_documents_staged
            and self.paper_runtime_technical_preflight
            and self.shadow_runtime_technical_preflight
        ):
            raise ValueError("external review stage requires complete technical intake")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("evidence preflight report fingerprint mismatch")
        return self


def evaluate_india_evidence_preflight(
    intake: IndiaEvidenceIntake,
    *,
    policy: EvidencePreflightPolicy | None = None,
) -> IndiaEvidencePreflightReport:
    """Stage documentary evidence, never infer real permissions or activate LIVE."""
    submitted = IndiaEvidenceIntake.model_validate(intake.model_dump(mode="python"))
    rule = policy or EvidencePreflightPolicy()
    reasons: set[str] = set()
    by_class = {licence.data_class: licence for licence in submitted.licences}
    for kind, expected in REQUIRED_RIGHTS.items():
        item = by_class.get(kind)
        if item is None:
            reasons.add(f"MISSING_{kind}_AGREEMENT")
            continue
        if not item.valid_from <= submitted.assessment_at < item.valid_until:
            reasons.add(f"{kind}_AGREEMENT_INACTIVE")
        if expected.difference(item.rights):
            reasons.add(f"{kind}_PERMITTED_USE_INCOMPLETE")
    staged_rights = not any(
        reason.startswith(("MISSING_INDIAN_", "INDIAN_")) for reason in reasons
    )
    receipt_kinds = {receipt.kind for receipt in submitted.custody_receipts}
    for kind in REQUIRED_RECEIPTS:
        if kind not in receipt_kinds:
            reasons.add(f"MISSING_{kind}_RECEIPT")
    staged_custody = all(kind in receipt_kinds for kind in REQUIRED_RECEIPTS)
    sessions = {session.mode: session for session in submitted.sessions}
    technical_ok: dict[Mode, bool] = {"PAPER": False, "SHADOW": False}
    for mode, minimum in (
        ("PAPER", rule.minimum_paper_decisions),
        ("SHADOW", rule.minimum_shadow_decisions),
    ):
        session = sessions.get(mode)
        if session is None:
            reasons.add(f"MISSING_{mode}_SESSION")
            continue
        if session.capture_origin != "RUNTIME_EXPORT":
            reasons.add(f"{mode}_SYNTHETIC_FIXTURE_ONLY")
        if session.recorded_decisions < minimum:
            reasons.add(f"{mode}_INSUFFICIENT_DECISIONS")
        if session.resolved_outcomes != session.recorded_decisions:
            reasons.add(f"{mode}_UNRESOLVED_OUTCOMES")
        if (session.completed_at - session.started_at).total_seconds() < (
            rule.minimum_soak_hours * 3600
        ):
            reasons.add(f"{mode}_INSUFFICIENT_SOAK")
        if (session.unresolved_unknowns or session.reconciliation_errors
                or session.risk_policy_breaches or session.stale_market_data_decisions):
            reasons.add(f"{mode}_INTEGRITY_OR_SAFETY_FAILURE")
        if mode == "PAPER" and session.simulated_fills == 0:
            reasons.add("PAPER_NO_SIMULATED_FILLS")
        technical_ok[mode] = not any(
            reason.startswith(mode + "_") for reason in reasons
        )
    if len(sessions) == 2:
        paper, shadow = sessions["PAPER"], sessions["SHADOW"]
        if (
            paper.market_export_fingerprint != shadow.market_export_fingerprint
            or paper.news_export_fingerprint != shadow.news_export_fingerprint
            or paper.decision_cohort_fingerprint != shadow.decision_cohort_fingerprint
            or paper.started_at != shadow.started_at
            or paper.completed_at != shadow.completed_at
            or paper.recorded_decisions != shadow.recorded_decisions
        ):
            reasons.add("PAPER_SHADOW_COHORT_OR_WINDOW_MISMATCH")
            technical_ok = {"PAPER": False, "SHADOW": False}
    # Even a perfectly filled self-declared dossier CANNOT become a regulatory,
    # licence, broker or trading authority. Independent acceptance is outside CI.
    reasons.update((
        "NEWS_AND_MARKET_RIGHTS_REQUIRE_EXTERNAL_REVIEW",
        "BROKER_AND_EXCHANGE_COMPLIANCE_REQUIRE_EXTERNAL_REVIEW",
        "PAPER_SHADOW_RUNTIME_REQUIRES_INDEPENDENT_ACCEPTANCE",
        "LIVE_EXECUTION_REMAINS_DISABLED",
    ))
    staged = staged_rights and staged_custody and all(technical_ok.values())
    payload = {
        "contract_version": VERSION,
        "intake_fingerprint": canonical_fingerprint(submitted.model_dump(mode="json")),
        "policy_fingerprint": canonical_fingerprint(rule.model_dump(mode="json")),
        "rights_documents_staged": staged_rights,
        "custody_documents_staged": staged_custody,
        "paper_runtime_technical_preflight": technical_ok["PAPER"],
        "shadow_runtime_technical_preflight": technical_ok["SHADOW"],
        "owner_live_review_requested": submitted.owner_live_intent.requested,
        "assessment": "EXTERNAL_REVIEW_REQUIRED" if staged else "EVIDENCE_INCOMPLETE",
        "reason_codes": sorted(reasons),
        "licensed_news_independently_approved": False,
        "licensed_market_data_independently_approved": False,
        "broker_exchange_compliance_independently_approved": False,
        "paper_shadow_independently_accepted": False,
        "live_order_execution_enabled": False,
        "can_authorize_capital": False,
    }
    return IndiaEvidencePreflightReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Offline India rights/PAPER/SHADOW intake; NEVER authorizes live orders"
    )
    parser.add_argument("intake_json", type=Path)
    parser.add_argument("output_json", type=Path)
    args = parser.parse_args()
    intake = IndiaEvidenceIntake.model_validate_json(
        args.intake_json.read_text(encoding="utf-8")
    )
    report = evaluate_india_evidence_preflight(intake)
    descriptor = os.open(args.output_json, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as target:
        target.write(report.model_dump_json(indent=2) + "\n")
    print(report.report_fingerprint)


if __name__ == "__main__":
    main()
