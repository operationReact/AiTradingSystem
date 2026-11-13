"""Deterministic receipt chronology tests, with no market-data access."""
from dataclasses import replace
from datetime import UTC, datetime, timedelta

from aitrading_intelligence.source_coverage_gate import CoverageDecision, CoverageResult
from aitrading_intelligence.source_receipt_order import assess_source_receipt_order


def evidence():
    t = datetime(2026, 2, 2, 9, 0, tzinfo=UTC)
    return dict(
        first_observation_at=t,
        last_observation_at=t + timedelta(minutes=1),
        source_received_at=t + timedelta(minutes=2),
        decision_at=t + timedelta(minutes=3),
        coverage=CoverageResult(
            CoverageDecision.RESEARCH_ONLY, "COMPLETE_LICENSED_AS_OF_DECISION"
        ),
    )


def test_received_observations_are_research_only():
    result = assess_source_receipt_order(**evidence())
    assert result.eligible_for_research
    assert not result.execution_authorized


def test_observation_after_receipt_abstains():
    args = evidence()
    args["last_observation_at"] = args["source_received_at"] + timedelta(seconds=1)
    assert not assess_source_receipt_order(**args).eligible_for_research


def test_source_after_decision_abstains():
    args = evidence()
    args["source_received_at"] = args["decision_at"] + timedelta(seconds=1)
    assert not assess_source_receipt_order(**args).eligible_for_research


def test_reversed_window_abstains():
    args = evidence()
    args["first_observation_at"] = args["last_observation_at"] + timedelta(seconds=1)
    assert not assess_source_receipt_order(**args).eligible_for_research


def test_naive_timestamp_abstains():
    args = evidence()
    args["source_received_at"] = args["source_received_at"].replace(tzinfo=None)
    assert not assess_source_receipt_order(**args).eligible_for_research


def test_unrecognized_coverage_or_authority_abstains():
    args = evidence()
    args["coverage"] = replace(args["coverage"], reason="UNKNOWN")
    assert not assess_source_receipt_order(**args).eligible_for_research
    args = evidence()
    args["coverage"] = replace(args["coverage"], can_authorize_capital=True)
    assert not assess_source_receipt_order(**args).eligible_for_research
