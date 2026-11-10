"""Fail-closed, research-only readiness for Mintick Mind Golden Source inputs.

Pure typed contracts: no provider, database, broker, network, trading or AWS calls.
An upstream, trusted data-rights registry must attest each rights receipt; a
hash supplied here is evidence *reference*, not proof a licence was purchased.
"""
from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
SourceId = Annotated[str, Field(pattern=r"^[a-z][a-z0-9_]{2,80}$")]
Right = Literal["MODEL_TRAINING", "MODEL_INFERENCE", "END_USER_DISPLAY", "REDISTRIBUTION"]
Quality = Literal["VERIFIED", "INCOMPLETE", "QUARANTINED", "UNKNOWN"]
SourceKind = Literal["EXTERNAL", "SYNTHETIC_TEST"]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("golden source timestamps must be timezone-aware")
    return value


class SourceSliceEvidence(BaseModel):
    """One bounded, point-in-time source slice, not a raw vendor dataset.

    A source slice should be extracted using the historical decision cutoff,
    not the current global provider inventory. Never persist prices here.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)
    source_id: SourceId
    source_kind: SourceKind
    dataset_digest: Hash64
    latest_event_at: datetime
    latest_available_at: datetime
    ingested_at: datetime
    valid_rows: Annotated[int, Field(ge=0)]
    expected_rows: Annotated[int, Field(ge=0)]
    quality: Quality = "UNKNOWN"
    permitted_uses: frozenset[Right] = frozenset()
    rights_receipt_digest: Hash64 | None = None
    rights_verified_upstream: bool = False

    @field_validator("latest_event_at", "latest_available_at", "ingested_at")
    @classmethod
    def timestamps_are_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_evidence(self) -> SourceSliceEvidence:
        if self.latest_event_at > self.latest_available_at:
            raise ValueError("source cannot be available before its market event")
        if self.ingested_at < self.latest_available_at:
            raise ValueError("ingestion cannot precede published/available data")
        if self.valid_rows > self.expected_rows:
            raise ValueError("validated rows cannot exceed expected rows")
        if self.rights_verified_upstream and self.rights_receipt_digest is None:
            raise ValueError("rights claim requires a separate attestation reference")
        return self


class ModelFeatureSlice(BaseModel):
    """Immutable materialized model-input vector, not a model prediction."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    decision_at: datetime
    data_cutoff: datetime
    feature_schema_digest: Hash64
    feature_digest: Hash64
    features: dict[str, float] = Field(default_factory=dict, max_length=1024)
    source_digests: dict[SourceId, Hash64] = Field(default_factory=dict, max_length=32)
    synthetic: bool = False
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_at", "data_cutoff")
    @classmethod
    def timestamps_are_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @field_validator("features")
    @classmethod
    def finite_ordered_features(cls, value: dict[str, float]) -> dict[str, float]:
        if list(value) != sorted(value):
            raise ValueError("model features must be deterministically ordered")
        if any(not math.isfinite(x) for x in value.values()):
            raise ValueError("model features must be finite")
        return value

    @model_validator(mode="after")
    def feature_cutoff_is_causal(self) -> ModelFeatureSlice:
        if self.data_cutoff > self.decision_at:
            raise ValueError("feature cutoff cannot exceed the decision timestamp")
        if list(self.source_digests) != sorted(self.source_digests):
            raise ValueError("source fingerprints must be deterministically ordered")
        return self


class ModelSourceRequirements(BaseModel):
    """Declared per-model contract derived from a reviewed model registry."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    model_name: Annotated[str, Field(min_length=1, max_length=160)]
    model_version: Annotated[str, Field(min_length=1, max_length=160)]
    requested_use: Right
    required_source_ids: list[SourceId] = Field(min_length=1, max_length=32)
    required_feature_names: list[str] = Field(min_length=1, max_length=128)
    expected_feature_schema_digest: Hash64
    minimum_valid_rows: dict[SourceId, Annotated[int, Field(ge=1)]] = Field(
        default_factory=dict,
        max_length=32,
    )
    maximum_age_seconds: dict[SourceId, Annotated[int, Field(gt=0)]] = Field(
        default_factory=dict,
        max_length=32,
    )

    @model_validator(mode="after")
    def validate_requirements(self) -> ModelSourceRequirements:
        if len(set(self.required_source_ids)) != len(self.required_source_ids):
            raise ValueError("required data sources cannot repeat")
        if len(set(self.required_feature_names)) != len(self.required_feature_names):
            raise ValueError("required model features cannot repeat")
        required = set(self.required_source_ids)
        if not set(self.minimum_valid_rows).issubset(required):
            raise ValueError("minimum rows must reference required sources")
        if not set(self.maximum_age_seconds).issubset(required):
            raise ValueError("freshness thresholds must reference required sources")
        return self


class ModelDataReadiness(BaseModel):
    """Validation result. RESEARCH_READY is not deployment or trading approval."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    model_name: str
    model_version: str
    decision_at: datetime
    status: Literal["RESEARCH_READY", "BLOCKED"]
    blockers: list[str]
    data_feature_digest: Hash64
    model_artifact_digest: Hash64 | None
    can_authorize_capital: Literal[False] = False


