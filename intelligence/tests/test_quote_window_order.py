"""Synthetic tests for quote/observation window chronology."""
from dataclasses import replace
from datetime import UTC, datetime, timedelta

from aitrading_intelligence.quote_asof_consistency import QuoteAsOfResult
from aitrading_intelligence.quote_window_order import assess_quote_window_order
from aitrading_intelligence.source_coverage_gate import CoverageDecision, CoverageResult


def evidence():
    t = datetime(2026, 2, 2, 9, 0, tzinfo=UTC)
    return dict(
        last_observation_at=t + timedelta(minutes=1),
        quote_observed_at=t + timedelta(minutes=2),
        decision_at=t + timedelta(minutes=3),
        coverage=CoverageResult(
            CoverageDecision.RESEARCH_ONLY, "COMPLETE_LICENSED_AS_OF_DECISION"
        ),
        quote_asof=QuoteAsOfResult(True, "QUOTE_ASOF_RESEARCH_ONLY_NOT_A_FILL"),
    )


def test_valid_quote_window_is_research_only():
    result = assess_quote_window_order(**evidence())
    assert result.eligible_for_research
    assert not result.execution_authorized


def test_quote_preceding_last_observation_abstains():
    args = evidence()
    args["quote_observed_at"] = args["last_observation_at"] - timedelta(seconds=1)
    assert not assess_quote_window_order(**args).eligible_for_research


def test_future_quote_abstains():
    args = evidence()
    args["quote_observed_at"] = args["decision_at"] + timedelta(seconds=1)
    assert not assess_quote_window_order(**args).eligible_for_research


def test_missing_or_naive_quote_abstains():
    args = evidence()
    args["quote_observed_at"] = None
    assert not assess_quote_window_order(**args).eligible_for_research
    args = evidence()
    args["quote_observed_at"] = args["decision_at"].replace(tzinfo=None)
    assert not assess_quote_window_order(**args).eligible_for_research


def test_upstream_abstention_or_authority_abstains():
    args = evidence()
    args["quote_asof"] = replace(args["quote_asof"], eligible_for_research=False)
    assert not assess_quote_window_order(**args).eligible_for_research
    args = evidence()
    args["coverage"] = replace(args["coverage"], can_authorize_capital=True)
    assert not assess_quote_window_order(**args).eligible_for_research
