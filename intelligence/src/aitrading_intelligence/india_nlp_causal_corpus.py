from __future__ import annotations

import argparse
import hashlib
import json
from collections.abc import Sequence
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .event_source import NewsResearchExport
from .fingerprints import canonical_fingerprint

PLAN_VERSION = "india-nlp-corpus-split-plan.v1"
REPORT_VERSION = "india-nlp-causal-rights-corpus.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Split = Literal["TRAIN", "CALIBRATION", "HOLDOUT"]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("India NLP corpus timestamps must be timezone-aware")
    return value


def _https(value: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https" or not parsed.hostname
        or parsed.username or parsed.password
        or parsed.hostname in {"localhost", "127.0.0.1"}
    ):
        raise ValueError("research corpus rights and source URLs must be public HTTPS")
    return value


class DeclaredNlpTextUse(BaseModel):
    """Operator-provided licensed text; this is NOT an independent rights audit."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    source_id: Annotated[str, Field(min_length=1, max_length=200)]
    source_content_hash: Annotated[str, Field(min_length=1, max_length=128)]
    excerpt: Annotated[str, Field(min_length=10, max_length=2_000)]
    excerpt_sha256: Hash64
    language: Literal["en", "hi", "te", "ta", "kn", "mr", "bn"]
    captured_at: datetime
    rights_basis: Literal["OWNED", "LICENSED", "OPEN_LICENSE"]
    rights_reference: Annotated[str, Field(min_length=12, max_length=2048)]
    model_research_use_declared_permitted: Literal[True] = True

    @field_validator("captured_at")
    @classmethod
    def timestamp_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @field_validator("rights_reference")
    @classmethod
    def require_rights_reference(cls, value: str) -> str:
        return _https(value)

    @model_validator(mode="after")
    def content_digest(self) -> DeclaredNlpTextUse:
        text = self.excerpt.strip()
        if text != self.excerpt:
            raise ValueError("excerpt cannot contain padding whitespace")
        if hashlib.sha256(text.encode("utf-8")).hexdigest() != self.excerpt_sha256:
            raise ValueError("excerpt digest differs from supplied research text")
        return self


class IndiaNlpCorpusSplitPlan(BaseModel):
    """Retrospective train data is allowed; holdout must be prospectively declared."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-nlp-corpus-split-plan.v1"] = PLAN_VERSION
    plan_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,79}$")]
    registered_at: datetime
    train_from: datetime
    train_to: datetime
    calibration_from: datetime
    calibration_to: datetime
    holdout_from: datetime
    holdout_to: datetime
    source_allowlist: list[str] = Field(min_length=1, max_length=32)
    minimum_per_split: Annotated[int, Field(ge=1, le=100)] = 1
    external_timestamp_verification: Literal[False] = False
    source_rights_independently_verified: Literal[False] = False
    market_source_independently_verified: Literal[False] = False
    capital_authorization_allowed: Literal[False] = False
    plan_fingerprint: Hash64

    @field_validator(
        "registered_at", "train_from", "train_to",
        "calibration_from", "calibration_to", "holdout_from", "holdout_to",
    )
    @classmethod
    def aware_times(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def check_plan(self) -> IndiaNlpCorpusSplitPlan:
        if not (
            self.train_from < self.train_to <= self.calibration_from
            < self.calibration_to <= self.holdout_from < self.holdout_to
        ):
            raise ValueError("train, calibration and holdout periods must not overlap")
        if self.registered_at >= self.holdout_from:
            raise ValueError("NLP plan must be registered before holdout begins")
        if self.source_allowlist != sorted(set(self.source_allowlist)):
            raise ValueError("corpus source allowlist must be unique and sorted")
        if self.plan_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"plan_fingerprint"})
        ):
            raise ValueError("NLP corpus plan fingerprint mismatch")
        return self


def make_india_nlp_split_plan(
    *, plan_id: str, registered_at: datetime, train_from: datetime,
    train_to: datetime, calibration_from: datetime, calibration_to: datetime,
    holdout_from: datetime, holdout_to: datetime,
    source_allowlist: Sequence[str], minimum_per_split: int = 1,
) -> IndiaNlpCorpusSplitPlan:
    payload = {
        "contract_version": PLAN_VERSION,
        "plan_id": plan_id,
        "registered_at": registered_at,
        "train_from": train_from,
        "train_to": train_to,
        "calibration_from": calibration_from,
        "calibration_to": calibration_to,
        "holdout_from": holdout_from,
        "holdout_to": holdout_to,
        "source_allowlist": sorted(source_allowlist),
        "minimum_per_split": minimum_per_split,
        "external_timestamp_verification": False,
        "source_rights_independently_verified": False,
        "market_source_independently_verified": False,
        "capital_authorization_allowed": False,
    }
    draft = IndiaNlpCorpusSplitPlan.model_construct(
        **payload, plan_fingerprint="0" * 64
    )
    return IndiaNlpCorpusSplitPlan(
        **payload, plan_fingerprint=canonical_fingerprint(
            draft.model_dump(mode="json", exclude={"plan_fingerprint"})
        )
    )


