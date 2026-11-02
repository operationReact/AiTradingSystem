from __future__ import annotations

from datetime import datetime

from .india_context import (
    IndiaUniverseMembership,
    IndiaUniverseSnapshot,
    build_india_universe_snapshot,
)
from .universe_identity_bridge import Nifty500LiquidityIdentityBridge

NIFTY500_LIQUID_UNIVERSE_ID = "INDIA_NSE_NIFTY500_LIQUID"
NIFTY500_INDEX_MEMBERSHIP = "NIFTY_500"


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


def build_liquidity_qualified_india_universe_snapshot(
    *,
    bridge: Nifty500LiquidityIdentityBridge,
    as_of: datetime,
) -> IndiaUniverseSnapshot:
    """Assemble the canonical India universe from exact, liquidity-qualified NIFTY 500 bindings."""

    _require_aware(as_of)
    if bridge.as_of != as_of:
        raise ValueError("identity bridge must match the universe decision time")
    if bridge.data_cutoff > as_of:
        raise ValueError("identity bridge cutoff cannot exceed the universe decision time")

    memberships = [
        IndiaUniverseMembership(
            instrument_id=binding.canonical_instrument_id,
            exchange="NSE",
            symbol=binding.symbol,
            series=binding.series,
            sector=binding.sector,
            index_memberships=[NIFTY500_INDEX_MEMBERSHIP],
            effective_from=binding.membership_effective_from,
            effective_to=None,
            available_at=binding.available_at,
            source=bridge.contract_version,
            source_id=binding.binding_fingerprint,
        )
        for binding in bridge.bindings
    ]

    return build_india_universe_snapshot(
        universe_id=NIFTY500_LIQUID_UNIVERSE_ID,
        as_of=as_of,
        memberships=memberships,
        source_versions={
            "instrument_catalogue": bridge.source_catalogue_fingerprint,
            "liquidity_qualified_catalogue": bridge.source_liquidity_snapshot_fingerprint,
            "nifty500_liquidity_identity_bridge": bridge.snapshot_fingerprint,
            "nifty500_sector_enrichment": bridge.source_sector_enrichment_fingerprint,
        },
    )
