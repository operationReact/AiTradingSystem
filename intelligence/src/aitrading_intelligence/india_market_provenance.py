from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime, time, timedelta
from pathlib import Path
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_fee_tax import IndiaFeeTaxBookV1, resolve_india_fee_tax_schedule
from .india_independent_holdout import IndiaHoldoutConfig, IndiaHoldoutReport
from .market_source import MarketResearchExport, load_market_export
from .pytorch_target import ForwardReturnTargetSpec

VERSION = "india-market-provenance-preflight.v1"
REGISTRATION_VERSION = "india-holdout-pre-registration.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
IST = ZoneInfo("Asia/Kolkata")


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("audit timestamps must have a timezone offset")
    return value


def _export_hash(export: MarketResearchExport) -> str:
    return canonical_fingerprint(export.model_dump(mode="json"))


class IndiaHoldoutRegistration(BaseModel):
    """Content-addressed research design. Local timestamps are NOT trusted attestations."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-holdout-pre-registration.v1"] = REGISTRATION_VERSION
    registered_at: datetime
    training_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    india_fee_book_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    holdout_config_fingerprint: Hash64
    candidate_families: list[Literal["CATBOOST", "LIGHTGBM", "PYTORCH", "XGBOOST"]]
    registration_fingerprint: Hash64
    externally_timestamped: Literal[False] = False
    independently_audited: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("registered_at")
    @classmethod
    def timestamp_is_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def check_registration(self) -> IndiaHoldoutRegistration:
        if not self.candidate_families or self.candidate_families != sorted(
            set(self.candidate_families)
        ):
            raise ValueError("candidate family registration must be unique and sorted")
        if self.registration_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"registration_fingerprint"})
        ):
            raise ValueError("holdout pre-registration fingerprint mismatch")
        return self


class SourceCustodyDeclaration(BaseModel):
    """A supplied assertion, never independent proof of exchange/broker provenance."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    export_role: Literal["TRAINING", "HOLDOUT"]
    export_fingerprint: Hash64
    declared_provider: Annotated[str, Field(min_length=1, max_length=80)]
    raw_capture_fingerprint: Hash64
    acquisition_reference: Annotated[str, Field(min_length=3, max_length=256)]
    licence_reference: Annotated[str, Field(min_length=3, max_length=256)]
    recorded_at: datetime

    @field_validator("recorded_at")
    @classmethod
    def timestamp_is_aware(cls, value: datetime) -> datetime:
        return _aware(value)


class IndiaMarketProvenanceReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-market-provenance-preflight.v1"] = VERSION
    registration_fingerprint: Hash64
    training_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    fee_book_fingerprint: Hash64
    assessment: Literal["CONSISTENT_BUT_UNVERIFIED", "INCONCLUSIVE"]
    reason_codes: list[str]
    training_candles: Annotated[int, Field(ge=0)]
    holdout_candles: Annotated[int, Field(ge=0)]
    data_source_independently_verified: Literal[False] = False
    preregistration_independently_timestamped: Literal[False] = False
    market_fee_schedule_independently_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def check_report(self) -> IndiaMarketProvenanceReport:
        if self.assessment == "CONSISTENT_BUT_UNVERIFIED" and self.reason_codes:
            raise ValueError("consistent preflight must not contain failed checks")
        if self.assessment == "INCONCLUSIVE" and not self.reason_codes:
            raise ValueError("inconclusive preflight requires reason codes")
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("reason codes must be canonical")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("provenance report fingerprint mismatch")
        return self


def register_holdout_design(
    training: MarketResearchExport,
    holdout: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    config: IndiaHoldoutConfig,
    *,
    registered_at: datetime,
    candidate_families: list[Literal["CATBOOST", "LIGHTGBM", "PYTORCH", "XGBOOST"]],
) -> IndiaHoldoutRegistration:
    """Freeze data, fees, labels and predeclared candidate families before evaluation."""
    _aware(registered_at)
    if registered_at < max(training.generated_at, holdout.generated_at):
        raise ValueError("registration cannot predate source export generation")
    families = sorted(set(candidate_families))
    if not candidate_families or families != candidate_families:
        raise ValueError("registered model family list must be unique and sorted")
    payload = {
        "contract_version": REGISTRATION_VERSION,
        "registered_at": TypeAdapter(datetime).dump_python(registered_at, mode="json"),
        "training_export_fingerprint": _export_hash(training),
        "holdout_export_fingerprint": _export_hash(holdout),
        "india_fee_book_fingerprint": fee_book.book_fingerprint,
        "target_spec_fingerprint": target.fingerprint,
        "holdout_config_fingerprint": config.fingerprint,
        "candidate_families": families,
        "externally_timestamped": False,
        "independently_audited": False,
        "can_authorize_capital": False,
    }
    return IndiaHoldoutRegistration(
        **payload, registration_fingerprint=canonical_fingerprint(payload)
    )


