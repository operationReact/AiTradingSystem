from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fusion import FusionEvidence, FusionRequest, fuse_signals
from aitrading_intelligence.regime import RegimeSnapshot


def _evidence(
    family: str,
    score: float,
    confidence: float,
    observed_at: datetime,
    seed: str,
) -> FusionEvidence:
    return FusionEvidence(
        family=family,
        provider=f"fixture-{family.lower()}",
        version="v1",
        score=score,
        confidence=confidence,
        observed_at=observed_at,
        fingerprint=(seed * 64)[:64],
        reasons=["fixture"],
    )


def _regime(as_of: datetime, *, seed: str = "f") -> RegimeSnapshot:
    probabilities = {
        "TREND_UP": 0.55,
        "TREND_DOWN": 0.10,
        "RANGE_LOW_VOL": 0.25,
        "STRESS_HIGH_VOL": 0.10,
    }
    return RegimeSnapshot(
        as_of=as_of,
        data_cutoff=as_of - timedelta(minutes=1),
        method="HEURISTIC_BASELINE",
        dominant_regime="TREND_UP",
        probabilities=probabilities,
        confidence=0.55,
        evidence={"fixture.metric": 1.0},
        input_fingerprint=(seed * 64)[:64],
        config_fingerprint=("e" * 64),
        snapshot_fingerprint=("d" * 64),
    )


def test_positive_fresh_components_rank_buy_but_never_authorize_capital() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    request = FusionRequest(
        symbol="RELIANCE",
        as_of=as_of,
        deterministic=_evidence("DETERMINISTIC", 0.80, 0.90, as_of, "a"),
        quant=_evidence("QUANT", 0.70, 0.80, as_of, "b"),
        gemini=_evidence("GEMINI", 0.60, 0.75, as_of, "c"),
    )

    result = fuse_signals(request)

    assert result.action == "BUY"
    assert result.fused_score >= 0.35
    assert result.rank_score > 0.5
    assert result.status == "CANDIDATE_NOT_EXECUTABLE"
    assert result.can_authorize_capital is False
    assert len(result.provenance) == 3
    assert "FUSION_BUY_THRESHOLD_MET" in result.reason_codes
    assert result.regime_context is None
    assert result.explainability_fingerprint is None


def test_gemini_is_optional_but_required_quant_and_strategy_must_be_fresh() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    without_gemini = FusionRequest(
        symbol="TCS",
        as_of=as_of,
        deterministic=_evidence("DETERMINISTIC", 0.75, 0.90, as_of, "d"),
        quant=_evidence("QUANT", 0.65, 0.85, as_of, "e"),
    )
    result = fuse_signals(without_gemini)
    assert result.action == "BUY"
    assert len(result.provenance) == 2

    stale_quant = FusionRequest(
        symbol="TCS",
        as_of=as_of,
        max_age_seconds=300,
        deterministic=_evidence("DETERMINISTIC", 0.75, 0.90, as_of, "f"),
        quant=_evidence(
            "QUANT",
            0.90,
            0.95,
            as_of - timedelta(minutes=10),
            "1",
        ),
        gemini=_evidence("GEMINI", 0.90, 0.95, as_of, "2"),
    )
    stale_result = fuse_signals(stale_quant)
    assert stale_result.action == "WATCH"
    assert "QUANT_STALE" in stale_result.reason_codes
    assert "REQUIRED_EVIDENCE_STALE" in stale_result.reason_codes
    quant_contribution = next(
        item for item in stale_result.contributions if item.family == "QUANT"
    )
    assert quant_contribution.included is False
    assert quant_contribution.reason == "STALE_EXCLUDED"


def test_model_disagreement_remains_watch() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    result = fuse_signals(
        FusionRequest(
            symbol="INFY",
            as_of=as_of,
            deterministic=_evidence("DETERMINISTIC", 0.80, 0.90, as_of, "3"),
            quant=_evidence("QUANT", -0.60, 0.90, as_of, "4"),
            gemini=_evidence("GEMINI", 0.90, 0.90, as_of, "5"),
        )
    )

    assert result.action == "WATCH"
    assert "QUANT_NOT_POSITIVE" in result.reason_codes


def test_regime_context_is_explainability_only_and_does_not_change_ranking_identity() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    deterministic = _evidence("DETERMINISTIC", 0.80, 0.90, as_of, "a")
    quant = _evidence("QUANT", 0.70, 0.80, as_of, "b")
    baseline = fuse_signals(
        FusionRequest(
            symbol="RELIANCE",
            as_of=as_of,
            deterministic=deterministic,
            quant=quant,
        )
    )
    explained = fuse_signals(
        FusionRequest(
            symbol="RELIANCE",
            as_of=as_of,
            deterministic=deterministic,
            quant=quant,
            regime=_regime(as_of),
        )
    )

    assert explained.action == baseline.action
    assert explained.fused_score == baseline.fused_score
    assert explained.rank_score == baseline.rank_score
    assert explained.confidence == baseline.confidence
    assert explained.reason_codes == baseline.reason_codes
    assert explained.provenance == baseline.provenance
    assert explained.opportunity_fingerprint == baseline.opportunity_fingerprint
    assert explained.regime_context is not None
    assert explained.regime_context.contract_version == "regime-explainability.v1"
    assert explained.regime_context.dominant_regime == "TREND_UP"
    assert explained.regime_context.probabilities["TREND_UP"] == 0.55
    assert 0.0 <= explained.regime_context.normalized_entropy <= 1.0
    assert explained.regime_context.status == "RESEARCH_EXPLAINABILITY_ONLY"
    assert explained.regime_context.can_authorize_capital is False
    assert explained.explainability_fingerprint == (
        explained.regime_context.explainability_fingerprint
    )
    assert explained.explainability_fingerprint is not None


def test_regime_explainability_is_deterministically_fingerprinted() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    request = FusionRequest(
        symbol="TCS",
        as_of=as_of,
        deterministic=_evidence("DETERMINISTIC", 0.75, 0.90, as_of, "a"),
        quant=_evidence("QUANT", 0.65, 0.85, as_of, "b"),
        regime=_regime(as_of),
    )

    first = fuse_signals(request)
    second = fuse_signals(request)

    assert first.explainability_fingerprint == second.explainability_fingerprint
    assert first.regime_context == second.regime_context


def test_future_regime_context_is_rejected_at_fusion_boundary() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    future_regime = _regime(as_of + timedelta(minutes=1))

    with pytest.raises(ValidationError, match="regime as_of cannot be later"):
        FusionRequest(
            symbol="INFY",
            as_of=as_of,
            deterministic=_evidence("DETERMINISTIC", 0.80, 0.90, as_of, "a"),
            quant=_evidence("QUANT", 0.70, 0.80, as_of, "b"),
            regime=future_regime,
        )
