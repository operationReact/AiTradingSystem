"""Boundary and fail-closed quote freshness tests."""
from dataclasses import replace
from datetime import UTC, datetime, timedelta

from aitrading_intelligence.quote_asof_consistency import QuoteAsOfResult
from aitrading_intelligence.quote_age_boundary import assess_quote_age_boundary


def inputs():
    now = datetime(2026, 2, 2, 9, 0, tzinfo=UTC)
    return dict(
        quote_observed_at=now - timedelta(seconds=30),
        decision_at=now,
        maximum_age=timedelta(seconds=30),
        quote_consistency=QuoteAsOfResult(
            True, "QUOTE_ASOF_RESEARCH_ONLY_NOT_A_FILL"
        ),
    )


def test_exact_boundary_research_only():
    result = assess_quote_age_boundary(**inputs())
    assert result.eligible_for_research
    assert not result.execution_authorized


def test_stale_quote_abstains():
    args = inputs()
    args["quote_observed_at"] -= timedelta(microseconds=1)
    assert assess_quote_age_boundary(**args).reason == "STALE_QUOTE"


def test_future_quote_abstains():
    args = inputs()
    args["quote_observed_at"] = args["decision_at"] + timedelta(seconds=1)
    assert assess_quote_age_boundary(**args).reason == "FUTURE_QUOTE"


def test_missing_or_naive_quote_abstains():
    args = inputs()
    args["quote_observed_at"] = None
    assert not assess_quote_age_boundary(**args).eligible_for_research
    args = inputs()
    args["decision_at"] = args["decision_at"].replace(tzinfo=None)
    assert assess_quote_age_boundary(**args).reason == "NAIVE_TIMESTAMP"


def test_invalid_bound_abstains():
    args = inputs()
    args["maximum_age"] = timedelta(seconds=-1)
    assert assess_quote_age_boundary(**args).reason == "NEGATIVE_MAXIMUM_AGE"


def test_upstream_not_ready_or_authority_abstains():
    args = inputs()
    args["quote_consistency"] = replace(args["quote_consistency"], eligible_for_research=False)
    assert not assess_quote_age_boundary(**args).eligible_for_research
    args = inputs()
    args["quote_consistency"] = replace(args["quote_consistency"], execution_authorized=True)
    assert assess_quote_age_boundary(**args).reason == "UNEXPECTED_EXECUTION_AUTHORITY"
