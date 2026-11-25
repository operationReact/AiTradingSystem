from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.india_context import (
    IndiaContextObservation,
    build_india_market_context_snapshot,
)
from aitrading_intelligence.regime import REGIME_LABELS
from aitrading_intelligence.regime_hmm import (
    HMMRegimeConfig,
    build_hmm_regime_snapshot,
    train_hmm_regime_model,
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


def _history(days: int = 96):
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


def test_hmm_training_is_deterministic_and_research_only() -> None:
    contexts = _history(80)
    training_as_of = contexts[-1].as_of

    first = train_hmm_regime_model(
        training_contexts=contexts,
        training_as_of=training_as_of,
    )
    second = train_hmm_regime_model(
        training_contexts=contexts,
        training_as_of=training_as_of,
    )

    assert first == second
    assert first.contract_version == "regime-hmm-model.v1"
    assert first.training_samples == 80
    assert first.training_as_of == training_as_of
    assert set(first.state_labels) == set(REGIME_LABELS)
    assert len(first.state_labels) == len(set(first.state_labels)) == 4
    assert first.config_fingerprint == HMMRegimeConfig().fingerprint
    assert first.model_fingerprint == second.model_fingerprint
    assert first.can_authorize_capital is False
    assert first.status == "RESEARCH_HMM_CHALLENGER_ONLY"


def test_future_context_mutation_cannot_change_historical_hmm_model() -> None:
    contexts = _history(96)
    training_as_of = contexts[71].as_of

    historical = train_hmm_regime_model(
        training_contexts=contexts[:72],
        training_as_of=training_as_of,
    )
    with_future_archive = train_hmm_regime_model(
        training_contexts=contexts,
        training_as_of=training_as_of,
    )

    assert with_future_archive == historical
    assert with_future_archive.model_fingerprint == historical.model_fingerprint
    assert with_future_archive.training_input_fingerprints == historical.training_input_fingerprints


def test_hmm_snapshot_is_probabilistic_causal_lineaged_and_advisory() -> None:
    contexts = _history(81)
    training = contexts[:80]
    target = contexts[80]
    model = train_hmm_regime_model(
        training_contexts=training,
        training_as_of=training[-1].as_of,
    )

    first = build_hmm_regime_snapshot(model=model, context=target)
    second = build_hmm_regime_snapshot(model=model, context=target)

    assert first == second
    assert first.method == "HMM_CHALLENGER"
    assert tuple(first.probabilities) == REGIME_LABELS
    assert sum(first.probabilities.values()) == pytest.approx(1.0, abs=1e-12)
    assert all(0.0 <= value <= 1.0 for value in first.probabilities.values())
    assert first.confidence == max(first.probabilities.values())
    assert first.model_fingerprint == model.model_fingerprint
    assert first.config_fingerprint == model.config_fingerprint
    assert first.training_data_cutoff == model.training_data_cutoff
    assert first.input_fingerprint == target.snapshot_fingerprint
    assert first.can_authorize_capital is False
    assert first.status == "RESEARCH_REGIME_EVIDENCE_ONLY"


def test_hmm_inference_rejects_context_at_training_boundary() -> None:
    contexts = _history(80)
    model = train_hmm_regime_model(
        training_contexts=contexts,
        training_as_of=contexts[-1].as_of,
    )

    with pytest.raises(ValueError, match="strictly after training_as_of"):
        build_hmm_regime_snapshot(model=model, context=contexts[-1])


def test_hmm_training_requires_enough_samples_before_cutoff() -> None:
    contexts = _history(80)

    with pytest.raises(ValueError, match="insufficient causal HMM training contexts"):
        train_hmm_regime_model(
            training_contexts=contexts,
            training_as_of=contexts[38].as_of,
        )


def test_hmm_training_rejects_duplicate_decision_timestamp() -> None:
    contexts = _history(80)
    duplicated = [*contexts, contexts[-1]]

    with pytest.raises(ValueError, match="unique decision timestamps"):
        train_hmm_regime_model(
            training_contexts=duplicated,
            training_as_of=contexts[-1].as_of,
        )
