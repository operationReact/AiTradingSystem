"""V4-07 causal Isolation Forest anomaly research guards (synthetic fixtures)."""

import math
from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_context import IndiaMarketContextSnapshot
from aitrading_intelligence.regime_anomaly import (
    FittedIsolationAnomaly,
    IsolationAnomalyConfig,
    score_isolation_anomaly,
    train_isolation_anomaly_challenger,
)
from aitrading_intelligence.regime_hmm import FEATURE_ORDER

BASE = datetime(2026, 1, 5, 5, 0, tzinfo=UTC)
CUTOFF = BASE + timedelta(hours=70)
CFG = IsolationAnomalyConfig(
    minimum_training_samples=32,
    maximum_training_samples=50,
    trees=24,
    max_tree_samples=32,
    upper_anomaly_quantile=0.95,
)
TRAINING = list(range(64))


def _context(
    index: int,
    *,
    spike: bool = False,
    stale: bool = False,
    flagged: bool = False,
    invalid_vix: bool = False,
) -> IndiaMarketContextSnapshot:
    ts = BASE + timedelta(hours=index)
    values = {
        FEATURE_ORDER[0]: (0.45 if spike else 0.008 * math.sin(index / 9)),
        FEATURE_ORDER[1]: (-1.0 if spike else 0.2 * math.cos(index / 13)),
        FEATURE_ORDER[2]: (0.9 if spike else 0.012 + 0.002 * (index % 5)),
        FEATURE_ORDER[3]: (
            -1.0 if invalid_vix else (80.0 if spike else 17.0 + index % 7)
        ),
    }
    metrics = dict(sorted(values.items()))
    flags = {FEATURE_ORDER[0]: ["SUSPECT_SOURCE"]} if flagged else {}
    return IndiaMarketContextSnapshot(
        as_of=ts,
        data_cutoff=ts - timedelta(minutes=200 if stale else 5),
        metrics=metrics,
        provenance={key: "synthetic-research-test" for key in metrics},
        quality_flags=flags,
        snapshot_fingerprint=canonical_fingerprint({
            "index": index,
            "values": metrics,
            "flagged": flagged,
            "stale": stale,
            "invalid_vix": invalid_vix,
        }),
    )


def _contexts():
    return [_context(i) for i in TRAINING]


def _fit(contexts=None, *, training_as_of=CUTOFF):
    return train_isolation_anomaly_challenger(
        contexts if contexts is not None else _contexts(),
        training_as_of=training_as_of,
        config=CFG,
    )


def test_causal_fitted_model_is_deterministic_and_never_trading_authority() -> None:
    fitted = _fit()
    another = _fit(list(reversed(_contexts())))
    assert fitted.evidence.contract_version == "india-regime-anomaly-isolation.v1"
    assert fitted.evidence.training_samples == 50
    assert fitted.evidence.feature_order == list(FEATURE_ORDER)
    assert fitted.evidence.model_fingerprint == another.evidence.model_fingerprint
    assert fitted.evidence.fitted_tree_fingerprint == (
        another.evidence.fitted_tree_fingerprint
    )
    assert fitted.evidence.promotion_allowed is False
    assert fitted.evidence.can_authorize_capital is False


def test_future_only_training_context_does_not_rewrite_model() -> None:
    before = _fit()
    after = _fit([*_contexts(), _context(120, spike=True)])
    assert before.evidence.model_fingerprint == after.evidence.model_fingerprint


def test_live_context_scoring_is_only_an_eligibility_reduction() -> None:
    fitted = _fit()
    normal = score_isolation_anomaly(fitted, _context(80))
    spike = score_isolation_anomaly(fitted, _context(81, spike=True))
    assert normal.evidence_fingerprint != spike.evidence_fingerprint
    assert spike.anomaly_score > normal.anomaly_score
    assert spike.action == "REDUCE_SIGNAL_ELIGIBILITY"
    assert spike.elevated_anomaly is True
    assert spike.status == "RESEARCH_ANOMALY_ADVISORY_ONLY"
    assert spike.model_promotion_allowed is False
    assert spike.can_authorize_capital is False
    assert normal.action in (
        "REDUCE_SIGNAL_ELIGIBILITY", "NO_ANOMALY_REDUCTION"
    )


def test_scoring_rejects_training_time_and_tampered_fitted_trees() -> None:
    fitted = _fit()
    with pytest.raises(ValueError, match="strictly after training"):
        score_isolation_anomaly(fitted, _context(63))
    tampered_evidence = fitted.evidence.model_copy(
        update={"fitted_tree_fingerprint": "0" * 64}
    )
    tampered = FittedIsolationAnomaly(
        evidence=tampered_evidence,
        config=fitted.config,
        scaler=fitted.scaler,
        estimator=fitted.estimator,
    )
    with pytest.raises(ValueError, match="tree identity"):
        score_isolation_anomaly(tampered, _context(81))


def test_training_rejects_stale_flagged_invalid_and_duplicate_inputs() -> None:
    clean = _contexts()
    for invalid, match in [
        (_context(63, stale=True), "stale India context"),
        (_context(63, flagged=True), "flagged India context"),
        (_context(63, invalid_vix=True), "invalid breadth"),
    ]:
        changed = [*clean[:-1], invalid]
        with pytest.raises(ValueError, match=match):
            _fit(changed)

    with pytest.raises(ValueError, match="unique timestamps"):
        _fit([*clean[:-1], _context(62)])


def test_scoring_rejects_stale_flagged_and_invalid_market_inputs() -> None:
    fitted = _fit()
    with pytest.raises(ValueError, match="stale India context"):
        score_isolation_anomaly(fitted, _context(81, stale=True))
    with pytest.raises(ValueError, match="flagged India context"):
        score_isolation_anomaly(fitted, _context(81, flagged=True))
    with pytest.raises(ValueError, match="invalid breadth"):
        score_isolation_anomaly(fitted, _context(81, invalid_vix=True))


def test_fewer_than_minimum_samples_and_naive_cutoff_fail_closed() -> None:
    with pytest.raises(ValueError, match="insufficient causal"):
        _fit(_contexts()[:20])
    with pytest.raises(ValueError, match="timezone-aware"):
        _fit(training_as_of=datetime(2026, 1, 5))


def test_context_metric_and_source_lineage_are_bound_to_decision() -> None:
    fitted = _fit()
    snapshot = _context(90)
    record = score_isolation_anomaly(fitted, snapshot)
    assert record.source_snapshot_fingerprint == snapshot.snapshot_fingerprint
    assert record.model_fingerprint == fitted.evidence.model_fingerprint
    assert record.training_data_cutoff == fitted.evidence.training_data_cutoff
    assert record.data_cutoff == snapshot.data_cutoff
    assert record.decision_at == snapshot.as_of
    assert record.threshold == fitted.evidence.anomaly_score_threshold
