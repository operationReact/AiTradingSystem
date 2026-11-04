from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from collections.abc import Sequence
from datetime import datetime, timedelta
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .ai_evidence_source import NewsAiEvidenceExport, ResearchAiEvidence
from .event_source import NewsResearchExport, ResearchNewsEvent
from .fingerprints import canonical_fingerprint
from .market_source import MarketResearchExport
from .pytorch_target import ForwardReturnTargetSpec

VERSION = "india-financial-nlp-causal-corpus.v1"
IST = ZoneInfo("Asia/Kolkata")
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
_SOURCE_TAGS = re.compile(r"<[^>]{0,1000}>")
_WHITESPACE = re.compile(r"\s+")


def _java_normalize(value: str, max_length: int) -> str:
    # Mirrors the Java NewsText bounded NFKC/HTML/whitespace normalization
    # for the common content profile. If Unicode case rules diverge, the
    # SHA-256 comparison fails closed rather than assuming a match.
    text = _SOURCE_TAGS.sub(" ", value)
    for src, dst in (
        ("&nbsp;", " "), ("&#160;", " "), ("&amp;", "&"),
        ("&quot;", '"'), ("&#39;", "'"), ("&lt;", "<"), ("&gt;", ">"),
    ):
        text = text.replace(src, dst)
    text = unicodedata.normalize("NFKC", text)
    text = _WHITESPACE.sub(" ", text).strip()
    return text[:max_length].lower()


def java_news_content_hash(title: str, content: str) -> str:
    """Cross-check operator-supplied text with Java NewsText.hash semantics."""
    body = _java_normalize(title, 500) + "\n" + _java_normalize(content, 8000)
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


class LicensedNewsText(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    source_id: Annotated[str, Field(min_length=1, max_length=200)]
    title: Annotated[str, Field(min_length=1, max_length=5000)]
    content: Annotated[str, Field(min_length=1, max_length=20_000)]
    captured_at: datetime
    declared_research_rights_reference: Annotated[
        str, Field(min_length=3, max_length=300)
    ]
    use_scope: Literal["OFFLINE_EVALUATION_ONLY"] = "OFFLINE_EVALUATION_ONLY"
    independently_verified_content_licence: Literal[False] = False

    @field_validator("captured_at")
    @classmethod
    def require_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("licensed news text capture time must be timezone aware")
        return value


class IndiaFinancialNlpCorpusConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-financial-nlp-causal-corpus.v1"] = VERSION
    horizon_bars: Annotated[int, Field(ge=1, le=6)] = 1
    minimum_eligible_cases: Annotated[int, Field(ge=1, le=2000)] = 2
    advisory_provider: Annotated[str, Field(min_length=1, max_length=40)] = "GEMINI"
    advisory_model: Annotated[str, Field(min_length=1, max_length=100)] = (
        "gemini-2.5-flash"
    )
    maximum_corpus_rows: Annotated[int, Field(ge=1, le=2000)] = 500

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class IndiaNlpCausalCase(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    source_id: str
    publisher: str
    source_url: str
    content_hash: Hash64
    declared_research_rights_reference: str
    news_available_at: datetime
    licensed_text_captured_at: datetime
    decision_at: datetime
    label_available_at: datetime
    target_symbol: str
    observed_forward_return: float
    hypothetical_long_after_assumed_cost_return: float
    advisory_analysis_id: str | None
    advisory_evidence_available_at: datetime | None
    advisory_direction_score: float | None
    advisory_confidence: float | None
    advisory_status: Literal["CAUSALLY_AVAILABLE", "MISSING_OR_LATE"]
    text_licence_independently_verified: Literal[False] = False
    original_market_source_independently_verified: Literal[False] = False
    trading_order_authorized: Literal[False] = False
    case_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_case(self) -> IndiaNlpCausalCase:
        if not (
            self.news_available_at <= self.licensed_text_captured_at
            <= self.decision_at < self.label_available_at
        ):
            raise ValueError("news/text/decision/future label chronology is invalid")
        if not all(math.isfinite(value) and abs(value) <= 10 for value in (
            self.observed_forward_return,
            self.hypothetical_long_after_assumed_cost_return,
        )):
            raise ValueError("NLP research return labels must be finite and bounded")
        present = self.advisory_status == "CAUSALLY_AVAILABLE"
        advisory_fields = (
            self.advisory_analysis_id,
            self.advisory_evidence_available_at,
            self.advisory_direction_score,
            self.advisory_confidence,
        )
        if present != all(value is not None for value in advisory_fields):
            raise ValueError("causal advisory must have complete evidence fields")
        if not present and any(value is not None for value in advisory_fields):
            raise ValueError("late AI evidence must not enter decision features")
        if present and (
            self.advisory_evidence_available_at is None
            or not self.news_available_at <= (
                self.advisory_evidence_available_at
            ) <= self.decision_at
        ):
            raise ValueError("AI evidence was not available at the model decision")
        if self.case_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"case_fingerprint"})
        ):
            raise ValueError("NLP causal case fingerprint mismatch")
        return self


class IndiaFinancialNlpCorpusReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-financial-nlp-causal-corpus.v1"] = VERSION
    config_fingerprint: Hash64
    market_export_fingerprint: Hash64
    news_export_fingerprint: Hash64
    ai_export_fingerprint: Hash64 | None
    target_spec_fingerprint: Hash64
    licensed_text_manifest_fingerprint: Hash64
    cases: list[IndiaNlpCausalCase] = Field(max_length=2000)
    excluded: dict[str, str] = Field(max_length=2000)
    status: Literal["CAUSAL_RESEARCH_CORPUS_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    independent_news_licensing_verified: Literal[False] = False
    independent_market_source_verified: Literal[False] = False
    prospectively_frozen_holdout_verified: Literal[False] = False
    financial_nlp_model_benchmark_completed: Literal[False] = False
    order_execution_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_report(self) -> IndiaFinancialNlpCorpusReport:
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("NLP research reason codes must be sorted and unique")
        if self.status == "INCONCLUSIVE" and not self.reason_codes:
            raise ValueError("inconclusive NLP corpus needs a reason")
        if self.status == "CAUSAL_RESEARCH_CORPUS_ONLY" and (
            self.reason_codes or not self.cases
        ):
            raise ValueError("NLP research corpus cannot claim empty success")
        ids = [case.source_id for case in self.cases]
        if len(ids) != len(set(ids)) or ids != sorted(ids):
            raise ValueError("NLP research source IDs must be sorted and unique")
        if set(ids) & set(self.excluded):
            raise ValueError("NLP excluded source cannot be evaluated")
        if list(self.excluded) != sorted(self.excluded):
            raise ValueError("NLP exclusion ledger must be sorted")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("NLP corpus report fingerprint mismatch")
        return self


def build_india_financial_nlp_corpus(
    news_export: NewsResearchExport,
    ai_export: NewsAiEvidenceExport | None,
    market_export: MarketResearchExport,
    target: ForwardReturnTargetSpec,
    licensed_text: Sequence[LicensedNewsText],
    *,
    config: IndiaFinancialNlpCorpusConfig | None = None,
) -> IndiaFinancialNlpCorpusReport:
    """Build fixed-time single-instrument research cases without any model call.

    Never include late Gemini evidence in features; never silently discard
    missing licensed text or post-close market labels. No trade is executed.
    """
    policy = config or IndiaFinancialNlpCorpusConfig()
    news = NewsResearchExport.model_validate(news_export.model_dump(mode="python"))
    ai = (
        NewsAiEvidenceExport.model_validate(ai_export.model_dump(mode="python"))
        if ai_export is not None else None
    )
    market = MarketResearchExport.model_validate(market_export.model_dump(mode="python"))
    objective = ForwardReturnTargetSpec.model_validate(target.model_dump(mode="python"))
    if objective.horizon_bars != policy.horizon_bars:
        raise ValueError("NLP corpus horizon does not match target specification")
    if market.exchange not in ("NSE", "BSE") or market.interval != "FIVE_MINUTE":
        raise ValueError("NLP research needs NSE/BSE five-minute cash-equity candles")
    if any(bar.fetched_at < bar.available_at for bar in market.candles):
        raise ValueError("NLP market bar was fetched before it was available")
    allowed_symbol = market.trading_symbol.removesuffix("-EQ")
    texts = [LicensedNewsText.model_validate(item.model_dump(mode="python"))
             for item in licensed_text]
    if len(texts) != len({item.source_id for item in texts}):
        raise ValueError("duplicate licensed article source IDs are forbidden")
    texts_by_id = {item.source_id: item for item in texts}
    articles = {item.source_id: item for item in news.events}
    if not set(texts_by_id).issubset(articles):
        raise ValueError("licensed text is not present in authoritative news export")

    evidence_by_source: dict[str, list[ResearchAiEvidence]] = {}
    if ai is not None:
        for item in ai.evidence:
            if item.source_id not in articles:
                raise ValueError("AI advisory refers to an unknown news source ID")
            article = articles[item.source_id]
            if item.source_available_at != article.available_at:
                raise ValueError("AI source availability differs from the original news")
            if not set(item.impacted_symbols).issubset(article.linked_symbols):
                raise ValueError("AI advisory includes symbols absent from source news")
            if item.provider == policy.advisory_provider and item.model == policy.advisory_model:
                evidence_by_source.setdefault(item.source_id, []).append(item)
        if any(len(items) > 1 for items in evidence_by_source.values()):
            raise ValueError("multiple advisory attempts require explicit trial governance")
    bars = sorted(market.candles, key=lambda bar: bar.available_at)
    if any(right.available_at <= left.available_at
           for left, right in zip(bars[:-1], bars[1:], strict=True)):
        raise ValueError("NLP research market decision bars must be strictly ordered")
    cases: list[IndiaNlpCausalCase] = []
    rejected: dict[str, str] = {}
    for event in sorted(news.events, key=lambda item: item.source_id):
        if allowed_symbol not in event.linked_symbols and (
            market.trading_symbol not in event.linked_symbols
        ):
            rejected[event.source_id] = "OTHER_INSTRUMENT"
            continue
        text = texts_by_id.get(event.source_id)
        if text is None:
            rejected[event.source_id] = "NO_DECLARED_RESEARCH_TEXT_RIGHTS"
            continue
        if text.captured_at < event.available_at:
            raise ValueError("licensed article text predates its authoritative availability")
        if java_news_content_hash(text.title, text.content) != event.content_hash:
            raise ValueError("licensed article text does not match Java source contentHash")
        start = max(event.available_at, text.captured_at)
        position = next(
            (index for index, bar in enumerate(bars)
             if bar.available_at >= start), None
        )
        if position is None or position + policy.horizon_bars >= len(bars):
            rejected[event.source_id] = "NO_FUTURE_LABEL_AFTER_CAUSAL_DECISION"
            continue
        decision, future = bars[position], bars[position + policy.horizon_bars]
        if (
            future.available_at.astimezone(IST).date()
            != decision.available_at.astimezone(IST).date()
            or any(
                right.available_at - left.available_at != timedelta(minutes=5)
                for left, right in zip(
                    bars[position:position + policy.horizon_bars],
                    bars[position + 1:position + policy.horizon_bars + 1],
                    strict=True,
                )
            )
        ):
            rejected[event.source_id] = "SESSION_BOUNDARY_OR_MISSING_CANDLES"
            continue
        advisory = (
            evidence_by_source.get(event.source_id, [None])[0]
        )
        is_ready = advisory is not None and (
            advisory.evidence_available_at <= decision.available_at
        )
        forward = future.close / decision.close - 1.0
        payload = {
            "source_id": event.source_id,
            "publisher": event.publisher,
            "source_url": event.source_url,
            "content_hash": event.content_hash,
            "declared_research_rights_reference": (
                text.declared_research_rights_reference
            ),
            "news_available_at": event.available_at,
            "licensed_text_captured_at": text.captured_at,
            "decision_at": decision.available_at,
            "label_available_at": future.available_at,
            "target_symbol": market.trading_symbol,
            "observed_forward_return": forward,
            "hypothetical_long_after_assumed_cost_return": (
                forward - objective.round_trip_cost_bps / 10_000
            ),
            "advisory_analysis_id": advisory.analysis_id if is_ready else None,
            "advisory_evidence_available_at": (
                advisory.evidence_available_at if is_ready else None
            ),
            "advisory_direction_score": (
                advisory.direction_score if is_ready else None
            ),
            "advisory_confidence": advisory.confidence if is_ready else None,
            "advisory_status": (
                "CAUSALLY_AVAILABLE" if is_ready else "MISSING_OR_LATE"
            ),
            "text_licence_independently_verified": False,
            "original_market_source_independently_verified": False,
            "trading_order_authorized": False,
        }
        # Normalize datetimes via Pydantic JSON before hashing.
        tmp = IndiaNlpCausalCase.model_construct(
            **payload, case_fingerprint="0" * 64,
        )
        cases.append(IndiaNlpCausalCase(
            **payload, case_fingerprint=canonical_fingerprint(
                tmp.model_dump(mode="json", exclude={"case_fingerprint"})
            ),
        ))
    cases.sort(key=lambda item: item.source_id)
    reasons = [] if len(cases) >= policy.minimum_eligible_cases else [
        "INSUFFICIENT_CAUSAL_LICENCED_NEWS_CASES"
    ]
    payload = {
        "contract_version": VERSION,
        "config_fingerprint": policy.fingerprint,
        "market_export_fingerprint": canonical_fingerprint(
            market.model_dump(mode="json")
        ),
        "news_export_fingerprint": canonical_fingerprint(
            news.model_dump(mode="json")
        ),
        "ai_export_fingerprint": (
            canonical_fingerprint(ai.model_dump(mode="json"))
            if ai is not None else None
        ),
        "target_spec_fingerprint": objective.fingerprint,
        "licensed_text_manifest_fingerprint": canonical_fingerprint([
            {"source_id": text.source_id,
             "content_hash": java_news_content_hash(text.title, text.content),
             "captured_at": text.captured_at.isoformat(),
             "declared_research_rights_reference": (
                 text.declared_research_rights_reference
             )}
            for text in sorted(texts, key=lambda item: item.source_id)
        ]),
        "cases": [case.model_dump(mode="json") for case in cases],
        "excluded": dict(sorted(rejected.items())),
        "status": "INCONCLUSIVE" if reasons else "CAUSAL_RESEARCH_CORPUS_ONLY",
        "reason_codes": reasons,
        "independent_news_licensing_verified": False,
        "independent_market_source_verified": False,
        "prospectively_frozen_holdout_verified": False,
        "financial_nlp_model_benchmark_completed": False,
        "order_execution_allowed": False,
        "can_authorize_capital": False,
    }
    return IndiaFinancialNlpCorpusReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )
