from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.adaptive_fusion import (
    STATIC_CHAMPION_WEIGHTS,
    AdaptiveFusionDecisionV1,
    AdaptiveFusionPolicy,
    build_adaptive_fusion_decision,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion import (
    V2_CHAMPION_FUSION_POLICY,
    FusionEvidence,
    FusionRequest,
    fuse_signals,
)
from aitrading_intelligence.fusion_quality_multipliers import (
    FusionExpertQualityMultiplierV1,
)
from aitrading_intelligence.regime import RegimeSnapshot

NOW = datetime(2026, 1, 12, 6, 0, tzinfo=UTC)
HASH_A = "a" * 64
HASH_B = "b" * 64
HASH_C = "c" * 64


def _evidence(family: str, score: float, seed: str) -> FusionEvidence:
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


def _regime() -> RegimeSnapshot:
    return RegimeSnapshot(
        as_of=NOW,
        data_cutoff=NOW - timedelta(minutes=1),
        method="HEURISTIC_BASELINE",
        dominant_regime="TREND_UP",
        probabilities={
            "TREND_UP": 1.0,
            "TREND_DOWN": 0.0,
            "RANGE_LOW_VOL": 0.0,
            "STRESS_HIGH_VOL": 0.0,
        },
        confidence=1.0,
        evidence={"benchmark.NIFTY50.return_1d": 0.01},
        input_fingerprint=HASH_A,
        config_fingerprint=HASH_B,
        snapshot_fingerprint=HASH_C,
    )


def _request(*, quant_score: float = 0.70) -> FusionRequest:
    return FusionRequest(
        symbol="RELIANCE",
        as_of=NOW,
        deterministic=_evidence("DETERMINISTIC", 0.80, "1"),
        quant=_evidence("QUANT", quant_score, "2"),
        gemini=_evidence("GEMINI", 0.60, "3"),
        regime=_regime(),
    )


def _quality(
    family: str,
    multiplier: float,
    *,
    evaluated_at: datetime = NOW,
    seed: str = "d",
) -> FusionExpertQualityMultiplierV1:
    performance = multiplier if multiplier >= 1.0 else 1.0
    calibration = multiplier if multiplier < 1.0 else 1.0
    raw = calibration * performance
    payload = {
        "contract_version": "fusion-quality-multiplier.v1",
        "family": family,
        "evaluated_at": evaluated_at.isoformat(),
        "calibration_quality_multiplier": calibration,
        "freshness_multiplier": 1.0,
        "performance_multiplier": performance,
        "raw_combined_multiplier": raw,
        "minimum_combined_multiplier": 0.0,
        "maximum_combined_multiplier": 1.20,
        "combined_multiplier": raw,
        "calibration_decision_fingerprint": None,
        "shadow_performance_evidence_fingerprint": None,
        "reason_codes": ["FIXTURE_RECONSTRUCTABLE_QUALITY"],
        "config_fingerprint": (seed * 64)[:64],
        "status": "RESEARCH_QUALITY_EVIDENCE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return FusionExpertQualityMultiplierV1(
        **payload,
        multiplier_fingerprint=canonical_fingerprint(payload),
    )


def test_static_champion_anchor_matches_v2_policy() -> None:
    request = _request()
    actual = dict(V2_CHAMPION_FUSION_POLICY.configured_weights(request))

    assert STATIC_CHAMPION_WEIGHTS == actual


def test_missing_quality_evidence_is_neutral_on_regime_base_weights() -> None:
    decision = build_adaptive_fusion_decision(_request(), [])

    assert decision.base_weights == pytest.approx(
        {"DETERMINISTIC": 0.40, "QUANT": 0.40, "GEMINI": 0.20}
    )
    assert decision.quality_multipliers == {
        "DETERMINISTIC": 1.0,
        "QUANT": 1.0,
        "GEMINI": 1.0,
    }
    assert decision.effective_weights == pytest.approx(decision.base_weights)
    assert decision.source_quality_multiplier_fingerprints == {}
    assert decision.fallback_to_static is False
    assert decision.can_authorize_capital is False


def test_quality_multipliers_are_normalized_and_reconstructable() -> None:
    request = _request()
    deterministic_quality = _quality("DETERMINISTIC", 0.50)

    decision = build_adaptive_fusion_decision(
        request,
        [deterministic_quality],
    )

    assert decision.unnormalized_weights == pytest.approx(
        {"DETERMINISTIC": 0.20, "QUANT": 0.40, "GEMINI": 0.20}
    )
    assert decision.effective_weights == pytest.approx(
        {"DETERMINISTIC": 0.25, "QUANT": 0.50, "GEMINI": 0.25}
    )
    assert (
        decision.source_quality_multiplier_fingerprints["DETERMINISTIC"]
        == deterministic_quality.multiplier_fingerprint
    )


def test_total_quality_collapse_falls_back_exactly_to_static_champion() -> None:
    decision = build_adaptive_fusion_decision(
        _request(),
        [
            _quality("DETERMINISTIC", 0.0, seed="4"),
            _quality("QUANT", 0.0, seed="5"),
            _quality("GEMINI", 0.0, seed="6"),
        ],
    )

    assert decision.fallback_to_static is True
    assert decision.fallback_reason == "QUALITY_COLLAPSE_STATIC_FALLBACK"
    assert decision.effective_weights == STATIC_CHAMPION_WEIGHTS
    assert sum(decision.unnormalized_weights.values()) == 0.0


def test_duplicate_or_future_quality_evidence_fails_closed() -> None:
    request = _request()
    quant_quality = _quality("QUANT", 0.90)

    with pytest.raises(ValueError, match="unique by family"):
        build_adaptive_fusion_decision(
            request,
            [quant_quality, quant_quality],
        )

    future_quality = _quality(
        "QUANT",
        0.90,
        evaluated_at=NOW + timedelta(seconds=1),
    )
    with pytest.raises(ValueError, match="cannot postdate fusion request"):
        build_adaptive_fusion_decision(request, [future_quality])


def test_tampered_quality_fingerprint_fails_closed() -> None:
    tampered = _quality("QUANT", 0.90).model_copy(
        update={"multiplier_fingerprint": "0" * 64}
    )

    with pytest.raises(ValueError, match="quality multiplier fingerprint mismatch"):
        build_adaptive_fusion_decision(_request(), [tampered])


def test_adaptive_policy_applies_exact_decision_weights_to_research_fusion() -> None:
    request = _request()
    decision = build_adaptive_fusion_decision(
        request,
        [_quality("DETERMINISTIC", 0.50)],
    )

    result = fuse_signals(request, policy=AdaptiveFusionPolicy(decision))

    configured = {
        contribution.family: contribution.configured_weight
        for contribution in result.contributions
    }
    assert configured == pytest.approx(decision.effective_weights)
    assert result.status == "CANDIDATE_NOT_EXECUTABLE"
    assert result.can_authorize_capital is False


def test_adaptive_policy_is_bound_to_full_fusion_request_fingerprint() -> None:
    request = _request()
    decision = build_adaptive_fusion_decision(request, [])
    policy = AdaptiveFusionPolicy(decision)
    changed_request = _request(quant_score=0.10)

    assert changed_request.symbol == request.symbol
    assert changed_request.as_of == request.as_of
    with pytest.raises(ValueError, match="request fingerprint mismatch"):
        policy.configured_weights(changed_request)


def test_schema_rejects_fabricated_effective_weights() -> None:
    decision = build_adaptive_fusion_decision(
        _request(),
        [_quality("DETERMINISTIC", 0.50)],
    )
    tampered = decision.model_dump(mode="python")
    tampered["effective_weights"] = {
        "DETERMINISTIC": 0.40,
        "QUANT": 0.40,
        "GEMINI": 0.20,
    }

    with pytest.raises(
        ValidationError,
        match="effective weights must normalize",
    ):
        AdaptiveFusionDecisionV1.model_validate(tampered)


def test_tampered_adaptive_decision_fingerprint_fails_policy_construction() -> None:
    decision = build_adaptive_fusion_decision(_request(), []).model_copy(
        update={"decision_fingerprint": "0" * 64}
    )

    with pytest.raises(ValueError, match="adaptive fusion decision fingerprint mismatch"):
        AdaptiveFusionPolicy(decision)
