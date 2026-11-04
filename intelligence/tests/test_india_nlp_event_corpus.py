from __future__ import annotations

from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from pydantic import ValidationError

from aitrading_intelligence.india_nlp_event_corpus import (
    IndiaFinancialEventCorpus,
    IndiaFinancialEventSample,
    build_india_financial_event_sample,
    freeze_india_financial_event_corpus,
)

IST = ZoneInfo("Asia/Kolkata")


def _sample(day: int, split: str, *, event_id: str | None = None):
    decision = datetime(2026, 10, day, 9, 15, tzinfo=IST)
    return build_india_financial_event_sample(
        event_id=event_id or f"synthetic-2026-10-{day}",
        split=split,
        source_kind="SYNTHETIC",
        source_url=f"https://example.org/original-test-event/{day}",
        rights_basis="SYNTHETIC_ORIGINAL",
        headline=f"Synthetic benchmark event number {day} only, no real news",
        published_at=decision - timedelta(minutes=15),
        captured_at=decision - timedelta(minutes=5),
        decision_at=decision,
        market_label_from=decision + timedelta(minutes=5),
        market_label_to=decision + timedelta(minutes=10),
        market_label_available_at=decision + timedelta(minutes=15),
        sentiment_label=(
            "POSITIVE" if day == 26 else "NEGATIVE" if day == 27 else "NEUTRAL"
        ),
        forward_return_bps={26: 10, 27: -8, 29: 0}[day],
    )


def _corpus():
    return freeze_india_financial_event_corpus(
        [_sample(26, "TRAIN"), _sample(27, "CALIBRATION"),
         _sample(29, "INDEPENDENT_HOLDOUT")],
        research_created_at=datetime(2026, 10, 30, 12, tzinfo=UTC),
    )


def test_frozen_india_corpus_is_replayable_research_only() -> None:
    first = _corpus()
    repeated = _corpus()
    assert first == repeated
    assert len(first.samples) == 3
    assert first.corpus_fingerprint == repeated.corpus_fingerprint
    assert first.prosus_finbert_weights_commercial_approved is False
    assert first.third_party_corpus_reuse_independently_verified is False
    assert first.india_market_labels_independently_verified is False
    assert first.gemini_output_allowed_to_issue_orders is False
    assert first.model_promotion_allowed is False
    assert first.can_authorize_capital is False
    assert IndiaFinancialEventCorpus.model_validate_json(
        first.model_dump_json()
    ) == first


def test_model_input_does_not_expose_forward_returns_or_future_labels() -> None:
    train, calibration, holdout = (
        _corpus().inputs_for(split)
        for split in ("TRAIN", "CALIBRATION", "INDEPENDENT_HOLDOUT")
    )
    assert len(train) == len(calibration) == len(holdout) == 1
    fields = set(holdout[0].model_dump())
    assert fields == {
        "event_id", "source_url", "headline", "headline_sha256",
        "published_at", "captured_at", "decision_at", "can_authorize_orders",
    }
    assert holdout[0].can_authorize_orders is False


def test_publication_and_capture_must_precede_model_decision() -> None:
    original = _sample(26, "TRAIN")
    payload = original.model_dump(mode="json")
    payload["published_at"] = (
        original.decision_at + timedelta(minutes=1)
    ).isoformat()
    with pytest.raises(ValidationError, match="must precede decision"):
        IndiaFinancialEventSample.model_validate(payload)
    payload = original.model_dump(mode="json")
    payload["captured_at"] = (
        original.decision_at + timedelta(minutes=1)
    ).isoformat()
    with pytest.raises(ValidationError, match="must precede decision"):
        IndiaFinancialEventSample.model_validate(payload)


def test_future_market_label_cannot_be_used_before_its_availability() -> None:
    original = _sample(27, "CALIBRATION")
    payload = original.model_dump(mode="json")
    payload["market_label_available_at"] = (
        original.market_label_to - timedelta(minutes=1)
    ).isoformat()
    with pytest.raises(ValidationError, match="future market label"):
        IndiaFinancialEventSample.model_validate(payload)

    corpus = _corpus().model_dump(mode="json")
    corpus["research_created_at"] = "2026-10-26T09:00:00Z"
    with pytest.raises(ValidationError, match="created before its future labels"):
        IndiaFinancialEventCorpus.model_validate(corpus)


