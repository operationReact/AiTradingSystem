from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.adaptive_fusion import build_adaptive_fusion_decision
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion import FusionEvidence, FusionRequest
from aitrading_intelligence.fusion_disagreement_control import (
    FusionDisagreementControlConfig,
    build_fusion_disagreement_control,
)
from aitrading_intelligence.fusion_policy_shadow_evaluation import (
    FusionPolicyShadowEvaluationConfig,
    evaluate_fusion_policy_shadow,
)
from aitrading_intelligence.pytorch_target import LabeledExample

BASE = datetime(2026, 1, 12, 6, 0, tzinfo=UTC)
TARGET_SPEC = "f" * 64


def _evidence(
    family: str,
    score: float,
    as_of: datetime,
    seed: str,
) -> FusionEvidence:
    return FusionEvidence(
        family=family,
        provider=f"fixture-{family.lower()}",
        version="v1",
        score=score,
        confidence=0.90,
        observed_at=as_of,
        fingerprint=(seed * 64)[:64],
        reasons=["fixture"],
    )


def _request(
    index: int,
    *,
    severe_disagreement: bool,
    as_of: datetime | None = None,
) -> FusionRequest:
    timestamp = as_of or BASE + timedelta(hours=index)
    if severe_disagreement:
        deterministic_score = 1.00
        quant_score = 0.05
        gemini_score = 0.00
    else:
        deterministic_score = 0.75
        quant_score = 0.65
        gemini_score = 0.55
    return FusionRequest(
        symbol=f"SYM{index}",
        as_of=timestamp,
        deterministic=_evidence("DETERMINISTIC", deterministic_score, timestamp, f"{index + 1:x}"),
        quant=_evidence("QUANT", quant_score, timestamp, f"{index + 11:x}"),
        gemini=_evidence("GEMINI", gemini_score, timestamp, f"{index + 21:x}"),
    )


def _outcome(
    request: FusionRequest,
    forward_return: float,
    *,
    target_observed_at: datetime | None = None,
) -> LabeledExample:
    observed_at = target_observed_at or request.as_of + timedelta(minutes=30)
    threshold_return = 0.0025
    payload = {
        "target_version": "forward-return-after-costs.v1",
        "symbol": request.symbol,
        "as_of": request.as_of.isoformat(),
        "target_observed_at": observed_at.isoformat(),
        "feature_row_fingerprint": canonical_fingerprint(
            {"symbol": request.symbol, "as_of": request.as_of.isoformat()}
        ),
        "target_spec_fingerprint": TARGET_SPEC,
        "label": 1 if forward_return > threshold_return else 0,
        "forward_return": forward_return,
        "threshold_return": threshold_return,
        "features": {"fixture": float(len(request.symbol))},
        "provenance": ["fixture"],
    }
    return LabeledExample(
        **payload,
        example_fingerprint=canonical_fingerprint(payload),
    )


def _artifacts(requests: list[FusionRequest]):
    decisions = []
    controls = []
    control_config = FusionDisagreementControlConfig(
        penalty_threshold=0.20,
        veto_threshold=0.45,
        maximum_weight_penalty=0.50,
    )
    for request in requests:
        decision = build_adaptive_fusion_decision(request, [])
        control = build_fusion_disagreement_control(
            request,
            decision,
            config=control_config,
        )
        decisions.append(decision)
        controls.append(control)
    return decisions, controls


def _comparison_config(**overrides) -> FusionPolicyShadowEvaluationConfig:
    values = {
        "minimum_resolved_requests": 4,
        "minimum_static_trades": 2,
        "minimum_challenger_trades": 2,
        "minimum_challenger_coverage": 0.25,
        "round_trip_cost_bps": 10.0,
        "minimum_expectancy_uplift_bps": 1.0,
        "maximum_drawdown_regression": 0.0,
    }
    values.update(overrides)
    return FusionPolicyShadowEvaluationConfig(**values)


def test_guarded_challenger_can_advance_only_on_resolved_shadow_superiority() -> None:
    requests = [
        _request(0, severe_disagreement=False),
        _request(1, severe_disagreement=False),
        _request(2, severe_disagreement=True),
        _request(3, severe_disagreement=True),
    ]
    decisions, controls = _artifacts(requests)
    outcomes = [
        _outcome(requests[0], 0.02),
        _outcome(requests[1], 0.02),
        _outcome(requests[2], -0.10),
        _outcome(requests[3], -0.10),
    ]
    evaluation_as_of = BASE + timedelta(hours=4)

    report = evaluate_fusion_policy_shadow(
        requests,
        decisions,
        controls,
        outcomes,
        evaluation_as_of=evaluation_as_of,
        config=_comparison_config(),
    )

    assert report.static_champion.trade_count == 4
    assert report.guarded_challenger.trade_count == 2
    assert report.static_champion.expectancy_bps is not None
    assert report.guarded_challenger.expectancy_bps is not None
    assert report.guarded_challenger.expectancy_bps > report.static_champion.expectancy_bps
    assert report.guarded_challenger.maximum_drawdown < report.static_champion.maximum_drawdown
    assert report.assessment == "CHALLENGER_EVIDENCE_SUPERIOR"
    assert report.required_next_gate == "V3_06_POLICY_GOVERNANCE_REVIEW"
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_tie_does_not_prove_superiority_and_preserves_static_champion() -> None:
    requests = [
        _request(index, severe_disagreement=False)
        for index in range(4)
    ]
    decisions, controls = _artifacts(requests)
    outcomes = [_outcome(request, 0.01) for request in requests]

    report = evaluate_fusion_policy_shadow(
        requests,
        decisions,
        controls,
        outcomes,
        evaluation_as_of=BASE + timedelta(hours=4),
        config=_comparison_config(minimum_expectancy_uplift_bps=1.0),
    )

    assert report.expectancy_uplift_bps == pytest.approx(0.0)
    assert report.assessment == "STATIC_CHAMPION_PRESERVED"
    assert report.required_next_gate == "V3_06_CHALLENGER_RESEARCH_REVIEW"