def evaluate_model_data_readiness(
    *,
    requirements: ModelSourceRequirements,
    features: ModelFeatureSlice,
    source_slices: list[SourceSliceEvidence],
    model_artifact_digest: str | None = None,
    model_artifact_verified: bool = False,
) -> ModelDataReadiness:
    """Conservatively validate only the passed, immutable as-of source slices.

    Callers must separately verify source credentials, rights attestations,
    signatures and source-manifest digests before constructing the evidence.
    A successful result is RESEARCH_READY only; never an order authorization.
    """

    blockers: set[str] = set()
    if model_artifact_verified is False or model_artifact_digest is None:
        blockers.add("UNTRAINED_OR_UNVERIFIED_MODEL")
    elif len(model_artifact_digest) != 64 or any(
        ch not in "0123456789abcdef" for ch in model_artifact_digest
    ):
        blockers.add("INVALID_MODEL_ARTIFACT_DIGEST")
    if features.synthetic:
        blockers.add("SYNTHETIC_FEATURE_SNAPSHOT")
    if features.feature_schema_digest != requirements.expected_feature_schema_digest:
        blockers.add("INCOMPATIBLE_FEATURE_SCHEMA")
    for name in requirements.required_feature_names:
        if name not in features.features:
            blockers.add(f"MISSING_REQUIRED_FEATURE:{name}")

    indexed: dict[str, SourceSliceEvidence] = {}
    for source in source_slices:
        if source.source_id in indexed:
            blockers.add(f"DUPLICATE_SOURCE:{source.source_id}")
        indexed[source.source_id] = source
    for source_id in requirements.required_source_ids:
        source = indexed.get(source_id)
        if source is None:
            blockers.add(f"SOURCE_UNAVAILABLE:{source_id}")
            continue
        if source.source_kind != "EXTERNAL":
            blockers.add(f"SYNTHETIC_SOURCE:{source_id}")
        if source.quality != "VERIFIED":
            blockers.add(f"SOURCE_QUALITY:{source_id}")
        if (
            not source.rights_verified_upstream
            or source.rights_receipt_digest is None
            or requirements.requested_use not in source.permitted_uses
        ):
            blockers.add(f"NOT_LICENSED:{source_id}")
        if source.latest_available_at > features.data_cutoff:
            blockers.add(f"FUTURE_SOURCE_EVIDENCE:{source_id}")
        minimum = requirements.minimum_valid_rows.get(source_id, 1)
        if source.valid_rows < minimum or source.valid_rows < source.expected_rows:
            blockers.add(f"INSUFFICIENT_HISTORY:{source_id}")
        maximum_age = requirements.maximum_age_seconds.get(source_id)
        if maximum_age is not None:
            age = (features.decision_at - source.latest_available_at).total_seconds()
            if age > maximum_age:
                blockers.add(f"STALE_SOURCE:{source_id}")
        if features.source_digests.get(source_id) != source.dataset_digest:
            blockers.add(f"FEATURE_SOURCE_MISMATCH:{source_id}")

    # A provenanced feature vector must belong to the same decision timestamp
    # as the validation request. The model receives no future labels or orders.
    status: Literal["RESEARCH_READY", "BLOCKED"] = (
        "BLOCKED" if blockers else "RESEARCH_READY"
    )
    return ModelDataReadiness(
        model_name=requirements.model_name,
        model_version=requirements.model_version,
        decision_at=features.decision_at,
        status=status,
        blockers=sorted(blockers),
        data_feature_digest=features.feature_digest,
        model_artifact_digest=(
            model_artifact_digest
            if model_artifact_digest is not None
            and len(model_artifact_digest) == 64
            and all(ch in "0123456789abcdef" for ch in model_artifact_digest)
            else None
        ),
    )


def requirements_from_reviewed_catalogue(
    *,
    catalogue: dict,
    model_name: str,
    model_version: str,
    feature_names: list[str],
    feature_schema_digest: str,
    requested_use: Right,
    minimum_valid_rows: dict[str, int] | None = None,
    maximum_age_seconds: dict[str, int] | None = None,
) -> ModelSourceRequirements:
    """Resolve one exact registered model's mandatory sources.

    The caller must load and authenticate the reviewed/version-pinned catalogue
    artifact before invoking this function. Fixture labels are not evidence
    that the associated trained model or dataset exists.
    """

    if catalogue.get("contract_version") != "minttick.mind.golden-source-requirements.v1":
        raise ValueError("unrecognized Golden Source model catalogue version")
    entries = catalogue.get("models")
    sources = catalogue.get("data_sources")
    if not isinstance(entries, list) or not isinstance(sources, dict):
        raise ValueError("Golden Source model catalogue is malformed")
    matches = [m for m in entries if isinstance(m, dict) and m.get("name") == model_name]
    if len(matches) != 1:
        raise ValueError("model must be uniquely present in reviewed catalogue")
    mandatory = matches[0].get("required_sources")
    if not isinstance(mandatory, list) or not mandatory or not set(mandatory) <= set(sources):
        raise ValueError("registered model has unrecognized mandatory sources")
    return ModelSourceRequirements(
        model_name=model_name,
        model_version=model_version,
        requested_use=requested_use,
        required_source_ids=mandatory,
        required_feature_names=feature_names,
        expected_feature_schema_digest=feature_schema_digest,
        minimum_valid_rows=minimum_valid_rows or {},
        maximum_age_seconds=maximum_age_seconds or {},
    )
