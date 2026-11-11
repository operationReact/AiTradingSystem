"""Synthetic, deterministic quote-as-of cross-gate regression tests."""
from dataclasses import replace
from datetime import datetime, timedelta, timezone

from aitrading_intelligence.cross_gate_asof import AsOfDecision
from aitrading_intelligence.next_bar_timing_gate import TimingEvidence
from aitrading_intelligence.quote_asof_consistency import assess_quote_asof_consistency


def inputs():
    t = datetime(2026, 2, 2, 9, 0, tzinfo=timezone.utc)
    return dict(
        decision_at=t + timedelta(minutes=3),
        timing=TimingEvidence(
            signal_bar_closed_at=t,
            research_received_at=t + timedelta(minutes=2),
            proposed_entry_at=t + timedelta(minutes=4),
            quote_observed_at=t + timedelta(minutes=1),
        ),
        cross_gate=AsOfDecision(True, "CROSS_GATE_ASOF_RESEARCH_ONLY"),
    )


def test_valid_quote_is_research_only():
    result = assess_quote_asof_consistency(**inputs())
    assert result.eligible_for_research
    assert not result.execution_authorized


def test_missing_quote_abstains():
    args = inputs()
    args["timing"] = replace(args["timing"], quote_observed_at=None)
    assert not assess_quote_asof_consistency(**args).eligible_for_research


def test_quote_after_receipt_abstains():
    args = inputs()
    args["timing"] = replace(
        args["timing"], quote_observed_at=args["decision_at"]
    )
    assert not assess_quote_asof_consistency(**args).eligible_for_research


def test_naive_quote_abstains():
    args = inputs()
    args["timing"] = replace(
        args["timing"], quote_observed_at=args["decision_at"].replace(tzinfo=None)
    )
    assert not assess_quote_asof_consistency(**args).eligible_for_research


def test_upstream_abstention_and_authority_fail_closed():
    args = inputs()
    args["cross_gate"] = replace(args["cross_gate"], eligible_for_research=False)
    assert not assess_quote_asof_consistency(**args).eligible_for_research
    args = inputs()
    args["cross_gate"] = replace(args["cross_gate"], execution_authorized=True)
    assert not assess_quote_asof_consistency(**args).eligible_for_research
