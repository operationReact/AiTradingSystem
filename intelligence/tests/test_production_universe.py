from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.production_universe import (
    NIFTY500_INDEX_MEMBERSHIP,
    NIFTY500_LIQUID_UNIVERSE_ID,
    build_liquidity_qualified_india_universe_snapshot,
)
from aitrading_intelligence.universe_identity_bridge import (
    MAPPING_RULE_VERSION,
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

AS_OF = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)
AVAILABLE_AT = AS_OF - timedelta(minutes=5)
EFFECTIVE_FROM = AS_OF - timedelta(days=30)


def _binding(
    *,
    isin: str,
    symbol: str,
    catalogue_instrument_id: int,
    token: str,
    sector: str,
    fingerprint_char: str,
    available_at: datetime = AVAILABLE_AT,
) -> Nifty500LiquidityIdentityBinding:
    trading_symbol = f"{symbol}-EQ"
    return Nifty500LiquidityIdentityBinding(
        canonical_instrument_id=f"NSE:{isin}",
        isin=isin,
        symbol=symbol,
        series="EQ",
        sector=sector,
        expected_trading_symbol=trading_symbol,
        catalogue_instrument_id=catalogue_instrument_id,
        broker_trading_symbol=trading_symbol,
        broker_symbol_token=token,
        liquidity_evidence_fingerprint=fingerprint_char * 64,
        sector_mapping_evidence_fingerprint="e" * 64,
        membership_effective_from=EFFECTIVE_FROM,
        available_at=available_at,
        mapping_rule=MAPPING_RULE_VERSION,
        binding_fingerprint=fingerprint_char * 64,
    )


def _bridge() -> Nifty500LiquidityIdentityBridge:
    bindings = sorted(
        [
            _binding(
                isin="INE009A01021",
                symbol="INFY",
                catalogue_instrument_id=101,
                token="1594",
                sector="Information Technology",
                fingerprint_char="a",
            ),
            _binding(
                isin="INE467B01029",
                symbol="TCS",
                catalogue_instrument_id=102,
                token="11536",
                sector="Information Technology",
                fingerprint_char="b",
                available_at=AS_OF - timedelta(minutes=2),
            ),
        ],
        key=lambda item: item.canonical_instrument_id,
    )
    return Nifty500LiquidityIdentityBridge(
        as_of=AS_OF,
        data_cutoff=max(item.available_at for item in bindings),
        source_catalogue_version="12345678-1234-1234-1234-123456789abc",
        source_catalogue_fingerprint="c" * 64,
        source_liquidity_snapshot_fingerprint="d" * 64,
        source_sector_enrichment_fingerprint="e" * 64,
        bindings=bindings,
        binding_count=len(bindings),
        snapshot_fingerprint="f" * 64,
    )


def test_builds_canonical_liquidity_qualified_nifty500_universe() -> None:
    bridge = _bridge()

    snapshot = build_liquidity_qualified_india_universe_snapshot(
        bridge=bridge,
        as_of=AS_OF,
    )

    assert snapshot.universe_id == NIFTY500_LIQUID_UNIVERSE_ID
    assert snapshot.as_of == AS_OF
    assert snapshot.data_cutoff == bridge.data_cutoff
    assert snapshot.can_authorize_capital is False
    assert [member.instrument_id for member in snapshot.members] == [
        "NSE:INE009A01021",
        "NSE:INE467B01029",
    ]
    assert [member.symbol for member in snapshot.members] == ["INFY", "TCS"]
    assert all(member.exchange == "NSE" for member in snapshot.members)
    assert all(member.series == "EQ" for member in snapshot.members)
    assert all(
        member.index_memberships == [NIFTY500_INDEX_MEMBERSHIP]
        for member in snapshot.members
    )


def test_uses_stable_isin_identity_not_broker_catalogue_id() -> None:
    snapshot = build_liquidity_qualified_india_universe_snapshot(
        bridge=_bridge(),
        as_of=AS_OF,
    )

    by_symbol = {member.symbol: member for member in snapshot.members}
    assert by_symbol["INFY"].instrument_id == "NSE:INE009A01021"
    assert by_symbol["INFY"].instrument_id != "101"
    assert by_symbol["TCS"].instrument_id == "NSE:INE467B01029"
    assert by_symbol["TCS"].instrument_id != "102"


def test_preserves_sector_membership_and_binding_provenance() -> None:
    bridge = _bridge()

    snapshot = build_liquidity_qualified_india_universe_snapshot(
        bridge=bridge,
        as_of=AS_OF,
    )

    binding_by_symbol = {item.symbol: item for item in bridge.bindings}
    for member in snapshot.members:
        binding = binding_by_symbol[member.symbol]
        assert member.sector == binding.sector
        assert member.effective_from == binding.membership_effective_from
        assert member.available_at == binding.available_at
        assert member.source == bridge.contract_version
        assert member.source_id == binding.binding_fingerprint


def test_carries_exact_bridge_lineage_into_snapshot_fingerprint_inputs() -> None:
    bridge = _bridge()

    snapshot = build_liquidity_qualified_india_universe_snapshot(
        bridge=bridge,
        as_of=AS_OF,
    )

    assert snapshot.source_versions == {
        "instrument_catalogue": bridge.source_catalogue_fingerprint,
        "liquidity_qualified_catalogue": bridge.source_liquidity_snapshot_fingerprint,
        "nifty500_liquidity_identity_bridge": bridge.snapshot_fingerprint,
        "nifty500_sector_enrichment": bridge.source_sector_enrichment_fingerprint,
    }


def test_bridge_lineage_change_changes_universe_fingerprint() -> None:
    bridge = _bridge()
    original = build_liquidity_qualified_india_universe_snapshot(
        bridge=bridge,
        as_of=AS_OF,
    )
    changed_bridge = bridge.model_copy(
        update={
            "snapshot_fingerprint": "9" * 64,
        }
    )

    changed = build_liquidity_qualified_india_universe_snapshot(
        bridge=changed_bridge,
        as_of=AS_OF,
    )

    assert original.members == changed.members
    assert original.snapshot_fingerprint != changed.snapshot_fingerprint


def test_rejects_bridge_built_for_a_different_decision_time() -> None:
    with pytest.raises(ValueError, match="identity bridge must match"):
        build_liquidity_qualified_india_universe_snapshot(
            bridge=_bridge(),
            as_of=AS_OF + timedelta(minutes=1),
        )


def test_rejects_naive_decision_time() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        build_liquidity_qualified_india_universe_snapshot(
            bridge=_bridge(),
            as_of=datetime(2026, 11, 2, 12, 0),
        )
