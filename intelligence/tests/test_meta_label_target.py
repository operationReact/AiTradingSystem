from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.cross_sectional_ranking import (
    CrossSectionalRankingDatasetV1,
    build_cross_sectional_ranking_dataset,
)
from aitrading_intelligence.cross_sectional_scan import build_cross_sectional_scan_plan
from aitrading_intelligence.datasets import MarketBar
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion import FusionEvidence, FusionRequest, fuse_signals
from aitrading_intelligence.india_context import (
    IndiaUniverseMembership,
    build_india_universe_snapshot,
)
from aitrading_intelligence.meta_label_target import (
    MetaLabelDatasetV1,
    MetaLabelTargetV1,
    build_meta_label_dataset,
)
from aitrading_intelligence.production_universe import (
    NIFTY500_INDEX_MEMBERSHIP,
    NIFTY500_LIQUID_UNIVERSE_ID,
)
from aitrading_intelligence.triple_barrier_target import (
    TripleBarrierTargetSpecV1,
    build_triple_barrier_targets,
)

AS_OF = datetime(2026, 11, 3, 4, 0, tzinfo=UTC)
CUTOFF = AS_OF + timedelta(minutes=10)


def _universe(symbols: list[str]):
    return build_india_universe_snapshot(
        universe_id=NIFTY500_LIQUID_UNIVERSE_ID,
        as_of=AS_OF,
        memberships=[
            IndiaUniverseMembership(
                instrument_id=f"NSE:INE{index:09d}",
                exchange="NSE",
                symbol=symbol,
                series="EQ",
                sector="Information Technology",
                index_memberships=[NIFTY500_INDEX_MEMBERSHIP],
                effective_from=AS_OF - timedelta(days=30),
                effective_to=None,
                available_at=AS_OF - timedelta(minutes=5),
                source="fixture",
                source_id=f"binding-{index}",
            )
            for index, symbol in enumerate(symbols, start=1)
        ],
        source_versions={"fixture": "a" * 64},
    )


def _evidence(family: str, score: float, seed: int) -> FusionEvidence:
    return FusionEvidence(
        family=family,
        provider=f"fixture-{family.lower()}",
        version="v1",
        score=score,
        confidence=1.0,
        observed_at=AS_OF,
        fingerprint=f"{seed:064x}",
        reasons=["fixture"],
    )


def _opportunity(symbol: str, score: float, seed: int):
    return fuse_signals(
        FusionRequest(
            symbol=symbol,
            as_of=AS_OF,
            deterministic=_evidence("DETERMINISTIC", score, seed),
            quant=_evidence("QUANT", score, seed + 100),
        )
    )


def _ranking(scores: dict[str, float]) -> CrossSectionalRankingDatasetV1:
    symbols = list(scores)
    plan = build_cross_sectional_scan_plan(_universe(symbols), batch_size=2)
    opportunities = [
        _opportunity(symbol, score, index)
        for index, (symbol, score) in enumerate(scores.items(), start=1)
    ]
    return build_cross_sectional_ranking_dataset(plan, opportunities)


def _feature_row(symbol: str, close: float = 100.0) -> FeatureRow:
    payload = {
        "feature_schema_version": "features.market.v1",
        "symbol": symbol,
        "as_of": AS_OF.isoformat(),
        "data_cutoff": AS_OF.isoformat(),
        "features": {"market.close": close, "market.volume": 10_000.0},
        "provenance": [f"fixture:{symbol}"],
    }
    return FeatureRow(**payload, fingerprint=canonical_fingerprint(payload))


def _bar(
    symbol: str,
    minutes: int,
    *,
    open_price: float = 100.0,
    high: float = 100.2,
    low: float = 99.8,
    close: float = 100.0,
) -> MarketBar:
    observed_at = AS_OF + timedelta(minutes=minutes)
    return MarketBar(
        symbol=symbol,
        interval="5m",
        observed_at=observed_at,
        event_at=observed_at,
        ingested_at=observed_at,
        open=open_price,
        high=high,
        low=low,
        close=close,
        volume=1_000.0,
        source="fixture",
    )


def _spec(*, horizon_bars: int = 2) -> TripleBarrierTargetSpecV1:
    return TripleBarrierTargetSpecV1(
        bar_interval="5m",
        horizon_bars=horizon_bars,
        upper_barrier_bps=50.0,
        lower_barrier_bps=30.0,
        round_trip_cost_bps=10.0,
    )


def test_buy_decision_becomes_positive_meta_label_after_costs() -> None:
    ranking = _ranking({"SYM1": 0.8})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1")],
        [_bar("SYM1", 5, high=100.6, low=99.9, close=100.4)],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    dataset = build_meta_label_dataset(
        ranking,
        targets,
        label_as_of=AS_OF + timedelta(minutes=5),
    )
    target = dataset.targets[0]

    assert target.primary_action == "BUY"
    assert target.state == "LABELED"
    assert target.meta_label == 1
    assert target.realized_net_return == pytest.approx(0.004)
    assert target.training_eligible is True
    assert target.source_ranking_row_fingerprint == ranking.rows[0].row_fingerprint
    assert target.source_triple_barrier_target_fingerprint == targets[0].target_fingerprint
    assert target.can_authorize_capital is False
    assert dataset.labeled_count == 1
    assert dataset.coverage == 1.0


def test_buy_decision_becomes_negative_meta_label_on_lower_barrier() -> None:
    ranking = _ranking({"SYM1": 0.8})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1")],
        [_bar("SYM1", 5, high=100.1, low=99.6, close=99.8)],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    target = build_meta_label_dataset(
        ranking,
        targets,
        label_as_of=AS_OF + timedelta(minutes=5),
    ).targets[0]

    assert target.state == "LABELED"
    assert target.meta_label == 0
    assert target.realized_net_return == pytest.approx(-0.004)
    assert target.training_eligible is True


