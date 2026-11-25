from datetime import UTC, datetime, timedelta

from aitrading_intelligence.india_context import (
    IndiaContextObservation,
    build_india_market_context_snapshot,
)
from aitrading_intelligence.regime_evaluation import (
    RegimeEvaluationConfig,
    evaluate_regime_models_walk_forward,
)
from aitrading_intelligence.regime_hmm import HMMRegimeConfig


def _observation(
    *,
    key: str,
    value: float,
    available_at: datetime,
    source_id: str,
) -> IndiaContextObservation:
    return IndiaContextObservation(
        context_key=key,
        event_at=available_at,
        available_at=available_at,
        value=value,
        source="TEST",
        source_id=source_id,
    )


def _context(
    *,
    as_of: datetime,
    return_1d: float,
    breadth: float,
    dispersion: float,
    vix: float,
):
    available_at = as_of - timedelta(minutes=5)
    return build_india_market_context_snapshot(
        as_of=as_of,
        observations=[
            _observation(
                key="benchmark.NIFTY50.return_1d",
                value=return_1d,
                available_at=available_at,
                source_id=f"nifty-{as_of.date()}",
            ),
            _observation(
                key="breadth.NSE.net_breadth",
                value=breadth,
                available_at=available_at,
                source_id=f"breadth-{as_of.date()}",
            ),
            _observation(
                key="breadth.NSE.return_dispersion",
                value=dispersion,
                available_at=available_at,
                source_id=f"dispersion-{as_of.date()}",
            ),
            _observation(
                key="volatility.INDIA_VIX.close",
                value=vix,
                available_at=available_at,
                source_id=f"vix-{as_of.date()}",
            ),
        ],
    )


def _history(days: int):
    start = datetime(2026, 6, 1, 10, 30, tzinfo=UTC)
    contexts = []
    for index in range(days):
        as_of = start + timedelta(days=index)
        cycle = index % 48
        wave = ((index % 5) - 2) * 0.0004
        if cycle < 12:
            values = (0.013 + wave, 0.52 + wave * 10, 0.008, 13.0 + index % 3)
        elif cycle < 24:
            values = (0.001 + wave, 0.02 + wave * 5, 0.006, 12.0 + index % 2)
        elif cycle < 36:
            values = (-0.013 + wave, -0.50 + wave * 10, 0.015, 21.0 + index % 3)
        else:
            values = (-0.022 + wave, -0.42 + wave * 8, 0.032, 31.0 + index % 4)
        contexts.append(
            _context(
                as_of=as_of,
                return_1d=values[0],
                breadth=values[1],
                dispersion=values[2],
                vix=values[3],
            )
        )
    return contexts


def _config() -> RegimeEvaluationConfig:
    return RegimeEvaluationConfig(
        minimum_training_samples=40,
        step_periods=15,
        transaction_cost_bps=5.0,
        hmm=HMMRegimeConfig(
            minimum_training_samples=40,
            maximum_training_samples=80,
            n_iter=100,
        ),
    )


def test_regime_walk_forward_is_deterministic_causal_and_non_promoting() -> None:
    contexts = _history(72)
    config = _config()

    first = evaluate_regime_models_walk_forward(contexts, config=config)
    second = evaluate_regime_models_walk_forward(contexts, config=config)

    assert first == second
    assert first.evaluation_version == "regime-walk-forward-evaluation.v1"
    assert first.config_fingerprint == config.fingerprint
    assert first.truth_assumption == "NO_DETERMINISTIC_REGIME_GROUND_TRUTH"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False
    assert len(first.observations) == 3
    assert all(item.realized_at > item.decision_at for item in first.observations)
    assert all(len(item.hmm_model_fingerprint) == 64 for item in first.observations)


def test_future_archive_growth_preserves_existing_walk_forward_observations() -> None:
    config = _config()
    historical = evaluate_regime_models_walk_forward(_history(72), config=config)
    expanded = evaluate_regime_models_walk_forward(_history(90), config=config)

    assert expanded.observations[: len(historical.observations)] == historical.observations
    assert expanded.observations[0].observation_fingerprint == (
        historical.observations[0].observation_fingerprint
    )


def test_regime_evaluation_records_transition_and_downstream_economic_metrics() -> None:
    evidence = evaluate_regime_models_walk_forward(_history(72), config=_config())

    for diagnostics in (evidence.heuristic_transition, evidence.hmm_transition):
        assert diagnostics.snapshot_count == len(evidence.observations)
        assert diagnostics.transition_count == len(evidence.observations) - 1
        assert 0.0 <= diagnostics.dominant_flip_rate <= 1.0
        assert 0.0 <= diagnostics.mean_probability_turnover <= 1.0
        assert 0.0 <= diagnostics.mean_normalized_entropy <= 1.0

    for metrics in (evidence.heuristic_economic, evidence.hmm_economic):
        assert metrics.observation_count == len(evidence.observations)
        assert 0.0 <= metrics.mean_absolute_exposure <= 1.0
        assert metrics.total_turnover >= 0.0
        assert metrics.total_friction_cost >= 0.0
        assert 0.0 <= metrics.worst_drawdown <= 1.0

    assert evidence.comparison in {
        "HMM_OUTPERFORMS",
        "HMM_COMPLEMENTS_HEURISTIC",
        "HEURISTIC_HOLDS",
        "INCONCLUSIVE",
    }


def test_regime_evaluation_requires_realized_period_after_training_history() -> None:
    config = _config()

    try:
        evaluate_regime_models_walk_forward(_history(41), config=config)
    except ValueError as exc:
        assert "insufficient contexts" in str(exc)
    else:
        raise AssertionError("expected insufficient-history evaluation failure")
