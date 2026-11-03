from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fusion import (
    V2_CHAMPION_FUSION_POLICY,
    FusionEvidence,
    FusionFamily,
    FusionRequest,
    fuse_signals,
)
from aitrading_intelligence.regime import REGIME_LABELS, RegimeSnapshot
from aitrading_intelligence.regime_fusion_policy import (
    FUSION_FAMILIES,
    RegimeConditionedManualFusionConfig,
    RegimeConditionedManualFusionPolicy,
)

NOW = datetime(2026, 1, 12, 6, 0, tzinfo=UTC)


def _evidence(family: FusionFamily, score: float, seed: str) -> FusionEvidence:
    return FusionEvidence(
        family=family,
        provider=f"fixture-{family.lower()}",
        version="v1",
        score=score,
        confidence=0.90,
        observed_at=NOW,
        fingerprint=(seed * 64)[:64],
        reasons=["fixture"],
    )


def _regime(
    probabilities: dict[str, float],
    *,
    as_of: datetime = NOW,
    seed: str = "9",
) -> RegimeSnapshot:
    maximum = max(probabilities.values())
    dominant = next(
        label for label in REGIME_LABELS if probabilities[label] == maximum
    )
    return RegimeSnapshot(
        as_of=as_of,
        data_cutoff=as_of - timedelta(minutes=1),
        method="HEURISTIC_BASELINE",
        dominant_regime=dominant,
        probabilities=probabilities,
        confidence=maximum,
        evidence={"benchmark.NIFTY50.return_1d": 0.01},
        input_fingerprint="8" * 64,
        config_fingerprint="7" * 64,
        snapshot_fingerprint=(seed * 64)[:64],
    )


def _request(
    *,
    regime: RegimeSnapshot | None = None,
    max_age_seconds: int = 1800,
) -> FusionRequest:
    return FusionRequest(
        symbol="RELIANCE",
        as_of=NOW,
        max_age_seconds=max_age_seconds,
        deterministic=_evidence("DETERMINISTIC", 0.70, "a"),
        quant=_evidence("QUANT", 0.60, "b"),
        gemini=_evidence("GEMINI", 0.30, "c"),
        regime=regime,
    )


def test_uniform_regime_uncertainty_blends_exactly_to_static_champion() -> None:
    probabilities = {label: 0.25 for label in REGIME_LABELS}
    request = _request(regime=_regime(probabilities))
    policy = RegimeConditionedManualFusionPolicy()

    explanation = policy.explain(request)
    champion = dict(V2_CHAMPION_FUSION_POLICY.configured_weights(request))

    assert explanation.fallback_to_static is False
    assert explanation.reason == "FRESH_REGIME_PROBABILITY_BLEND"
    assert explanation.configured_weights == pytest.approx(champion)
    assert explanation.can_authorize_capital is False


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        (
            "TREND_UP",
            {"DETERMINISTIC": 0.40, "QUANT": 0.40, "GEMINI": 0.20},
        ),
        (
            "TREND_DOWN",
            {"DETERMINISTIC": 0.50, "QUANT": 0.30, "GEMINI": 0.20},
        ),
        (
            "RANGE_LOW_VOL",
            {"DETERMINISTIC": 0.35, "QUANT": 0.45, "GEMINI": 0.20},
        ),
        (
            "STRESS_HIGH_VOL",
            {"DETERMINISTIC": 0.55, "QUANT": 0.25, "GEMINI": 0.20},
        ),
    ],
)
def test_one_hot_regime_probabilities_select_manual_weight_row(
    label: str,
    expected: dict[str, float],
) -> None:
    probabilities = {
        candidate: float(candidate == label) for candidate in REGIME_LABELS
    }
    request = _request(regime=_regime(probabilities))

    weights = RegimeConditionedManualFusionPolicy().configured_weights(request)

    assert weights == pytest.approx(expected)


def test_soft_probabilities_are_reconstructable_from_versioned_matrix() -> None:
    probabilities = {
        "TREND_UP": 0.60,
        "TREND_DOWN": 0.10,
        "RANGE_LOW_VOL": 0.20,
        "STRESS_HIGH_VOL": 0.10,
    }
    request = _request(regime=_regime(probabilities))
    policy = RegimeConditionedManualFusionPolicy()
    explanation = policy.explain(request)

    reconstructed = {
        family: sum(
            probabilities[label] * policy.config.regime_weights[label][family]
            for label in REGIME_LABELS
        )
        for family in FUSION_FAMILIES
    }

    assert explanation.regime_probabilities == probabilities
    assert explanation.configured_weights == pytest.approx(reconstructed)
    assert sum(explanation.configured_weights.values()) == pytest.approx(1.0)
    assert explanation.config_fingerprint == policy.config.fingerprint
    assert request.regime is not None
    assert (
        explanation.source_regime_fingerprint
        == request.regime.snapshot_fingerprint
    )


