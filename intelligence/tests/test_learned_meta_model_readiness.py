from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion_policy_shadow_evaluation import (
    FusionPolicyShadowEvaluationReport,
    FusionPolicyShadowPoint,
)
from aitrading_intelligence.learned_meta_model_readiness import (
    LearnedMetaModelReadinessConfig,
    assess_learned_meta_model_readiness,
)

BASE = datetime(2026, 1, 12, 6, 0, tzinfo=UTC)
TARGET_SPEC = "a" * 64
EVAL_CONFIG = "b" * 64
BASE_POLICY_CONFIG = "c" * 64
DISAGREEMENT_CONFIG = "d" * 64


def _hash(value: int) -> str:
    return f"{value:064x}"


def _point(
    policy: str,
    *,
    trade_count: int,
    expectancy_bps: float,
    maximum_drawdown: float,
) -> FusionPolicyShadowPoint:
    mean_return = expectancy_bps / 10_000.0
    return FusionPolicyShadowPoint(
        policy=policy,
        coverage=trade_count / 2,
        trade_count=trade_count,
        winning_trades=trade_count,
        win_rate=1.0 if trade_count else 0.0,
        total_net_return=mean_return * trade_count,
        mean_net_return=mean_return if trade_count else None,
        expectancy_bps=expectancy_bps if trade_count else None,
        maximum_drawdown=maximum_drawdown,
    )


