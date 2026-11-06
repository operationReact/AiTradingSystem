"""V4-07.3 advisory cannot create signals or grant trading authority."""

from datetime import UTC, datetime, timedelta

import pytest
from test_regime import _context

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion import FusionEvidence, FusionRequest
from aitrading_intelligence.regime import (
    RegimeSnapshot,
    build_heuristic_regime_snapshot,
)
from aitrading_intelligence.regime_anomaly import IsolationAnomalyObservation
from aitrading_intelligence.regime_anomaly_advisory import (
    RegimeAnomalyAdvisoryConfig,
    build_regime_anomaly_advisory,
)

AT = datetime(2026, 11, 3, 10, 0, tzinfo=UTC)


def _data(*, stressed=False, timestamp=AT):
    return _context(
        as_of=timestamp,
        return_1d=-0.08 if stressed else 0.025,
        net_breadth=-0.9 if stressed else 0.7,
        dispersion=0.07 if stressed else 0.008,
        india_vix=55.0 if stressed else 14.0,
    )


def _evidence(family: str, score: float, ts: datetime, seed: str):
    return FusionEvidence(
        family=family, provider="synthetic-test", version="v1",
        score=score, confidence=1.0, observed_at=ts,
        fingerprint=seed * 64, reasons=["test-only"],
    )


def _fixture(*, buy=True, stressed=False, age_seconds=0):
    ts = AT - timedelta(seconds=age_seconds)
    regime = build_heuristic_regime_snapshot(context=_data(stressed=stressed, timestamp=ts))
    request = FusionRequest(
        symbol="TESTEQ", as_of=AT, regime=regime,
        deterministic=_evidence("DETERMINISTIC", 0.9 if buy else -0.9, AT, "a"),
        quant=_evidence("QUANT", 0.8 if buy else -0.8, AT, "b"),
        gemini=_evidence("GEMINI", 0.7 if buy else -0.7, AT, "c"),
    )
    return request, regime


