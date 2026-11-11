"""Deterministic research-only coverage/timing conjunction tests."""
from dataclasses import replace

from aitrading_intelligence.next_bar_timing_gate import TimingDecision
from aitrading_intelligence.research_readiness_gate import assess_research_readiness
from aitrading_intelligence.source_coverage_gate import CoverageDecision, CoverageResult


def evidence():
    return (
        CoverageResult(CoverageDecision.RESEARCH_ONLY, "COMPLETE_LICENSED_AS_OF_DECISION"),
        TimingDecision(True, "TIMING_RESEARCH_ONLY_NOT_EXECUTABLE"),
    )


def test_both_gates_pass_without_order_authority():
    result = assess_research_readiness(*evidence())
    assert result.eligible_for_research
    assert result.reason == "RESEARCH_ONLY_BOTH_GATES_PASSED"
    assert not result.execution_authorized


def test_coverage_abstention_blocks_research():
    coverage, timing = evidence()
    result = assess_research_readiness(
        replace(coverage, decision=CoverageDecision.ABSTAIN, reason="INCOMPLETE_COVERAGE"),
        timing,
    )
    assert not result.eligible_for_research
    assert result.reason == "COVERAGE_INCOMPLETE_COVERAGE"


def test_timing_abstention_blocks_research():
    coverage, timing = evidence()
    result = assess_research_readiness(
        coverage, replace(timing, eligible_for_timing_research=False, reason="STALE_AS_OF_QUOTE")
    )
    assert not result.eligible_for_research
    assert result.reason == "TIMING_STALE_AS_OF_QUOTE"


def test_unexpected_execution_authority_fails_closed():
    coverage, timing = evidence()
    decision = assess_research_readiness(replace(coverage, can_authorize_capital=True), timing)
    assert not decision.eligible_for_research
    decision = assess_research_readiness(coverage, replace(timing, execution_authorized=True))
    assert not decision.eligible_for_research


def test_unrecognized_success_reasons_fail_closed():
    coverage, timing = evidence()
    decision = assess_research_readiness(replace(coverage, reason="UNKNOWN"), timing)
    assert not decision.eligible_for_research
    decision = assess_research_readiness(coverage, replace(timing, reason="UNKNOWN"))
    assert not decision.eligible_for_research