def _market_candle_checks(export: MarketResearchExport, role: str) -> list[str]:
    problems: set[str] = set()
    if not export.candles:
        problems.add(f"{role}_EMPTY_EXPORT")
    ordered = sorted(export.candles, key=lambda bar: bar.candle_time)
    prev = None
    for bar in ordered:
        start = bar.candle_time.astimezone(IST)
        available = bar.available_at.astimezone(IST)
        if (
            start.time() < time(9, 15)
            or start.time() >= time(15, 30)
            or (start.hour * 60 + start.minute - 555) % 5 != 0
            or start.date() != available.date()
            or available > start + timedelta(minutes=5)
            or available.time() > time(15, 30)
            or bar.available_at < bar.candle_time + timedelta(minutes=5)
        ):
            problems.add(f"{role}_FIVE_MINUTE_SESSION_BOUNDARY_INVALID")
        if bar.low > min(bar.open, bar.close) or bar.high < max(
            bar.open, bar.close
        ) or bar.low > bar.high:
            problems.add(f"{role}_OHLC_INCONSISTENT")
        if bar.fetched_at < bar.available_at:
            problems.add(f"{role}_FETCH_PRECEDES_AVAILABILITY")
        if bar.fetched_at > export.generated_at:
            problems.add(f"{role}_FETCH_AFTER_EXPORT_GENERATED")
        if prev is not None and prev.candle_time >= bar.candle_time:
            problems.add(f"{role}_CANDLE_TIME_NOT_STRICTLY_INCREASING")
        prev = bar
    return sorted(problems)


