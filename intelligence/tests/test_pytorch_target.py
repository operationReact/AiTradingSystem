from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.pytorch_target import (
    ForwardReturnTargetSpec,
    build_labeled_examples,
)


def _row(index: int, close: float) -> FeatureRow:
    as_of = datetime(2026, 1, 1, 3, 45, tzinfo=UTC) + timedelta(minutes=5 * index)
    return FeatureRow(
        symbol="RELIANCE",
        as_of=as_of,
        data_cutoff=as_of,
        features={
            "market.close": close,
            "market.return_1": 0.001 * index,
            "market.range_pct": 0.01,
            "market.volume_change_1": 0.02 * index,
        },
        provenance=[f"market:fixture:{index}"],
        fingerprint=f"{index + 1:064x}",
    )


def test_forward_target_is_causal_deterministic_and_cost_aware() -> None:
    rows = [_row(index, 100.0 + index) for index in range(12)]
    spec = ForwardReturnTargetSpec(
        horizon_bars=2,
        minimum_edge_bps=50,
        round_trip_cost_bps=25,
    )

    first = build_labeled_examples(rows, spec)
    second = build_labeled_examples(list(reversed(rows)), spec)

    assert len(first) == 10
    assert [item.example_fingerprint for item in first] == [
        item.example_fingerprint for item in second
    ]
    assert first[0].as_of == rows[0].as_of
    assert first[0].target_observed_at == rows[2].as_of
    assert first[0].target_observed_at > first[0].as_of
    assert first[0].feature_row_fingerprint == rows[0].fingerprint
    assert first[0].target_spec_fingerprint == spec.fingerprint
    assert first[0].features == rows[0].features
    assert first[0].threshold_return == pytest.approx(0.0075)
    assert first[0].label == 1
    assert first[0].can_authorize_capital is False


def test_future_close_changes_label_not_input_snapshot() -> None:
    base = [_row(index, 100.0) for index in range(5)]
    rising = list(base)
    rising[2] = _row(2, 102.0)
    falling = list(base)
    falling[2] = _row(2, 99.0)
    spec = ForwardReturnTargetSpec(horizon_bars=2, minimum_edge_bps=10, round_trip_cost_bps=5)

    positive = build_labeled_examples(rising, spec)[0]
    negative = build_labeled_examples(falling, spec)[0]

    assert positive.features == negative.features == base[0].features
    assert positive.label == 1
    assert negative.label == 0
    assert positive.forward_return != negative.forward_return


def test_target_generation_rejects_mixed_symbols_and_short_history() -> None:
    spec = ForwardReturnTargetSpec(horizon_bars=2)
    with pytest.raises(ValueError, match="not enough feature rows"):
        build_labeled_examples([_row(0, 100.0), _row(1, 101.0)], spec)

    rows = [_row(index, 100.0 + index) for index in range(4)]
    other = rows[-1].model_copy(update={"symbol": "TCS"})
    with pytest.raises(ValueError, match="one symbol"):
        build_labeled_examples([*rows[:-1], other], spec)
