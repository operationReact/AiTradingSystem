from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_context import IndiaUniverseMembership, IndiaUniverseSnapshot
from .production_universe import NIFTY500_LIQUID_UNIVERSE_ID

CROSS_SECTIONAL_SCAN_PLAN_VERSION = "cross-sectional-scan-plan.v1"
CROSS_SECTIONAL_SCAN_BATCH_VERSION = "cross-sectional-scan-batch.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class CrossSectionalScanMemberV1(BaseModel):
    """Immutable member reference copied from one canonical universe snapshot."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    instrument_id: Annotated[str, Field(min_length=1, max_length=120)]
    exchange: Literal["NSE", "BSE"]
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    series: Annotated[str, Field(min_length=1, max_length=24)]
    sector: Annotated[str, Field(min_length=1, max_length=120)]
    index_memberships: list[str] = Field(max_length=32)
    source_membership_id: Annotated[str, Field(min_length=1, max_length=240)]
    member_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_member_fingerprint(self) -> CrossSectionalScanMemberV1:
        if self.index_memberships != sorted(self.index_memberships):
            raise ValueError("scan member index memberships must be canonically ordered")
        payload = self.model_dump(mode="json", exclude={"member_fingerprint"})
        if self.member_fingerprint != canonical_fingerprint(payload):
            raise ValueError("scan member fingerprint mismatch")
        return self


class CrossSectionalScanBatchV1(BaseModel):
    """One bounded deterministic slice of a versioned research universe."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["cross-sectional-scan-batch.v1"] = (
        CROSS_SECTIONAL_SCAN_BATCH_VERSION
    )
    batch_index: Annotated[int, Field(ge=0)]
    start_offset: Annotated[int, Field(ge=0)]
    member_count: Annotated[int, Field(ge=1, le=200)]
    members: list[CrossSectionalScanMemberV1] = Field(min_length=1, max_length=200)
    batch_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_batch(self) -> CrossSectionalScanBatchV1:
        if self.member_count != len(self.members):
            raise ValueError("scan batch member_count must match members")
        identities = [(item.exchange, item.instrument_id) for item in self.members]
        if len(set(identities)) != len(identities):
            raise ValueError("scan batch members must have unique identities")
        if identities != sorted(identities):
            raise ValueError("scan batch members must remain canonically ordered")
        payload = self.model_dump(mode="json", exclude={"batch_fingerprint"})
        if self.batch_fingerprint != canonical_fingerprint(payload):
            raise ValueError("scan batch fingerprint mismatch")
        return self