def test_insufficient_sample_never_advances_challenger() -> None:
    requests = [
        _request(0, severe_disagreement=False),
        _request(1, severe_disagreement=True),
    ]
    decisions, controls = _artifacts(requests)
    outcomes = [
        _outcome(requests[0], 0.02),
        _outcome(requests[1], -0.10),
    ]

    report = evaluate_fusion_policy_shadow(
        requests,
        decisions,
        controls,
        outcomes,
        evaluation_as_of=BASE + timedelta(hours=2),
        config=_comparison_config(
            minimum_resolved_requests=4,
            minimum_static_trades=2,
            minimum_challenger_trades=1,
        ),
    )

    assert report.assessment == "INSUFFICIENT_EVIDENCE"
    assert report.required_next_gate == "V3_06_CHALLENGER_RESEARCH_REVIEW"
    assert "INSUFFICIENT_RESOLVED_SHADOW_REQUESTS" in report.reason_codes


def test_future_requests_and_future_outcomes_cannot_rewrite_historical_report() -> None:
    requests = [
        _request(0, severe_disagreement=False),
        _request(1, severe_disagreement=False),
        _request(2, severe_disagreement=True),
        _request(3, severe_disagreement=True),
    ]
    decisions, controls = _artifacts(requests)
    outcomes = [
        _outcome(requests[0], 0.02),
        _outcome(requests[1], 0.02),
        _outcome(requests[2], -0.10),
        _outcome(requests[3], -0.10),
    ]
    cutoff = BASE + timedelta(hours=4)
    config = _comparison_config()
    baseline = evaluate_fusion_policy_shadow(
        requests,
        decisions,
        controls,
        outcomes,
        evaluation_as_of=cutoff,
        config=config,
    )

    future_request = _request(
        9,
        severe_disagreement=False,
        as_of=cutoff + timedelta(hours=1),
    )
    future_decisions, future_controls = _artifacts([future_request])
    future_outcome = _outcome(
        future_request,
        0.99,
        target_observed_at=cutoff + timedelta(hours=2),
    )
    mutated = evaluate_fusion_policy_shadow(
        [*requests, future_request],
        [*decisions, *future_decisions],
        [*controls, *future_controls],
        [*outcomes, future_outcome],
        evaluation_as_of=cutoff,
        config=config,
    )

    assert mutated.model_dump(mode="json") == baseline.model_dump(mode="json")
    assert mutated.report_fingerprint == baseline.report_fingerprint


def test_unresolved_outcome_is_excluded_at_cutoff_not_peeked() -> None:
    requests = [
        _request(0, severe_disagreement=False),
        _request(1, severe_disagreement=False),
        _request(2, severe_disagreement=True),
        _request(3, severe_disagreement=True),
        _request(4, severe_disagreement=False),
    ]
    decisions, controls = _artifacts(requests)
    cutoff = BASE + timedelta(hours=5)
    outcomes = [
        _outcome(requests[0], 0.02),
        _outcome(requests[1], 0.02),
        _outcome(requests[2], -0.10),
        _outcome(requests[3], -0.10),
        _outcome(
            requests[4],
            0.99,
            target_observed_at=cutoff + timedelta(hours=1),
        ),
    ]

    report = evaluate_fusion_policy_shadow(
        requests,
        decisions,
        controls,
        outcomes,
        evaluation_as_of=cutoff,
        config=_comparison_config(),
    )

    assert report.eligible_request_count == 5
    assert report.resolved_request_count == 4
    assert report.unresolved_request_count == 1
    assert "UNRESOLVED_SHADOW_OUTCOMES_EXCLUDED_AT_CUTOFF" in report.reason_codes


def test_tampered_outcome_fingerprint_fails_closed() -> None:
    requests = [
        _request(0, severe_disagreement=False),
        _request(1, severe_disagreement=False),
    ]
    decisions, controls = _artifacts(requests)
    outcomes = [
        _outcome(requests[0], 0.02),
        _outcome(requests[1], 0.02).model_copy(
            update={"example_fingerprint": "0" * 64}
        ),
    ]

    with pytest.raises(ValueError, match="labeled outcome fingerprint mismatch"):
        evaluate_fusion_policy_shadow(
            requests,
            decisions,
            controls,
            outcomes,
            evaluation_as_of=BASE + timedelta(hours=2),
            config=_comparison_config(
                minimum_resolved_requests=2,
                minimum_static_trades=1,
                minimum_challenger_trades=1,
            ),
        )
