from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.model_governance_control_plane import (
    activate_champion,
    initialize_model_governance_control_plane,
    transition_model_lifecycle,
)
from aitrading_intelligence.model_registry import register_governance_candidate
from aitrading_intelligence.monte_carlo import MonteCarloEvidence, MonteCarloSummary
from aitrading_intelligence.promotion_governance import PromotionGovernanceDecision

REGISTERED_AT = datetime(2026, 1, 6, 4, 0, tzinfo=UTC)


def _hash(value: int) -> str:
    return f"{value:064x}"


def _evidence(seed: int) -> MonteCarloEvidence:
    return MonteCarloEvidence(
        config_fingerprint=_hash(seed),
        source_walk_forward_fingerprint=_hash(seed + 1),
        source_trade_fingerprints=[_hash(seed + 2 + offset) for offset in range(5)],
        returns_distribution_fingerprint=_hash(seed + 7),
        drawdowns_distribution_fingerprint=_hash(seed + 8),
        summary=MonteCarloSummary(
            simulations=100,
            oos_trade_count=5,
            p05_total_return=0.01,
            median_total_return=0.10,
            p95_total_return=0.25,
            p50_max_drawdown=0.05,
            p95_max_drawdown=0.10,
            probability_of_loss=0.10,
            probability_of_ruin=0.0,
            probability_drawdown_exceeds_limit=0.05,
            profitable_fold_fraction=0.75,
            worst_observed_fold_drawdown=0.12,
        ),
        assessment="V2_09_GOVERNANCE_CANDIDATE",
        next_gate="V2_09_OUTCOME_GOVERNANCE",
        evidence_fingerprint=_hash(seed + 9),
    )


def _candidate(
    *,
    seed: int,
    challenger_of: str | None,
    registered_at: datetime = REGISTERED_AT,
):
    return register_governance_candidate(
        model_family="PYTORCH",
        model_version=f"pytorch-mlp.v{seed}",
        artifact_fingerprint=_hash(seed + 20),
        feature_schema_fingerprint=_hash(seed + 21),
        training_dataset_fingerprint=_hash(seed + 22),
        training_config_fingerprint=_hash(seed + 23),
        code_sha=f"{seed % 16:x}" * 40,
        evaluation_fingerprint=_hash(seed + 24),
        monte_carlo_evidence=_evidence(seed + 30),
        registered_at=registered_at,
        challenger_of=challenger_of,
    )


def _promotion_decision(
    *,
    champion_registry_fingerprint: str,
    challenger_registry_fingerprint: str,
    seed: int,
) -> PromotionGovernanceDecision:
    return PromotionGovernanceDecision(
        config_fingerprint=_hash(seed),
        champion_registry_fingerprint=champion_registry_fingerprint,
        challenger_registry_fingerprint=challenger_registry_fingerprint,
        shadow_evidence_fingerprint=_hash(seed + 1),
        monitoring_report_fingerprint=_hash(seed + 2),
        decision="PROMOTION_RECOMMENDED",
        reason_codes=["ALL_OBJECTIVE_PROMOTION_GATES_PASSED"],
        recommended_registry_fingerprint=challenger_registry_fingerprint,
        rollback_registry_fingerprint=champion_registry_fingerprint,
        required_next_gate="V2_09_EXPLICIT_ACTIVATION_APPROVAL",
        decision_fingerprint=_hash(seed + 3),
    )


def _transition(
    plane,
    *,
    registry_fingerprint: str,
    target_state: str,
    at: datetime,
    seed: int,
):
    return transition_model_lifecycle(
        plane,
        registry_fingerprint=registry_fingerprint,
        target_state=target_state,
        evidence_fingerprint=_hash(seed),
        config_fingerprint=_hash(seed + 1),
        occurred_at=at,
        actor="model-governance-test",
        reason=f"move model to {target_state}",
    )


