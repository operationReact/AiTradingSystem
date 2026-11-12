"""Research-only numeric feature sanity gate; never authorizes orders.

A finite scalar is necessary, not sufficient, for valid research. Provenance,
as-of chronology and coverage must be verified by separate upstream gates.
"""

from dataclasses import dataclass
import math
from numbers import Real
from typing import Mapping


@dataclass(frozen=True)
class NumericFeatureDecision:
    eligible: bool
    reason: str
    can_authorize_capital: bool = False


def evaluate_numeric_features(
    *,
    features: Mapping[str, object],
    source_verified: bool,
    coverage_verified: bool,
    asof_verified: bool,
) -> NumericFeatureDecision:
    """Fail closed on missing, non-numeric or non-finite research inputs."""
    def reject(reason: str) -> NumericFeatureDecision:
        return NumericFeatureDecision(False, reason)

    if not (source_verified and coverage_verified and asof_verified):
        return reject("unverified_upstream_evidence")
    if not isinstance(features, Mapping) or not features:
        return reject("missing_features")
    for name, value in features.items():
        if not isinstance(name, str) or not name.strip():
            return reject("invalid_feature_name")
        # bool is a Real in Python but is not a numeric model observation.
        if isinstance(value, bool) or not isinstance(value, Real):
            return reject("non_numeric_feature")
        try:
            if not math.isfinite(value):
                return reject("non_finite_feature")
        except (TypeError, ValueError, OverflowError):
            return reject("non_numeric_feature")
    return NumericFeatureDecision(True, "research_eligible_only")