def test_missing_or_stale_regime_falls_back_exactly_to_static_champion() -> None:
    policy = RegimeConditionedManualFusionPolicy()

    no_regime_request = _request()
    no_regime = policy.explain(no_regime_request)
    no_regime_champion = dict(
        V2_CHAMPION_FUSION_POLICY.configured_weights(no_regime_request)
    )
    assert no_regime.fallback_to_static is True
    assert no_regime.reason == "NO_REGIME_STATIC_FALLBACK"
    assert no_regime.configured_weights == no_regime_champion

    stale_probabilities = {
        "TREND_UP": 1.0,
        "TREND_DOWN": 0.0,
        "RANGE_LOW_VOL": 0.0,
        "STRESS_HIGH_VOL": 0.0,
    }
    stale_request = _request(
        regime=_regime(stale_probabilities, as_of=NOW - timedelta(minutes=10)),
        max_age_seconds=300,
    )
    stale = policy.explain(stale_request)
    stale_champion = dict(
        V2_CHAMPION_FUSION_POLICY.configured_weights(stale_request)
    )

    assert stale.fallback_to_static is True
    assert stale.reason == "STALE_REGIME_STATIC_FALLBACK"
    assert stale.source_regime_age_seconds == 600
    assert stale.configured_weights == stale_champion


def test_manual_challenger_changes_policy_not_execution_authority() -> None:
    probabilities = {
        "TREND_UP": 0.0,
        "TREND_DOWN": 0.0,
        "RANGE_LOW_VOL": 0.0,
        "STRESS_HIGH_VOL": 1.0,
    }
    request = _request(regime=_regime(probabilities))

    champion = fuse_signals(request)
    challenger = fuse_signals(
        request,
        policy=RegimeConditionedManualFusionPolicy(),
    )

    champion_weights = [
        item.configured_weight for item in champion.contributions
    ]
    challenger_weights = [
        item.configured_weight for item in challenger.contributions
    ]
    assert champion_weights == [0.45, 0.35, 0.20]
    assert challenger_weights == [0.55, 0.25, 0.20]
    assert champion.can_authorize_capital is False
    assert challenger.can_authorize_capital is False
    assert champion.status == challenger.status == "CANDIDATE_NOT_EXECUTABLE"


def test_invalid_manual_matrix_fails_closed() -> None:
    invalid = {
        "TREND_UP": {
            "DETERMINISTIC": 0.40,
            "QUANT": 0.40,
            "GEMINI": 0.20,
        },
        "TREND_DOWN": {
            "DETERMINISTIC": 0.50,
            "QUANT": 0.30,
            "GEMINI": 0.20,
        },
        "RANGE_LOW_VOL": {
            "DETERMINISTIC": 0.35,
            "QUANT": 0.45,
            "GEMINI": 0.20,
        },
        "STRESS_HIGH_VOL": {
            "DETERMINISTIC": 0.55,
            "QUANT": 0.30,
            "GEMINI": 0.20,
        },
    }

    with pytest.raises(ValidationError, match="must sum to 1"):
        RegimeConditionedManualFusionConfig(regime_weights=invalid)


def test_matrix_must_center_back_to_static_champion() -> None:
    off_center = {
        "TREND_UP": {
            "DETERMINISTIC": 0.45,
            "QUANT": 0.35,
            "GEMINI": 0.20,
        },
        "TREND_DOWN": {
            "DETERMINISTIC": 0.50,
            "QUANT": 0.30,
            "GEMINI": 0.20,
        },
        "RANGE_LOW_VOL": {
            "DETERMINISTIC": 0.35,
            "QUANT": 0.45,
            "GEMINI": 0.20,
        },
        "STRESS_HIGH_VOL": {
            "DETERMINISTIC": 0.55,
            "QUANT": 0.25,
            "GEMINI": 0.20,
        },
    }

    with pytest.raises(
        ValidationError,
        match="blend back to the static 45/35/20 champion",
    ):
        RegimeConditionedManualFusionConfig(regime_weights=off_center)