def test_control_plane_enrollment_is_fingerprinted_and_cannot_authorize_capital() -> None:
    candidate = _candidate(seed=100, challenger_of=_hash(90))
    as_of = REGISTERED_AT + timedelta(minutes=1)

    first = initialize_model_governance_control_plane(
        [candidate],
        governance_config_fingerprint=_hash(200),
        as_of=as_of,
    )
    second = initialize_model_governance_control_plane(
        [candidate],
        governance_config_fingerprint=_hash(200),
        as_of=as_of,
    )

    assert first.snapshot_fingerprint == second.snapshot_fingerprint
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False
    assert first.audit_log == []
    entry = first.entries[0]
    assert entry.lifecycle_state == "REGISTERED"
    assert entry.enabled is True
    assert entry.quarantined is False
    assert entry.live_eligible is False
    assert entry.can_authorize_capital is False
    assert entry.challenger_of == _hash(90)


def test_generic_transition_cannot_bypass_explicit_champion_activation() -> None:
    candidate = _candidate(seed=110, challenger_of=_hash(91))
    plane = initialize_model_governance_control_plane(
        [candidate],
        governance_config_fingerprint=_hash(210),
        as_of=REGISTERED_AT + timedelta(minutes=1),
    )

    with pytest.raises(ValueError, match="explicit promotion approval"):
        _transition(
            plane,
            registry_fingerprint=candidate.registry_fingerprint,
            target_state="CHAMPION",
            at=REGISTERED_AT + timedelta(minutes=2),
            seed=220,
        )


def test_shadow_eligible_and_quarantine_transitions_are_auditable() -> None:
    candidate = _candidate(seed=120, challenger_of=_hash(92))
    plane = initialize_model_governance_control_plane(
        [candidate],
        governance_config_fingerprint=_hash(230),
        as_of=REGISTERED_AT + timedelta(minutes=1),
    )
    plane = _transition(
        plane,
        registry_fingerprint=candidate.registry_fingerprint,
        target_state="SHADOW",
        at=REGISTERED_AT + timedelta(minutes=2),
        seed=231,
    )
    plane = _transition(
        plane,
        registry_fingerprint=candidate.registry_fingerprint,
        target_state="ELIGIBLE",
        at=REGISTERED_AT + timedelta(minutes=3),
        seed=233,
    )
    before_quarantine = plane.snapshot_fingerprint
    plane = _transition(
        plane,
        registry_fingerprint=candidate.registry_fingerprint,
        target_state="QUARANTINED",
        at=REGISTERED_AT + timedelta(minutes=4),
        seed=235,
    )

    assert plane.snapshot_fingerprint != before_quarantine
    assert [event.to_state for event in plane.audit_log] == [
        "SHADOW",
        "ELIGIBLE",
        "QUARANTINED",
    ]
    assert len({event.audit_fingerprint for event in plane.audit_log}) == 3
    entry = plane.entries[0]
    assert entry.lifecycle_state == "QUARANTINED"
    assert entry.enabled is False
    assert entry.quarantined is True
    assert entry.live_eligible is False
    assert entry.can_authorize_capital is False

    with pytest.raises(ValueError, match="only an ELIGIBLE model"):
        activate_champion(
            plane,
            registry_fingerprint=candidate.registry_fingerprint,
            promotion_decision=_promotion_decision(
                champion_registry_fingerprint=_hash(92),
                challenger_registry_fingerprint=candidate.registry_fingerprint,
                seed=240,
            ),
            explicit_activation_approved=True,
            occurred_at=REGISTERED_AT + timedelta(minutes=5),
            actor="governance-approver",
            reason="attempt promotion while quarantined",
        )