class IndiaNlpCausalDocument(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    source_id: str
    source: str
    publisher: str
    source_url: str
    source_content_hash: str
    excerpt_sha256: Hash64
    excerpt: str
    language: str
    rights_basis: str
    rights_reference: str
    published_at: datetime | None
    source_available_at: datetime
    text_captured_at: datetime
    first_usable_at: datetime
    linked_symbols: list[str] = Field(min_length=1, max_length=64)
    split: Split
    document_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_document(self) -> IndiaNlpCausalDocument:
        _https(self.source_url)
        if self.first_usable_at != max(self.source_available_at, self.text_captured_at):
            raise ValueError("corpus document decision availability is not causal")
        if self.published_at is not None and self.first_usable_at < self.published_at:
            raise ValueError("corpus document cannot be usable before publication")
        if hashlib.sha256(self.excerpt.encode()).hexdigest() != self.excerpt_sha256:
            raise ValueError("corpus document excerpt changed")
        if self.document_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"document_fingerprint"})
        ):
            raise ValueError("corpus document fingerprint mismatch")
        return self


class IndiaNlpCausalCorpus(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-nlp-causal-rights-corpus.v1"] = REPORT_VERSION
    plan: IndiaNlpCorpusSplitPlan
    news_export_fingerprint: Hash64
    as_of: datetime
    documents: list[IndiaNlpCausalDocument] = Field(max_length=10_000)
    train_count: Annotated[int, Field(ge=0)]
    calibration_count: Annotated[int, Field(ge=0)]
    holdout_count: Annotated[int, Field(ge=0)]
    status: Literal["SELF_DECLARED_RESEARCH_CORPUS_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    independent_publication_verification: Literal[False] = False
    independent_licence_verification: Literal[False] = False
    independent_timestamp_verification: Literal[False] = False
    sentiment_model_evaluated: Literal[False] = False
    market_outcome_linked: Literal[False] = False
    any_llm_can_place_orders: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    corpus_fingerprint: Hash64

    @field_validator("as_of")
    @classmethod
    def aware_as_of(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_corpus(self) -> IndiaNlpCausalCorpus:
        rows = self.documents
        if [(d.first_usable_at, d.source_id) for d in rows] != sorted(
            (d.first_usable_at, d.source_id) for d in rows
        ):
            raise ValueError("corpus documents must be chronologically sorted")
        if len({d.source_id for d in rows}) != len(rows):
            raise ValueError("corpus source IDs cannot appear twice")
        if len({d.excerpt_sha256 for d in rows}) != len(rows):
            raise ValueError("syndicated duplicate text cannot appear in multiple folds")
        counts = {part: sum(d.split == part for d in rows)
                  for part in ("TRAIN", "CALIBRATION", "HOLDOUT")}
        if (
            counts["TRAIN"] != self.train_count
            or counts["CALIBRATION"] != self.calibration_count
            or counts["HOLDOUT"] != self.holdout_count
        ):
            raise ValueError("corpus split counts differ from documents")
        for item in rows:
            start, end = {
                "TRAIN": (self.plan.train_from, self.plan.train_to),
                "CALIBRATION": (
                    self.plan.calibration_from, self.plan.calibration_to
                ),
                "HOLDOUT": (self.plan.holdout_from, self.plan.holdout_to),
            }[item.split]
            if not start <= item.first_usable_at < end or item.first_usable_at > self.as_of:
                raise ValueError("corpus document entered incorrect or future split")
            if item.source not in self.plan.source_allowlist:
                raise ValueError("corpus document source was not predeclared")
        expected_reasons = sorted(
            "INSUFFICIENT_" + part + "_DOCUMENTS"
            for part, count in counts.items()
            if count < self.plan.minimum_per_split
        )
        if self.reason_codes != expected_reasons or self.status != (
            "INCONCLUSIVE" if expected_reasons else "SELF_DECLARED_RESEARCH_CORPUS_ONLY"
        ):
            raise ValueError("corpus status must reflect missing fold evidence")
        if self.corpus_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"corpus_fingerprint"})
        ):
            raise ValueError("India NLP corpus fingerprint mismatch")
        return self


