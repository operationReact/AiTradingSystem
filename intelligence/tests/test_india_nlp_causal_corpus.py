from __future__ import annotations

import hashlib
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from aitrading_intelligence.event_source import NewsResearchExport
from aitrading_intelligence.india_nlp_causal_corpus import (
    DeclaredNlpTextUse,
    IndiaNlpCausalCorpus,
    IndiaNlpCorpusSplitPlan,
    build_india_nlp_causal_corpus,
    make_india_nlp_split_plan,
)


def _time(day: int, hour: int = 0, minute: int = 0):
    return datetime(2026, 10, day, hour, minute, tzinfo=UTC)


def _plan():
    return make_india_nlp_split_plan(
        plan_id="india-news-corpus-synthetic",
        registered_at=_time(2, 12),
        train_from=_time(1),
        train_to=_time(2),
        calibration_from=_time(2),
        calibration_to=_time(3),
        holdout_from=_time(3),
        holdout_to=_time(4),
        source_allowlist=["NSE_ANNOUNCEMENTS"],
    )


def _news():
    # The third story was first published during the *training* period but
    # its licensed text was not captured until the future holdout period.
    # Splitting by publication would falsely leak the heldout text into train.
    events = [
        ("corp-001", _time(1, 8), _time(1, 8, 5)),
        ("corp-002", _time(2, 8), _time(2, 8, 5)),
        ("corp-003", _time(1, 7), _time(1, 7, 5)),
    ]
    payload = {
        "contractVersion": "news-events.v1",
        "generatedAt": _time(4, 12).isoformat(),
        "fromInclusive": _time(1).isoformat(),
        "toExclusive": _time(4).isoformat(),
        "dataCutoff": _time(2, 8, 5).isoformat(),
        "providerSources": ["NSE_ANNOUNCEMENTS"],
        "events": [
            {
                "sourceId": identifier,
                "source": "NSE_ANNOUNCEMENTS",
                "publisher": "Synthetic NSE-style fixture",
                "sourceUrl": "https://example.org/market-notice/" + identifier,
                "publishedAt": published.isoformat(),
                "availableAt": available.isoformat(),
                "contentHash": "source-" + identifier,
                "linkedSymbols": ["RELIANCE", "TCS"],
            }
            for identifier, published, available in events
        ],
    }
    return NewsResearchExport.model_validate(payload)


def _rights(source_id: str, day: int, *, excerpt: str | None = None):
    text = excerpt or f"Original synthetic financial research sentence {source_id}."
    return DeclaredNlpTextUse(
        source_id=source_id,
        source_content_hash="source-" + source_id,
        excerpt=text,
        excerpt_sha256=hashlib.sha256(text.encode("utf-8")).hexdigest(),
        language="en",
        captured_at=_time(day, 10),
        rights_basis="OWNED",
        rights_reference="https://example.org/research-text-rights",
        model_research_use_declared_permitted=True,
    )


def _texts():
    return [_rights("corp-001", 1), _rights("corp-002", 2), _rights("corp-003", 3)]


def _build(*, texts=None, plan=None, news=None, as_of=None):
    return build_india_nlp_causal_corpus(
        news or _news(),
        _texts() if texts is None else texts,
        plan or _plan(),
        as_of=as_of or _time(4, 13),
    )


def test_causal_corpus_uses_first_text_capture_not_backdated_publication() -> None:
    corpus = _build()
    assert corpus.status == "SELF_DECLARED_RESEARCH_CORPUS_ONLY"
    assert corpus.reason_codes == []
    assert (corpus.train_count, corpus.calibration_count, corpus.holdout_count) == (
        1, 1, 1
    )
    split = {d.source_id: d.split for d in corpus.documents}
    assert split == {
        "corp-001": "TRAIN", "corp-002": "CALIBRATION", "corp-003": "HOLDOUT"
    }
    holdout = next(d for d in corpus.documents if d.source_id == "corp-003")
    assert holdout.published_at == _time(1, 7)
    assert holdout.first_usable_at == _time(3, 10)
    assert holdout.first_usable_at != holdout.published_at
    assert all(document.linked_symbols == ["RELIANCE", "TCS"]
               for document in corpus.documents)
    assert corpus.independent_publication_verification is False
    assert corpus.independent_licence_verification is False
    assert corpus.independent_timestamp_verification is False
    assert corpus.sentiment_model_evaluated is False
    assert corpus.market_outcome_linked is False
    assert corpus.any_llm_can_place_orders is False
    assert corpus.can_authorize_capital is False
    assert IndiaNlpCausalCorpus.model_validate_json(corpus.model_dump_json()) == corpus
    assert _build() == corpus


def test_corpus_without_holdout_is_inconclusive_not_implicitly_complete() -> None:
    result = _build(texts=_texts()[:-1])
    assert result.status == "INCONCLUSIVE"
    assert result.reason_codes == ["INSUFFICIENT_HOLDOUT_DOCUMENTS"]
    assert result.holdout_count == 0
    assert result.can_authorize_capital is False


