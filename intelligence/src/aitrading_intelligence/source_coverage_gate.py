"""Research-only source coverage gate; never authorizes trading execution."""

from dataclasses import dataclass
from datetime import datetime, timezone
from math import isfinite


@dataclass(frozen=True)
class CoverageDecision:
    eligible: bool
    reason: str
    observed: int
    expected: int


def evaluate_source_coverage(
    *,
    expected_bars: int,
    observed_bars: int,
    min_coverage: float,
    source_verified: bool,
    timestamps: tuple[datetime, ...],
) -> CoverageDecision:
    """Fail closed on missing provenance, gaps, and non-causal timestamps."""
    if not source_verified:
        return CoverageDecision(False, "source_unverified", observed_bars, expected_bars)
    if expected_bars <= 0 or observed_bars < 0 or observed_bars > expected_bars:
        return CoverageDecision(False, "invalid_counts", observed_bars, expected_bars)
    if not isfinite(min_coverage) or not 0.0 <= min_coverage <= 1.0:
        return CoverageDecision(False, "invalid_threshold", observed_bars, expected_bars)
    if len(timestamps) != observed_bars:
        return CoverageDecision(False, "timestamp_count_mismatch", observed_bars, expected_bars)
    if any(ts.tzinfo is None or ts.utcoffset() is None for ts in timestamps):
        return CoverageDecision(False, "naive_timestamp", observed_bars, expected_bars)
    normalized = tuple(ts.astimezone(timezone.utc) for ts in timestamps)
    if any(left >= right for left, right in zip(normalized, normalized[1:])):
        return CoverageDecision(False, "non_monotonic_timestamps", observed_bars, expected_bars)
    if observed_bars / expected_bars < min_coverage:
        return CoverageDecision(False, "insufficient_coverage", observed_bars, expected_bars)
    return CoverageDecision(True, "research_eligible_only", observed_bars, expected_bars)
