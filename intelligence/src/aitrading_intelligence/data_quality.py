from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

SOURCE_HEALTH_CONTRACT_VERSION = "source-health.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
SourceFamily = Annotated[str, Field(pattern=r"^[A-Z][A-Z0-9_]{1,63}$")]


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class SourceHealthSample(BaseModel):
    """One causally available source observation used only for quality evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    family: SourceFamily
    source: Annotated[str, Field(min_length=1, max_length=80)]
    source_id: Annotated[str, Field(min_length=1, max_length=240)]
    event_at: datetime
    available_at: datetime
    quality_flags: list[Annotated[str, Field(min_length=1, max_length=80)]] = Field(
        default_factory=list,
        max_length=32,
    )

    @field_validator("event_at", "available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @model_validator(mode="after")
    def validate_sample(self) -> SourceHealthSample:
        if self.available_at < self.event_at:
            raise ValueError("available_at cannot precede event_at")
        if len(set(self.quality_flags)) != len(self.quality_flags):
            raise ValueError("quality_flags must be unique")
        return self


class SourceHealthRule(BaseModel):
    """Fail-closed policy for one required source family."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    family: SourceFamily
    max_age_seconds: Annotated[int, Field(ge=0, le=31_536_000)]
    minimum_records: Annotated[int, Field(ge=1, le=1_000_000)] = 1


class SourceFamilyHealth(BaseModel):
    """Deterministic health evidence for one declared source family."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    family: SourceFamily
    health: Literal["HEALTHY", "DEGRADED", "STALE", "MISSING"]
    record_count: Annotated[int, Field(ge=0)]
    source_count: Annotated[int, Field(ge=0)]
    sources: list[Annotated[str, Field(min_length=1, max_length=80)]] = Field(
        default_factory=list,
        max_length=128,
    )
    latest_event_at: datetime | None = None
    latest_available_at: datetime | None = None
    age_seconds: float | None = None
    quality_flags: list[Annotated[str, Field(min_length=1, max_length=80)]] = Field(
        default_factory=list,
        max_length=256,
    )
    reasons: list[Annotated[str, Field(min_length=1, max_length=120)]] = Field(
        default_factory=list,
        max_length=32,
    )
    evidence_fingerprint: Hash64

    @field_validator("latest_event_at", "latest_available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _require_aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_health(self) -> SourceFamilyHealth:
        if self.sources != sorted(self.sources):
            raise ValueError("sources must be deterministically ordered")
        if self.quality_flags != sorted(self.quality_flags):
            raise ValueError("quality_flags must be deterministically ordered")
        if self.reasons != sorted(self.reasons):
            raise ValueError("reasons must be deterministically ordered")
        if self.health == "MISSING":
            if self.record_count != 0 or self.source_count != 0:
                raise ValueError("missing family cannot contain source records")
            if any(
                value is not None
                for value in (self.latest_event_at, self.latest_available_at, self.age_seconds)
            ):
                raise ValueError("missing family cannot contain freshness timestamps")
        else:
            if self.record_count == 0 or self.source_count == 0:
                raise ValueError("non-missing family requires source records")
            if self.latest_event_at is None or self.latest_available_at is None:
                raise ValueError("non-missing family requires latest timestamps")
            if self.age_seconds is None or self.age_seconds < 0:
                raise ValueError("non-missing family requires non-negative age")
        return self


class SourceHealthReport(BaseModel):
    """Immutable V3 data-quality gate. It is evidence and never trading authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["source-health.v1"] = SOURCE_HEALTH_CONTRACT_VERSION
    as_of: datetime
    data_cutoff: datetime | None
    policy_fingerprint: Hash64
    families: list[SourceFamilyHealth] = Field(min_length=1, max_length=128)
    causal_sample_count: Annotated[int, Field(ge=0)]
    missing_families: list[SourceFamily] = Field(default_factory=list, max_length=128)
    stale_families: list[SourceFamily] = Field(default_factory=list, max_length=128)
    degraded_families: list[SourceFamily] = Field(default_factory=list, max_length=128)
    health: Literal["HEALTHY", "DEGRADED", "BLOCKED"]
    report_fingerprint: Hash64
    status: Literal["RESEARCH_DATA_QUALITY_ONLY"] = "RESEARCH_DATA_QUALITY_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _require_aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_report(self) -> SourceHealthReport:
        family_names = [item.family for item in self.families]
        if family_names != sorted(family_names):
            raise ValueError("families must be deterministically ordered")
        if len(set(family_names)) != len(family_names):
            raise ValueError("families must be unique")
        for field_name in (
            "missing_families",
            "stale_families",
            "degraded_families",
        ):
            values = getattr(self, field_name)
            if values != sorted(values) or len(set(values)) != len(values):
                raise ValueError(f"{field_name} must be unique and deterministically ordered")
        if self.data_cutoff is not None and self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        if self.health == "HEALTHY" and (
            self.missing_families or self.stale_families or self.degraded_families
        ):
            raise ValueError("healthy report cannot contain unhealthy families")
        if self.health == "BLOCKED" and not (
            self.missing_families or self.stale_families
        ):
            raise ValueError("blocked report requires a missing or stale family")
        return self


def _rule_payload(rule: SourceHealthRule) -> dict[str, object]:
    return {
        "family": rule.family,
        "max_age_seconds": rule.max_age_seconds,
        "minimum_records": rule.minimum_records,
    }


