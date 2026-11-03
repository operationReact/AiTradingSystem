from __future__ import annotations

import math
from datetime import datetime, timedelta
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .market_source import MarketResearchExport, ResearchCandle
from .state_dependent_slippage import StateDependentSlippageEstimateV1
from .universe_identity_bridge import (
    IDENTITY_BRIDGE_VERSION,
    MAPPING_RULE_VERSION,
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

FILL_SIMULATOR_VERSION = "fill-simulator.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
TradeSide = Literal["BUY", "SELL"]
OrderType = Literal["MARKET", "LIMIT"]
FillState = Literal["FULLY_FILLED", "PARTIALLY_FILLED", "UNFILLED", "INCONCLUSIVE"]


class FillSimulationConfigV1(BaseModel):
    """Conservative bar-level fill policy; price impact is intentionally out of scope."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    simulator_version: Literal["fill-simulator.v1"] = FILL_SIMULATOR_VERSION
    latency_milliseconds: Annotated[int, Field(ge=0, le=600_000)] = 250
    max_fill_fraction_of_bar_volume: Annotated[float, Field(gt=0.0, le=1.0)] = 0.05
    max_wait_bars: Annotated[int, Field(ge=1, le=1_000)] = 5
    max_slippage_age_seconds: Annotated[int, Field(ge=1, le=3_600)] = 60

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class FillSimulationRequestV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    simulator_version: Literal["fill-simulator.v1"] = FILL_SIMULATOR_VERSION
    order_id: Annotated[str, Field(min_length=1, max_length=128)]
    canonical_symbol: Annotated[str, Field(min_length=1, max_length=32)]
    canonical_instrument_id: Annotated[str, Field(min_length=1, max_length=80)]
    broker_trading_symbol: Annotated[str, Field(min_length=1, max_length=100)]
    side: TradeSide
    order_type: OrderType
    quantity: Annotated[int, Field(ge=1, le=1_000_000_000)]
    submitted_at: datetime
    reference_price: Annotated[float, Field(gt=0.0)]
    limit_price: Annotated[float | None, Field(gt=0.0)] = None
    config_fingerprint: Hash64
    slippage_estimate_fingerprint: Hash64
    identity_bridge_fingerprint: Hash64
    identity_binding_fingerprint: Hash64
    market_export_fingerprint: Hash64
    request_fingerprint: Hash64
    status: Literal["RESEARCH_FILL_REQUEST_ONLY"] = "RESEARCH_FILL_REQUEST_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("submitted_at")
    @classmethod
    def submitted_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("fill request submitted_at must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_request(self) -> FillSimulationRequestV1:
        if self.order_type == "MARKET" and self.limit_price is not None:
            raise ValueError("MARKET fill request cannot contain limit_price")
        if self.order_type == "LIMIT" and self.limit_price is None:
            raise ValueError("LIMIT fill request requires limit_price")
        if self.request_fingerprint != canonical_fingerprint(_request_payload(self)):
            raise ValueError("fill simulation request fingerprint mismatch")
        return self


class SimulatedFillV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fill_index: Annotated[int, Field(ge=0)]
    candle_time: datetime
    candle_source_fingerprint: Hash64
    quantity: Annotated[int, Field(ge=1)]
    price: Annotated[float, Field(gt=0.0)]
    bar_open: Annotated[float, Field(gt=0.0)]
    bar_high: Annotated[float, Field(gt=0.0)]
    bar_low: Annotated[float, Field(gt=0.0)]
    bar_volume: Annotated[float, Field(ge=0.0)]
    capacity_quantity: Annotated[int, Field(ge=1)]
    slippage_bps_applied: Annotated[float, Field(ge=0.0)]
    price_bound_applied: bool
    fill_fingerprint: Hash64

    @field_validator("candle_time")
    @classmethod
    def candle_time_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("fill candle_time must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_fill(self) -> SimulatedFillV1:
        if not self.bar_low <= self.price <= self.bar_high:
            raise ValueError("simulated fill price must remain inside historical bar range")
        if self.quantity > self.capacity_quantity:
            raise ValueError("simulated fill cannot exceed configured bar capacity")
        if self.fill_fingerprint != canonical_fingerprint(_fill_payload(self)):
            raise ValueError("simulated fill fingerprint mismatch")
        return self


class FillSimulationResultV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    simulator_version: Literal["fill-simulator.v1"] = FILL_SIMULATOR_VERSION
    state: FillState
    reason_codes: list[str]
    request: FillSimulationRequestV1
    eligible_at: datetime
    latency_skipped_bar_count: Annotated[int, Field(ge=0)]
    considered_bar_count: Annotated[int, Field(ge=0)]
    requested_quantity: Annotated[int, Field(ge=1)]
    filled_quantity: Annotated[int, Field(ge=0)]
    remaining_quantity: Annotated[int, Field(ge=0)]
    fill_fraction: Annotated[float, Field(ge=0.0, le=1.0)]
    average_fill_price: Annotated[float | None, Field(gt=0.0)] = None
    arrival_gap_bps: float | None = None
    fills: list[SimulatedFillV1]
    result_fingerprint: Hash64
    status: Literal["RESEARCH_FILL_SIMULATION_ONLY"] = "RESEARCH_FILL_SIMULATION_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("eligible_at")
    @classmethod
    def eligible_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("fill simulation eligible_at must be timezone-aware")
        return value

    @field_validator("arrival_gap_bps")
    @classmethod
    def gap_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("arrival_gap_bps must be finite")
        return value

    @model_validator(mode="after")
    def validate_result(self) -> FillSimulationResultV1:
        if self.filled_quantity + self.remaining_quantity != self.requested_quantity:
            raise ValueError("fill quantities must reconcile to requested quantity")
        if self.filled_quantity != sum(item.quantity for item in self.fills):
            raise ValueError("filled_quantity must equal simulated fill quantities")
        expected_fraction = self.filled_quantity / self.requested_quantity
        if not math.isclose(self.fill_fraction, expected_fraction, abs_tol=1e-12):
            raise ValueError("fill_fraction does not match filled quantity")
        if self.filled_quantity == 0:
            if self.average_fill_price is not None or self.fills:
                raise ValueError("zero-filled simulation cannot contain fill price evidence")
        else:
            if self.average_fill_price is None or not self.fills:
                raise ValueError("filled simulation requires fill price evidence")
        expected_state: FillState
        if self.state == "INCONCLUSIVE":
            expected_state = "INCONCLUSIVE"
            if self.filled_quantity != 0:
                raise ValueError("inconclusive fill simulation cannot contain fills")
        elif self.filled_quantity == self.requested_quantity:
            expected_state = "FULLY_FILLED"
        elif self.filled_quantity > 0:
            expected_state = "PARTIALLY_FILLED"
        else:
            expected_state = "UNFILLED"
        if self.state != expected_state:
            raise ValueError("fill simulation state does not match fill quantities")
        if self.state == "FULLY_FILLED" and self.reason_codes:
            raise ValueError("fully filled simulation cannot contain reason codes")
        if self.state != "FULLY_FILLED" and not self.reason_codes:
            raise ValueError("non-full fill simulation requires reason codes")
        if self.result_fingerprint != canonical_fingerprint(_result_payload(self)):
            raise ValueError("fill simulation result fingerprint mismatch")
        return self


def _binding_payload(binding: Nifty500LiquidityIdentityBinding) -> dict[str, object]:
    return {
        "canonicalInstrumentId": binding.canonical_instrument_id,
        "isin": binding.isin,
        "symbol": binding.symbol,
        "series": binding.series,
        "sector": binding.sector,
        "expectedTradingSymbol": binding.expected_trading_symbol,
        "catalogueInstrumentId": binding.catalogue_instrument_id,
        "brokerTradingSymbol": binding.broker_trading_symbol,
        "brokerSymbolToken": binding.broker_symbol_token,
        "liquidityEvidenceFingerprint": binding.liquidity_evidence_fingerprint,
        "sectorMappingEvidenceFingerprint": binding.sector_mapping_evidence_fingerprint,
        "membershipEffectiveFrom": binding.membership_effective_from.isoformat(),
        "availableAt": binding.available_at.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
    }


def _bridge_payload(bridge: Nifty500LiquidityIdentityBridge) -> dict[str, object]:
    return {
        "contractVersion": IDENTITY_BRIDGE_VERSION,
        "asOf": bridge.as_of.isoformat(),
        "dataCutoff": bridge.data_cutoff.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
        "sourceCatalogueVersion": bridge.source_catalogue_version,
        "sourceCatalogueFingerprint": bridge.source_catalogue_fingerprint,
        "sourceLiquiditySnapshotFingerprint": bridge.source_liquidity_snapshot_fingerprint,
        "sourceSectorEnrichmentFingerprint": bridge.source_sector_enrichment_fingerprint,
        "bindings": [item.model_dump(mode="json") for item in bridge.bindings],
        "bindingCount": bridge.binding_count,
        "status": bridge.status,
        "canAuthorizeCapital": bridge.can_authorize_capital,
    }


def _market_export_payload(export: MarketResearchExport) -> dict[str, object]:
    return {
        "contractVersion": export.contract_version,
        "generatedAt": export.generated_at.isoformat(),
        "instrumentId": export.instrument_id,
        "exchange": export.exchange,
        "tradingSymbol": export.trading_symbol,
        "symbolToken": export.symbol_token,
        "catalogueVersion": str(export.catalogue_version),
        "interval": export.interval,
        "fromInclusive": export.from_inclusive.isoformat(),
        "toExclusive": export.to_exclusive.isoformat(),
        "dataCutoff": export.data_cutoff.isoformat() if export.data_cutoff else None,
        "providerSources": sorted(export.provider_sources),
        "candles": [
            candle.model_dump(mode="json", by_alias=True)
            for candle in sorted(export.candles, key=lambda item: item.candle_time)
        ],
    }


def _request_payload(request: FillSimulationRequestV1) -> dict[str, object]:
    return request.model_dump(mode="json", exclude={"request_fingerprint"})


def _fill_payload(fill: SimulatedFillV1) -> dict[str, object]:
    return fill.model_dump(mode="json", exclude={"fill_fingerprint"})


def _result_payload(result: FillSimulationResultV1) -> dict[str, object]:
    return result.model_dump(mode="json", exclude={"result_fingerprint"})


def _find_binding(
    bridge: Nifty500LiquidityIdentityBridge,
    slippage: StateDependentSlippageEstimateV1,
) -> Nifty500LiquidityIdentityBinding:
    matches = [
        item
        for item in bridge.bindings
        if item.symbol == slippage.symbol and item.canonical_instrument_id.startswith("NSE:")
    ]
    if len(matches) != 1:
        raise ValueError("slippage symbol must resolve to exactly one identity binding")
    binding = matches[0]
    if binding.binding_fingerprint != canonical_fingerprint(_binding_payload(binding)):
        raise ValueError("identity binding fingerprint mismatch")
    return binding


def _build_request(
    *,
    order_id: str,
    side: TradeSide,
    order_type: OrderType,
    quantity: int,
    submitted_at: datetime,
    reference_price: float,
    limit_price: float | None,
    config: FillSimulationConfigV1,
    slippage: StateDependentSlippageEstimateV1,
    bridge: Nifty500LiquidityIdentityBridge,
    binding: Nifty500LiquidityIdentityBinding,
    market_export_fingerprint: str,
) -> FillSimulationRequestV1:
    payload = {
        "simulator_version": FILL_SIMULATOR_VERSION,
        "order_id": order_id,
        "canonical_symbol": slippage.symbol,
        "canonical_instrument_id": binding.canonical_instrument_id,
        "broker_trading_symbol": binding.broker_trading_symbol,
        "side": side,
        "order_type": order_type,
        "quantity": quantity,
        "submitted_at": submitted_at,
        "reference_price": reference_price,
        "limit_price": limit_price,
        "config_fingerprint": config.fingerprint,
        "slippage_estimate_fingerprint": slippage.estimate_fingerprint,
        "identity_bridge_fingerprint": bridge.snapshot_fingerprint,
        "identity_binding_fingerprint": binding.binding_fingerprint,
        "market_export_fingerprint": market_export_fingerprint,
        "status": "RESEARCH_FILL_REQUEST_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = FillSimulationRequestV1.model_construct(
        **payload,
        request_fingerprint="0" * 64,
    )
    return FillSimulationRequestV1(
        **payload,
        request_fingerprint=canonical_fingerprint(_request_payload(provisional)),
    )


def _gap_bps(side: TradeSide, reference_price: float, bar_open: float) -> float:
    raw = (bar_open / reference_price) - 1.0
    return raw * 10_000.0 if side == "BUY" else -raw * 10_000.0


def _adverse_market_price(
    candle: ResearchCandle,
    *,
    side: TradeSide,
    slippage_bps: float,
) -> tuple[float, bool]:
    fraction = slippage_bps / 10_000.0
    raw = candle.open * (1.0 + fraction if side == "BUY" else 1.0 - fraction)
    bounded = min(max(raw, candle.low), candle.high)
    return bounded, not math.isclose(raw, bounded, rel_tol=1e-12, abs_tol=1e-12)


def _fill_price(
    candle: ResearchCandle,
    *,
    side: TradeSide,
    order_type: OrderType,
    limit_price: float | None,
    slippage_bps: float,
) -> tuple[float, bool] | None:
    market_price, bounded = _adverse_market_price(
        candle,
        side=side,
        slippage_bps=slippage_bps,
    )
    if order_type == "MARKET":
        return market_price, bounded

    assert limit_price is not None
    if side == "BUY":
        if candle.low > limit_price:
            return None
        if candle.open <= limit_price:
            return min(limit_price, market_price), bounded or market_price > limit_price
        return limit_price, False

    if candle.high < limit_price:
        return None
    if candle.open >= limit_price:
        return max(limit_price, market_price), bounded or market_price < limit_price
    return limit_price, False


def _make_fill(
    *,
    fill_index: int,
    candle: ResearchCandle,
    quantity: int,
    capacity_quantity: int,
    price: float,
    slippage_bps: float,
    price_bound_applied: bool,
) -> SimulatedFillV1:
    candle_fingerprint = canonical_fingerprint(
        candle.model_dump(mode="json", by_alias=True)
    )
    payload = {
        "fill_index": fill_index,
        "candle_time": candle.candle_time,
        "candle_source_fingerprint": candle_fingerprint,
        "quantity": quantity,
        "price": price,
        "bar_open": candle.open,
        "bar_high": candle.high,
        "bar_low": candle.low,
        "bar_volume": candle.volume,
        "capacity_quantity": capacity_quantity,
        "slippage_bps_applied": slippage_bps,
        "price_bound_applied": price_bound_applied,
    }
    provisional = SimulatedFillV1.model_construct(**payload, fill_fingerprint="0" * 64)
    return SimulatedFillV1(
        **payload,
        fill_fingerprint=canonical_fingerprint(_fill_payload(provisional)),
    )


def _build_result(
    *,
    state: FillState,
    reasons: list[str],
    request: FillSimulationRequestV1,
    eligible_at: datetime,
    latency_skipped_bar_count: int,
    considered_bar_count: int,
    arrival_gap_bps: float | None,
    fills: list[SimulatedFillV1],
) -> FillSimulationResultV1:
    filled_quantity = sum(item.quantity for item in fills)
    remaining_quantity = request.quantity - filled_quantity
    average_fill_price = (
        sum(item.price * item.quantity for item in fills) / filled_quantity
        if filled_quantity
        else None
    )
    payload = {
        "simulator_version": FILL_SIMULATOR_VERSION,
        "state": state,
        "reason_codes": sorted(set(reasons)),
        "request": request,
        "eligible_at": eligible_at,
        "latency_skipped_bar_count": latency_skipped_bar_count,
        "considered_bar_count": considered_bar_count,
        "requested_quantity": request.quantity,
        "filled_quantity": filled_quantity,
        "remaining_quantity": remaining_quantity,
        "fill_fraction": filled_quantity / request.quantity,
        "average_fill_price": average_fill_price,
        "arrival_gap_bps": arrival_gap_bps,
        "fills": fills,
        "status": "RESEARCH_FILL_SIMULATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = FillSimulationResultV1.model_construct(
        **payload,
        result_fingerprint="0" * 64,
    )
    return FillSimulationResultV1(
        **payload,
        result_fingerprint=canonical_fingerprint(_result_payload(provisional)),
    )


def simulate_fill(
    *,
    order_id: str,
    side: TradeSide,
    order_type: OrderType,
    quantity: int,
    submitted_at: datetime,
    reference_price: float,
    slippage: StateDependentSlippageEstimateV1,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    market_export: MarketResearchExport,
    limit_price: float | None = None,
    config: FillSimulationConfigV1 | None = None,
) -> FillSimulationResultV1:
    """Simulate conservative bar-level fills after latency; never place an order."""

    cfg = config or FillSimulationConfigV1()
    checked_slippage = StateDependentSlippageEstimateV1.model_validate(
        slippage.model_dump(mode="python")
    )
    checked_bridge = Nifty500LiquidityIdentityBridge.model_validate(
        identity_bridge.model_dump(mode="python")
    )
    checked_export = MarketResearchExport.model_validate(
        market_export.model_dump(mode="python", by_alias=True)
    )

    if submitted_at.tzinfo is None or submitted_at.utcoffset() is None:
        raise ValueError("submitted_at must be timezone-aware")
    if checked_bridge.snapshot_fingerprint != canonical_fingerprint(
        _bridge_payload(checked_bridge)
    ):
        raise ValueError("identity bridge fingerprint mismatch")
    if checked_slippage.side != side:
        raise ValueError("fill side must match slippage side")
    if checked_slippage.exchange != "NSE":
        raise ValueError("fill simulator currently supports canonical NSE cash equity only")
    if checked_bridge.as_of != checked_slippage.as_of:
        raise ValueError("identity bridge and slippage estimate must share decision time")
    if checked_bridge.data_cutoff > checked_slippage.as_of:
        raise ValueError("identity bridge contains future evidence")

    binding = _find_binding(checked_bridge, checked_slippage)
    if binding.available_at > submitted_at:
        raise ValueError("identity binding was not available by order submission")
    if checked_export.instrument_id != binding.catalogue_instrument_id:
        raise ValueError("market export instrument does not match identity binding")
    if checked_export.exchange != "NSE":
        raise ValueError("market export exchange does not match canonical identity")
    if checked_export.trading_symbol != binding.broker_trading_symbol:
        raise ValueError("market export trading symbol does not match identity binding")
    if checked_export.symbol_token != binding.broker_symbol_token:
        raise ValueError("market export token does not match identity binding")
    if str(checked_export.catalogue_version) != checked_bridge.source_catalogue_version:
        raise ValueError("market export catalogue version does not match identity bridge")
    if checked_export.interval == "ONE_DAY":
        raise ValueError("fill simulation requires intraday market history")
    if not checked_export.from_inclusive <= submitted_at < checked_export.to_exclusive:
        raise ValueError("order submission is outside market export window")

    market_export_fingerprint = canonical_fingerprint(_market_export_payload(checked_export))
    request = _build_request(
        order_id=order_id,
        side=side,
        order_type=order_type,
        quantity=quantity,
        submitted_at=submitted_at,
        reference_price=reference_price,
        limit_price=limit_price,
        config=cfg,
        slippage=checked_slippage,
        bridge=checked_bridge,
        binding=binding,
        market_export_fingerprint=market_export_fingerprint,
    )
    eligible_at = submitted_at + timedelta(milliseconds=cfg.latency_milliseconds)

    slippage_age = (submitted_at - checked_slippage.as_of).total_seconds()
    if checked_slippage.state != "EVALUATED":
        return _build_result(
            state="INCONCLUSIVE",
            reasons=["SLIPPAGE_ESTIMATE_INCONCLUSIVE"],
            request=request,
            eligible_at=eligible_at,
            latency_skipped_bar_count=0,
            considered_bar_count=0,
            arrival_gap_bps=None,
            fills=[],
        )
    if slippage_age < 0 or slippage_age > cfg.max_slippage_age_seconds:
        return _build_result(
            state="INCONCLUSIVE",
            reasons=["SLIPPAGE_ESTIMATE_STALE_OR_FUTURE"],
            request=request,
            eligible_at=eligible_at,
            latency_skipped_bar_count=0,
            considered_bar_count=0,
            arrival_gap_bps=None,
            fills=[],
        )
    assert checked_slippage.estimated_slippage_bps is not None

    chronological = sorted(checked_export.candles, key=lambda item: item.candle_time)
    skipped_for_latency = [
        candle
        for candle in chronological
        if submitted_at <= candle.candle_time < eligible_at
    ]
    eligible = [candle for candle in chronological if candle.candle_time >= eligible_at]
    considered = eligible[: cfg.max_wait_bars]
    arrival_gap = (
        _gap_bps(side, reference_price, eligible[0].open) if eligible else None
    )

    fills: list[SimulatedFillV1] = []
    remaining = quantity
    for candle in considered:
        capacity = math.floor(candle.volume * cfg.max_fill_fraction_of_bar_volume)
        if capacity <= 0:
            continue
        price_result = _fill_price(
            candle,
            side=side,
            order_type=order_type,
            limit_price=limit_price,
            slippage_bps=checked_slippage.estimated_slippage_bps,
        )
        if price_result is None:
            continue
        price, price_bound_applied = price_result
        fill_quantity = min(remaining, capacity)
        fills.append(
            _make_fill(
                fill_index=len(fills),
                candle=candle,
                quantity=fill_quantity,
                capacity_quantity=capacity,
                price=price,
                slippage_bps=checked_slippage.estimated_slippage_bps,
                price_bound_applied=price_bound_applied,
            )
        )
        remaining -= fill_quantity
        if remaining == 0:
            break

    if remaining == 0:
        state: FillState = "FULLY_FILLED"
        reasons: list[str] = []
    elif fills:
        state = "PARTIALLY_FILLED"
        reasons = ["MAX_WAIT_BARS_REACHED_WITH_REMAINDER"]
    else:
        state = "UNFILLED"
        if not eligible:
            reasons = ["NO_POST_LATENCY_BARS"]
        elif order_type == "LIMIT":
            reasons = ["LIMIT_NOT_REACHED_OR_NO_FILL_CAPACITY"]
        else:
            reasons = ["NO_FILL_CAPACITY"]

    return _build_result(
        state=state,
        reasons=reasons,
        request=request,
        eligible_at=eligible_at,
        latency_skipped_bar_count=len(skipped_for_latency),
        considered_bar_count=len(considered),
        arrival_gap_bps=arrival_gap,
        fills=fills,
    )


__all__ = [
    "FILL_SIMULATOR_VERSION",
    "FillSimulationConfigV1",
    "FillSimulationRequestV1",
    "FillSimulationResultV1",
    "SimulatedFillV1",
    "simulate_fill",
]
