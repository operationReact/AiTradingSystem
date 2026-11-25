from __future__ import annotations

import hashlib
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint

VERSION = "india-financial-event-corpus.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Partition = Literal["TRAIN", "CALIBRATION", "INDEPENDENT_HOLDOUT"]
Sentiment = Literal["NEGATIVE", "NEUTRAL", "POSITIVE"]
RightsBasis = Literal["SYNTHETIC_ORIGINAL", "DOCUMENTED_RESEARCH_REUSE"]


def _require_https(value: str) -> str:
    parts = urlsplit(value)
    if (
        parts.scheme.lower() != "https"
        or not parts.hostname
        or parts.username is not None
        or parts.password is not None
        or parts.fragment
    ):
        raise ValueError("event source references must use absolute HTTPS URLs")
    return value


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("NLP event timestamps must be timezone-aware")
    return value


class IndiaFinancialEventSample(BaseModel):
    """Point-in-time event research record with a separately available future label.

    Source reuse rights are SELF-DECLARED, not inferred from a .gov or exchange
    domain. Synthetic content is allowed for regression tests; real text must
    carry a documented research-reuse reference for downstream review.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    event_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")]
    split: Partition
    source_kind: Literal["OFFICIAL", "LICENSED_PROVIDER", "SYNTHETIC"]
    source_url: Annotated[str, Field(min_length=12, max_length=1024)]
    source_license_reference_url: str | None = None
    rights_basis: RightsBasis
    headline: Annotated[str, Field(min_length=10, max_length=1000)]
    headline_sha256: Hash64
    published_at: datetime
    captured_at: datetime
    decision_at: datetime
    market_label_from: datetime
    market_label_to: datetime
    market_label_available_at: datetime
    sentiment_label: Sentiment
    forward_return_bps: Annotated[float, Field(ge=-10000, le=30000)]
    actual_provider_authorization_verified: Literal[False] = False
    real_market_label_independently_verified: Literal[False] = False
    can_authorize_orders: Literal[False] = False
    sample_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_sample(self) -> IndiaFinancialEventSample:
        _require_https(self.source_url)
        if self.rights_basis == "SYNTHETIC_ORIGINAL":
            if self.source_kind != "SYNTHETIC" or self.source_license_reference_url:
                raise ValueError("synthetic-only content must not claim external rights")
        elif self.source_kind == "SYNTHETIC":
            raise ValueError("synthetic samples cannot claim publisher reuse permission")
        elif not self.source_license_reference_url:
            raise ValueError("real-source research requires a reuse-rights reference")
        if self.source_license_reference_url is not None:
            _require_https(self.source_license_reference_url)
        if hashlib.sha256(self.headline.encode("utf-8")).hexdigest() != (
            self.headline_sha256
        ):
            raise ValueError("headline content digest does not match research sample")
        for value in (
            self.published_at, self.captured_at, self.decision_at,
            self.market_label_from, self.market_label_to,
            self.market_label_available_at,
        ):
            _require_aware(value)
        if not (
            self.published_at <= self.captured_at <= self.decision_at
            < self.market_label_from < self.market_label_to
            <= self.market_label_available_at
        ):
            raise ValueError(
                "published/captured event must precede decision and future market label"
            )
        if self.sample_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"sample_fingerprint"})
        ):
            raise ValueError("financial NLP sample fingerprint mismatch")
        return self


class IndiaFinancialEventModelInput(BaseModel):
    """Only information that was available to a model at its decision time."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    event_id: str
    source_url: str
    headline: str
    headline_sha256: Hash64
    published_at: datetime
    captured_at: datetime
    decision_at: datetime
    can_authorize_orders: Literal[False] = False


