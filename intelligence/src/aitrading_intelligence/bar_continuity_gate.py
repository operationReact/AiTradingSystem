"""Research-only bar continuity gate. Never authorizes capital or orders."""

from dataclasses import dataclass
from datetime import datetime, timedelta


@dataclass(frozen=True)
class BarContinuityDecision:
    eligible: bool
    reason: str
    can_authorize_capital: bool = False


def evaluate_bar_continuity(
    *,
    timestamps: tuple[datetime, ...],
    expected_interval: timedelta,
    source_verified: bool,
) -> BarContinuityDecision:
    """Require exact, ordered, timezone-aware cadence for observed bars."""
    def reject(reason: str) -> BarContinuityDecision:
        return BarContinuityDecision(False, reason)

    if not source_verified:
        return reject("source_unverified")
    if expected_interval <= timedelta(0):
        return reject("invalid_interval")
    if len(timestamps) < 2:
        return reject("insufficient_observations")
    if any(ts.tzinfo is None or ts.utcoffset() is None for ts in timestamps):
        return reject("naive_timestamp")
    for previous, current in zip(timestamps, timestamps[1:], strict=False):
        elapsed = current - previous
        if elapsed <= timedelta(0):
            return reject("non_monotonic_timestamps")
        if elapsed != expected_interval:
            return reject("missing_or_irregular_bar")
    return BarContinuityDecision(True, "research_eligible_only")
