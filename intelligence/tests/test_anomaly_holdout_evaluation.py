"""V4-07.2 non-leaking anomaly warning holdout and synthetic label tests."""

from datetime import UTC, datetime, timedelta

import pytest
from test_regime_anomaly import _context, _fit

from aitrading_intelligence.anomaly_holdout_evaluation import (
    AnomalyHoldoutConfig,
    AnomalyHoldoutLabel,
    evaluate_anomaly_holdout,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.regime_anomaly import (
    IsolationAnomalyObservation,
    score_isolation_anomaly,
)

BASE = datetime(2026, 2, 2, 5, 0, tzinfo=UTC)
CUTOFF = BASE + timedelta(days=1)
CONFIG = AnomalyHoldoutConfig(
    minimum_resolved_observations=10,
    minimum_abnormal_labels=5,
    minimum_normal_labels=5,
    minimum_recall=0.70,
    maximum_false_positive_rate=0.20,
)


def _observation(index: int, *, alert: bool) -> IsolationAnomalyObservation:
    decision = BASE + timedelta(hours=index)
    data_cutoff = decision - timedelta(minutes=5)
    training_cutoff = BASE - timedelta(days=2)
    payload = {
        "contract_version": "india-regime-anomaly-observation.v1",
        "decision_at": decision.isoformat(),
        "data_cutoff": data_cutoff.isoformat(),
        "source_snapshot_fingerprint": canonical_fingerprint({"id": index}),
        "model_fingerprint": "a" * 64,
        "training_data_cutoff": training_cutoff.isoformat(),
        "anomaly_score": 0.8 if alert else 0.2,
        "threshold": 0.5,
        "elevated_anomaly": alert,
        "action": "REDUCE_SIGNAL_ELIGIBILITY" if alert else "NO_ANOMALY_REDUCTION",
        "status": "RESEARCH_ANOMALY_ADVISORY_ONLY",
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IsolationAnomalyObservation(
        **payload, evidence_fingerprint=canonical_fingerprint(payload)
    )


def _label(
    observation: IsolationAnomalyObservation,
    *,
    abnormal: bool,
    observed_at: datetime | None = None,
) -> AnomalyHoldoutLabel:
    payload = {
        "label_version": "india-anomaly-retrospective-label.v1",
        "decision_at": observation.decision_at.isoformat(),
        "observed_at": (
            observed_at or (observation.decision_at + timedelta(minutes=30))
        ).isoformat(),
        "source_snapshot_fingerprint": observation.source_snapshot_fingerprint,
        "abnormal_market_outcome": abnormal,
        "evidence_status": "SELF_DECLARED_UNVERIFIED",
    }
    return AnomalyHoldoutLabel(
        **payload, label_fingerprint=canonical_fingerprint(payload)
    )


def _population():
    observations = [
        _observation(i, alert=i in (0, 1, 2, 3, 4, 6))
        for i in range(12)
    ]
    labels = [
        _label(observation, abnormal=i < 6)
        for i, observation in enumerate(observations)
    ]
    return observations, labels


def test_self_declared_holdout_passes_only_research_screen() -> None:
    observations, labels = _population()
    result = evaluate_anomaly_holdout(
        observations, labels, evaluated_as_of=CUTOFF, config=CONFIG
    )
    assert result.assessment == "RESEARCH_SCREEN_PASSED"
    assert (result.true_positive, result.false_positive, result.true_negative,
            result.false_negative) == (5, 1, 5, 1)
    assert result.recall == pytest.approx(5 / 6)
    assert result.false_positive_rate == pytest.approx(1 / 6)
    assert result.resolved_observations == 12
    assert result.unresolved_observations == 0
    assert result.label_custody == "SELF_DECLARED_UNVERIFIED"
    assert result.real_market_acceptance is False
    assert result.promotion_allowed is False
    assert result.can_authorize_capital is False


def test_one_weak_challenger_screen_fails_but_does_not_authorize_capital() -> None:
    observations, labels = _population()
    changed = [_observation(i, alert=i in (0, 6, 7, 8)) for i in range(12)]
    result = evaluate_anomaly_holdout(
        changed, labels, evaluated_as_of=CUTOFF, config=CONFIG
    )
    assert result.assessment == "RESEARCH_SCREEN_FAILED"
    assert "RECALL_OR_FALSE_POSITIVE_POLICY_FAILED" in result.reason_codes
    assert result.promotion_allowed is False
    assert result.can_authorize_capital is False


def test_missing_and_not_yet_realized_labels_are_reported_inconclusive() -> None:
    observations, labels = _population()
    result = evaluate_anomaly_holdout(
        observations,
        labels[:5],
        evaluated_as_of=CUTOFF,
        config=CONFIG,
    )
    assert result.assessment == "INCONCLUSIVE"
    assert result.resolved_observations == 5
    assert result.unresolved_observations == 7
    assert result.false_positive_rate is None

    late_label = _label(
        observations[5],
        abnormal=True,
        observed_at=CUTOFF + timedelta(hours=2),
    )
    future = evaluate_anomaly_holdout(
        observations, [*labels[:5], late_label],
        evaluated_as_of=CUTOFF,
        config=CONFIG,
    )
    assert future.report_fingerprint == result.report_fingerprint


def test_future_only_evidence_and_order_reversal_do_not_change_result() -> None:
    observations, labels = _population()
    result = evaluate_anomaly_holdout(
        observations, labels, evaluated_as_of=CUTOFF, config=CONFIG
    )
    reversed_result = evaluate_anomaly_holdout(
        list(reversed(observations)),
        list(reversed(labels)),
        evaluated_as_of=CUTOFF,
        config=CONFIG,
    )
    assert reversed_result.report_fingerprint == result.report_fingerprint

    later = _observation(48, alert=True)
    altered = evaluate_anomaly_holdout(
        [*observations, later],
        [*labels, _label(later, abnormal=True)],
        evaluated_as_of=CUTOFF,
        config=CONFIG,
    )
    assert altered.report_fingerprint == result.report_fingerprint


def test_bad_source_fingerprint_and_repeated_or_unmatched_labels_fail_closed() -> None:
    observations, labels = _population()
    forged = observations[0].model_copy(
        update={"evidence_fingerprint": "f" * 64}
    )
    with pytest.raises(ValueError, match="evidence_fingerprint mismatch"):
        evaluate_anomaly_holdout(
            [forged, *observations[1:]], labels,
            evaluated_as_of=CUTOFF, config=CONFIG,
        )

    with pytest.raises(ValueError, match="duplicate realized label"):
        evaluate_anomaly_holdout(
            observations, [*labels, labels[0]],
            evaluated_as_of=CUTOFF, config=CONFIG,
        )
    with pytest.raises(ValueError, match="matching decision"):
        evaluate_anomaly_holdout(
            observations, [*labels, _label(_observation(20, alert=False), abnormal=False)],
            evaluated_as_of=CUTOFF,
            config=CONFIG,
        )


def test_inconsistent_decision_scores_and_multiple_model_versions_rejected() -> None:
    observations, labels = _population()
    bad = observations[0].model_copy(
        update={"action": "NO_ANOMALY_REDUCTION"}
    )
    payload = bad.model_dump(mode="python", exclude={"evidence_fingerprint"})
    for key in ("decision_at", "data_cutoff", "training_data_cutoff"):
        payload[key] = payload[key].isoformat()
    bad = bad.model_copy(
        update={"evidence_fingerprint": canonical_fingerprint(payload)}
    )
    with pytest.raises(ValueError, match="action/score gate mismatch"):
        evaluate_anomaly_holdout(
            [bad, *observations[1:]], labels,
            evaluated_as_of=CUTOFF, config=CONFIG,
        )

    mixed = observations[-1].model_copy(update={"model_fingerprint": "b" * 64})
    payload = mixed.model_dump(mode="python", exclude={"evidence_fingerprint"})
    for key in ("decision_at", "data_cutoff", "training_data_cutoff"):
        payload[key] = payload[key].isoformat()
    mixed = mixed.model_copy(
        update={"evidence_fingerprint": canonical_fingerprint(payload)}
    )
    with pytest.raises(ValueError, match="one frozen model"):
        evaluate_anomaly_holdout(
            [*observations[:-1], mixed], labels,
            evaluated_as_of=CUTOFF, config=CONFIG,
        )


def test_actual_isolation_forest_scores_feed_offline_holdout_without_trading() -> None:
    fitted = _fit()
    snapshots = [
        _context(80 + i, spike=(i % 4 == 0)) for i in range(12)
    ]
    observations = [
        score_isolation_anomaly(fitted, snapshot) for snapshot in snapshots
    ]
    labels = [
        _label(obs, abnormal=(i % 4 == 0))
        for i, obs in enumerate(observations)
    ]
    output = evaluate_anomaly_holdout(
        observations,
        labels,
        evaluated_as_of=snapshots[-1].as_of + timedelta(hours=1),
        config=CONFIG,
    )
    assert output.resolved_observations == 12
    assert output.model_fingerprint == fitted.evidence.model_fingerprint
    assert output.assessment in (
        "RESEARCH_SCREEN_PASSED",
        "RESEARCH_SCREEN_FAILED",
        "INCONCLUSIVE",
    )
    assert output.real_market_acceptance is False


def test_naive_cutoff_and_lookahead_labels_are_rejected() -> None:
    observations, labels = _population()
    with pytest.raises(ValueError, match="timezone-aware"):
        evaluate_anomaly_holdout(
            observations, labels,
            evaluated_as_of=datetime(2026, 2, 3), config=CONFIG,
        )
    with pytest.raises(ValueError, match="follow the decision"):
        AnomalyHoldoutLabel(
            label_version="india-anomaly-retrospective-label.v1",
            decision_at=observations[0].decision_at,
            observed_at=observations[0].decision_at,
            source_snapshot_fingerprint=observations[0].source_snapshot_fingerprint,
            abnormal_market_outcome=True,
            label_fingerprint="a" * 64,
        )