def _family_health(
    *,
    as_of: datetime,
    rule: SourceHealthRule,
    samples: Sequence[SourceHealthSample],
) -> SourceFamilyHealth:
    if not samples:
        payload = {
            "family": rule.family,
            "health": "MISSING",
            "record_count": 0,
            "source_count": 0,
            "sources": [],
            "latest_event_at": None,
            "latest_available_at": None,
            "age_seconds": None,
            "quality_flags": [],
            "reasons": ["NO_CAUSAL_SAMPLES"],
        }
        return SourceFamilyHealth(
            **payload,
            evidence_fingerprint=canonical_fingerprint(payload),
        )

    ordered = sorted(
        samples,
        key=lambda item: (
            item.available_at,
            item.event_at,
            item.source,
            item.source_id,
        ),
    )
    latest_available_at = max(item.available_at for item in ordered)
    latest_event_at = max(
        item.event_at
        for item in ordered
        if item.available_at == latest_available_at
    )
    age_seconds = (as_of - latest_available_at).total_seconds()
    sources = sorted({item.source for item in ordered})
    quality_flags = sorted(
        {
            flag
            for item in ordered
            for flag in item.quality_flags
        }
    )

    reasons: list[str] = []
    if age_seconds > rule.max_age_seconds:
        health: Literal["HEALTHY", "DEGRADED", "STALE", "MISSING"] = "STALE"
        reasons.append("LATEST_SAMPLE_EXCEEDS_MAX_AGE")
    else:
        if len(ordered) < rule.minimum_records:
            reasons.append("INSUFFICIENT_CAUSAL_RECORDS")
        if quality_flags:
            reasons.append("QUALITY_FLAGS_PRESENT")
        health = "DEGRADED" if reasons else "HEALTHY"

    reasons = sorted(reasons)
    payload = {
        "family": rule.family,
        "health": health,
        "record_count": len(ordered),
        "source_count": len(sources),
        "sources": sources,
        "latest_event_at": latest_event_at.isoformat(),
        "latest_available_at": latest_available_at.isoformat(),
        "age_seconds": age_seconds,
        "quality_flags": quality_flags,
        "reasons": reasons,
    }
    return SourceFamilyHealth(
        family=rule.family,
        health=health,
        record_count=len(ordered),
        source_count=len(sources),
        sources=sources,
        latest_event_at=latest_event_at,
        latest_available_at=latest_available_at,
        age_seconds=age_seconds,
        quality_flags=quality_flags,
        reasons=reasons,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def build_source_health_report(
    *,
    as_of: datetime,
    rules: Sequence[SourceHealthRule],
    samples: Iterable[SourceHealthSample],
) -> SourceHealthReport:
    """Evaluate causal source health with deterministic, fail-closed semantics."""

    _require_aware(as_of)
    if not rules:
        raise ValueError("at least one source health rule is required")

    rule_by_family = {rule.family: rule for rule in rules}
    if len(rule_by_family) != len(rules):
        raise ValueError("source health rules must have unique families")

    materialized = list(samples)
    unknown_families = sorted(
        {sample.family for sample in materialized}.difference(rule_by_family)
    )
    if unknown_families:
        raise ValueError(
            "source family is not enrolled in the quality policy: "
            + ",".join(unknown_families)
        )

    causal = [sample for sample in materialized if sample.available_at <= as_of]
    grouped: dict[str, list[SourceHealthSample]] = defaultdict(list)
    for sample in causal:
        grouped[sample.family].append(sample)

    ordered_rules = [rule_by_family[name] for name in sorted(rule_by_family)]
    policy_payload = [_rule_payload(rule) for rule in ordered_rules]
    policy_fingerprint = canonical_fingerprint(policy_payload)
    family_health = [
        _family_health(
            as_of=as_of,
            rule=rule,
            samples=grouped.get(rule.family, []),
        )
        for rule in ordered_rules
    ]

    missing_families = sorted(
        item.family for item in family_health if item.health == "MISSING"
    )
    stale_families = sorted(
        item.family for item in family_health if item.health == "STALE"
    )
    degraded_families = sorted(
        item.family for item in family_health if item.health == "DEGRADED"
    )
    if missing_families or stale_families:
        overall: Literal["HEALTHY", "DEGRADED", "BLOCKED"] = "BLOCKED"
    elif degraded_families:
        overall = "DEGRADED"
    else:
        overall = "HEALTHY"

    data_cutoff = max((sample.available_at for sample in causal), default=None)
    payload = {
        "contract_version": SOURCE_HEALTH_CONTRACT_VERSION,
        "as_of": as_of.isoformat(),
        "data_cutoff": data_cutoff.isoformat() if data_cutoff is not None else None,
        "policy_fingerprint": policy_fingerprint,
        "families": [item.model_dump(mode="json") for item in family_health],
        "causal_sample_count": len(causal),
        "missing_families": missing_families,
        "stale_families": stale_families,
        "degraded_families": degraded_families,
        "health": overall,
        "status": "RESEARCH_DATA_QUALITY_ONLY",
        "can_authorize_capital": False,
    }
    return SourceHealthReport(
        as_of=as_of,
        data_cutoff=data_cutoff,
        policy_fingerprint=policy_fingerprint,
        families=family_health,
        causal_sample_count=len(causal),
        missing_families=missing_families,
        stale_families=stale_families,
        degraded_families=degraded_families,
        health=overall,
        report_fingerprint=canonical_fingerprint(payload),
    )
