from __future__ import annotations

import math
from datetime import UTC, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from . import CONTRACT_VERSION


class ProvenanceRef(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    source: Annotated[str, Field(min_length=1, max_length=80)]
    version: Annotated[str, Field(min_length=1, max_length=120)]
    observed_at: datetime
    fingerprint: Annotated[str | None, Field(max_length=128)] = None

    @field_validator("observed_at")
    @classmethod
    def timezone_required(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("observed_at must be timezone-aware")
        return value


class EvidenceContractRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["intelligence.v1"] = CONTRACT_VERSION
    request_id: Annotated[str, Field(min_length=8, max_length=100)]
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    as_of: datetime
    data_cutoff: datetime
    feature_schema_version: Annotated[str, Field(min_length=1, max_length=120)]
    features: dict[str, float] = Field(default_factory=dict, max_length=512)
    provenance: list[ProvenanceRef] = Field(default_factory=list, max_length=128)

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timezone_required(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("timestamps must be timezone-aware")
        return value

    @field_validator("features")
    @classmethod
    def finite_features(cls, value: dict[str, float]) -> dict[str, float]:
        for name, number in value.items():
            if not name or len(name) > 120:
                raise ValueError("feature names must contain 1..120 characters")
            if not math.isfinite(number):
                raise ValueError(f"feature {name!r} must be finite")
        return value

    @model_validator(mode="after")
    def point_in_time_guards(self) -> EvidenceContractRequest:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot be later than as_of")
        now = datetime.now(UTC)
        if self.as_of.astimezone(UTC) > now.replace(microsecond=0):
            raise ValueError("as_of cannot be in the future")
        for ref in self.provenance:
            if ref.observed_at > self.data_cutoff:
                raise ValueError("provenance observed_at cannot be later than data_cutoff")
        return self


class EvidenceItem(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    family: Literal["DETERMINISTIC", "QUANT", "PYTORCH", "GEMINI", "FUNDAMENTAL", "REGIME"]
    provider: Annotated[str, Field(min_length=1, max_length=80)]
    version: Annotated[str, Field(min_length=1, max_length=120)]
    score: Annotated[float, Field(ge=-1.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    observed_at: datetime
    explanation: Annotated[str, Field(min_length=1, max_length=1000)]


class EvidenceContractResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["intelligence.v1"] = CONTRACT_VERSION
    request_id: str
    accepted: bool
    input_fingerprint: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    evidence: list[EvidenceItem] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    can_authorize_capital: Literal[False] = False
    generated_at: datetime
