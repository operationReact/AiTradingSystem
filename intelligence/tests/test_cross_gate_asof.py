"""Deterministic cross-gate as-of chronology checks."""
from dataclasses import replace
from datetime import UTC, datetime, timedelta

from aitrading_intelligence.cross_gate_asof import assess_cross_gate_asof
from aitrading_intelligence.next_bar_timing_gate import TimingEvidence
from aitrading_intelligence.research_readiness_gate import ResearchReadiness
from aitrading_intelligence.source_coverage_gate import CoverageDecision, CoverageResult


def snapshot():
    t = datetime(2026, 1, 5, 10, 0, tzinfo=UTC)
    return dict(
        decision_at=t + timedelta(minutes=3),
        source_received_at=t + timedelta(minutes=1),
        timing=TimingEvidence(
            signal_bar_closed_at=t,
            research_received_at=t + timedelta(minutes=2),
            proposed_entry_at=t + timedelta(minutes=4),
            quote_observed_at=t,
        ),
        coverage=CoverageResult(
            CoverageDecision.RESEARCH_ONLY, "COMPLETE_LICENSED_AS_OF_DECISION"
        ),
        readiness=ResearchReadiness(True, "RESEARCH_ONLY_BOTH_GATES_PASSED"),
    )


def test_consistent_snapshot_is_research_only():
    result = assess_cross_gate_asof(**snapshot())
    assert result.eligible_for_research
    assert not result.execution_authorized


def test_future_source_is_rejected():
    args = snapshot()
    args["source_received_at"] = args["decision_at"] + timedelta(seconds=1)
    assert not assess_cross_gate_asof(**args).eligible_for_research


def test_timing_receipt_before_source_is_rejected():
    args = snapshot()
    args["timing"] = replace(
        args["timing"],
        research_received_at=args["source_received_at"] - timedelta(seconds=1),
    )
    assert not assess_cross_gate_asof(**args).eligible_for_research


def test_entry_before_decision_is_rejected():
    args = snapshot()
    args["timing"] = replace(args["timing"], proposed_entry_at=args["decision_at"])
    assert not assess_cross_gate_asof(**args).eligible_for_research


def test_naive_timestamp_is_rejected():
    args = snapshot()
    args["decision_at"] = args["decision_at"].replace(tzinfo=None)
    assert not assess_cross_gate_asof(**args).eligible_for_research


def test_upstream_abstention_or_authority_is_rejected():
    args = snapshot()
    args["readiness"] = replace(args["readiness"], eligible_for_research=False)
    assert not assess_cross_gate_asof(**args).eligible_for_research
    args = snapshot()
    args["coverage"] = replace(args["coverage"], can_authorize_capital=True)
    assert not assess_cross_gate_asof(**args).eligible_for_research