class CrossSectionalScanPlanV1(BaseModel):
    """Bounded work manifest for scanning one exact point-in-time liquid universe."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["cross-sectional-scan-plan.v1"] = (
        CROSS_SECTIONAL_SCAN_PLAN_VERSION
    )
    universe_contract_version: Literal["india-universe-snapshot.v1"]
    universe_id: Annotated[str, Field(min_length=1, max_length=120)]
    scan_as_of: datetime
    universe_data_cutoff: datetime
    source_universe_fingerprint: Hash64
    source_universe_member_count: Annotated[int, Field(ge=1, le=1_000)]
    batch_size: Annotated[int, Field(ge=1, le=200)]
    batch_count: Annotated[int, Field(ge=1, le=1_000)]
    batches: list[CrossSectionalScanBatchV1] = Field(min_length=1, max_length=1_000)
    plan_fingerprint: Hash64
    status: Literal["RESEARCH_SCAN_PLAN_ONLY"] = "RESEARCH_SCAN_PLAN_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("scan_as_of", "universe_data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("scan plan timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_plan(self) -> CrossSectionalScanPlanV1:
        if self.universe_data_cutoff > self.scan_as_of:
            raise ValueError("universe data cutoff cannot exceed scan_as_of")
        if self.batch_count != len(self.batches):
            raise ValueError("scan plan batch_count must match batches")
        flattened = [member for batch in self.batches for member in batch.members]
        if len(flattened) != self.source_universe_member_count:
            raise ValueError("scan plan must cover the full source universe exactly once")
        identities = [(item.exchange, item.instrument_id) for item in flattened]
        if len(set(identities)) != len(identities):
            raise ValueError("scan plan cannot contain duplicate universe identities")
        if identities != sorted(identities):
            raise ValueError("scan plan must preserve canonical universe ordering")
        expected_offset = 0
        for expected_index, batch in enumerate(self.batches):
            if batch.batch_index != expected_index:
                raise ValueError("scan batches must have contiguous zero-based indexes")
            if batch.start_offset != expected_offset:
                raise ValueError("scan batch start offsets must be contiguous")
            if batch.member_count > self.batch_size:
                raise ValueError("scan batch cannot exceed configured batch_size")
            expected_offset += batch.member_count
        payload = self.model_dump(mode="json", exclude={"plan_fingerprint"})
        if self.plan_fingerprint != canonical_fingerprint(payload):
            raise ValueError("scan plan fingerprint mismatch")
        return self


def build_cross_sectional_scan_plan(
    universe: IndiaUniverseSnapshot,
    *,
    batch_size: int = 50,
    maximum_universe_members: int = 1_000,
) -> CrossSectionalScanPlanV1:
    """Freeze one canonical liquid-universe snapshot into bounded scan batches."""

    validated_universe = _validate_universe_snapshot(universe)
    if validated_universe.universe_id != NIFTY500_LIQUID_UNIVERSE_ID:
        raise ValueError("V3 scanner requires the canonical liquid NIFTY 500 universe")
    if batch_size < 1 or batch_size > 200:
        raise ValueError("batch_size must be between 1 and 200")
    if maximum_universe_members < 1 or maximum_universe_members > 1_000:
        raise ValueError("maximum_universe_members must be between 1 and 1000")
    if len(validated_universe.members) > maximum_universe_members:
        raise ValueError(
            "source universe exceeds configured scan safety bound; refusing silent truncation"
        )

    scan_members = [_scan_member(member) for member in validated_universe.members]
    batches: list[CrossSectionalScanBatchV1] = []
    for batch_index, start in enumerate(range(0, len(scan_members), batch_size)):
        members = scan_members[start : start + batch_size]
        payload = {
            "contract_version": CROSS_SECTIONAL_SCAN_BATCH_VERSION,
            "batch_index": batch_index,
            "start_offset": start,
            "member_count": len(members),
            "members": [item.model_dump(mode="json") for item in members],
        }
        batches.append(
            CrossSectionalScanBatchV1(
                **payload,
                batch_fingerprint=canonical_fingerprint(payload),
            )
        )

    plan_payload = {
        "contract_version": CROSS_SECTIONAL_SCAN_PLAN_VERSION,
        "universe_contract_version": validated_universe.contract_version,
        "universe_id": validated_universe.universe_id,
        "scan_as_of": validated_universe.as_of.isoformat(),
        "universe_data_cutoff": validated_universe.data_cutoff.isoformat(),
        "source_universe_fingerprint": validated_universe.snapshot_fingerprint,
        "source_universe_member_count": len(scan_members),
        "batch_size": batch_size,
        "batch_count": len(batches),
        "batches": [item.model_dump(mode="json") for item in batches],
        "status": "RESEARCH_SCAN_PLAN_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CrossSectionalScanPlanV1(
        **plan_payload,
        plan_fingerprint=canonical_fingerprint(plan_payload),
    )


def _scan_member(member: IndiaUniverseMembership) -> CrossSectionalScanMemberV1:
    payload = {
        "instrument_id": member.instrument_id,
        "exchange": member.exchange,
        "symbol": member.symbol,
        "series": member.series,
        "sector": member.sector,
        "index_memberships": sorted(member.index_memberships),
        "source_membership_id": member.source_id,
    }
    return CrossSectionalScanMemberV1(
        **payload,
        member_fingerprint=canonical_fingerprint(payload),
    )


def _validate_universe_snapshot(
    item: IndiaUniverseSnapshot,
) -> IndiaUniverseSnapshot:
    validated = IndiaUniverseSnapshot.model_validate(item.model_dump(mode="python"))
    payload = validated.model_dump(mode="json", exclude={"snapshot_fingerprint"})
    if validated.snapshot_fingerprint != canonical_fingerprint(payload):
        raise ValueError("source universe snapshot fingerprint mismatch")
    if validated.can_authorize_capital:
        raise ValueError("source universe cannot carry capital authority")
    return validated
