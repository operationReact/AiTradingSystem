"""Synthetic tests for the independent numeric feature research gate."""

from aitrading_intelligence.research_numeric_feature_gate import evaluate_numeric_features


def decide(features, **overrides):
    flags = dict(source_verified=True, coverage_verified=True, asof_verified=True)
    flags.update(overrides)
    return evaluate_numeric_features(features=features, **flags)


def test_finite_values_are_research_only():
    result = decide({"momentum": -0.25, "volume_ratio": 1.5, "zero": 0})
    assert result.eligible
    assert result.reason == "research_eligible_only"
    assert result.can_authorize_capital is False


def test_missing_features_fail_closed():
    assert decide({}).reason == "missing_features"
    assert decide(None).reason == "missing_features"


def test_non_finite_values_fail_closed():
    for value in (float("nan"), float("inf"), -float("inf")):
        result = decide({"momentum": value})
        assert not result.eligible
        assert result.reason == "non_finite_feature"
        assert not result.can_authorize_capital


def test_non_numeric_and_boolean_values_fail_closed():
    for value in (True, False, None, "1.5", complex(1, 2)):
        result = decide({"momentum": value})
        assert not result.eligible
        assert result.reason == "non_numeric_feature"


def test_blank_or_non_string_feature_names_fail_closed():
    for name in ("", "  ", 42):
        assert decide({name: 1.0}).reason == "invalid_feature_name"


def test_upstream_evidence_is_mandatory():
    for flag in ("source_verified", "coverage_verified", "asof_verified"):
        result = decide({"momentum": 1.0}, **{flag: False})
        assert not result.eligible
        assert result.reason == "unverified_upstream_evidence"
        assert result.can_authorize_capital is False


def test_one_bad_value_rejects_entire_batch():
    assert decide({"valid": 1.0, "invalid": float("nan")}).reason == "non_finite_feature"