def test_champion_activation_requires_approval_and_preserves_java_capital_boundary() -> None:
    historical_champion = _hash(93)
    candidate = _candidate(seed=130, challenger_of=historical_champion)
    plane = initialize_model_governance_control_plane(
        [candidate],
        governance_config_fingerprint=_hash(250),
        as_of=REGISTERED_AT + timedelta(minutes=1),
    )
    plane = _transition(
        plane,
        registry_fingerprint=candidate.registry_fingerprint,
        target_state="SHADOW",
        at=REGISTERED_AT + timedelta(minutes=2),
        seed=251,
    )
    plane = _transition(
        plane,
        registry_fingerprint=candidate.registry_fingerprint,
        target_state="ELIGIBLE",
        at=REGISTERED_AT + timedelta(minutes=3),
        seed=253,
    )
    decision = _promotion_decision(
        champion_registry_fingerprint=historical_champion,
        challenger_registry_fingerprint=candidate.registry_fingerprint,
        seed=260,
    )

    with pytest.raises(ValueError, match="explicit approval"):
        activate_champion(
            plane,
            registry_fingerprint=candidate.registry_fingerprint,
            promotion_decision=decision,
            explicit_activation_approved=False,
            occurred_at=REGISTERED_AT + timedelta(minutes=4),
            actor="governance-approver",
            reason="promotion rejected without explicit approval",
        )

    promoted = activate_champion(
        plane,
        registry_fingerprint=candidate.registry_fingerprint,
        promotion_decision=decision,
        explicit_activation_approved=True,
        occurred_at=REGISTERED_AT + timedelta(minutes=4),
        actor="governance-approver",
        reason="objective gates passed and activation explicitly approved",
    )

    entry = promoted.entries[0]
    assert entry.lifecycle_state == "CHAMPION"
    assert entry.live_eligible is True
    assert entry.enabled is True
    assert entry.can_authorize_capital is False
    assert promoted.can_authorize_capital is False
    assert promoted.audit_log[-1].explicit_activation_approved is True
    assert promoted.audit_log[-1].promotion_decision_fingerprint == decision.decision_fingerprint


def test_new_champion_atomically_demotes_previous_champion() -> None:
    external_champion = _hash(94)
    first = _candidate(seed=140, challenger_of=external_champion)
    second = _candidate(seed=150, challenger_of=first.registry_fingerprint)
    plane = initialize_model_governance_control_plane(
        [first, second],
        governance_config_fingerprint=_hash(270),
        as_of=REGISTERED_AT + timedelta(minutes=1),
    )

    plane = _transition(
        plane,
        registry_fingerprint=first.registry_fingerprint,
        target_state="SHADOW",
        at=REGISTERED_AT + timedelta(minutes=2),
        seed=271,
    )
    plane = _transition(
        plane,
        registry_fingerprint=first.registry_fingerprint,
        target_state="ELIGIBLE",
        at=REGISTERED_AT + timedelta(minutes=3),
        seed=273,
    )
    plane = activate_champion(
        plane,
        registry_fingerprint=first.registry_fingerprint,
        promotion_decision=_promotion_decision(
            champion_registry_fingerprint=external_champion,
            challenger_registry_fingerprint=first.registry_fingerprint,
            seed=280,
        ),
        explicit_activation_approved=True,
        occurred_at=REGISTERED_AT + timedelta(minutes=4),
        actor="governance-approver",
        reason="establish first controlled champion",
    )
    plane = _transition(
        plane,
        registry_fingerprint=second.registry_fingerprint,
        target_state="SHADOW",
        at=REGISTERED_AT + timedelta(minutes=5),
        seed=285,
    )
    plane = _transition(
        plane,
        registry_fingerprint=second.registry_fingerprint,
        target_state="ELIGIBLE",
        at=REGISTERED_AT + timedelta(minutes=6),
        seed=287,
    )
    plane = activate_champion(
        plane,
        registry_fingerprint=second.registry_fingerprint,
        promotion_decision=_promotion_decision(
            champion_registry_fingerprint=first.registry_fingerprint,
            challenger_registry_fingerprint=second.registry_fingerprint,
            seed=290,
        ),
        explicit_activation_approved=True,
        occurred_at=REGISTERED_AT + timedelta(minutes=7),
        actor="governance-approver",
        reason="replace champion with approved challenger",
    )

    states = {entry.registry_fingerprint: entry.lifecycle_state for entry in plane.entries}
    assert states[first.registry_fingerprint] == "ELIGIBLE"
    assert states[second.registry_fingerprint] == "CHAMPION"
    assert sum(entry.live_eligible for entry in plane.entries) == 1
    assert all(entry.can_authorize_capital is False for entry in plane.entries)
    assert [event.to_state for event in plane.audit_log[-2:]] == ["ELIGIBLE", "CHAMPION"]
