from __future__ import annotations

import argparse
import json
import os
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_fee_tax import IndiaFeeTaxBookV1
from .india_independent_holdout import IndiaHoldoutConfig, IndiaHoldoutReport
from .market_source import MarketResearchExport, load_market_export
from .pytorch_target import ForwardReturnTargetSpec

PLAN_VERSION = "india-holdout-freeze-plan.v1"
BINDING_VERSION = "india-holdout-plan-binding.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ModelFamily = Literal["CATBOOST", "LIGHTGBM", "PYTORCH"]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("research plan timestamps must be timezone-aware")
    return value


class IndiaHoldoutFreezePlan(BaseModel):
    """Self-declared pre-analysis commitments; never an independently verified timestamp."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-holdout-freeze-plan.v1"] = PLAN_VERSION
    plan_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,79}$")]
    recorded_at: datetime
    training_generated_at: datetime
    training_export_fingerprint: Hash64
    planned_holdout_from: datetime
    planned_holdout_to: datetime
    exchange: Literal["NSE", "BSE"]
    trading_symbol: str
    instrument_id: Annotated[int, Field(gt=0)]
    symbol_token: str
    catalogue_version: str
    provider_sources: list[str] = Field(min_length=1, max_length=16)
    india_fee_book_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    config_fingerprint: Hash64
    model_families: list[ModelFamily] = Field(min_length=2, max_length=3)
    registered_at_independently_attested: Literal[False] = False
    external_market_source_verified: Literal[False] = False
    fees_independently_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    plan_fingerprint: Hash64

    @field_validator(
        "recorded_at", "training_generated_at",
        "planned_holdout_from", "planned_holdout_to",
    )
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_freeze(self) -> IndiaHoldoutFreezePlan:
        if not (
            self.training_generated_at <= self.recorded_at
            < self.planned_holdout_from < self.planned_holdout_to
        ):
            raise ValueError("freeze plan must be recorded after training export, before holdout")
        if self.provider_sources != sorted(set(self.provider_sources)):
            raise ValueError("plan provider sources must be sorted and unique")
        if self.model_families != sorted(set(self.model_families)):
            raise ValueError("planned model families must be sorted and unique")
        if not {"CATBOOST", "LIGHTGBM"}.issubset(self.model_families):
            raise ValueError("holdout plan requires CatBoost and LightGBM baselines")
        if self.plan_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"plan_fingerprint"})
        ):
            raise ValueError("holdout freeze plan fingerprint mismatch")
        return self


class IndiaHoldoutPlanBinding(BaseModel):
    """Fingerprint-only consistency record; not validation of market-source authenticity."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-holdout-plan-binding.v1"] = BINDING_VERSION
    plan_fingerprint: Hash64
    training_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    holdout_report_fingerprint: Hash64 | None
    holdout_row_count: Annotated[int, Field(ge=1)]
    result: Literal["SELF_DECLARED_PLAN_CONSISTENT"] = "SELF_DECLARED_PLAN_CONSISTENT"
    registered_at_independently_attested: Literal[False] = False
    external_market_source_verified: Literal[False] = False
    fees_independently_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    binding_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_binding(self) -> IndiaHoldoutPlanBinding:
        if self.binding_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"binding_fingerprint"})
        ):
            raise ValueError("holdout plan binding fingerprint mismatch")
        return self


def _fingerprint(export: MarketResearchExport) -> str:
    return canonical_fingerprint(export.model_dump(mode="json"))