def test_vertical_outcome_meta_label_uses_after_cost_return_not_barrier_class() -> None:
    ranking = _ranking({"SYM1": 0.8})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1")],
        [
            _bar("SYM1", 5, close=100.1),
            _bar(
                "SYM1",
                10,
                open_price=100.1,
                high=100.45,
                low=99.9,
                close=100.4,
            ),
        ],
        _spec(horizon_bars=2),
        label_as_of=CUTOFF,
    )

    target = build_meta_label_dataset(
        ranking,
        targets,
        label_as_of=CUTOFF,
    ).targets[0]

    assert target.barrier_outcome == "VERTICAL"
    assert target.realized_net_return == pytest.approx(0.003)
    assert target.meta_label == 1


def test_watch_primary_decision_is_explicit_abstain_not_counterfactual_label() -> None:
    ranking = _ranking({"SYM1": 0.1})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1")],
        [_bar("SYM1", 5, high=100.6, low=99.9, close=100.4)],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    dataset = build_meta_label_dataset(
        ranking,
        targets,
        label_as_of=AS_OF + timedelta(minutes=5),
    )
    target = dataset.targets[0]

    assert target.primary_action == "WATCH"
    assert target.state == "PRIMARY_ABSTAIN"
    assert target.meta_label is None
    assert target.realized_net_return is None
    assert target.training_eligible is False
    assert dataset.primary_abstain_count == 1
    assert dataset.labeled_count == 0


def test_ambiguous_triple_barrier_target_cannot_become_training_label() -> None:
    ranking = _ranking({"SYM1": 0.8})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1")],
        [_bar("SYM1", 5, high=100.7, low=99.5, close=100.2)],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    dataset = build_meta_label_dataset(
        ranking,
        targets,
        label_as_of=AS_OF + timedelta(minutes=5),
    )
    target = dataset.targets[0]

    assert target.state == "AMBIGUOUS_TARGET"
    assert target.meta_label is None
    assert target.realized_net_return is None
    assert target.training_eligible is False
    assert dataset.ambiguous_target_count == 1


def test_unresolved_ranking_rows_are_recorded_explicitly_in_coverage() -> None:
    ranking = _ranking({"SYM1": 0.8, "SYM2": 0.7})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1")],
        [_bar("SYM1", 5, high=100.6, low=99.9, close=100.4)],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    dataset = build_meta_label_dataset(
        ranking,
        targets,
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    assert dataset.source_ranked_member_count == 2
    assert dataset.matched_target_count == 1
    assert dataset.unresolved_count == 1
    assert dataset.coverage == pytest.approx(0.5)
    unresolved_symbol = next(row for row in ranking.rows if row.symbol == "SYM2")
    assert dataset.unresolved_row_fingerprints == [unresolved_symbol.row_fingerprint]


def test_target_outside_ranking_dataset_fails_closed() -> None:
    ranking = _ranking({"SYM1": 0.8})
    outside = build_triple_barrier_targets(
        [_feature_row("OTHER")],
        [_bar("OTHER", 5, high=100.6, low=99.9, close=100.4)],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    with pytest.raises(ValueError, match="outside the ranking dataset"):
        build_meta_label_dataset(
            ranking,
            outside,
            label_as_of=AS_OF + timedelta(minutes=5),
        )


def test_mixed_or_wrong_label_cutoff_fails_closed() -> None:
    ranking = _ranking({"SYM1": 0.8})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1")],
        [_bar("SYM1", 5, high=100.6, low=99.9, close=100.4)],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    with pytest.raises(ValueError, match="cutoff must match"):
        build_meta_label_dataset(
            ranking,
            targets,
            label_as_of=CUTOFF,
        )


def test_target_input_order_cannot_change_meta_label_dataset() -> None:
    ranking = _ranking({"SYM1": 0.8, "SYM2": 0.7})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1"), _feature_row("SYM2")],
        [
            _bar("SYM1", 5, high=100.6, low=99.9, close=100.4),
            _bar("SYM2", 5, high=100.1, low=99.6, close=99.8),
        ],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    forward = build_meta_label_dataset(
        ranking,
        targets,
        label_as_of=AS_OF + timedelta(minutes=5),
    )
    reversed_input = build_meta_label_dataset(
        ranking,
        list(reversed(targets)),
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    assert reversed_input.model_dump(mode="json") == forward.model_dump(mode="json")
    assert reversed_input.dataset_fingerprint == forward.dataset_fingerprint


def test_tampered_meta_label_or_dataset_fingerprint_fails_schema_validation() -> None:
    ranking = _ranking({"SYM1": 0.8})
    targets = build_triple_barrier_targets(
        [_feature_row("SYM1")],
        [_bar("SYM1", 5, high=100.6, low=99.9, close=100.4)],
        _spec(horizon_bars=4),
        label_as_of=AS_OF + timedelta(minutes=5),
    )
    dataset = build_meta_label_dataset(
        ranking,
        targets,
        label_as_of=AS_OF + timedelta(minutes=5),
    )

    tampered_target = dataset.targets[0].model_dump(mode="python")
    tampered_target["meta_label"] = 0
    with pytest.raises(ValidationError, match="positive after-cost return"):
        MetaLabelTargetV1.model_validate(tampered_target)

    tampered_dataset = dataset.model_dump(mode="python")
    tampered_dataset["dataset_fingerprint"] = "0" * 64
    with pytest.raises(ValidationError, match="dataset fingerprint mismatch"):
        MetaLabelDatasetV1.model_validate(tampered_dataset)
