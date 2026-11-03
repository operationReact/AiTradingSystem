from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.adaptive_fusion import build_adaptive_fusion_decision
from aitrading_intelligence.fusion import FusionEvidence, FusionRequest
from aitrading_intelligence.fusion_disagreement_control import (
    DisagreementControlledOpportunityV1,
    FusionDisagreementControlConfig,
    FusionDisagreementControlV1,
    build_fusion_disagreement_control,
    fuse_with_disagreement_control,
)

NOW = datetime(2026, 1, 12, 6, 0, tzinfo=UTC)


def _evidence(
    family: str,
    score: float,
    seed: str,
    *,
    confidence: float = 0.90,
    observed_at: datetime = NOW,
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


def _request(
    *,
    deterministic_score: float = 0.75,
    quant_score: float = 0.65,
    gemini_score: float | None = 0.55,
    quant_observed_at: datetime = NOW,
    max_age_seconds: int = 1_800,
) -> FusionRequest:
    return FusionRequest(
        symbol="RELIANCE",
        as_of=NOW,
        max_age_seconds=max_age_seconds,
        deterministic=_evidence("DETERMINISTIC", deterministic_score, "a"),
        quant=_evidence(
            "QUANT",
            quant_score,
            "b",
            observed_at=quant_observed_at,
        ),
        gemini=(
            _evidence("GEMINI", gemini_score, "c")
            if gemini_score is not None
            else None
        ),
    )


def _control(request: FusionRequest, **config_overrides):
    adaptive = build_adaptive_fusion_decision(request, [])
    config = FusionDisagreementControlConfig(**config_overrides)
    return adaptive, build_fusion_disagreement_control(
        request,
        adaptive,
        config=config,
    )


def test_low_disagreement_keeps_weights_and_does_not_veto() -> None:
    request = _request(
        deterministic_score=0.70,
        quant_score=0.65,
        gemini_score=0.60,
    )
    adaptive, control = _control(request)

    assert control.penalty_applied is False
    assert control.veto_buy is False
    assert control.controlled_weights == pytest.approx(adaptive.effective_weights)
    assert control.can_authorize_capital is False
    assert control.promotion_allowed is False


def test_moderate_disagreement_penalizes_outlier_but_does_not_force_veto() -> None:
    request = _request(
        deterministic_score=0.90,
        quant_score=0.35,
        gemini_score=0.30,
    )
    adaptive, control = _control(
        request,
        penalty_threshold=0.20,
        veto_threshold=0.80,
        maximum_weight_penalty=0.50,
    )

    assert control.penalty_applied is True
    assert control.veto_buy is False
    assert (
        control.family_penalty_multipliers["DETERMINISTIC"]
        < control.family_penalty_multipliers["QUANT"]
    )
    assert control.controlled_weights["DETERMINISTIC"] < adaptive.effective_weights[
        "DETERMINISTIC"
    ]
    assert sum(control.controlled_weights.values()) == pytest.approx(1.0)


def test_required_family_sign_conflict_is_an_explicit_buy_veto() -> None:
    request = _request(
        deterministic_score=0.80,
        quant_score=-0.20,
        gemini_score=0.70,
    )
    adaptive, control = _control(request)

    assert control.required_family_sign_conflict is True
    assert control.veto_buy is True
    assert "REQUIRED_FAMILY_DIRECTION_CONFLICT" in control.reason_codes

    guarded = fuse_with_disagreement_control(request, adaptive, control)
    assert guarded.guarded_action == "WATCH"
    assert guarded.veto_applied is True
    assert guarded.can_authorize_capital is False


def test_stale_required_family_vetoes_even_when_other_scores_are_positive() -> None:
    request = _request(
        deterministic_score=0.80,
        quant_score=0.70,
        gemini_score=0.60,
        quant_observed_at=NOW - timedelta(minutes=10),
        max_age_seconds=300,
    )
    _, control = _control(request)

    assert control.required_family_stale is True
    assert control.veto_buy is True
    assert control.family_penalty_multipliers["QUANT"] == 0.0
    assert "REQUIRED_FAMILY_STALE_VETO" in control.reason_codes


def test_missing_optional_gemini_is_excluded_without_weakening_required_gate() -> None:
    request = _request(gemini_score=None)
    adaptive, control = _control(request)

    assert control.missing_families == ["GEMINI"]
    assert control.family_penalty_multipliers["GEMINI"] == 0.0
    assert control.required_family_stale is False
    assert control.veto_buy is False

    guarded = fuse_with_disagreement_control(request, adaptive, control)
    assert guarded.guarded_action == guarded.weighted_action


def test_severe_same_direction_score_disagreement_can_veto() -> None:
    request = _request(
        deterministic_score=1.00,
        quant_score=0.05,
        gemini_score=0.00,
    )
    _, control = _control(
        request,
        penalty_threshold=0.20,
        veto_threshold=0.45,
    )

    assert control.required_family_sign_conflict is False
    assert control.normalized_disagreement_score >= 0.45
    assert control.veto_buy is True
    assert "SEVERE_DISAGREEMENT_VETO" in control.reason_codes


def test_control_is_bound_to_exact_request_and_adaptive_decision() -> None:
    request = _request()
    adaptive, control = _control(request)
    changed_request = _request(quant_score=0.10)

    with pytest.raises(ValueError, match="request fingerprint mismatch"):
        fuse_with_disagreement_control(changed_request, adaptive, control)

    changed_adaptive = build_adaptive_fusion_decision(request, []).model_copy(
        update={"decision_fingerprint": "0" * 64}
    )
    with pytest.raises(ValueError):
        fuse_with_disagreement_control(request, changed_adaptive, control)


def test_tampered_control_arithmetic_fails_schema_validation() -> None:
    request = _request(
        deterministic_score=0.90,
        quant_score=0.35,
        gemini_score=0.30,
    )
    _, control = _control(request, penalty_threshold=0.20, veto_threshold=0.80)
    tampered = control.model_dump(mode="python")
    tampered["controlled_weights"] = {
        "DETERMINISTIC": 0.45,
        "QUANT": 0.35,
        "GEMINI": 0.20,
    }

    with pytest.raises(ValidationError, match="controlled weights must normalize"):
        FusionDisagreementControlV1.model_validate(tampered)


def test_guarded_opportunity_schema_rejects_vetoed_buy() -> None:
    request = _request(
        deterministic_score=0.80,
        quant_score=-0.20,
        gemini_score=0.70,
    )
    adaptive, control = _control(request)
    valid = fuse_with_disagreement_control(request, adaptive, control)
    tampered = valid.model_dump(mode="python")
    tampered["guarded_action"] = "BUY"

    with pytest.raises(ValidationError, match="veto must force WATCH"):
        DisagreementControlledOpportunityV1.model_validate(tampered)


def test_invalid_threshold_order_fails_closed() -> None:
    with pytest.raises(ValidationError, match="penalty threshold must be below veto"):
        FusionDisagreementControlConfig(
            penalty_threshold=0.80,
            veto_threshold=0.70,
        )