def freeze_india_holdout_plan(
    training: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    config: IndiaHoldoutConfig,
    *,
    plan_id: str,
    holdout_from: datetime,
    holdout_to: datetime,
    recorded_at: datetime,
    include_pytorch: bool = False,
) -> IndiaHoldoutFreezePlan:
    train = MarketResearchExport.model_validate(training.model_dump(mode="python"))
    fees = IndiaFeeTaxBookV1.model_validate(fee_book.model_dump(mode="python"))
    _aware(recorded_at)
    _aware(holdout_from)
    _aware(holdout_to)
    if train.exchange not in ("NSE", "BSE") or train.interval != "FIVE_MINUTE":
        raise ValueError("freeze plan supports only NSE/BSE five-minute cash equities")
    if not train.candles:
        raise ValueError("freeze plan needs a nonempty captured training export")
    if train.generated_at > recorded_at or any(
        c.fetched_at > recorded_at for c in train.candles
    ):
        raise ValueError("training export was not fully captured when the plan was recorded")
    if train.to_exclusive > holdout_from:
        raise ValueError("training and prospectively frozen holdout cannot overlap")
    families: list[ModelFamily] = ["CATBOOST", "LIGHTGBM"]
    if include_pytorch:
        families.append("PYTORCH")
    payload = {
        "contract_version": PLAN_VERSION,
        "plan_id": plan_id,
        "recorded_at": recorded_at,
        "training_generated_at": train.generated_at,
        "training_export_fingerprint": _fingerprint(train),
        "planned_holdout_from": holdout_from,
        "planned_holdout_to": holdout_to,
        "exchange": train.exchange,
        "trading_symbol": train.trading_symbol,
        "instrument_id": train.instrument_id,
        "symbol_token": train.symbol_token,
        "catalogue_version": str(train.catalogue_version),
        "provider_sources": sorted(train.provider_sources),
        "india_fee_book_fingerprint": fees.book_fingerprint,
        "target_spec_fingerprint": target.fingerprint,
        "config_fingerprint": config.fingerprint,
        "model_families": families,
        "registered_at_independently_attested": False,
        "external_market_source_verified": False,
        "fees_independently_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    # Pydantic normalizes UTC datetimes to "Z" in JSON-mode serialization;
    # fingerprint the canonical serialized model, not Python isoformat strings.
    preliminary = IndiaHoldoutFreezePlan.model_construct(
        **payload, plan_fingerprint="0" * 64
    )
    fingerprint = canonical_fingerprint(
        preliminary.model_dump(mode="json", exclude={"plan_fingerprint"})
    )
    return IndiaHoldoutFreezePlan(**payload, plan_fingerprint=fingerprint)


def bind_india_holdout_to_plan(
    plan: IndiaHoldoutFreezePlan,
    training: MarketResearchExport,
    holdout: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    config: IndiaHoldoutConfig,
    *,
    report: IndiaHoldoutReport | None = None,
) -> IndiaHoldoutPlanBinding:
    frozen = IndiaHoldoutFreezePlan.model_validate(plan.model_dump(mode="python"))
    train = MarketResearchExport.model_validate(training.model_dump(mode="python"))
    later = MarketResearchExport.model_validate(holdout.model_dump(mode="python"))
    fees = IndiaFeeTaxBookV1.model_validate(fee_book.model_dump(mode="python"))
    if _fingerprint(train) != frozen.training_export_fingerprint:
        raise ValueError("training export changed after research plan freeze")
    if fees.book_fingerprint != frozen.india_fee_book_fingerprint:
        raise ValueError("India fee book changed after research plan freeze")
    if target.fingerprint != frozen.target_spec_fingerprint:
        raise ValueError("target changed after research plan freeze")
    if config.fingerprint != frozen.config_fingerprint:
        raise ValueError("model configuration changed after research plan freeze")
    if (
        later.exchange != frozen.exchange
        or later.trading_symbol != frozen.trading_symbol
        or later.instrument_id != frozen.instrument_id
        or later.symbol_token != frozen.symbol_token
        or str(later.catalogue_version) != frozen.catalogue_version
        or sorted(later.provider_sources) != frozen.provider_sources
        or later.interval != "FIVE_MINUTE"
    ):
        raise ValueError("holdout instrument/source lineage differs from frozen plan")
    if (
        later.from_inclusive != frozen.planned_holdout_from
        or later.to_exclusive != frozen.planned_holdout_to
    ):
        raise ValueError("holdout window differs from prospectively frozen window")
    if later.generated_at <= frozen.recorded_at or (
        later.data_cutoff is not None and later.data_cutoff > later.generated_at
    ):
        raise ValueError("holdout export was not captured after the plan was recorded")
    if train.to_exclusive > later.from_inclusive or not later.candles:
        raise ValueError("holdout must be disjoint and nonempty")
    if any(
        c.fetched_at > later.generated_at or c.fetched_at < c.available_at
        for c in later.candles
    ):
        raise ValueError("holdout fetchedAt/availableAt violates export generation time")

    report_hash: str | None = None
    if report is not None:
        outcome = IndiaHoldoutReport.model_validate(report.model_dump(mode="python"))
        if (
            outcome.train_export_fingerprint != _fingerprint(train)
            or outcome.holdout_export_fingerprint != _fingerprint(later)
            or outcome.india_fee_book_fingerprint != fees.book_fingerprint
            or outcome.target_spec_fingerprint != target.fingerprint
            or outcome.config_fingerprint != config.fingerprint
        ):
            raise ValueError("holdout report does not match frozen research evidence")
        if outcome.status == "RESEARCH_HOLDOUT_METRICS_ONLY" and (
            sorted(item.model_family for item in outcome.results) != frozen.model_families
        ):
            raise ValueError("evaluated model families differ from prospectively registered plan")
        report_hash = outcome.report_fingerprint

    payload = {
        "contract_version": BINDING_VERSION,
        "plan_fingerprint": frozen.plan_fingerprint,
        "training_export_fingerprint": frozen.training_export_fingerprint,
        "holdout_export_fingerprint": _fingerprint(later),
        "holdout_report_fingerprint": report_hash,
        "holdout_row_count": len(later.candles),
        "result": "SELF_DECLARED_PLAN_CONSISTENT",
        "registered_at_independently_attested": False,
        "external_market_source_verified": False,
        "fees_independently_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IndiaHoldoutPlanBinding(**payload, binding_fingerprint=canonical_fingerprint(payload))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Self-declared, zero-capital NSE/BSE holdout freeze plan and binding"
    )
    modes = parser.add_subparsers(dest="command", required=True)
    freeze = modes.add_parser("freeze", help="Freeze prospective holdout before collection")
    freeze.add_argument("training_export", type=Path)
    freeze.add_argument("fee_book", type=Path)
    freeze.add_argument("output_plan", type=Path)
    freeze.add_argument("--plan-id", required=True)
    freeze.add_argument("--from-at", required=True)
    freeze.add_argument("--to-at", required=True)
    freeze.add_argument("--cost-bps", required=True, type=float)
    freeze.add_argument("--horizon-bars", type=int, default=1)
    freeze.add_argument("--minimum-edge-bps", type=float, default=15.0)
    freeze.add_argument("--include-pytorch", action="store_true")
    bind = modes.add_parser("bind", help="Check captured exports against a frozen plan")
    bind.add_argument("plan", type=Path)
    bind.add_argument("training_export", type=Path)
    bind.add_argument("holdout_export", type=Path)
    bind.add_argument("fee_book", type=Path)
    bind.add_argument("--report", type=Path)
    bind.add_argument("--cost-bps", required=True, type=float)
    bind.add_argument("--horizon-bars", type=int, default=1)
    bind.add_argument("--minimum-edge-bps", type=float, default=15.0)
    args = parser.parse_args()
    fees = IndiaFeeTaxBookV1.model_validate_json(args.fee_book.read_text(encoding="utf-8"))
    target = ForwardReturnTargetSpec(
        round_trip_cost_bps=args.cost_bps,
        horizon_bars=args.horizon_bars,
        minimum_edge_bps=args.minimum_edge_bps,
    )
    config = IndiaHoldoutConfig()
    training = load_market_export(args.training_export)
    if args.command == "freeze":
        now = datetime.now(UTC)
        frozen = freeze_india_holdout_plan(
            training, fees, target, config, plan_id=args.plan_id,
            holdout_from=datetime.fromisoformat(args.from_at),
            holdout_to=datetime.fromisoformat(args.to_at),
            recorded_at=now,
            include_pytorch=args.include_pytorch,
        )
        handle = os.open(str(args.output_plan), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump(frozen.model_dump(mode="json"), stream, sort_keys=True)
            stream.write("\n")
        print(frozen.plan_fingerprint)
    else:
        frozen = IndiaHoldoutFreezePlan.model_validate_json(
            args.plan.read_text(encoding="utf-8")
        )
        outcome = (
            IndiaHoldoutReport.model_validate_json(args.report.read_text(encoding="utf-8"))
            if args.report else None
        )
        bound = bind_india_holdout_to_plan(
            frozen, training, load_market_export(args.holdout_export),
            fees, target, config, report=outcome,
        )
        print(json.dumps(bound.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
