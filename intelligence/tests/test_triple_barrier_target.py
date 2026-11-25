from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.datasets import MarketBar
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.triple_barrier_target import (
    TripleBarrierTargetSpecV1,
    TripleBarrierTargetV1,
    build_triple_barrier_targets,
)

AS_OF = datetime(2026, 11, 3, 4, 0, tzinfo=UTC)


def _feature_row(*, symbol: str = "RELIANCE", close: float = 100.0) -> FeatureRow:
    payload = {
        "feature_schema_version": "features.market.v1",
        "symbol": symbol,
        "as_of": AS_OF.isoformat(),
        "data_cutoff": AS_OF.isoformat(),
        "features": {
            "market.close": close,
            "market.volume": 10_000.0,
        },
        "provenance": ["fixture:decision"],
    }
    return FeatureRow(**payload, fingerprint=canonical_fingerprint(payload))


def _bar(
    minutes: int,
    *,
    open_price: float = 100.0,
    high: float = 100.2,
    low: float = 99.8,
    close: float = 100.0,
    symbol: str = "RELIANCE",
    interval: str = "5m",
) -> MarketBar:
    return MarketBar(
        symbol=symbol,
        interval=interval,
        observed_at=AS_OF + timedelta(minutes=minutes),
        event_at=AS_OF + timedelta(minutes=minutes),
        ingested_at=AS_OF + timedelta(minutes=minutes),
        open=open_price,
        high=high,
        low=low,
        close=close,
        volume=1_000.0,
        source="fixture",
    )


def _spec(*, horizon_bars: int = 3) -> TripleBarrierTargetSpecV1:
    return TripleBarrierTargetSpecV1(
        bar_interval="5m",
        horizon_bars=horizon_bars,
        upper_barrier_bps=50.0,
        lower_barrier_bps=30.0,
        round_trip_cost_bps=10.0,
    )


def test_upper_barrier_first_touch_resolves_before_vertical_horizon() -> None:
    cutoff = AS_OF + timedelta(minutes=10)
    target = build_triple_barrier_targets(
        [_feature_row()],
        [
            _bar(5, high=100.4, low=99.8, close=100.1),
            _bar(10, open_price=100.1, high=100.6, low=99.9, close=100.4),
        ],
        _spec(horizon_bars=6),
        label_as_of=cutoff,
    )[0]

    assert target.barrier_outcome == "UPPER"
    assert target.label == 1
    assert target.first_touch_bar_index == 2
    assert target.first_touch_bar_at == cutoff
    assert target.target_observed_at == cutoff
    assert target.observed_bar_count == 2
    assert target.exit_price == pytest.approx(100.5)
    assert target.gross_return == pytest.approx(0.005)
    assert target.net_return == pytest.approx(0.004)
    assert target.label_as_of == cutoff
    assert target.promotion_allowed is False
    assert target.can_authorize_capital is False


def test_lower_barrier_first_touch_is_cost_aware() -> None:
    cutoff = AS_OF + timedelta(minutes=5)
    target = build_triple_barrier_targets(
        [_feature_row()],
        [_bar(5, high=100.1, low=99.6, close=99.8)],
        _spec(horizon_bars=4),
        label_as_of=cutoff,
    )[0]

    assert target.barrier_outcome == "LOWER"
    assert target.label == -1
    assert target.first_touch_bar_index == 1
    assert target.exit_price == pytest.approx(99.7)
    assert target.gross_return == pytest.approx(-0.003)
    assert target.net_return == pytest.approx(-0.004)


def test_vertical_barrier_requires_full_horizon_and_uses_terminal_close() -> None:
    cutoff = AS_OF + timedelta(minutes=15)
    target = build_triple_barrier_targets(
        [_feature_row()],
        [
            _bar(5, close=100.1),
            _bar(10, open_price=100.1, high=100.3, low=99.9, close=100.2),
            _bar(15, open_price=100.2, high=100.4, low=99.9, close=100.25),
        ],
        _spec(horizon_bars=3),
        label_as_of=cutoff,
    )[0]

    assert target.barrier_outcome == "VERTICAL"
    assert target.label == 0
    assert target.first_touch_bar_index is None
    assert target.first_touch_bar_at is None
    assert target.observed_bar_count == 3
    assert target.target_observed_at == cutoff
    assert target.exit_price == pytest.approx(100.25)
    assert target.gross_return == pytest.approx(0.0025)
    assert target.net_return == pytest.approx(0.0015)