def _report(
    index: int,
    *,
    assessment: str = "CHALLENGER_EVIDENCE_SUPERIOR",
    request_seed: int | None = None,
    target_spec: str = TARGET_SPEC,
    evaluation_config: str = EVAL_CONFIG,
    base_policy_config: str = BASE_POLICY_CONFIG,
    disagreement_config: str = DISAGREEMENT_CONFIG,
    evaluation_as_of: datetime | None = None,
) -> FusionPolicyShadowEvaluationReport:
    request_base = request_seed if request_seed is not None else (index * 100)
    request_fingerprints = [_hash(request_base + 1), _hash(request_base + 2)]
    adaptive_fingerprints = [_hash(request_base + 11), _hash(request_base + 12)]
    control_fingerprints = [_hash(request_base + 21), _hash(request_base + 22)]
    static_fingerprints = [_hash(request_base + 31), _hash(request_base + 32)]
    guarded_fingerprints = [_hash(request_base + 41), _hash(request_base + 42)]
    outcome_fingerprints = [_hash(request_base + 51), _hash(request_base + 52)]
    timestamp = evaluation_as_of or BASE + timedelta(days=index)

    if assessment == "CHALLENGER_EVIDENCE_SUPERIOR":
        challenger_expectancy = 120.0
        static_expectancy = 50.0
        next_gate = "V3_06_POLICY_GOVERNANCE_REVIEW"
        reasons = ["GUARDED_CHALLENGER_EXPECTANCY_GATE_PASSED"]
    elif assessment == "STATIC_CHAMPION_PRESERVED":
        challenger_expectancy = 40.0
        static_expectancy = 50.0
        next_gate = "V3_06_CHALLENGER_RESEARCH_REVIEW"
        reasons = ["GUARDED_CHALLENGER_EXPECTANCY_GATE_FAILED"]
    else:
        challenger_expectancy = 120.0
        static_expectancy = 50.0
        next_gate = "V3_06_CHALLENGER_RESEARCH_REVIEW"
        reasons = ["INSUFFICIENT_RESOLVED_SHADOW_REQUESTS"]

    static = _point(
        "STATIC_45_35_20",
        trade_count=2,
        expectancy_bps=static_expectancy,
        maximum_drawdown=0.10,
    )
    challenger = _point(
        "ADAPTIVE_DISAGREEMENT_GUARDED",
        trade_count=2,
        expectancy_bps=challenger_expectancy,
        maximum_drawdown=0.05,
    )
    payload = {
        "contract_version": "fusion-policy-shadow-evaluation.v1",
        "evaluation_as_of": timestamp.isoformat(),
        "config_fingerprint": evaluation_config,
        "target_spec_fingerprint": target_spec,
        "challenger_base_policy_config_fingerprint": base_policy_config,
        "challenger_disagreement_config_fingerprint": disagreement_config,
        "eligible_request_count": 2,
        "resolved_request_count": 2,
        "unresolved_request_count": 0,
        "static_champion": static.model_dump(mode="json"),
        "guarded_challenger": challenger.model_dump(mode="json"),
        "expectancy_uplift_bps": challenger_expectancy - static_expectancy,
        "total_net_return_uplift": (
            challenger.total_net_return - static.total_net_return
        ),
        "maximum_drawdown_change": (
            challenger.maximum_drawdown - static.maximum_drawdown
        ),
        "assessment": assessment,
        "reason_codes": reasons,
        "source_request_fingerprints": request_fingerprints,
        "source_adaptive_decision_fingerprints": adaptive_fingerprints,
        "source_disagreement_control_fingerprints": control_fingerprints,
        "source_static_opportunity_fingerprints": static_fingerprints,
        "source_guarded_opportunity_fingerprints": guarded_fingerprints,
        "source_outcome_fingerprints": outcome_fingerprints,
        "required_next_gate": next_gate,
        "status": "RESEARCH_SHADOW_EVALUATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return FusionPolicyShadowEvaluationReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )


def _config(**overrides) -> LearnedMetaModelReadinessConfig:
    values = {
        "minimum_independent_oos_windows": 2,
        "minimum_total_resolved_requests": 4,
        "minimum_total_challenger_trades": 4,
    }
    values.update(overrides)
    return LearnedMetaModelReadinessConfig(**values)


def test_independent_superior_oos_windows_only_unlock_research_design() -> None:
    readiness = assess_learned_meta_model_readiness(
        [_report(1), _report(2)],
        assessed_at=BASE + timedelta(days=3),
        config=_config(),
    )

    assert readiness.readiness == "RESEARCH_TRAINING_ELIGIBLE"
    assert readiness.required_next_gate == "LEARNED_META_MODEL_RESEARCH_DESIGN"
    assert readiness.independent_oos_windows is True
    assert readiness.comparable_evidence is True
    assert readiness.all_windows_superior is True
    assert readiness.learned_policy_enabled is False
    assert readiness.promotion_allowed is False
    assert readiness.can_authorize_capital is False


def test_one_non_superior_window_keeps_learned_meta_model_not_ready() -> None:
    readiness = assess_learned_meta_model_readiness(
        [_report(1), _report(2, assessment="STATIC_CHAMPION_PRESERVED")],
        assessed_at=BASE + timedelta(days=3),
        config=_config(),
    )

    assert readiness.readiness == "NOT_READY"
    assert readiness.required_next_gate == "CONTINUE_NON_LEAKING_OOS_EVIDENCE"
    assert "CHALLENGER_NOT_SUPERIOR_IN_EVERY_OOS_WINDOW" in readiness.reason_codes
    assert readiness.learned_policy_enabled is False


def test_overlapping_oos_windows_are_not_independent() -> None:
    readiness = assess_learned_meta_model_readiness(
        [_report(1, request_seed=100), _report(2, request_seed=100)],
        assessed_at=BASE + timedelta(days=3),
        config=_config(),
    )

    assert readiness.readiness == "NOT_READY"
    assert readiness.independent_oos_windows is False
    assert "OOS_EVIDENCE_WINDOWS_OVERLAP" in readiness.reason_codes


def test_incomparable_target_or_policy_configuration_stays_not_ready() -> None:
    readiness = assess_learned_meta_model_readiness(
        [
            _report(1),
            _report(2, target_spec="e" * 64),
        ],
        assessed_at=BASE + timedelta(days=3),
        config=_config(),
    )

    assert readiness.readiness == "NOT_READY"
    assert readiness.comparable_evidence is False
    assert readiness.target_spec_fingerprint is None
    assert "OOS_EVIDENCE_NOT_COMPARABLE" in readiness.reason_codes


def test_insufficient_window_and_sample_floors_fail_closed() -> None:
    readiness = assess_learned_meta_model_readiness(
        [_report(1)],
        assessed_at=BASE + timedelta(days=2),
        config=_config(
            minimum_independent_oos_windows=3,
            minimum_total_resolved_requests=10,
            minimum_total_challenger_trades=10,
        ),
    )

    assert readiness.readiness == "NOT_READY"
    assert "INSUFFICIENT_INDEPENDENT_OOS_WINDOWS" in readiness.reason_codes
    assert "INSUFFICIENT_TOTAL_RESOLVED_REQUESTS" in readiness.reason_codes
    assert "INSUFFICIENT_TOTAL_CHALLENGER_TRADES" in readiness.reason_codes


def test_future_shadow_report_cannot_rewrite_historical_readiness() -> None:
    reports = [_report(1), _report(2)]
    cutoff = BASE + timedelta(days=3)
    config = _config()
    baseline = assess_learned_meta_model_readiness(
        reports,
        assessed_at=cutoff,
        config=config,
    )
    future = _report(
        9,
        assessment="STATIC_CHAMPION_PRESERVED",
        evaluation_as_of=cutoff + timedelta(days=1),
    )
    mutated = assess_learned_meta_model_readiness(
        [*reports, future],
        assessed_at=cutoff,
        config=config,
    )

    assert mutated.model_dump(mode="json") == baseline.model_dump(mode="json")
    assert mutated.readiness_fingerprint == baseline.readiness_fingerprint


def test_tampered_shadow_report_fingerprint_fails_closed() -> None:
    tampered = _report(1).model_copy(update={"report_fingerprint": "0" * 64})

    with pytest.raises(ValueError, match="shadow report fingerprint mismatch"):
        assess_learned_meta_model_readiness(
            [tampered],
            assessed_at=BASE + timedelta(days=2),
            config=_config(
                minimum_independent_oos_windows=2,
                minimum_total_resolved_requests=2,
                minimum_total_challenger_trades=2,
            ),
        )