def _anomaly(regime, *, elevated=False, anomalous_score=None):
    score = anomalous_score if anomalous_score is not None else (0.8 if elevated else 0.2)
    payload = {
        "contract_version": "india-regime-anomaly-observation.v1",
        "decision_at": regime.as_of.isoformat(),
        "data_cutoff": regime.data_cutoff.isoformat(),
        "source_snapshot_fingerprint": regime.input_fingerprint,
        "model_fingerprint": "d" * 64,
        "training_data_cutoff": (regime.as_of - timedelta(days=5)).isoformat(),
        "anomaly_score": score,
        "threshold": 0.5,
        "elevated_anomaly": elevated,
        "action": (
            "REDUCE_SIGNAL_ELIGIBILITY" if elevated else "NO_ANOMALY_REDUCTION"
        ),
        "status": "RESEARCH_ANOMALY_ADVISORY_ONLY",
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IsolationAnomalyObservation(
        **payload, evidence_fingerprint=canonical_fingerprint(payload)
    )


def _hmm(regime, *, stress=False):
    payload = regime.model_dump(mode="json", exclude={"snapshot_fingerprint"})
    payload["method"] = "HMM_CHALLENGER"
    payload["model_fingerprint"] = "e" * 64
    payload["training_data_cutoff"] = (regime.as_of - timedelta(days=10)).isoformat()
    if stress:
        payload["probabilities"] = {
            "TREND_UP": 0.02,
            "TREND_DOWN": 0.02,
            "RANGE_LOW_VOL": 0.06,
            "STRESS_HIGH_VOL": 0.90,
        }
        payload["confidence"] = 0.90
        payload["dominant_regime"] = "STRESS_HIGH_VOL"
    return RegimeSnapshot(
        **payload, snapshot_fingerprint=canonical_fingerprint(payload)
    )


def test_normal_evidence_only_retains_existing_non_executable_buy_candidate():
    request, regime = _fixture()
    result = build_regime_anomaly_advisory(
        request, heuristic=regime, anomaly=_anomaly(regime),
    )
    assert result.original_fusion_action == "BUY"
    assert result.research_disposition == "RETAIN_UNCHANGED_RESEARCH_ONLY"
    assert result.research_buy_candidate_eligible is True
    assert result.production_risk_policy_unchanged is True
    assert result.broker_or_paper_execution_enabled is False
    assert result.model_promotion_allowed is False
    assert result.can_authorize_capital is False
    assert result.source_market_context_fingerprint == regime.input_fingerprint


def test_watch_is_never_upgraded_even_when_all_evidence_is_normal():
    request, regime = _fixture(buy=False)
    result = build_regime_anomaly_advisory(
        request, heuristic=regime, anomaly=_anomaly(regime),
    )
    assert result.original_fusion_action == "WATCH"
    assert result.research_disposition == "PRESERVE_NON_BUY"
    assert result.research_buy_candidate_eligible is False
    assert "FUSION_WATCH_CANNOT_BE_UPGRADED" in result.reason_codes


def test_anomaly_or_market_stress_can_only_reduce_research_eligibility():
    request, regime = _fixture()
    anomaly = build_regime_anomaly_advisory(
        request, heuristic=regime, anomaly=_anomaly(regime, elevated=True),
    )
    assert anomaly.research_disposition == "REDUCE_RESEARCH_ELIGIBILITY"
    assert anomaly.research_buy_candidate_eligible is False
    assert "ELEVATED_ANOMALY" in anomaly.reason_codes

    stressed_request, stressed_regime = _fixture(stressed=True)
    stress = build_regime_anomaly_advisory(
        stressed_request,
        heuristic=stressed_regime,
        anomaly=_anomaly(stressed_regime),
    )
    assert stress.research_disposition == "REDUCE_RESEARCH_ELIGIBILITY"
    assert "HIGH_STRESS_REGIME" in stress.reason_codes
    assert stress.can_authorize_capital is False


def test_missing_or_expired_advisory_data_fails_conservatively():
    request, regime = _fixture()
    missing = build_regime_anomaly_advisory(
        request, heuristic=regime, anomaly=None,
    )
    assert missing.research_buy_candidate_eligible is False
    assert "ANOMALY_EVIDENCE_MISSING" in missing.reason_codes

    stale_request, stale_regime = _fixture(age_seconds=100)
    stale = build_regime_anomaly_advisory(
        stale_request, heuristic=stale_regime, anomaly=_anomaly(stale_regime),
        config=RegimeAnomalyAdvisoryConfig(
            maximum_regime_age_seconds=60, maximum_anomaly_age_seconds=60,
        ),
    )
    assert "HEURISTIC_REGIME_EXPIRED" in stale.reason_codes
    assert "ANOMALY_EVIDENCE_EXPIRED" in stale.reason_codes
    assert stale.research_buy_candidate_eligible is False


def test_hmm_optional_conflicting_challenger_does_not_replace_v3_heuristic():
    request, regime = _fixture()
    agreeable = build_regime_anomaly_advisory(
        request, heuristic=regime, anomaly=_anomaly(regime), hmm=_hmm(regime),
    )
    assert agreeable.research_buy_candidate_eligible is True
    conflicting = build_regime_anomaly_advisory(
        request, heuristic=regime, anomaly=_anomaly(regime),
        hmm=_hmm(regime, stress=True),
    )
    assert conflicting.research_disposition == "REDUCE_RESEARCH_ELIGIBILITY"
    assert "HMM_HEURISTIC_DISAGREEMENT" in conflicting.reason_codes
    assert "HIGH_STRESS_REGIME" in conflicting.reason_codes
    assert conflicting.source_heuristic_fingerprint == regime.snapshot_fingerprint


def test_causal_sources_are_bound_to_exact_same_snapshot_identity():
    request, regime = _fixture()
    other_request, other_regime = _fixture(stressed=True)
    with pytest.raises(ValueError, match="source market context mismatch"):
        build_regime_anomaly_advisory(
            request, heuristic=regime, anomaly=_anomaly(other_regime),
        )
    with pytest.raises(ValueError, match="source market context mismatch"):
        build_regime_anomaly_advisory(
            request, heuristic=regime, anomaly=_anomaly(regime), hmm=_hmm(other_regime),
        )
    with pytest.raises(ValueError, match="source fingerprints mismatch"):
        build_regime_anomaly_advisory(
            request, heuristic=other_regime, anomaly=_anomaly(other_regime),
        )
    assert other_request.regime is not None


def test_tampered_frozen_regime_anomaly_and_fusion_contents_are_rejected():
    request, regime = _fixture()
    bad_regime = regime.model_copy(update={"confidence": 0.7})
    with pytest.raises(ValueError, match="snapshot_fingerprint mismatch"):
        build_regime_anomaly_advisory(
            request, heuristic=bad_regime, anomaly=_anomaly(regime),
        )

    bad_anomaly = _anomaly(regime).model_copy(update={"anomaly_score": 0.99})
    with pytest.raises(ValueError, match="evidence_fingerprint mismatch"):
        build_regime_anomaly_advisory(
            request, heuristic=regime, anomaly=bad_anomaly,
        )

    corrupt_request = request.model_copy(
        update={"regime": regime.model_copy(update={"confidence": 0.7})}
    )
    with pytest.raises(ValueError, match="not the exact frozen heuristic"):
        build_regime_anomaly_advisory(
            corrupt_request, heuristic=regime, anomaly=_anomaly(regime),
        )


def test_forged_self_consistent_anomaly_threshold_action_is_rejected():
    request, regime = _fixture()
    bad = _anomaly(regime, elevated=False, anomalous_score=0.8)
    with pytest.raises(ValueError, match="anomaly score and action are inconsistent"):
        build_regime_anomaly_advisory(
            request, heuristic=regime, anomaly=bad,
        )


def test_result_is_deterministic_and_future_only_inputs_cannot_rewrite_causal_output():
    request, regime = _fixture()
    baseline = build_regime_anomaly_advisory(
        request, heuristic=regime, anomaly=_anomaly(regime),
    )
    again = build_regime_anomaly_advisory(
        request, heuristic=regime, anomaly=_anomaly(regime),
    )
    assert baseline.advisory_fingerprint == again.advisory_fingerprint

    future = _data(timestamp=AT + timedelta(hours=2), stressed=True)
    assert future.as_of > request.as_of
    assert baseline.advisory_fingerprint == again.advisory_fingerprint
