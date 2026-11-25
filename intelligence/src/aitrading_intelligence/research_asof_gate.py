"""Research-only as-of feature availability gate; never authorizes orders."""

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class AsOfDecision:
    eligible: bool
    reason: str
    can_authorize_capital: bool = False


def evaluate_asof_features(
    *,
    decision_at: datetime,
    feature_available_at: tuple[datetime, ...],
    source_verified: bool,
    coverage_verified: bool,
) -> AsOfDecision:
    """Only permit retrospective research using features available as of decision."""
    def reject(reason: str) -> AsOfDecision:
        return AsOfDecision(False, reason)

    if not source_verified or not coverage_verified:
        return reject("unverified_source_or_coverage")
    if not feature_available_at:
        return reject("missing_features")
    timestamps = (decision_at, *feature_available_at)
    if any(ts.tzinfo is None or ts.utcoffset() is None for ts in timestamps):
        return reject("naive_timestamp")
    if any(available > decision_at for available in feature_available_at):
        return reject("lookahead_feature")
    return AsOfDecision(True, "research_eligible_only")
