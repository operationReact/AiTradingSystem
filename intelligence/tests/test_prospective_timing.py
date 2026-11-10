from datetime import UTC, datetime, timedelta

from aitrading_intelligence.prospective_timing import (
    TimingStatus,
    assess_prospective_next_bar,
)

T = datetime(2026, 11, 10, 9, 20, tzinfo=UTC)


def check(**changes):
    inputs = dict(
        decision_at=T,
        next_bar_open_at=T + timedelta(minutes=5),
        source_published_at=T - timedelta(minutes=2),
        source_received_at=T - timedelta(minutes=1),
        source_licensed=True,
        coverage_complete=True,
    )
    inputs.update(changes)
    return assess_prospective_next_bar(**inputs)


def test_valid_timing_is_still_research_only():
    result = check()
    assert result.status == TimingStatus.ELIGIBLE_RESEARCH_ONLY
    assert result.can_authorize_capital is False


def test_missing_or_naive_times_abstain():
    assert check(next_bar_open_at=None).reason == "MISSING_OR_NAIVE_TIMESTAMP"
    assert check(source_received_at=T.replace(tzinfo=None)).status == TimingStatus.ABSTAIN


def test_custody_and_coverage_abstain():
    assert check(source_licensed=False).reason == "SOURCE_RIGHTS_UNVERIFIED"
    assert check(coverage_complete=False).reason == "COVERAGE_INCOMPLETE"


def test_late_evidence_or_nonprospective_bar_abstains():
    assert (check(source_received_at=T + timedelta(seconds=1)).reason
            == "EVIDENCE_NOT_AVAILABLE_AT_DECISION")
    assert (check(source_published_at=T + timedelta(seconds=1),
                  source_received_at=T + timedelta(seconds=2)).status
            == TimingStatus.ABSTAIN)
    assert check(next_bar_open_at=T).reason == "NEXT_BAR_NOT_PROSPECTIVE"


def test_inconsistent_publication_clock_abstains():
    assert (check(source_published_at=T,
                  source_received_at=T - timedelta(seconds=1)).reason
            == "SOURCE_CLOCK_INCONSISTENT")
