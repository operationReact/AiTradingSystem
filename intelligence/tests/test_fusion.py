from collections.abc import Mapping
from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.fusion import (
    V2_CHAMPION_FUSION_POLICY,
    FusionEvidence,
    FusionFamily,
    FusionRequest,
    fuse_signals,
)
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


def _regime(as_of: datetime, *, seed: str = "9") -> RegimeSnapshot:
    return RegimeSnapshot(
        as_of=as_of,
        data_cutoff=as_of - timedelta(minutes=1),
        method="HEURISTIC_BASELINE",
        dominant_regime="TREND_UP",
        probabilities={
            "TREND_UP": 0.60,
            "TREND_DOWN": 0.10,
            "RANGE_LOW_VOL": 0.20,
            "STRESS_HIGH_VOL": 0.10,
        },
        confidence=0.60,
        evidence={"benchmark.NIFTY50.return_1d": 0.012},
        input_fingerprint=("8" * 64),
        config_fingerprint=("7" * 64),
        snapshot_fingerprint=(seed * 64)[:64],
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


def test_fresh_regime_is_exposed_but_does_not_change_fusion_decision() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    base = FusionRequest(
        symbol="RELIANCE",
        as_of=as_of,
        deterministic=_evidence("DETERMINISTIC", 0.80, 0.90, as_of, "a"),
        quant=_evidence("QUANT", 0.70, 0.80, as_of, "b"),
    )
    with_regime = base.model_copy(update={"regime": _regime(as_of)})

    without_regime_result = fuse_signals(base)
    result = fuse_signals(with_regime)

    assert result.action == without_regime_result.action == "BUY"
    assert result.fused_score == without_regime_result.fused_score
    assert result.rank_score == without_regime_result.rank_score
    assert result.confidence == without_regime_result.confidence
    assert result.contributions == without_regime_result.contributions
    assert result.regime_evidence is not None
    assert result.regime_evidence.included is True
    assert result.regime_evidence.reason == "FRESH_ADVISORY_ONLY"
    assert result.regime_evidence.influences_fusion_score is False
    assert result.regime_evidence.can_authorize_capital is False
    assert result.regime_evidence.dominant_regime == "TREND_UP"
    assert "REGIME_ADVISORY_ONLY" in result.reason_codes
    assert result.provenance[-1].startswith("REGIME:HEURISTIC_BASELINE:")
    assert result.opportunity_fingerprint != without_regime_result.opportunity_fingerprint


def test_stale_regime_remains_visible_but_cannot_block_or_create_buy() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    request = FusionRequest(
        symbol="TCS",
        as_of=as_of,
        max_age_seconds=300,
        deterministic=_evidence("DETERMINISTIC", 0.75, 0.90, as_of, "d"),
        quant=_evidence("QUANT", 0.65, 0.85, as_of, "e"),
        regime=_regime(as_of - timedelta(minutes=10), seed="6"),
    )

    result = fuse_signals(request)

    assert result.action == "BUY"
    assert result.regime_evidence is not None
    assert result.regime_evidence.included is False
    assert result.regime_evidence.reason == "STALE_ADVISORY_ONLY"
    assert "REGIME_STALE" in result.reason_codes
    assert "REGIME_ADVISORY_ONLY" in result.reason_codes


def test_future_regime_evidence_is_rejected() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)

    with pytest.raises(ValueError, match="regime as_of cannot be later than fusion as_of"):
        FusionRequest(
            symbol="INFY",
            as_of=as_of,
            deterministic=_evidence("DETERMINISTIC", 0.80, 0.90, as_of, "3"),
            quant=_evidence("QUANT", 0.60, 0.90, as_of, "4"),
            regime=_regime(as_of + timedelta(minutes=1), seed="5"),
        )


def test_default_policy_is_byte_identical_to_explicit_v2_champion() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    request = FusionRequest(
        symbol="RELIANCE",
        as_of=as_of,
        deterministic=_evidence("DETERMINISTIC", 0.80, 0.90, as_of, "a"),
        quant=_evidence("QUANT", 0.70, 0.80, as_of, "b"),
        gemini=_evidence("GEMINI", 0.60, 0.75, as_of, "c"),
        regime=_regime(as_of),
    )

    default = fuse_signals(request)
    explicit_champion = fuse_signals(request, policy=V2_CHAMPION_FUSION_POLICY)

    assert default.model_dump(mode="json") == explicit_champion.model_dump(mode="json")
    assert default.opportunity_fingerprint == explicit_champion.opportunity_fingerprint
    assert [item.configured_weight for item in default.contributions] == [0.45, 0.35, 0.20]


class _StrictResearchPolicy:
    policy_id = "fixture-strict-threshold"

    def configured_weights(self, request: FusionRequest) -> Mapping[FusionFamily, float]:
        del request
        return {"DETERMINISTIC": 0.45, "QUANT": 0.35, "GEMINI": 0.20}

    def required_positive_families(self, request: FusionRequest) -> tuple[FusionFamily, ...]:
        del request
        return ("DETERMINISTIC", "QUANT")

    def buy_threshold(self, request: FusionRequest) -> float:
        del request
        return 0.90


def test_policy_boundary_is_injectable_without_changing_default_champion() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    request = FusionRequest(
        symbol="TCS",
        as_of=as_of,
        deterministic=_evidence("DETERMINISTIC", 0.80, 0.90, as_of, "d"),
        quant=_evidence("QUANT", 0.70, 0.80, as_of, "e"),
        gemini=_evidence("GEMINI", 0.60, 0.75, as_of, "f"),
    )

    champion = fuse_signals(request)
    challenger = fuse_signals(request, policy=_StrictResearchPolicy())

    assert champion.action == "BUY"
    assert challenger.action == "WATCH"
    assert "FUSION_SCORE_BELOW_BUY_THRESHOLD" in challenger.reason_codes
    assert champion.can_authorize_capital is False
    assert challenger.can_authorize_capital is False


class _InvalidResearchPolicy(_StrictResearchPolicy):
    policy_id = "fixture-invalid-weights"

    def configured_weights(self, request: FusionRequest) -> Mapping[FusionFamily, float]:
        del request
        return {"DETERMINISTIC": 0.60, "QUANT": 0.40}


def test_invalid_policy_shape_fails_closed_before_fusion() -> None:
    as_of = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    request = FusionRequest(
        symbol="INFY",
        as_of=as_of,
        deterministic=_evidence("DETERMINISTIC", 0.80, 0.90, as_of, "1"),
        quant=_evidence("QUANT", 0.70, 0.80, as_of, "2"),
    )

    with pytest.raises(ValueError, match="must configure DETERMINISTIC, QUANT and GEMINI"):
        fuse_signals(request, policy=_InvalidResearchPolicy())
