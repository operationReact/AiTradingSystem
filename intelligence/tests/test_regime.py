from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.india_context import (
    IndiaContextObservation,
    build_india_market_context_snapshot,
)
from aitrading_intelligence.regime import (
    REGIME_LABELS,
    HeuristicRegimeConfig,
    RegimeSnapshot,
    build_heuristic_regime_snapshot,
)


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
    net_breadth: float,
    dispersion: float,
    india_vix: float,
    extra: list[IndiaContextObservation] | None = None,
):
    available_at = as_of - timedelta(minutes=5)
    observations = [
        _observation(
            key="benchmark.NIFTY50.return_1d",
            value=return_1d,
            available_at=available_at,
            source_id="nifty",
        ),
        _observation(
            key="breadth.NSE.net_breadth",
            value=net_breadth,
            available_at=available_at,
            source_id="breadth",
        ),
        _observation(
            key="breadth.NSE.return_dispersion",
            value=dispersion,
            available_at=available_at,
            source_id="dispersion",
        ),
        _observation(
            key="volatility.INDIA_VIX.close",
            value=india_vix,
            available_at=available_at,
            source_id="vix",
        ),
        *(extra or []),
    ]
    return build_india_market_context_snapshot(as_of=as_of, observations=observations)


def test_heuristic_regime_snapshot_is_deterministic_probabilistic_and_advisory() -> None:
    as_of = datetime(2026, 11, 2, 10, 30, tzinfo=UTC)
    context = _context(
        as_of=as_of,
        return_1d=0.018,
        net_breadth=0.58,
        dispersion=0.009,
        india_vix=13.5,
    )

    first = build_heuristic_regime_snapshot(context=context)
    second = build_heuristic_regime_snapshot(context=context)

    assert first == second
    assert first.method == "HEURISTIC_BASELINE"
    assert first.dominant_regime == "TREND_UP"
    assert tuple(first.probabilities) == REGIME_LABELS
    assert sum(first.probabilities.values()) == pytest.approx(1.0, abs=1e-12)
    assert 0.0 < min(first.probabilities.values()) < max(first.probabilities.values()) < 1.0
    assert first.confidence == max(first.probabilities.values())
    assert first.input_fingerprint == context.snapshot_fingerprint
    assert first.config_fingerprint == HeuristicRegimeConfig().fingerprint
    assert first.can_authorize_capital is False
    assert first.status == "RESEARCH_REGIME_EVIDENCE_ONLY"


def test_heuristic_regime_snapshot_identifies_high_volatility_stress() -> None:
    as_of = datetime(2026, 11, 2, 10, 30, tzinfo=UTC)
    context = _context(
        as_of=as_of,
        return_1d=-0.024,
        net_breadth=-0.42,
        dispersion=0.032,
        india_vix=31.0,
    )

    snapshot = build_heuristic_regime_snapshot(context=context)

    assert snapshot.dominant_regime == "STRESS_HIGH_VOL"
    assert snapshot.probabilities["STRESS_HIGH_VOL"] == snapshot.confidence


def test_future_context_mutation_cannot_change_historical_regime_snapshot() -> None:
    as_of = datetime(2026, 11, 2, 10, 30, tzinfo=UTC)
    baseline_context = _context(
        as_of=as_of,
        return_1d=0.012,
        net_breadth=0.45,
        dispersion=0.010,
        india_vix=15.0,
    )
    future_at = as_of + timedelta(hours=3)
    future = [
        _observation(
            key="benchmark.NIFTY50.return_1d",
            value=-0.08,
            available_at=future_at,
            source_id="future-nifty",
        ),
        _observation(
            key="breadth.NSE.net_breadth",
            value=-0.95,
            available_at=future_at,
            source_id="future-breadth",
        ),
        _observation(
            key="breadth.NSE.return_dispersion",
            value=0.08,
            available_at=future_at,
            source_id="future-dispersion",
        ),
        _observation(
            key="volatility.INDIA_VIX.close",
            value=55.0,
            available_at=future_at,
            source_id="future-vix",
        ),
    ]
    mutated_context = _context(
        as_of=as_of,
        return_1d=0.012,
        net_breadth=0.45,
        dispersion=0.010,
        india_vix=15.0,
        extra=future,
    )

    assert mutated_context == baseline_context
    assert build_heuristic_regime_snapshot(
        context=mutated_context
    ) == build_heuristic_regime_snapshot(context=baseline_context)


def test_heuristic_regime_requires_complete_causal_context() -> None:
    as_of = datetime(2026, 11, 2, 10, 30, tzinfo=UTC)
    context = build_india_market_context_snapshot(
        as_of=as_of,
        observations=[
            _observation(
                key="benchmark.NIFTY50.return_1d",
                value=0.01,
                available_at=as_of - timedelta(minutes=5),
                source_id="nifty",
            )
        ],
    )

    with pytest.raises(ValueError, match="missing required regime context metrics"):
        build_heuristic_regime_snapshot(context=context)


def test_regime_contract_rejects_non_normalized_probabilities() -> None:
    as_of = datetime(2026, 11, 2, 10, 30, tzinfo=UTC)
    context = _context(
        as_of=as_of,
        return_1d=0.01,
        net_breadth=0.25,
        dispersion=0.01,
        india_vix=16.0,
    )
    valid = build_heuristic_regime_snapshot(context=context)
    payload = valid.model_dump()
    payload["probabilities"] = {
        "TREND_UP": 0.5,
        "TREND_DOWN": 0.2,
        "RANGE_LOW_VOL": 0.2,
        "STRESS_HIGH_VOL": 0.2,
    }

    with pytest.raises(ValidationError, match="probabilities must sum to 1"):
        RegimeSnapshot.model_validate(payload)
