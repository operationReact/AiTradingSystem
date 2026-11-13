"""Additional research-only observation coverage gate; no execution authority."""

from dataclasses import dataclass
from datetime import UTC, datetime
from math import isfinite


@dataclass(frozen=True)
class ObservationCoverageDecision:
    eligible: bool
    reason: str
    observed: int
    expected: int
    can_authorize_capital: bool = False


def evaluate_observation_coverage(
    *,
    expected_bars: int,
    observed_bars: int,
    min_coverage: float,
    source_verified: bool,
    timestamps: tuple[datetime, ...],
) -> ObservationCoverageDecision:
    """Reject unverified, incomplete, or non-monotonic observation evidence."""
    def abstain(reason: str) -> ObservationCoverageDecision:
        return ObservationCoverageDecision(False, reason, observed_bars, expected_bars)

    if not source_verified:
        return abstain("source_unverified")
    if expected_bars <= 0 or observed_bars < 0 or observed_bars > expected_bars:
        return abstain("invalid_counts")
    if not isfinite(min_coverage) or not 0.0 <= min_coverage <= 1.0:
        return abstain("invalid_threshold")
    if len(timestamps) != observed_bars:
        return abstain("timestamp_count_mismatch")
    if any(ts.tzinfo is None or ts.utcoffset() is None for ts in timestamps):
        return abstain("naive_timestamp")
    normalized = tuple(ts.astimezone(UTC) for ts in timestamps)
    if any(left >= right for left, right in zip(normalized, normalized[1:], strict=False)):
        return abstain("non_monotonic_timestamps")
    if observed_bars / expected_bars < min_coverage:
        return abstain("insufficient_coverage")
    return ObservationCoverageDecision(True, "research_eligible_only", observed_bars, expected_bars)