def build_india_nlp_causal_corpus(
    news: NewsResearchExport,
    declared_texts: Sequence[DeclaredNlpTextUse],
    plan: IndiaNlpCorpusSplitPlan,
    *, as_of: datetime,
) -> IndiaNlpCausalCorpus:
    """Use observed text capture, not retrospective publication, as split clock."""
    _aware(as_of)
    frozen = IndiaNlpCorpusSplitPlan.model_validate(plan.model_dump(mode="python"))
    export = NewsResearchExport.model_validate(news.model_dump(mode="python"))
    if export.generated_at > as_of:
        raise ValueError("cannot evaluate a news export before it was generated")
    by_id = {e.source_id: e for e in export.events}
    rights = [DeclaredNlpTextUse.model_validate(item.model_dump(mode="python"))
              for item in declared_texts]
    if len({item.source_id for item in rights}) != len(rights):
        raise ValueError("duplicate licensed-text declarations for same event")
    documents: list[IndiaNlpCausalDocument] = []
    for item in rights:
        event = by_id.get(item.source_id)
        if event is None:
            raise ValueError("licensed research text has no matching source news event")
        if event.source not in frozen.source_allowlist:
            raise ValueError("news provider was not frozen in NLP corpus allowlist")
        if item.source_content_hash != event.content_hash:
            raise ValueError("text source hash differs from news-event provenance")
        if item.captured_at < event.available_at:
            raise ValueError("research text claimed available before source event")
        if item.captured_at > as_of:
            raise ValueError("future-captured text cannot enter current NLP corpus")
        _https(event.source_url)
        first = max(event.available_at, item.captured_at)
        parts = (
            ("TRAIN", frozen.train_from, frozen.train_to),
            ("CALIBRATION", frozen.calibration_from, frozen.calibration_to),
            ("HOLDOUT", frozen.holdout_from, frozen.holdout_to),
        )
        split: Split | None = next(
            (name for name, start, end in parts if start <= first < end), None
        )
        if split is None:
            raise ValueError("captured research text falls outside frozen corpus folds")
        payload = {
            "source_id": event.source_id,
            "source": event.source,
            "publisher": event.publisher,
            "source_url": event.source_url,
            "source_content_hash": event.content_hash,
            "excerpt_sha256": item.excerpt_sha256,
            "excerpt": item.excerpt,
            "language": item.language,
            "rights_basis": item.rights_basis,
            "rights_reference": item.rights_reference,
            "published_at": event.published_at,
            "source_available_at": event.available_at,
            "text_captured_at": item.captured_at,
            "first_usable_at": first,
            "linked_symbols": sorted(event.linked_symbols),
            "split": split,
        }
        draft = IndiaNlpCausalDocument.model_construct(
            **payload, document_fingerprint="0" * 64
        )
        documents.append(IndiaNlpCausalDocument(
            **payload, document_fingerprint=canonical_fingerprint(
                draft.model_dump(mode="json", exclude={"document_fingerprint"})
            )
        ))
    documents.sort(key=lambda row: (row.first_usable_at, row.source_id))
    if len({d.excerpt_sha256 for d in documents}) != len(documents):
        raise ValueError("duplicate or syndicated text cannot cross corpus folds")
    counts = {part: sum(d.split == part for d in documents)
              for part in ("TRAIN", "CALIBRATION", "HOLDOUT")}
    reasons = sorted(
        "INSUFFICIENT_" + part + "_DOCUMENTS"
        for part, count in counts.items()
        if count < frozen.minimum_per_split
    )
    payload = {
        "contract_version": REPORT_VERSION,
        "plan": frozen.model_dump(mode="json"),
        "news_export_fingerprint": canonical_fingerprint(export.model_dump(mode="json")),
        "as_of": as_of,
        "documents": [row.model_dump(mode="json") for row in documents],
        "train_count": counts["TRAIN"],
        "calibration_count": counts["CALIBRATION"],
        "holdout_count": counts["HOLDOUT"],
        "status": "INCONCLUSIVE" if reasons else "SELF_DECLARED_RESEARCH_CORPUS_ONLY",
        "reason_codes": reasons,
        "independent_publication_verification": False,
        "independent_licence_verification": False,
        "independent_timestamp_verification": False,
        "sentiment_model_evaluated": False,
        "market_outcome_linked": False,
        "any_llm_can_place_orders": False,
        "can_authorize_capital": False,
    }
    draft = IndiaNlpCausalCorpus.model_construct(
        **payload, corpus_fingerprint="0" * 64
    )
    return IndiaNlpCausalCorpus(
        **payload, corpus_fingerprint=canonical_fingerprint(
            draft.model_dump(mode="json", exclude={"corpus_fingerprint"})
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build offline licensed-text India NLP corpus; no model inference"
    )
    parser.add_argument("frozen_plan", type=Path)
    parser.add_argument("news_export", type=Path)
    parser.add_argument("licensed_texts", type=Path)
    parser.add_argument("--as-of", type=datetime.fromisoformat, required=True)
    args = parser.parse_args()
    from pydantic import TypeAdapter

    plan = IndiaNlpCorpusSplitPlan.model_validate_json(
        args.frozen_plan.read_text(encoding="utf-8")
    )
    news = NewsResearchExport.model_validate_json(
        args.news_export.read_text(encoding="utf-8")
    )
    texts = TypeAdapter(list[DeclaredNlpTextUse]).validate_json(
        args.licensed_texts.read_text(encoding="utf-8")
    )
    result = build_india_nlp_causal_corpus(
        news, texts, plan, as_of=args.as_of
    )
    print(json.dumps(result.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
