"""Synthetic offset-aware quote freshness regression cases (research only)."""

from datetime import UTC, datetime, timedelta, timezone

from aitrading_intelligence.quote_age_boundary import assess_quote_age_boundary
from aitrading_intelligence.quote_asof_consistency import QuoteAsOfResult

RESEARCH_ONLY = QuoteAsOfResult(True, "QUOTE_ASOF_RESEARCH_ONLY_NOT_A_FILL")
IST = timezone(timedelta(hours=5, minutes=30))


def assess(observed_at, decision_at, maximum_age):
    return assess_quote_age_boundary(
        quote_observed_at=observed_at,
        decision_at=decision_at,
        maximum_age=maximum_age,
        quote_consistency=RESEARCH_ONLY,
    )


def test_same_instant_different_offsets_with_zero_age_limit():
    observed_at = datetime(2026, 11, 13, 9, 15, tzinfo=IST)
    decision_at = datetime(2026, 11, 13, 3, 45, tzinfo=UTC)
    result = assess(observed_at, decision_at, timedelta(0))
    assert result.eligible_for_research
    assert result.reason == "FRESH_QUOTE_RESEARCH_ONLY_NOT_A_FILL"
    assert not result.execution_authorized


def test_cross_midnight_offset_age_exactly_at_limit():
    observed_at = datetime(2026, 11, 12, 23, 59, 30, tzinfo=UTC)
    decision_at = datetime(2026, 11, 13, 5, 30, tzinfo=IST)
    result = assess(observed_at, decision_at, timedelta(seconds=30))
    assert result.eligible_for_research
    assert not result.execution_authorized


def test_future_quote_in_different_timezone_abstains():
    observed_at = datetime(2026, 11, 13, 9, 15, 1, tzinfo=IST)
    decision_at = datetime(2026, 11, 13, 3, 45, tzinfo=UTC)
    result = assess(observed_at, decision_at, timedelta(seconds=30))
    assert result.reason == "FUTURE_QUOTE"
    assert not result.eligible_for_research
    assert not result.execution_authorized


def test_stale_quote_across_timezone_boundary_abstains():
    observed_at = datetime(2026, 11, 13, 9, 14, 29, tzinfo=IST)
    decision_at = datetime(2026, 11, 13, 3, 45, tzinfo=UTC)
    result = assess(observed_at, decision_at, timedelta(seconds=30))
    assert result.reason == "STALE_QUOTE"
    assert not result.eligible_for_research
    assert not result.execution_authorized
