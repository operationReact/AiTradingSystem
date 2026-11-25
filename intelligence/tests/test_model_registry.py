from datetime import UTC, datetime

import pytest

from aitrading_intelligence.model_registry import register_governance_candidate
from aitrading_intelligence.monte_carlo import MonteCarloEvidence, MonteCarloSummary

REGISTERED_AT = datetime(2026, 1, 6, 4, 0, tzinfo=UTC)


def _hash(value: int) -> str:
    return f"{value:064x}"


def _evidence(*, passes: bool) -> MonteCarloEvidence:
    return MonteCarloEvidence(
        config_fingerprint=_hash(1),
        source_walk_forward_fingerprint=_hash(2),
        source_trade_fingerprints=[_hash(3), _hash(4), _hash(5), _hash(6), _hash(7)],
        returns_distribution_fingerprint=_hash(8),
        drawdowns_distribution_fingerprint=_hash(9),
        summary=MonteCarloSummary(
            simulations=100,
            oos_trade_count=5,
            p05_total_return=0.01 if passes else -0.50,
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
        assessment=(
            "V2_09_GOVERNANCE_CANDIDATE" if passes else "V2_08_RESEARCH_REVIEW"
        ),
        next_gate=(
            "V2_09_OUTCOME_GOVERNANCE" if passes else "V2_08_RESEARCH_REVIEW"
        ),
        evidence_fingerprint=_hash(10 if passes else 11),
    )


def test_registry_accepts_only_v2_08_governance_candidates() -> None:
    candidate = register_governance_candidate(
        model_family="PYTORCH",
        model_version="pytorch-mlp.v1",
        artifact_fingerprint=_hash(20),
        feature_schema_fingerprint=_hash(21),
        training_dataset_fingerprint=_hash(22),
        training_config_fingerprint=_hash(23),
        code_sha="a" * 40,
        evaluation_fingerprint=_hash(24),
        monte_carlo_evidence=_evidence(passes=True),
        registered_at=REGISTERED_AT,
        challenger_of=_hash(25),
    )

    assert candidate.stage == "GOVERNANCE_CANDIDATE"
    assert candidate.required_next_gate == "V2_09_SHADOW_PAPER_EVALUATION"
    assert candidate.monte_carlo_evidence_fingerprint == _hash(10)
    assert candidate.promotion_allowed is False
    assert candidate.can_authorize_capital is False

    with pytest.raises(ValueError, match="passing V2-08"):
        register_governance_candidate(
            model_family="PYTORCH",
            model_version="pytorch-mlp.v1",
            artifact_fingerprint=_hash(20),
            feature_schema_fingerprint=_hash(21),
            training_dataset_fingerprint=_hash(22),
            training_config_fingerprint=_hash(23),
            code_sha="a" * 40,
            evaluation_fingerprint=_hash(24),
            monte_carlo_evidence=_evidence(passes=False),
            registered_at=REGISTERED_AT,
        )


def test_registry_fingerprint_is_stable_and_lineage_sensitive() -> None:
    kwargs = {
        "model_family": "LOGISTIC",
        "model_version": "logistic.v1",
        "artifact_fingerprint": _hash(30),
        "feature_schema_fingerprint": _hash(31),
        "training_dataset_fingerprint": _hash(32),
        "training_config_fingerprint": _hash(33),
        "code_sha": "b" * 40,
        "evaluation_fingerprint": _hash(34),
        "monte_carlo_evidence": _evidence(passes=True),
        "registered_at": REGISTERED_AT,
    }
    first = register_governance_candidate(**kwargs)
    second = register_governance_candidate(**kwargs)
    changed = register_governance_candidate(
        **{**kwargs, "training_dataset_fingerprint": _hash(35)}
    )

    assert first.registry_fingerprint == second.registry_fingerprint
    assert first.registry_fingerprint != changed.registry_fingerprint
