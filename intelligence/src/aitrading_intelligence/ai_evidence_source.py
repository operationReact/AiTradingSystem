from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .datasets import EventRecord

AI_EVIDENCE_EXPORT_CONTRACT = "news-ai-evidence.v1"


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Symbol = Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]


class ResearchAiEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    analysis_id: Annotated[str, Field(min_length=1, max_length=200)] = Field(alias="analysisId")
    source_id: Annotated[str, Field(min_length=1, max_length=200)] = Field(alias="sourceId")
    provider: Annotated[str, Field(min_length=1, max_length=40)]
    model: Annotated[str, Field(min_length=1, max_length=100)]
    provider_request_id: str | None = Field(default=None, alias="providerRequestId", max_length=200)
    source_available_at: datetime = Field(alias="sourceAvailableAt")
    evidence_available_at: datetime = Field(alias="evidenceAvailableAt")
    prompt_hash: Hash64 = Field(alias="promptHash")
    request_fingerprint: Hash64 = Field(alias="requestFingerprint")
    response_fingerprint: Hash64 = Field(alias="responseFingerprint")
    sentiment: Literal["POSITIVE", "NEGATIVE", "NEUTRAL", "MIXED"]
    direction_score: Annotated[float, Field(ge=-1, le=1)] = Field(alias="directionScore")
    category: str
    confidence: Annotated[float, Field(ge=0, le=1)]
    materiality: Annotated[float, Field(ge=0, le=1)]
    horizon: Literal["INTRADAY", "MULTI_DAY", "LONGER_TERM", "UNKNOWN"]
    novelty: Annotated[float, Field(ge=0, le=1)]
    contradiction: bool
    ambiguity: bool
    impacted_symbols: list[Symbol] = Field(alias="impactedSymbols", max_length=10)
    input_tokens: Annotated[int, Field(ge=0)] | None = Field(default=None, alias="inputTokens")
    output_tokens: Annotated[int, Field(ge=0)] | None = Field(default=None, alias="outputTokens")
    total_tokens: Annotated[int, Field(ge=0)] | None = Field(default=None, alias="totalTokens")

    @field_validator("source_available_at", "evidence_available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_causality(self) -> ResearchAiEvidence:
        if self.source_available_at > self.evidence_available_at:
            raise ValueError("sourceAvailableAt cannot be after evidenceAvailableAt")
        if len(self.impacted_symbols) != len(set(self.impacted_symbols)):
            raise ValueError("impactedSymbols cannot contain duplicates")
        if (
            self.total_tokens is not None
            and self.input_tokens is not None
            and self.output_tokens is not None
            and self.total_tokens < self.input_tokens + self.output_tokens
        ):
            raise ValueError("totalTokens cannot be smaller than inputTokens + outputTokens")
        return self


class NewsAiEvidenceExport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    contract_version: Literal["news-ai-evidence.v1"] = Field(alias="contractVersion")
    generated_at: datetime = Field(alias="generatedAt")
    from_inclusive: datetime = Field(alias="fromInclusive")
    to_exclusive: datetime = Field(alias="toExclusive")
    data_cutoff: datetime | None = Field(alias="dataCutoff")
    provider_sources: list[str] = Field(alias="providerSources", max_length=64)
    evidence: list[ResearchAiEvidence] = Field(max_length=20_000)

    @field_validator("generated_at", "from_inclusive", "to_exclusive", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_export(self) -> NewsAiEvidenceExport:
        if self.from_inclusive >= self.to_exclusive:
            raise ValueError("fromInclusive must be before toExclusive")
        ids = [item.analysis_id for item in self.evidence]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate analysisId values are not allowed")
        for item in self.evidence:
            if not self.from_inclusive <= item.evidence_available_at < self.to_exclusive:
                raise ValueError("evidenceAvailableAt is outside the export window")
            if item.evidence_available_at > self.generated_at:
                raise ValueError("evidenceAvailableAt cannot be after generatedAt")
        expected_cutoff = max(
            (item.evidence_available_at for item in self.evidence), default=None
        )
        if self.data_cutoff != expected_cutoff:
            raise ValueError("dataCutoff must equal the latest evidenceAvailableAt")
        expected_sources = {f"{item.provider}:{item.model}" for item in self.evidence}
        if not expected_sources.issubset(set(self.provider_sources)):
            raise ValueError("providerSources must include every provider:model source")
        return self

    def to_event_records(self) -> list[EventRecord]:
        records: list[EventRecord] = []
        for item in sorted(
            self.evidence,
            key=lambda value: (value.evidence_available_at, value.analysis_id),
        ):
            numeric = {
                "direction_score": item.direction_score,
                "confidence": item.confidence,
                "materiality": item.materiality,
                "novelty": item.novelty,
                "contradiction": 1.0 if item.contradiction else 0.0,
                "ambiguity": 1.0 if item.ambiguity else 0.0,
                f"sentiment_{item.sentiment.lower()}": 1.0,
                f"horizon_{item.horizon.lower()}": 1.0,
            }
            lineage = [
                f"news:{item.source_id}",
                f"model:{item.model}",
                f"prompt:{item.prompt_hash}",
                f"request:{item.request_fingerprint}",
                f"response:{item.response_fingerprint}",
            ]
            if item.provider_request_id:
                lineage.append(f"provider-request:{item.provider_request_id}")
            for symbol in sorted(item.impacted_symbols):
                records.append(
                    EventRecord(
                        symbol=symbol,
                        event_type="AI_NEWS_EVIDENCE",
                        observed_at=item.evidence_available_at,
                        effective_at=item.source_available_at,
                        source_id=item.analysis_id,
                        source=item.provider,
                        numeric_features=numeric,
                        lineage_ids=lineage,
                    )
                )
        return records


def load_ai_evidence_export(path: Path) -> NewsAiEvidenceExport:
    return NewsAiEvidenceExport.model_validate_json(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate and normalize point-in-time Gemini research evidence"
    )
    parser.add_argument("export_json", type=Path)
    args = parser.parse_args()
    records = load_ai_evidence_export(args.export_json).to_event_records()
    print(json.dumps([record.model_dump(mode="json") for record in records], sort_keys=True))


if __name__ == "__main__":
    main()
