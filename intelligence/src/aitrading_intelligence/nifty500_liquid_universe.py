from __future__ import annotations

from .india_context import (
    IndiaUniverseMembership,
    IndiaUniverseSnapshot,
    build_india_universe_snapshot,
)
from .universe_identity_bridge import Nifty500LiquidityIdentityBridge

NIFTY500_LIQUID_UNIVERSE_ID = "NIFTY500_LIQUIDITY_QUALIFIED"
NIFTY500_LIQUID_UNIVERSE_SOURCE = "NIFTY500_LIQUIDITY_IDENTITY_BRIDGE"


def build_nifty500_liquidity_qualified_universe(
    *,
    bridge: Nifty500LiquidityIdentityBridge,
) -> IndiaUniverseSnapshot:
    """Materialize the causal liquid NIFTY 500 universe without re-resolving identity."""

    memberships = [
        IndiaUniverseMembership(
            instrument_id=binding.canonical_instrument_id,
            exchange="NSE",
            symbol=binding.symbol,
            series=binding.series,
            sector=binding.sector,
            index_memberships=["NIFTY_500"],
            effective_from=binding.membership_effective_from,
            available_at=binding.available_at,
            source=NIFTY500_LIQUID_UNIVERSE_SOURCE,
            source_id=binding.binding_fingerprint,
        )
        for binding in bridge.bindings
    ]
    source_versions = {
        "identity-mapping-rule": bridge.mapping_rule,
        "instrument-catalogue": bridge.source_catalogue_fingerprint,
        "liquidity-qualified-catalogue": bridge.source_liquidity_snapshot_fingerprint,
        "nifty500-liquidity-identity-bridge": bridge.snapshot_fingerprint,
        "nifty500-sector-enrichment": bridge.source_sector_enrichment_fingerprint,
    }
    snapshot = build_india_universe_snapshot(
        universe_id=NIFTY500_LIQUID_UNIVERSE_ID,
        as_of=bridge.as_of,
        memberships=memberships,
        source_versions=source_versions,
    )
    if snapshot.data_cutoff != bridge.data_cutoff:
        raise ValueError("universe data_cutoff must exactly match identity bridge data_cutoff")
    if len(snapshot.members) != bridge.binding_count:
        raise ValueError("universe member count must exactly match identity bridge binding count")
    return snapshot
