from datetime import UTC, datetime, timedelta

from aitrading_intelligence.source_coverage_gate import (
    CoverageDecision,
    assess_source_coverage,
)

T = datetime(2026, 11, 10, 9, 20, tzinfo=UTC)


def check(**changes):
    values = dict(
        decision_at=T,
        first_observation_at=T - timedelta(days=5),
        last_observation_at=T - timedelta(minutes=1),
        source_received_at=T,
        source_licensed=True,
        instrument_identified=True,
        expected_bars=5,
        observed_bars=5,
        missing_bars=0,
    )
    values.update(changes)
    return assess_source_coverage(**values)


def test_complete_evidence_is_research_only():
    result = check()
    assert result.decision == CoverageDecision.RESEARCH_ONLY
    assert result.can_authorize_capital is False


def test_rejects_future_or_naive_timestamps():
    assert check(source_received_at=T + timedelta(seconds=1)).reason == "FUTURE_INFORMATION"
    assert check(last_observation_at=T + timedelta(seconds=1)).decision == CoverageDecision.ABSTAIN
    assert check(decision_at=T.replace(tzinfo=None)).reason == "MISSING_OR_NAIVE_TIMESTAMP"
    assert check(first_observation_at=T + timedelta(days=1)).decision == CoverageDecision.ABSTAIN


def test_rejects_unlicensed_or_unknown_source():
    assert check(source_licensed=False).reason == "UNLICENSED_SOURCE"
    assert check(instrument_identified=False).reason == "UNKNOWN_INSTRUMENT"


def test_rejects_missing_inconsistent_or_invalid_coverage():
    assert check(missing_bars=1, observed_bars=4).reason == "INCOMPLETE_COVERAGE"
    assert check(observed_bars=4).reason == "INCONSISTENT_COVERAGE_COUNTS"
    assert check(expected_bars=0).reason == "INVALID_COVERAGE_COUNTS"
    assert check(missing_bars=-1).decision == CoverageDecision.ABSTAIN
