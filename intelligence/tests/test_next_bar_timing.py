"""Synthetic timing tests: no orders, market data, or profitability claims."""
from dataclasses import replace
from datetime import UTC, datetime, timedelta

from aitrading_intelligence.next_bar_timing import assess_next_bar_timing
from aitrading_intelligence.quote_window_order import QuoteWindowDecision


def evidence():
    t = datetime(2026, 2, 2, 9, 0, tzinfo=UTC)
    return dict(
        decision_at=t,
        next_bar_open_at=t + timedelta(minutes=1),
        next_bar_close_at=t + timedelta(minutes=2),
        evaluated_at=t + timedelta(minutes=3),
        quote_window=QuoteWindowDecision(True, "QUOTE_WINDOW_RESEARCH_ONLY"),
    )


def test_completed_prospective_bar_research_only():
    result = assess_next_bar_timing(**evidence())
    assert result.eligible_for_research
    assert not result.execution_authorized


def test_same_bar_and_past_bar_rejected():
    args = evidence()
    for delta in (0, -1):
        args["next_bar_open_at"] = args["decision_at"] + timedelta(minutes=delta)
        assert not assess_next_bar_timing(**args).eligible_for_research


def test_incomplete_or_invalid_interval_rejected():
    args = evidence()
    args["evaluated_at"] = args["next_bar_close_at"] - timedelta(seconds=1)
    assert not assess_next_bar_timing(**args).eligible_for_research
    args = evidence()
    args["next_bar_close_at"] = args["next_bar_open_at"]
    assert not assess_next_bar_timing(**args).eligible_for_research


def test_missing_naive_and_upstream_abstention_rejected():
    args = evidence()
    args["next_bar_open_at"] = None
    assert not assess_next_bar_timing(**args).eligible_for_research
    args = evidence()
    args["decision_at"] = args["decision_at"].replace(tzinfo=None)
    assert not assess_next_bar_timing(**args).eligible_for_research
    args = evidence()
    args["quote_window"] = replace(args["quote_window"], execution_authorized=True)
    assert not assess_next_bar_timing(**args).eligible_for_research
    args = evidence()
    args["quote_window"] = replace(args["quote_window"], eligible_for_research=False)
    assert not assess_next_bar_timing(**args).eligible_for_research