def test_train_labels_must_be_observable_before_calibration_or_holdout() -> None:
    first = _sample(26, "TRAIN").model_dump(mode="json")
    # Keep per-sample timing valid but extend training result availability
    # past calibration model decision, which would leak future labels.
    future = datetime(2026, 10, 28, 10, 0, tzinfo=IST).isoformat()
    first["market_label_available_at"] = future
    from aitrading_intelligence.fingerprints import canonical_fingerprint

    first["sample_fingerprint"] = canonical_fingerprint({
        key: value for key, value in first.items() if key != "sample_fingerprint"
    })
    row = IndiaFinancialEventSample.model_validate(first)
    with pytest.raises(ValueError, match="partition labels overlap"):
        freeze_india_financial_event_corpus(
            [row, _sample(27, "CALIBRATION"), _sample(29, "INDEPENDENT_HOLDOUT")],
            research_created_at=datetime(2026, 10, 30, 12, tzinfo=UTC),
        )


def test_duplicate_event_or_text_reuse_across_partitions_is_prohibited() -> None:
    with pytest.raises(ValueError, match="event IDs must be unique"):
        freeze_india_financial_event_corpus(
            [_sample(26, "TRAIN", event_id="duplicate-id"),
             _sample(27, "CALIBRATION", event_id="duplicate-id"),
             _sample(29, "INDEPENDENT_HOLDOUT")],
            research_created_at=datetime(2026, 10, 30, tzinfo=UTC),
        )
    first = _sample(27, "CALIBRATION")
    duplicate = _sample(29, "INDEPENDENT_HOLDOUT").model_dump(mode="json")
    duplicate["headline"] = first.headline
    duplicate["headline_sha256"] = first.headline_sha256
    from aitrading_intelligence.fingerprints import canonical_fingerprint

    duplicate["sample_fingerprint"] = canonical_fingerprint({
        key: value for key, value in duplicate.items() if key != "sample_fingerprint"
    })
    repeated = IndiaFinancialEventSample.model_validate(duplicate)
    with pytest.raises(ValueError, match="repeated event text"):
        freeze_india_financial_event_corpus(
            [_sample(26, "TRAIN"), first, repeated],
            research_created_at=datetime(2026, 10, 30, tzinfo=UTC),
        )


def test_official_news_source_needs_documented_permission_not_just_domain() -> None:
    source = _sample(26, "TRAIN").model_dump(mode="json")
    source["source_kind"] = "OFFICIAL"
    with pytest.raises(ValidationError, match="synthetic-only"):
        IndiaFinancialEventSample.model_validate(source)
    source["rights_basis"] = "DOCUMENTED_RESEARCH_REUSE"
    with pytest.raises(ValidationError, match="reuse-rights reference"):
        IndiaFinancialEventSample.model_validate(source)
    source["source_license_reference_url"] = "http://example.org/permission"
    with pytest.raises(ValidationError, match="HTTPS"):
        IndiaFinancialEventSample.model_validate(source)
    source["source_license_reference_url"] = "https://example.org/permission"
    source["source_url"] = "https://rbi.org.in/example-announcement"
    from aitrading_intelligence.fingerprints import canonical_fingerprint

    source["sample_fingerprint"] = canonical_fingerprint({
        key: value for key, value in source.items() if key != "sample_fingerprint"
    })
    accepted = IndiaFinancialEventSample.model_validate(source)
    assert accepted.actual_provider_authorization_verified is False
    assert accepted.can_authorize_orders is False


def test_text_digest_tampering_and_commercial_flags_cannot_be_forged() -> None:
    sample = _sample(26, "TRAIN").model_dump(mode="json")
    sample["headline"] = "Different unregistered headline with future sentiment"
    with pytest.raises(ValidationError, match="digest does not match"):
        IndiaFinancialEventSample.model_validate(sample)
    corpus = _corpus().model_dump(mode="json")
    corpus["prosus_finbert_weights_commercial_approved"] = True
    with pytest.raises(ValidationError):
        IndiaFinancialEventCorpus.model_validate(corpus)
    corpus = _corpus().model_dump(mode="json")
    corpus["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        IndiaFinancialEventCorpus.model_validate(corpus)


def test_reject_corpus_reorder_and_missing_independent_holdout() -> None:
    first = _corpus()
    reordered = first.model_dump(mode="json")
    reordered["samples"] = list(reversed(reordered["samples"]))
    with pytest.raises(ValidationError, match="sorted by decision"):
        IndiaFinancialEventCorpus.model_validate(reordered)
    with pytest.raises(ValueError, match="needs train, calibration and independent"):
        freeze_india_financial_event_corpus(
            [_sample(26, "TRAIN"), _sample(27, "CALIBRATION"),
             _sample(29, "CALIBRATION")],
            research_created_at=datetime(2026, 10, 30, tzinfo=UTC),
        )
