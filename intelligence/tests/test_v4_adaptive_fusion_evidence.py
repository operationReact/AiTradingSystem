"""Causal, fail-closed tests for V4-06.2 research comparison."""

from datetime import UTC, datetime, timedelta

import pytest
from test_learned_meta_model_readiness import BASE, _report

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.v4_adaptive_fusion_evidence import (
    AdaptiveFusionEvidenceConfig,
    compare_v4_adaptive_fusion,
)
from aitrading_intelligence.v4_decision_fusion_baseline import (
    FusionBaselineFreezeConfig,
    freeze_v4_static_fusion_baseline,
)

BASE_CFG = FusionBaselineFreezeConfig(
    minimum_independent_windows=2, minimum_resolved_requests=4
)
COMPARE_CFG = AdaptiveFusionEvidenceConfig(
    minimum_independent_windows=2,
    minimum_resolved_requests=4,
    minimum_window_challenger_trades=2,
    minimum_challenger_coverage=0.5,
    minimum_window_expectancy_uplift_bps=1.0,
    maximum_window_drawdown_regression=0.0,
)
CUTOFF = BASE + timedelta(days=3)


def _freeze(reports):
    return freeze_v4_static_fusion_baseline(
        reports, assessed_at=CUTOFF, config=BASE_CFG
    )


def _compare(reports, **kwargs):
    return compare_v4_adaptive_fusion(
        reports,
        baseline=kwargs.get("baseline", _freeze(reports)),
        assessed_at=kwargs.get("assessed_at", CUTOFF),
        config=kwargs.get("config", COMPARE_CFG),
        baseline_config=BASE_CFG,
    )


def test_separate_v3_windows_yield_unpromoted_research_candidate() -> None:
    report = _compare([_report(1), _report(2)])
    assert report.contract_version == "v4-adaptive-fusion-evidence.v1"
    assert report.resolved_requests == 4
    assert report.research_uplift_windows == 2
    assert report.champion_preserved_windows == 0
    assert report.inconclusive_windows == 0
    assert report.assessment == "OFFLINE_RESEARCH_CANDIDATE"
    assert all(w.static_enter_count == 2 for w in report.windows)
    assert all(w.challenger_non_enter_count == 0 for w in report.windows)
    assert report.non_enter_breakdown == "WAIT_VS_ABSTAIN_UNAVAILABLE"
    assert report.latency_status == "NOT_MEASURED"
    assert report.source_custody == "UNVERIFIED"
    assert report.real_paper_shadow_status == "NOT_VALIDATED"
    assert report.champion_policy_changed is False
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_one_failed_window_preserves_static_champion() -> None:
    report = _compare([
        _report(1),
        _report(2, assessment="STATIC_CHAMPION_PRESERVED"),
    ])
    assert report.assessment == "STATIC_CHAMPION_PRESERVED"
    assert report.champion_preserved_windows == 1
    assert report.research_uplift_windows == 1
    assert "NOT_ALL_WINDOWS_SHOW_RESEARCH_UPLIFT" in report.reason_codes
    assert report.champion_policy_changed is False


def test_insufficient_sample_and_source_status_are_inconclusive() -> None:
    short = _compare([_report(1)])
    assert short.assessment == "INSUFFICIENT_EVIDENCE"
    assert "INSUFFICIENT_INDEPENDENT_WINDOWS" in short.reason_codes

    source = _compare([
        _report(1),
        _report(2, assessment="INSUFFICIENT_EVIDENCE"),
    ])
    assert source.assessment == "INSUFFICIENT_EVIDENCE"
    assert source.inconclusive_windows == 1
    assert source.windows[1].assessment == "INCONCLUSIVE"


def test_minimum_trades_is_enforced_per_window() -> None:
    stronger = COMPARE_CFG.model_copy(
        update={"minimum_window_challenger_trades": 3}
    )
    report = _compare([_report(1), _report(2)], config=stronger)
    assert report.inconclusive_windows == 2
    assert report.assessment == "INSUFFICIENT_EVIDENCE"
    assert all(
        "INSUFFICIENT_WINDOW_CHALLENGER_TRADES" in w.reason_codes
        for w in report.windows
    )


def test_order_and_future_only_input_mutation_do_not_change_frozen_report() -> None:
    reports = [_report(2), _report(1)]
    original = _compare(reports)
    reversed_order = _compare(list(reversed(reports)))
    future = _compare([
        *reports, _report(9, evaluation_as_of=CUTOFF + timedelta(days=1))
    ])
    assert original.model_dump(mode="json") == reversed_order.model_dump(mode="json")
    assert original.evidence_fingerprint == future.evidence_fingerprint


def test_baseline_must_match_exact_source_and_config_fingerprints() -> None:
    reports = [_report(1), _report(2)]
    forged = _freeze(reports).model_copy(
        update={"evidence_fingerprint": "0" * 64}
    )
    with pytest.raises(ValueError, match="baseline does not match"):
        _compare(reports, baseline=forged)

    with pytest.raises(ValueError, match="baseline does not match"):
        compare_v4_adaptive_fusion(
            reports,
            baseline=_freeze(reports),
            assessed_at=CUTOFF,
            config=COMPARE_CFG,
            # Different V4-06.1 sample policy is not an equivalent freeze.
            baseline_config=FusionBaselineFreezeConfig(),
        )


def test_tampered_and_overlapping_v3_reports_fail_before_comparison() -> None:
    reports = [_report(1), _report(2)]
    with pytest.raises(ValueError, match="fingerprint"):
        _compare([
            reports[0].model_copy(update={"report_fingerprint": "0" * 64}),
            reports[1],
        ])
    with pytest.raises(ValueError, match="overlapping request cohort"):
        _compare([_report(1), _report(2, request_seed=100)])


def test_covered_report_with_inconsistent_metrics_is_rejected() -> None:
    original = _report(1)
    bad_point = original.guarded_challenger.model_copy(
        update={"coverage": 0.4}
    )
    altered = original.model_copy(update={"guarded_challenger": bad_point})
    value = altered.model_dump(mode="python", exclude={"report_fingerprint"})
    value["evaluation_as_of"] = altered.evaluation_as_of.isoformat()
    altered = altered.model_copy(
        update={"report_fingerprint": canonical_fingerprint(value)}
    )
    with pytest.raises(ValueError, match="coverage does not reconcile"):
        _compare([altered, _report(2)])


def test_naive_cutoff_does_not_generate_a_research_decision() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        compare_v4_adaptive_fusion(
            [_report(1)],
            baseline=_freeze([_report(1)]),
            assessed_at=datetime(2026, 1, 15),
            config=COMPARE_CFG,
            baseline_config=BASE_CFG,
        )
    with pytest.raises(ValueError, match="precede"):
        compare_v4_adaptive_fusion(
            [_report(9)],
            baseline=_freeze([_report(1)]),
            assessed_at=datetime(2026, 1, 1, tzinfo=UTC),
            config=COMPARE_CFG,
            baseline_config=BASE_CFG,
        )
