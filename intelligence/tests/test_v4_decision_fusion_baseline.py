"""V4-06.1 regression tests over the accepted V3-06 source evidence fixtures."""

from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.v4_decision_fusion_baseline import (
    FusionBaselineFreezeConfig,
    freeze_v4_static_fusion_baseline,
)
from test_learned_meta_model_readiness import BASE, _report


def _config(**kwargs) -> FusionBaselineFreezeConfig:
    return FusionBaselineFreezeConfig(
        minimum_independent_windows=kwargs.get("minimum_independent_windows", 2),
        minimum_resolved_requests=kwargs.get("minimum_resolved_requests", 4),
    )


def test_v3_static_champion_is_preserved_without_execution_authority() -> None:
    result = freeze_v4_static_fusion_baseline(
        [_report(1), _report(2)],
        assessed_at=BASE + timedelta(days=3),
        config=_config(),
    )
    assert result.contract_version == "v4-decision-fusion-baseline.v1"
    assert result.evaluated_windows == 2
    assert result.resolved_requests == 4
    assert result.static_champion.policy == "STATIC_45_35_20"
    assert result.adaptive_challenger.policy == "ADAPTIVE_DISAGREEMENT_GUARDED"
    assert result.static_champion.trades == 4
    assert result.static_champion.mean_net_return_bps == pytest.approx(50.0)
    assert result.adaptive_challenger.mean_net_return_bps == pytest.approx(120.0)
    assert result.assessment == "OFFLINE_UNVERIFIED"
    assert result.latency_evidence == "NOT_MEASURED"
    assert result.external_source_custody_verified is False
    assert result.paper_shadow_runtime_validated is False
    assert result.model_promotion_allowed is False
    assert result.can_authorize_capital is False


def test_canonical_order_and_future_only_invariance() -> None:
    reports = [_report(2), _report(1)]
    cutoff = BASE + timedelta(days=3)
    original = freeze_v4_static_fusion_baseline(
        reports, assessed_at=cutoff, config=_config()
    )
    shuffled = freeze_v4_static_fusion_baseline(
        list(reversed(reports)), assessed_at=cutoff, config=_config()
    )
    future = freeze_v4_static_fusion_baseline(
        [*reports, _report(9, evaluation_as_of=cutoff + timedelta(days=1))],
        assessed_at=cutoff,
        config=_config(),
    )
    assert original.model_dump(mode="json") == shuffled.model_dump(mode="json")
    assert original.evidence_fingerprint == future.evidence_fingerprint


def test_insufficient_frozen_evidence_remains_explicitly_inconclusive() -> None:
    result = freeze_v4_static_fusion_baseline(
        [_report(1)],
        assessed_at=BASE + timedelta(days=3),
        config=_config(minimum_resolved_requests=20),
    )
    assert result.assessment == "INSUFFICIENT_SAMPLE"
    assert "INSUFFICIENT_INDEPENDENT_OOS_WINDOWS" in result.reason_codes
    assert "INSUFFICIENT_RESOLVED_REQUESTS" in result.reason_codes
    assert result.model_promotion_allowed is False


def test_repeated_report_and_overlapping_oos_membership_rejected() -> None:
    cutoff = BASE + timedelta(days=3)
    with pytest.raises(ValueError, match="duplicate report"):
        freeze_v4_static_fusion_baseline(
            [_report(1), _report(1)], assessed_at=cutoff, config=_config()
        )
    with pytest.raises(ValueError, match="overlapping request cohort"):
        freeze_v4_static_fusion_baseline(
            [_report(1), _report(2, request_seed=100)],
            assessed_at=cutoff,
            config=_config(),
        )


def test_target_or_cost_policy_mismatch_fails_closed() -> None:
    with pytest.raises(ValueError, match="share one target, cost and policy"):
        freeze_v4_static_fusion_baseline(
            [_report(1), _report(2, target_spec="e" * 64)],
            assessed_at=BASE + timedelta(days=3),
            config=_config(),
        )


def test_tampered_v3_source_report_is_not_accepted() -> None:
    forged = _report(1).model_copy(
        update={"report_fingerprint": "0" * 64}
    )
    with pytest.raises(ValueError, match="fingerprint"):
        freeze_v4_static_fusion_baseline(
            [forged], assessed_at=BASE + timedelta(days=3), config=_config()
        )


def test_naive_cutoff_and_future_only_set_are_not_accepted() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        freeze_v4_static_fusion_baseline(
            [_report(1)], assessed_at=datetime(2026, 1, 20), config=_config()
        )
    with pytest.raises(ValueError, match="precede"):
        freeze_v4_static_fusion_baseline(
            [_report(9)],
            assessed_at=datetime(2026, 1, 1, tzinfo=UTC),
            config=_config(),
        )
