from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.cross_sectional_scan import (
    CrossSectionalScanPlanV1,
    build_cross_sectional_scan_plan,
)
from aitrading_intelligence.india_context import (
    IndiaUniverseMembership,
    build_india_universe_snapshot,
)
from aitrading_intelligence.production_universe import (
    NIFTY500_INDEX_MEMBERSHIP,
    NIFTY500_LIQUID_UNIVERSE_ID,
)

AS_OF = datetime(2026, 11, 3, 6, 0, tzinfo=UTC)


def _membership(index: int, *, available_at: datetime = AS_OF - timedelta(minutes=5)):
    return IndiaUniverseMembership(
        instrument_id=f"NSE:INE{index:09d}",
        exchange="NSE",
        symbol=f"SYM{index}",
        series="EQ",
        sector="Information Technology" if index % 2 else "Financial Services",
        index_memberships=[NIFTY500_INDEX_MEMBERSHIP],
        effective_from=AS_OF - timedelta(days=30),
        effective_to=None,
        available_at=available_at,
        source="nifty500-liquidity-identity-bridge.v1",
        source_id=f"binding-{index}",
    )


def _universe(
    count: int = 5,
    *,
    as_of: datetime = AS_OF,
    universe_id: str = NIFTY500_LIQUID_UNIVERSE_ID,
):
    return build_india_universe_snapshot(
        universe_id=universe_id,
        as_of=as_of,
        memberships=[
            _membership(
                index,
                available_at=min(
                    AS_OF - timedelta(minutes=5),
                    as_of - timedelta(minutes=1),
                ),
            )
            for index in range(1, count + 1)
        ],
        source_versions={
            "nifty500_liquidity_identity_bridge": "a" * 64,
            "instrument_catalogue": "b" * 64,
        },
    )


def test_freezes_full_canonical_universe_into_bounded_batches() -> None:
    universe = _universe(5)

    plan = build_cross_sectional_scan_plan(universe, batch_size=2)

    assert plan.source_universe_fingerprint == universe.snapshot_fingerprint
    assert plan.source_universe_member_count == 5
    assert plan.batch_size == 2
    assert plan.batch_count == 3
    assert [batch.start_offset for batch in plan.batches] == [0, 2, 4]
    assert [batch.member_count for batch in plan.batches] == [2, 2, 1]
    assert [
        member.instrument_id
        for batch in plan.batches
        for member in batch.members
    ] == [member.instrument_id for member in universe.members]
    assert plan.promotion_allowed is False
    assert plan.can_authorize_capital is False


def test_same_snapshot_and_batch_size_are_byte_deterministic() -> None:
    universe = _universe(5)

    first = build_cross_sectional_scan_plan(universe, batch_size=2)
    second = build_cross_sectional_scan_plan(universe, batch_size=2)

    assert second.model_dump(mode="json") == first.model_dump(mode="json")
    assert second.plan_fingerprint == first.plan_fingerprint
    assert [item.batch_fingerprint for item in second.batches] == [
        item.batch_fingerprint for item in first.batches
    ]


def test_batch_size_changes_work_partition_not_source_universe() -> None:
    universe = _universe(5)

    small = build_cross_sectional_scan_plan(universe, batch_size=2)
    large = build_cross_sectional_scan_plan(universe, batch_size=3)

    assert small.source_universe_fingerprint == large.source_universe_fingerprint
    assert small.plan_fingerprint != large.plan_fingerprint
    assert [
        member.member_fingerprint
        for batch in small.batches
        for member in batch.members
    ] == [
        member.member_fingerprint
        for batch in large.batches
        for member in batch.members
    ]


def test_refuses_silent_universe_truncation_when_safety_bound_is_exceeded() -> None:
    with pytest.raises(ValueError, match="refusing silent truncation"):
        build_cross_sectional_scan_plan(
            _universe(5),
            batch_size=2,
            maximum_universe_members=4,
        )


def test_rejects_noncanonical_universe_id() -> None:
    with pytest.raises(ValueError, match="canonical liquid NIFTY 500 universe"):
        build_cross_sectional_scan_plan(
            _universe(3, universe_id="INDIA_NSE_CURRENT_CASH"),
            batch_size=2,
        )


def test_rejects_tampered_source_universe_fingerprint() -> None:
    tampered = _universe(3).model_copy(
        update={"snapshot_fingerprint": "0" * 64}
    )

    with pytest.raises(ValueError, match="source universe snapshot fingerprint mismatch"):
        build_cross_sectional_scan_plan(tampered, batch_size=2)


def test_member_batch_and_plan_fingerprints_are_self_verifying() -> None:
    plan = build_cross_sectional_scan_plan(_universe(3), batch_size=2)
    tampered = plan.model_dump(mode="python")
    tampered["batches"][0]["members"][0]["symbol"] = "MUTATED"

    with pytest.raises(ValidationError, match="scan member fingerprint mismatch"):
        CrossSectionalScanPlanV1.model_validate(tampered)


def test_future_universe_growth_cannot_mutate_historical_scan_plan() -> None:
    historical_universe = _universe(4)
    historical = build_cross_sectional_scan_plan(
        historical_universe,
        batch_size=2,
    )
    future_as_of = AS_OF + timedelta(days=1)
    future_universe = _universe(5, as_of=future_as_of)
    future = build_cross_sectional_scan_plan(future_universe, batch_size=2)
    rebuilt_historical = build_cross_sectional_scan_plan(
        historical_universe,
        batch_size=2,
    )

    assert future.source_universe_fingerprint != historical.source_universe_fingerprint
    assert rebuilt_historical.model_dump(mode="json") == historical.model_dump(mode="json")
    assert rebuilt_historical.plan_fingerprint == historical.plan_fingerprint


def test_invalid_batch_size_fails_closed() -> None:
    with pytest.raises(ValueError, match="batch_size must be between 1 and 200"):
        build_cross_sectional_scan_plan(_universe(3), batch_size=0)