def test_same_bar_double_touch_abstains_instead_of_guessing_intrabar_order() -> None:
    cutoff = AS_OF + timedelta(minutes=5)
    target = build_triple_barrier_targets(
        [_feature_row()],
        [_bar(5, high=100.7, low=99.5, close=100.2)],
        _spec(horizon_bars=6),
        label_as_of=cutoff,
    )[0]

    assert target.barrier_outcome == "AMBIGUOUS"
    assert target.label is None
    assert target.first_touch_bar_index == 1
    assert target.first_touch_bar_at == cutoff
    assert target.exit_price is None
    assert target.gross_return is None
    assert target.net_return is None


def test_unresolved_short_path_is_not_fabricated_as_vertical_label() -> None:
    with pytest.raises(ValueError, match="no resolved triple-barrier targets"):
        build_triple_barrier_targets(
            [_feature_row()],
            [_bar(5, high=100.4, low=99.8, close=100.1)],
            _spec(horizon_bars=3),
            label_as_of=AS_OF + timedelta(minutes=5),
        )


def test_future_bar_cannot_rewrite_target_at_frozen_label_cutoff() -> None:
    cutoff = AS_OF + timedelta(minutes=15)
    historical_bars = [
        _bar(5, close=100.1),
        _bar(10, open_price=100.1, high=100.3, low=99.9, close=100.2),
        _bar(15, open_price=100.2, high=100.4, low=99.9, close=100.25),
    ]
    future_bar = _bar(
        20,
        open_price=100.25,
        high=102.0,
        low=98.0,
        close=101.0,
    )

    baseline = build_triple_barrier_targets(
        [_feature_row()],
        historical_bars,
        _spec(horizon_bars=3),
        label_as_of=cutoff,
    )[0]
    mutated = build_triple_barrier_targets(
        [_feature_row()],
        [*historical_bars, future_bar],
        _spec(horizon_bars=3),
        label_as_of=cutoff,
    )[0]

    assert mutated.model_dump(mode="json") == baseline.model_dump(mode="json")
    assert mutated.target_fingerprint == baseline.target_fingerprint


def test_wrong_interval_bars_do_not_satisfy_target_horizon() -> None:
    with pytest.raises(ValueError, match="no resolved triple-barrier targets"):
        build_triple_barrier_targets(
            [_feature_row()],
            [
                _bar(5, interval="1m", high=101.0, low=99.9, close=100.5),
                _bar(10, interval="1m", high=101.0, low=99.9, close=100.5),
            ],
            _spec(horizon_bars=2),
            label_as_of=AS_OF + timedelta(minutes=10),
        )


def test_duplicate_target_interval_timestamp_fails_closed() -> None:
    first = _bar(5, close=100.1)
    duplicate = _bar(5, open_price=100.1, high=100.3, low=99.9, close=100.2)

    with pytest.raises(ValueError, match="duplicate 5m bar timestamp"):
        build_triple_barrier_targets(
            [_feature_row()],
            [first, duplicate],
            _spec(horizon_bars=2),
            label_as_of=AS_OF + timedelta(minutes=5),
        )


def test_tampered_feature_row_fingerprint_fails_closed() -> None:
    tampered = _feature_row().model_copy(update={"fingerprint": "0" * 64})

    with pytest.raises(ValueError, match="feature row fingerprint mismatch"):
        build_triple_barrier_targets(
            [tampered],
            [_bar(5, high=100.6, low=99.9, close=100.4)],
            _spec(horizon_bars=3),
            label_as_of=AS_OF + timedelta(minutes=5),
        )


def test_tampered_target_economics_or_fingerprint_fails_schema_validation() -> None:
    target = build_triple_barrier_targets(
        [_feature_row()],
        [_bar(5, high=100.6, low=99.9, close=100.4)],
        _spec(horizon_bars=3),
        label_as_of=AS_OF + timedelta(minutes=5),
    )[0]

    tampered_return = target.model_dump(mode="python")
    tampered_return["net_return"] = 0.99
    with pytest.raises(ValidationError, match="net return"):
        TripleBarrierTargetV1.model_validate(tampered_return)

    tampered_fingerprint = target.model_dump(mode="python")
    tampered_fingerprint["target_fingerprint"] = "0" * 64
    with pytest.raises(ValidationError, match="target fingerprint mismatch"):
        TripleBarrierTargetV1.model_validate(tampered_fingerprint)


def test_target_observation_cannot_exceed_frozen_label_cutoff() -> None:
    target = build_triple_barrier_targets(
        [_feature_row()],
        [_bar(5, high=100.6, low=99.9, close=100.4)],
        _spec(horizon_bars=3),
        label_as_of=AS_OF + timedelta(minutes=5),
    )[0]
    tampered = target.model_dump(mode="python")
    tampered["label_as_of"] = AS_OF + timedelta(minutes=4)

    with pytest.raises(ValidationError, match="frozen label cutoff"):
        TripleBarrierTargetV1.model_validate(tampered)