class IndiaFinancialEventCorpus(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-financial-event-corpus.v1"] = VERSION
    research_created_at: datetime
    samples: list[IndiaFinancialEventSample] = Field(min_length=3, max_length=1000)
    # A code or repository license does not license third-party MODEL WEIGHTS.
    prosus_finbert_weights_commercial_approved: Literal[False] = False
    third_party_corpus_reuse_independently_verified: Literal[False] = False
    india_market_labels_independently_verified: Literal[False] = False
    gemini_output_allowed_to_issue_orders: Literal[False] = False
    model_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    corpus_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_corpus(self) -> IndiaFinancialEventCorpus:
        _require_aware(self.research_created_at)
        if self.samples != sorted(
            self.samples, key=lambda sample: (sample.decision_at, sample.event_id)
        ):
            raise ValueError("NLP corpus events must be sorted by decision and ID")
        if len({sample.event_id for sample in self.samples}) != len(self.samples):
            raise ValueError("NLP corpus event IDs must be unique")
        if len({sample.headline_sha256 for sample in self.samples}) != len(self.samples):
            raise ValueError("repeated event text cannot cross research partitions")
        groups = {
            group: [item for item in self.samples if item.split == group]
            for group in ("TRAIN", "CALIBRATION", "INDEPENDENT_HOLDOUT")
        }
        if any(not group for group in groups.values()):
            raise ValueError("NLP corpus needs train, calibration and independent holdout")
        for left, right in (
            ("TRAIN", "CALIBRATION"),
            ("CALIBRATION", "INDEPENDENT_HOLDOUT"),
        ):
            if max(item.market_label_available_at for item in groups[left]) >= min(
                item.decision_at for item in groups[right]
            ):
                raise ValueError(
                    "NLP corpus partition labels overlap a later model decision"
                )
        if max(item.market_label_available_at for item in self.samples) > (
            self.research_created_at
        ):
            raise ValueError("event corpus created before its future labels existed")
        if self.corpus_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"corpus_fingerprint"})
        ):
            raise ValueError("financial NLP corpus fingerprint mismatch")
        return self

    def inputs_for(self, split: Partition) -> list[IndiaFinancialEventModelInput]:
        """Cannot leak sentiment outcomes, future returns or rights claims to model."""
        return [
            IndiaFinancialEventModelInput(
                event_id=item.event_id,
                source_url=item.source_url,
                headline=item.headline,
                headline_sha256=item.headline_sha256,
                published_at=item.published_at,
                captured_at=item.captured_at,
                decision_at=item.decision_at,
            )
            for item in self.samples if item.split == split
        ]


def build_india_financial_event_sample(
    *,
    event_id: str,
    split: Partition,
    source_kind: Literal["OFFICIAL", "LICENSED_PROVIDER", "SYNTHETIC"],
    source_url: str,
    rights_basis: RightsBasis,
    source_license_reference_url: str | None = None,
    headline: str,
    published_at: datetime,
    captured_at: datetime,
    decision_at: datetime,
    market_label_from: datetime,
    market_label_to: datetime,
    market_label_available_at: datetime,
    sentiment_label: Sentiment,
    forward_return_bps: float,
) -> IndiaFinancialEventSample:
    payload = {
        "event_id": event_id,
        "split": split,
        "source_kind": source_kind,
        "source_url": source_url,
        "source_license_reference_url": source_license_reference_url,
        "rights_basis": rights_basis,
        "headline": headline,
        "headline_sha256": hashlib.sha256(headline.encode("utf-8")).hexdigest(),
        "published_at": published_at,
        "captured_at": captured_at,
        "decision_at": decision_at,
        "market_label_from": market_label_from,
        "market_label_to": market_label_to,
        "market_label_available_at": market_label_available_at,
        "sentiment_label": sentiment_label,
        "forward_return_bps": forward_return_bps,
        "actual_provider_authorization_verified": False,
        "real_market_label_independently_verified": False,
        "can_authorize_orders": False,
    }
    preliminary = IndiaFinancialEventSample.model_construct(
        **payload, sample_fingerprint="0" * 64
    )
    return IndiaFinancialEventSample(
        **payload,
        sample_fingerprint=canonical_fingerprint(
            preliminary.model_dump(mode="json", exclude={"sample_fingerprint"})
        ),
    )


def freeze_india_financial_event_corpus(
    samples: Sequence[IndiaFinancialEventSample],
    *,
    research_created_at: datetime,
) -> IndiaFinancialEventCorpus:
    ordered = sorted(samples, key=lambda sample: (sample.decision_at, sample.event_id))
    payload = {
        "contract_version": VERSION,
        "research_created_at": research_created_at,
        "samples": [row.model_dump(mode="json") for row in ordered],
        "prosus_finbert_weights_commercial_approved": False,
        "third_party_corpus_reuse_independently_verified": False,
        "india_market_labels_independently_verified": False,
        "gemini_output_allowed_to_issue_orders": False,
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    preliminary = IndiaFinancialEventCorpus.model_construct(
        **payload, corpus_fingerprint="0" * 64
    )
    return IndiaFinancialEventCorpus(
        **payload,
        corpus_fingerprint=canonical_fingerprint(
            preliminary.model_dump(mode="json", exclude={"corpus_fingerprint"})
        ),
    )
