from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .feature_v2 import FEATURE_SCHEMA_V2_VERSION, FeatureRowV2
from .fingerprints import canonical_fingerprint
from .market_depth_source import (
    MARKET_DEPTH_SPREAD_JOIN_VERSION,
    OBSERVED_SPREAD_FLAG,
    MarketDepthSpreadJoinSnapshot,
)

STATE_DEPENDENT_SLIPPAGE_VERSION = "state-dependent-slippage.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
TradeSide = Literal["BUY", "SELL"]
SlippageState = Literal["EVALUATED", "INCONCLUSIVE"]


class StateDependentSlippageConfigV1(BaseModel):
    """Bounded spread/liquidity/volatility slippage policy for cash equity."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    slippage_version: Literal["state-dependent-slippage.v1"] = (
        STATE_DEPENDENT_SLIPPAGE_VERSION
    )
    max_spread_liquidity_age_seconds: Annotated[int, Field(ge=1, le=3_600)] = 300
    max_feature_age_seconds: Annotated[int, Field(ge=1, le=86_400)] = 900
    spread_capture_fraction: Annotated[float, Field(ge=0.0, le=1.0)] = 0.50
    reference_adv_notional: Annotated[float, Field(gt=0.0)] = 100_000_000.0
    reference_relative_volume: Annotated[float, Field(gt=0.0)] = 1.0
    adv_liquidity_weight: Annotated[float, Field(ge=0.0, le=1.0)] = 0.70
    relative_volume_liquidity_weight: Annotated[float, Field(ge=0.0, le=1.0)] = 0.30
    maximum_liquidity_addon_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 10.0
    reference_range_pct: Annotated[float, Field(gt=0.0, le=1.0)] = 0.01
    volatility_bps_at_reference_range: Annotated[
        float,
        Field(ge=0.0, le=500.0),
    ] = 5.0
    maximum_volatility_addon_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 20.0
    maximum_total_slippage_bps: Annotated[float, Field(gt=0.0, le=2_000.0)] = 50.0

    @model_validator(mode="after")
    def validate_weights_and_cap(self) -> StateDependentSlippageConfigV1:
        total_weight = self.adv_liquidity_weight + self.relative_volume_liquidity_weight
        if not math.isclose(total_weight, 1.0, rel_tol=1e-12, abs_tol=1e-12):
            raise ValueError("liquidity weights must sum to 1")
        minimum_possible_cap = 0.0
        if self.maximum_total_slippage_bps < minimum_possible_cap:
            raise ValueError("maximum_total_slippage_bps is invalid")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class StateDependentSlippageEstimateV1(BaseModel):
    """Research-only adverse slippage estimate bound to exact PIT market-state evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    slippage_version: Literal["state-dependent-slippage.v1"] = (
        STATE_DEPENDENT_SLIPPAGE_VERSION
    )
    state: SlippageState
    reason_codes: list[str]
    side: TradeSide
    exchange: Literal["NSE", "BSE"]
    symbol: Annotated[str, Field(min_length=1, max_length=32)]
    as_of: datetime
    data_cutoff: datetime
    config_fingerprint: Hash64
    source_spread_snapshot_fingerprint: Hash64
    source_feature_row_fingerprint: Hash64
    source_market_state_id: Hash64 | None = None
    spread_bps: Annotated[float | None, Field(ge=0.0, le=10_000.0)] = None
    adv_notional: Annotated[float | None, Field(ge=0.0)] = None
    relative_volume: Annotated[float | None, Field(ge=0.0)] = None
    volatility_range_pct: Annotated[float | None, Field(ge=0.0)] = None
    spread_component_bps: Annotated[float | None, Field(ge=0.0)] = None
    liquidity_pressure: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    liquidity_component_bps: Annotated[float | None, Field(ge=0.0)] = None
    volatility_component_bps: Annotated[float | None, Field(ge=0.0)] = None
    raw_slippage_bps: Annotated[float | None, Field(ge=0.0)] = None
    estimated_slippage_bps: Annotated[float | None, Field(ge=0.0)] = None
    capped: bool = False
    estimate_fingerprint: Hash64
    status: Literal["RESEARCH_SLIPPAGE_ESTIMATE_ONLY"] = (
        "RESEARCH_SLIPPAGE_ESTIMATE_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("slippage timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_estimate(self) -> StateDependentSlippageEstimateV1:
        if self.data_cutoff > self.as_of:
            raise ValueError("slippage data_cutoff cannot exceed as_of")
        component_fields = (
            self.spread_component_bps,
            self.liquidity_pressure,
            self.liquidity_component_bps,
            self.volatility_component_bps,
            self.raw_slippage_bps,
            self.estimated_slippage_bps,
        )
        if self.state == "EVALUATED":
            required_inputs = (
                self.source_market_state_id,
                self.spread_bps,
                self.adv_notional,
                self.relative_volume,
                self.volatility_range_pct,
            )
            if self.reason_codes:
                raise ValueError("evaluated slippage estimate cannot contain reason codes")
            if any(value is None for value in required_inputs):
                raise ValueError("evaluated slippage estimate requires complete market state")
            if any(value is None for value in component_fields):
                raise ValueError("evaluated slippage estimate requires complete components")
        else:
            if not self.reason_codes:
                raise ValueError("inconclusive slippage estimate requires reason codes")
            if any(value is not None for value in component_fields):
                raise ValueError("inconclusive slippage estimate cannot expose computed slippage")
            if self.capped:
                raise ValueError("inconclusive slippage estimate cannot be capped")
        if self.estimate_fingerprint != canonical_fingerprint(_estimate_payload(self)):
            raise ValueError("state-dependent slippage fingerprint mismatch")
        return self


def _feature_row_payload(row: FeatureRowV2) -> dict[str, object]:
    return {
        "feature_schema_version": FEATURE_SCHEMA_V2_VERSION,
        "instrument_id": row.instrument_id,
        "exchange": row.exchange,
        "symbol": row.symbol,
        "sector": row.sector,
        "index_memberships": row.index_memberships,
        "as_of": row.as_of.isoformat(),
        "data_cutoff": row.data_cutoff.isoformat(),
        "base_feature_row_fingerprint": row.base_feature_row_fingerprint,
        "universe_snapshot_fingerprint": row.universe_snapshot_fingerprint,
        "market_context_snapshot_fingerprint": row.market_context_snapshot_fingerprint,
        "features": row.features,
        "missing_features": row.missing_features,
        "quality_flags": row.quality_flags,
        "provenance": row.provenance,
        "status": row.status,
        "can_authorize_capital": row.can_authorize_capital,
    }


def _spread_snapshot_payload(snapshot: MarketDepthSpreadJoinSnapshot) -> dict[str, object]:
    return {
        "contract_version": MARKET_DEPTH_SPREAD_JOIN_VERSION,
        "as_of": snapshot.as_of.isoformat(),
        "data_cutoff": snapshot.data_cutoff.isoformat(),
        "identity_bridge_fingerprint": snapshot.identity_bridge_fingerprint,
        "config_fingerprint": snapshot.config_fingerprint,
        "observations": [item.model_dump(mode="json") for item in snapshot.observations],
        "selected_depth_source_ids": snapshot.selected_depth_source_ids,
        "status": snapshot.status,
        "can_authorize_capital": snapshot.can_authorize_capital,
    }


def _estimate_payload(estimate: StateDependentSlippageEstimateV1) -> dict[str, object]:
    return estimate.model_dump(mode="json", exclude={"estimate_fingerprint"})


def _make_inconclusive(
    *,
    side: TradeSide,
    feature_row: FeatureRowV2,
    spread_snapshot: MarketDepthSpreadJoinSnapshot,
    config: StateDependentSlippageConfigV1,
    reasons: list[str],
    source_market_state_id: str | None = None,
    spread_bps: float | None = None,
    adv_notional: float | None = None,
    relative_volume: float | None = None,
    volatility_range_pct: float | None = None,
) -> StateDependentSlippageEstimateV1:
    payload = {
        "slippage_version": STATE_DEPENDENT_SLIPPAGE_VERSION,
        "state": "INCONCLUSIVE",
        "reason_codes": sorted(set(reasons)),
        "side": side,
        "exchange": feature_row.exchange,
        "symbol": feature_row.symbol,
        "as_of": feature_row.as_of,
        "data_cutoff": max(feature_row.data_cutoff, spread_snapshot.data_cutoff),
        "config_fingerprint": config.fingerprint,
        "source_spread_snapshot_fingerprint": spread_snapshot.snapshot_fingerprint,
        "source_feature_row_fingerprint": feature_row.fingerprint,
        "source_market_state_id": source_market_state_id,
        "spread_bps": spread_bps,
        "adv_notional": adv_notional,
        "relative_volume": relative_volume,
        "volatility_range_pct": volatility_range_pct,
        "spread_component_bps": None,
        "liquidity_pressure": None,
        "liquidity_component_bps": None,
        "volatility_component_bps": None,
        "raw_slippage_bps": None,
        "estimated_slippage_bps": None,
        "capped": False,
        "status": "RESEARCH_SLIPPAGE_ESTIMATE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = StateDependentSlippageEstimateV1.model_construct(
        **payload,
        estimate_fingerprint="0" * 64,
    )
    return StateDependentSlippageEstimateV1(
        **payload,
        estimate_fingerprint=canonical_fingerprint(_estimate_payload(provisional)),
    )


def estimate_state_dependent_slippage(
    *,
    spread_snapshot: MarketDepthSpreadJoinSnapshot,
    feature_row: FeatureRowV2,
    side: TradeSide,
    config: StateDependentSlippageConfigV1 | None = None,
) -> StateDependentSlippageEstimateV1:
    """Estimate adverse slippage from causal spread, liquidity and volatility state."""

    cfg = config or StateDependentSlippageConfigV1()
    checked_snapshot = MarketDepthSpreadJoinSnapshot.model_validate(
        spread_snapshot.model_dump(mode="python")
    )
    checked_feature = FeatureRowV2.model_validate(feature_row.model_dump(mode="python"))

    if checked_snapshot.snapshot_fingerprint != canonical_fingerprint(
        _spread_snapshot_payload(checked_snapshot)
    ):
        raise ValueError("market-depth spread snapshot fingerprint mismatch")
    if checked_feature.fingerprint != canonical_fingerprint(_feature_row_payload(checked_feature)):
        raise ValueError("FeatureRowV2 fingerprint mismatch")
    if checked_snapshot.as_of != checked_feature.as_of:
        raise ValueError("spread snapshot and FeatureRowV2 must share decision time")
    if checked_snapshot.data_cutoff > checked_feature.as_of:
        raise ValueError("spread snapshot contains future evidence")
    if checked_feature.data_cutoff > checked_feature.as_of:
        raise ValueError("FeatureRowV2 contains future evidence")

    matches = [
        item
        for item in checked_snapshot.observations
        if item.exchange == checked_feature.exchange and item.symbol == checked_feature.symbol
    ]
    if not matches:
        return _make_inconclusive(
            side=side,
            feature_row=checked_feature,
            spread_snapshot=checked_snapshot,
            config=cfg,
            reasons=["MISSING_SPREAD_LIQUIDITY_STATE"],
        )
    if len(matches) != 1:
        raise ValueError("spread snapshot contains duplicate symbol state")
    market_state = matches[0]

    reasons: list[str] = []
    source_market_state_id: str | None = None
    if (
        market_state.source != MARKET_DEPTH_SPREAD_JOIN_VERSION
        or OBSERVED_SPREAD_FLAG not in market_state.quality_flags
    ):
        reasons.append("SPREAD_NOT_OBSERVED_FROM_MARKET_DEPTH")
    elif len(market_state.source_id) != 64 or any(
        character not in "0123456789abcdef" for character in market_state.source_id
    ):
        reasons.append("MARKET_STATE_LINEAGE_NOT_FINGERPRINTED")
    else:
        source_market_state_id = market_state.source_id

    state_age_seconds = (checked_feature.as_of - market_state.available_at).total_seconds()
    if state_age_seconds < 0:
        reasons.append("MARKET_STATE_FROM_FUTURE")
    elif state_age_seconds > cfg.max_spread_liquidity_age_seconds:
        reasons.append("STALE_SPREAD_LIQUIDITY_STATE")

    feature_age_seconds = (checked_feature.as_of - checked_feature.data_cutoff).total_seconds()
    if feature_age_seconds < 0:
        reasons.append("VOLATILITY_FEATURE_FROM_FUTURE")
    elif feature_age_seconds > cfg.max_feature_age_seconds:
        reasons.append("STALE_VOLATILITY_FEATURE")

    volatility_range_pct = checked_feature.features.get("market.range_pct")
    if volatility_range_pct is None:
        reasons.append("MISSING_VOLATILITY_RANGE_FEATURE")
    elif not math.isfinite(volatility_range_pct) or volatility_range_pct < 0.0:
        reasons.append("INVALID_VOLATILITY_RANGE_FEATURE")

    if market_state.adv_notional <= 0.0:
        reasons.append("LIQUIDITY_ADV_NOT_POSITIVE")
    if market_state.relative_volume < 0.0 or not math.isfinite(market_state.relative_volume):
        reasons.append("INVALID_RELATIVE_VOLUME")

    if reasons:
        return _make_inconclusive(
            side=side,
            feature_row=checked_feature,
            spread_snapshot=checked_snapshot,
            config=cfg,
            reasons=reasons,
            source_market_state_id=source_market_state_id,
            spread_bps=market_state.spread_bps,
            adv_notional=market_state.adv_notional,
            relative_volume=market_state.relative_volume,
            volatility_range_pct=volatility_range_pct,
        )

    assert volatility_range_pct is not None
    spread_component = market_state.spread_bps * cfg.spread_capture_fraction

    adv_coverage = min(market_state.adv_notional / cfg.reference_adv_notional, 1.0)
    relative_volume_coverage = min(
        market_state.relative_volume / cfg.reference_relative_volume,
        1.0,
    )
    liquidity_pressure = (
        cfg.adv_liquidity_weight * (1.0 - adv_coverage)
        + cfg.relative_volume_liquidity_weight * (1.0 - relative_volume_coverage)
    )
    liquidity_component = cfg.maximum_liquidity_addon_bps * liquidity_pressure

    volatility_ratio = volatility_range_pct / cfg.reference_range_pct
    volatility_component = min(
        cfg.maximum_volatility_addon_bps,
        cfg.volatility_bps_at_reference_range * volatility_ratio,
    )
    raw_slippage = spread_component + liquidity_component + volatility_component
    estimated_slippage = min(raw_slippage, cfg.maximum_total_slippage_bps)
    capped = raw_slippage > cfg.maximum_total_slippage_bps

    payload = {
        "slippage_version": STATE_DEPENDENT_SLIPPAGE_VERSION,
        "state": "EVALUATED",
        "reason_codes": [],
        "side": side,
        "exchange": checked_feature.exchange,
        "symbol": checked_feature.symbol,
        "as_of": checked_feature.as_of,
        "data_cutoff": max(checked_feature.data_cutoff, checked_snapshot.data_cutoff),
        "config_fingerprint": cfg.fingerprint,
        "source_spread_snapshot_fingerprint": checked_snapshot.snapshot_fingerprint,
        "source_feature_row_fingerprint": checked_feature.fingerprint,
        "source_market_state_id": source_market_state_id,
        "spread_bps": market_state.spread_bps,
        "adv_notional": market_state.adv_notional,
        "relative_volume": market_state.relative_volume,
        "volatility_range_pct": volatility_range_pct,
        "spread_component_bps": spread_component,
        "liquidity_pressure": liquidity_pressure,
        "liquidity_component_bps": liquidity_component,
        "volatility_component_bps": volatility_component,
        "raw_slippage_bps": raw_slippage,
        "estimated_slippage_bps": estimated_slippage,
        "capped": capped,
        "status": "RESEARCH_SLIPPAGE_ESTIMATE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = StateDependentSlippageEstimateV1.model_construct(
        **payload,
        estimate_fingerprint="0" * 64,
    )
    return StateDependentSlippageEstimateV1(
        **payload,
        estimate_fingerprint=canonical_fingerprint(_estimate_payload(provisional)),
    )


__all__ = [
    "STATE_DEPENDENT_SLIPPAGE_VERSION",
    "StateDependentSlippageConfigV1",
    "StateDependentSlippageEstimateV1",
    "estimate_state_dependent_slippage",
]
