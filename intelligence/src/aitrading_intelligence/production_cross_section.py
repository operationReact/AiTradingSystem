from __future__ import annotations

from collections.abc import Iterable
from datetime import datetime

from .fingerprints import canonical_fingerprint
from .india_cross_section import IndiaCrossSectionMemberObservation
from .market_depth_source import (
    MARKET_DEPTH_SPREAD_JOIN_VERSION,
    OBSERVED_SPREAD_FLAG,
    AngelOneMarketDepthResearchExport,
    MarketDepthSpreadJoinConfig,
    MarketDepthSpreadJoinSnapshot,
    _latest_causal_depth,
)
from .market_history_cross_section import MarketHistoryCrossSectionSnapshot
from .universe_identity_bridge import Nifty500LiquidityIdentityBridge


def assemble_production_cross_section_observations(
    *,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    market_history: MarketHistoryCrossSectionSnapshot,
    market_depth_exports: Iterable[AngelOneMarketDepthResearchExport],
    as_of: datetime,
    depth_config: MarketDepthSpreadJoinConfig | None = None,
) -> MarketDepthSpreadJoinSnapshot:
    """Combine causal daily market metrics with observed Angel One top-of-book spread."""

    if as_of.tzinfo is None or as_of.utcoffset() is None:
        raise ValueError("as_of must be timezone-aware")
    depth_config = depth_config or MarketDepthSpreadJoinConfig()
    if identity_bridge.as_of != as_of or market_history.as_of != as_of:
        raise ValueError("identity bridge, market history and production join must share as_of")
    if market_history.identity_bridge_fingerprint != identity_bridge.snapshot_fingerprint:
        raise ValueError("market-history inputs do not belong to the supplied identity bridge")

    binding_by_symbol = {row.symbol: row for row in identity_bridge.bindings}
    if len(binding_by_symbol) != len(identity_bridge.bindings):
        raise ValueError("identity bridge symbols must be unique")
    history_by_symbol = {row.symbol: row for row in market_history.inputs}
    if set(history_by_symbol) != set(binding_by_symbol):
        raise ValueError("market-history inputs must exactly cover identity-bridge members")

    latest_depth = _latest_causal_depth(
        identity_bridge=identity_bridge,
        exports=market_depth_exports,
        as_of=as_of,
    )

    observations: list[IndiaCrossSectionMemberObservation] = []
    selected_depth_ids: list[str] = []
    for symbol in sorted(binding_by_symbol):
        binding = binding_by_symbol[symbol]
        history = history_by_symbol[symbol]
        if history.instrument_id != binding.catalogue_instrument_id:
            raise ValueError("market-history instrument ID does not match identity bridge")
        depth = latest_depth.get(binding.catalogue_instrument_id)
        if depth is None:
            raise ValueError("missing causal market-depth evidence for production member")
        age_seconds = (as_of - depth.available_at).total_seconds()
        if age_seconds < 0 or age_seconds > depth_config.max_quote_age_seconds:
            raise ValueError("market-depth evidence is stale for production cross-section")

        available_at = max(history.available_at, binding.available_at, depth.available_at)
        if available_at > as_of:
            raise ValueError("production cross-section evidence is not causal")
        event_at = max(history.event_at, depth.event_at)
        quality_flags = sorted(set([*history.quality_flags, OBSERVED_SPREAD_FLAG]))
        source_id = canonical_fingerprint(
            {
                "contract_version": MARKET_DEPTH_SPREAD_JOIN_VERSION,
                "market_history_source_id": history.source_id,
                "identity_binding_fingerprint": binding.binding_fingerprint,
                "depth_source_id": depth.source_id,
                "depth_spread_bps": str(depth.spread_bps),
                "depth_config_fingerprint": depth_config.fingerprint,
            }
        )
        observations.append(
            IndiaCrossSectionMemberObservation(
                exchange="NSE",
                symbol=symbol,
                event_at=event_at,
                available_at=available_at,
                return_1=history.return_1,
                adv_notional=history.adv_notional,
                spread_bps=float(depth.spread_bps),
                relative_volume=history.relative_volume,
                source=MARKET_DEPTH_SPREAD_JOIN_VERSION,
                source_id=source_id,
                quality_flags=quality_flags,
            )
        )
        selected_depth_ids.append(depth.source_id)

    data_cutoff = max(
        identity_bridge.data_cutoff,
        market_history.data_cutoff,
        *(row.available_at for row in observations),
    )
    payload = {
        "contract_version": MARKET_DEPTH_SPREAD_JOIN_VERSION,
        "as_of": as_of.isoformat(),
        "data_cutoff": data_cutoff.isoformat(),
        "identity_bridge_fingerprint": identity_bridge.snapshot_fingerprint,
        "config_fingerprint": depth_config.fingerprint,
        "observations": [row.model_dump(mode="json") for row in observations],
        "selected_depth_source_ids": selected_depth_ids,
        "status": "RESEARCH_SPREAD_EVIDENCE_ONLY",
        "can_authorize_capital": False,
    }
    return MarketDepthSpreadJoinSnapshot(
        as_of=as_of,
        data_cutoff=data_cutoff,
        identity_bridge_fingerprint=identity_bridge.snapshot_fingerprint,
        config_fingerprint=depth_config.fingerprint,
        observations=observations,
        selected_depth_source_ids=selected_depth_ids,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