def test_rights_and_excerpt_digest_are_required_even_for_public_news_urls() -> None:
    bad = _texts()[0].model_dump(mode="json")
    bad["rights_reference"] = "http://example.org/rights"
    with pytest.raises(ValidationError, match="public HTTPS"):
        DeclaredNlpTextUse.model_validate(bad)
    bad = _texts()[0].model_dump(mode="json")
    bad["model_research_use_declared_permitted"] = False
    with pytest.raises(ValidationError):
        DeclaredNlpTextUse.model_validate(bad)
    bad = _texts()[0].model_dump(mode="json")
    bad["excerpt"] += " Altered after capture."
    with pytest.raises(ValidationError, match="excerpt digest"):
        DeclaredNlpTextUse.model_validate(bad)


def test_news_event_and_text_capture_hashes_must_match() -> None:
    changed = _texts()[0].model_copy(
        update={"source_content_hash": "forged-source-id"}
    )
    with pytest.raises(ValueError, match="text source hash differs"):
        _build(texts=[changed, *_texts()[1:]])
    changed = _texts()[0].model_copy(update={"source_id": "unknown-news-id"})
    with pytest.raises(ValueError, match="no matching source news event"):
        _build(texts=[changed, *_texts()[1:]])


def test_text_cannot_be_claimed_available_before_actual_event_or_after_asof() -> None:
    changed = _texts()[0].model_copy(
        update={"captured_at": _time(1, 7)}
    )
    with pytest.raises(ValueError, match="before source event"):
        _build(texts=[changed, *_texts()[1:]])
    changed = _texts()[2].model_copy(
        update={"captured_at": _time(5)}
    )
    with pytest.raises(ValueError, match="future-captured"):
        _build(texts=[*_texts()[:2], changed])
    with pytest.raises(ValueError, match="before it was generated"):
        _build(as_of=_time(3, 22))


def test_identical_syndicated_text_cannot_be_reused_in_separate_market_folds() -> None:
    reused = _rights("corp-003", 3, excerpt=_texts()[0].excerpt)
    with pytest.raises(ValueError, match="duplicate or syndicated"):
        _build(texts=[*_texts()[:2], reused])


def test_duplicate_licence_declarations_and_undeclared_source_are_rejected() -> None:
    with pytest.raises(ValueError, match="duplicate licensed-text declarations"):
        _build(texts=[*_texts(), _texts()[0]])
    restricted = make_india_nlp_split_plan(
        plan_id="corpus-source-restrictions",
        registered_at=_time(2, 12),
        train_from=_time(1), train_to=_time(2),
        calibration_from=_time(2), calibration_to=_time(3),
        holdout_from=_time(3), holdout_to=_time(4),
        source_allowlist=["OTHER_SOURCE"],
    )
    with pytest.raises(ValueError, match="not frozen in NLP corpus allowlist"):
        _build(plan=restricted)


def test_holdout_plan_must_precede_market_and_folds_must_not_overlap() -> None:
    data = _plan().model_dump(mode="json")
    data["registered_at"] = _time(3, 1).isoformat()
    from aitrading_intelligence.fingerprints import canonical_fingerprint

    data["plan_fingerprint"] = canonical_fingerprint({
        k: v for k, v in data.items() if k != "plan_fingerprint"
    })
    with pytest.raises(ValidationError, match="before holdout begins"):
        IndiaNlpCorpusSplitPlan.model_validate(data)
    with pytest.raises(ValidationError, match="periods must not overlap"):
        make_india_nlp_split_plan(
            plan_id="overlapping-folds",
            registered_at=_time(2, 12),
            train_from=_time(1), train_to=_time(2),
            calibration_from=_time(1), calibration_to=_time(3),
            holdout_from=_time(3), holdout_to=_time(4),
            source_allowlist=["NSE_ANNOUNCEMENTS"],
        )


def test_rehashed_status_or_changed_document_split_still_fails_closed() -> None:
    result = _build()
    forged = result.model_dump(mode="json")
    forged["documents"][0]["split"] = "HOLDOUT"
    with pytest.raises(ValidationError, match="document fingerprint mismatch"):
        IndiaNlpCausalCorpus.model_validate(forged)
    forged = result.model_dump(mode="json")
    forged["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        IndiaNlpCausalCorpus.model_validate(forged)


def test_current_report_is_gated_if_text_delivered_after_holdout_period() -> None:
    late = _texts()[2].model_copy(
        update={"captured_at": _time(4, 4)}
    )
    with pytest.raises(ValueError, match="outside frozen corpus folds"):
        _build(texts=[*_texts()[:2], late])


def test_news_provider_source_urls_must_be_https() -> None:
    p = _news().model_dump(mode="json", by_alias=True)
    p["events"][1]["sourceUrl"] = "http://insecure.example.org/article"
    with pytest.raises(ValueError, match="public HTTPS"):
        _build(news=NewsResearchExport.model_validate(p))