def audit_india_market_holdout(
    registration: IndiaHoldoutRegistration,
    training: MarketResearchExport,
    holdout: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    config: IndiaHoldoutConfig,
    *,
    evaluation_started_at: datetime,
    declarations: list[SourceCustodyDeclaration] | None = None,
    report: IndiaHoldoutReport | None = None,
) -> IndiaMarketProvenanceReport:
    """Check internal consistency, NEVER self-certify exchange provenance/preregistration."""
    _aware(evaluation_started_at)
    frozen = IndiaHoldoutRegistration.model_validate(registration.model_dump(mode="json"))
    train = MarketResearchExport.model_validate(training.model_dump(mode="python"))
    test = MarketResearchExport.model_validate(holdout.model_dump(mode="python"))
    book = IndiaFeeTaxBookV1.model_validate(fee_book.model_dump(mode="python"))
    reasons: set[str] = set()
    train_hash, holdout_hash = _export_hash(train), _export_hash(test)
    for label, expected, actual in (
        ("TRAINING_EXPORT_CHANGED", frozen.training_export_fingerprint, train_hash),
        ("HOLDOUT_EXPORT_CHANGED", frozen.holdout_export_fingerprint, holdout_hash),
        ("FEE_BOOK_CHANGED", frozen.india_fee_book_fingerprint, book.book_fingerprint),
        ("TARGET_SPEC_CHANGED", frozen.target_spec_fingerprint, target.fingerprint),
        ("HOLDOUT_CONFIG_CHANGED", frozen.holdout_config_fingerprint, config.fingerprint),
    ):
        if expected != actual:
            reasons.add(label)
    if frozen.registered_at >= evaluation_started_at:
        reasons.add("NOT_REGISTERED_BEFORE_EVALUATION")
    if frozen.registered_at < max(train.generated_at, test.generated_at):
        reasons.add("REGISTRATION_PREDATES_SOURCE_EXPORT")
    if not train.candles or not test.candles:
        reasons.add("MISSING_MARKET_CANDLES")
    if (train.exchange, train.instrument_id, train.symbol_token) != (
        test.exchange, test.instrument_id, test.symbol_token
    ) or train.trading_symbol != test.trading_symbol:
        reasons.add("MARKET_INSTRUMENT_MISMATCH")
    if train.exchange not in {"NSE", "BSE"} or (
        train.interval != "FIVE_MINUTE" or test.interval != "FIVE_MINUTE"
    ):
        reasons.add("NOT_NSE_BSE_FIVE_MINUTE_CASH_EQUITY")
    if train.to_exclusive > test.from_inclusive:
        reasons.add("TRAIN_HOLDOUT_WINDOWS_OVERLAP")
    if train.catalogue_version != test.catalogue_version:
        reasons.add("INSTRUMENT_CATALOGUE_CHANGED")
    if train.provider_sources != test.provider_sources:
        reasons.add("PROVIDER_LINEAGE_CHANGED")
    reasons.update(_market_candle_checks(train, "TRAIN"))
    reasons.update(_market_candle_checks(test, "HOLDOUT"))

    # A custody declaration can support traceability but not cryptographically prove an
    # exchange/broker generated raw data or that the specified licence permits this use.
    custody = declarations or []
    roles = [row.export_role for row in custody]
    if sorted(roles) != ["HOLDOUT", "TRAINING"]:
        reasons.add("TWO_SOURCE_CUSTODY_DECLARATIONS_REQUIRED")
    for row in custody:
        expected = train if row.export_role == "TRAINING" else test
        if row.export_fingerprint != _export_hash(expected):
            reasons.add(f"{row.export_role}_CUSTODY_EXPORT_HASH_MISMATCH")
        if row.declared_provider not in expected.provider_sources:
            reasons.add(f"{row.export_role}_CUSTODY_PROVIDER_NOT_IN_EXPORT")
        if row.recorded_at > evaluation_started_at:
            reasons.add(f"{row.export_role}_CUSTODY_LOGGED_AFTER_EVALUATION")
        if row.recorded_at < expected.generated_at:
            reasons.add(f"{row.export_role}_CUSTODY_LOGGED_BEFORE_EXPORT_GENERATED")
    for export in (train, test):
        for bar in export.candles:
            try:
                resolve_india_fee_tax_schedule(
                    book,
                    exchange=export.exchange,
                    product="EQUITY_INTRADAY",
                    trade_date=bar.candle_time.astimezone(IST).date(),
                )
            except ValueError:
                reasons.add("FEE_SCHEDULE_MISSING_FOR_MARKET_SESSION")

    if report is not None:
        actual_report = IndiaHoldoutReport.model_validate(report.model_dump(mode="python"))
        if any(
            result.model_family not in frozen.candidate_families
            for result in actual_report.results
        ):
            reasons.add("UNREGISTERED_RESULT_MODEL_FAMILY")
        for label, expected, actual in (
            ("REPORT_TRAINING_SOURCE_MISMATCH", actual_report.train_export_fingerprint, train_hash),
            ("REPORT_HOLDOUT_SOURCE_MISMATCH", actual_report.holdout_export_fingerprint,
             holdout_hash),
            ("REPORT_FEES_MISMATCH", actual_report.india_fee_book_fingerprint,
             book.book_fingerprint),
            ("REPORT_TARGET_MISMATCH", actual_report.target_spec_fingerprint,
             target.fingerprint),
            ("REPORT_CONFIG_MISMATCH", actual_report.config_fingerprint,
             config.fingerprint),
        ):
            if expected != actual:
                reasons.add(label)
    findings = sorted(reasons)
    payload = {
        "contract_version": VERSION,
        "registration_fingerprint": frozen.registration_fingerprint,
        "training_export_fingerprint": train_hash,
        "holdout_export_fingerprint": holdout_hash,
        "fee_book_fingerprint": book.book_fingerprint,
        "assessment": "INCONCLUSIVE" if findings else "CONSISTENT_BUT_UNVERIFIED",
        "reason_codes": findings,
        "training_candles": len(train.candles),
        "holdout_candles": len(test.candles),
        "data_source_independently_verified": False,
        "preregistration_independently_timestamped": False,
        "market_fee_schedule_independently_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IndiaMarketProvenanceReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def _load_design_inputs(args: argparse.Namespace):
    return (
        load_market_export(args.training_export),
        load_market_export(args.holdout_export),
        IndiaFeeTaxBookV1.model_validate_json(
            args.fee_book.read_text(encoding="utf-8")
        ),
        ForwardReturnTargetSpec.model_validate_json(
            args.target.read_text(encoding="utf-8")
        ),
        IndiaHoldoutConfig.model_validate_json(
            args.config.read_text(encoding="utf-8")
        ),
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Freeze an offline India holdout or audit its unverified source custody"
    )
    commands = parser.add_subparsers(dest="command", required=True)
    registration_parser = commands.add_parser("register")
    audit_parser = commands.add_parser("audit")
    for subparser in (registration_parser, audit_parser):
        subparser.add_argument("training_export", type=Path)
        subparser.add_argument("holdout_export", type=Path)
        subparser.add_argument("fee_book", type=Path)
        subparser.add_argument("target", type=Path)
        subparser.add_argument("config", type=Path)
    registration_parser.add_argument("output", type=Path)
    registration_parser.add_argument(
        "--candidate-families", default="CATBOOST,LIGHTGBM",
        help="Sorted, comma-separated preregistered family names",
    )
    audit_parser.add_argument("registration", type=Path)
    audit_parser.add_argument("--source-declarations", type=Path)
    audit_parser.add_argument("--holdout-report", type=Path)
    args = parser.parse_args()

    training, holdout, book, target, config = _load_design_inputs(args)
    if args.command == "register":
        families = args.candidate_families.split(",")
        registration = register_holdout_design(
            training, holdout, book, target, config,
            registered_at=datetime.now(UTC),
            candidate_families=families,
        )
        # Prevent overwriting a frozen design with a later one after evaluation.
        import os

        descriptor = os.open(str(args.output), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(registration.model_dump(mode="json"), stream, sort_keys=True)
            stream.write("\\n")
        print(registration.registration_fingerprint)
        return

    declarations = (
        [SourceCustodyDeclaration.model_validate(x) for x in json.loads(
            args.source_declarations.read_text(encoding="utf-8")
        )] if args.source_declarations else None
    )
    report = audit_india_market_holdout(
        IndiaHoldoutRegistration.model_validate_json(
            args.registration.read_text(encoding="utf-8")
        ),
        training, holdout, book, target, config,
        evaluation_started_at=datetime.now(UTC),
        declarations=declarations,
        report=(
            IndiaHoldoutReport.model_validate_json(
                args.holdout_report.read_text(encoding="utf-8")
            ) if args.holdout_report else None
        ),
    )
    print(json.dumps(report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
