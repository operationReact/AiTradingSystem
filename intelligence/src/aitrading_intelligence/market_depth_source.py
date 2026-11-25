from __future__ import annotations

from collections.abc import Iterable, Mapping
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_cross_section import IndiaCrossSectionMemberObservation
from .universe_identity_bridge import Nifty500LiquidityIdentityBridge

ANGEL_ONE_MARKET_DEPTH_CONTRACT_VERSION = "angel-one-market-depth.v1"
MARKET_DEPTH_SPREAD_JOIN_VERSION = "angel-one-market-depth-spread-join.v1"
ANGEL_ONE_FULL_QUOTE_SOURCE = "ANGEL_ONE_SMARTAPI_FULL_QUOTE"
OBSERVED_SPREAD_FLAG = "OBSERVED_ANGEL_ONE_FULL_DEPTH_SPREAD"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


def _to_camel(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(part.capitalize() for part in tail)


class AngelOneMarketDepthEvidence(BaseModel):
    """Strict mirror of one backend `angel-one-market-depth.v1` evidence row."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        alias_generator=_to_camel,
        populate_by_name=True,
    )

    instrument_id: Annotated[int, Field(gt=0)]
    catalogue_version: Annotated[str, Field(min_length=1, max_length=80)]
    exchange: Literal["NSE"]
    trading_symbol: Annotated[str, Field(min_length=1, max_length=100)]
    symbol_token: Annotated[str, Field(min_length=1, max_length=64)]
    event_at: datetime
    available_at: datetime
    ltp: Annotated[Decimal, Field(gt=0)]
    best_bid_price: Annotated[Decimal, Field(gt=0)]
    best_bid_quantity: Annotated[int, Field(ge=0)]
    best_bid_orders: Annotated[int, Field(ge=0)]
    best_ask_price: Annotated[Decimal, Field(gt=0)]
    best_ask_quantity: Annotated[int, Field(ge=0)]
    best_ask_orders: Annotated[int, Field(ge=0)]
    midpoint: Annotated[Decimal, Field(gt=0)]
    spread_bps: Annotated[Decimal, Field(ge=0, le=10_000)]
    source: Literal["ANGEL_ONE_SMARTAPI_FULL_QUOTE"] = ANGEL_ONE_FULL_QUOTE_SOURCE
    source_id: Hash64

    @field_validator("event_at", "available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_depth(self) -> AngelOneMarketDepthEvidence:
        if self.available_at < self.event_at:
            raise ValueError("market-depth available_at cannot precede event_at")
        if self.best_ask_price < self.best_bid_price:
            raise ValueError("market-depth book cannot be crossed")
        expected_midpoint = (self.best_bid_price + self.best_ask_price) / Decimal("2")
        if self.midpoint != expected_midpoint:
            raise ValueError("market-depth midpoint does not match best bid/ask")
        expected_spread = (
            (self.best_ask_price - self.best_bid_price)
            / expected_midpoint
            * Decimal("10000")
        ).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)
        if self.spread_bps != expected_spread:
            raise ValueError("market-depth spread_bps does not match best bid/ask")
        return self


class AngelOneMarketDepthResearchExport(BaseModel):
    """Strict Python boundary for the authenticated backend research export."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        alias_generator=_to_camel,
        populate_by_name=True,
    )

    contract_version: Literal["angel-one-market-depth.v1"] = (
        ANGEL_ONE_MARKET_DEPTH_CONTRACT_VERSION
    )
    generated_at: datetime
    data_cutoff: datetime
    row_count: Annotated[int, Field(ge=1, le=50)]
    evidence: list[AngelOneMarketDepthEvidence] = Field(min_length=1, max_length=50)
    status: Literal["RESEARCH_MARKET_DEPTH_ONLY"] = "RESEARCH_MARKET_DEPTH_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("generated_at", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_export(self) -> AngelOneMarketDepthResearchExport:
        if self.data_cutoff != self.generated_at:
            raise ValueError("market-depth data_cutoff must equal generated_at")
        if self.row_count != len(self.evidence):
            raise ValueError("market-depth row_count must equal evidence length")
        instrument_ids = [row.instrument_id for row in self.evidence]
        if instrument_ids != sorted(instrument_ids):
            raise ValueError("market-depth evidence must use deterministic instrument ordering")
        if len(instrument_ids) != len(set(instrument_ids)):
            raise ValueError("market-depth instrument IDs must be unique")
        tokens = [row.symbol_token for row in self.evidence]
        if len(tokens) != len(set(tokens)):
            raise ValueError("market-depth symbol tokens must be unique")
        source_ids = [row.source_id for row in self.evidence]
        if len(source_ids) != len(set(source_ids)):
            raise ValueError("market-depth source IDs must be unique")
        if any(row.available_at != self.generated_at for row in self.evidence):
            raise ValueError("market-depth row availability must equal export generated_at")
        return self

    @property
    def causal_fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json", by_alias=True))


class MarketDepthSpreadJoinConfig(BaseModel):
    """Freshness policy for using observed top-of-book spread in research features."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    max_quote_age_seconds: Annotated[int, Field(gt=0, le=3600)] = 120

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class MarketDepthSpreadJoinSnapshot(BaseModel):
    """Causal spread-enriched cross-section inputs; never capital authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["angel-one-market-depth-spread-join.v1"] = (
        MARKET_DEPTH_SPREAD_JOIN_VERSION
    )
    as_of: datetime
    data_cutoff: datetime
    identity_bridge_fingerprint: Hash64
    config_fingerprint: Hash64
    observations: list[IndiaCrossSectionMemberObservation] = Field(
        min_length=1,
        max_length=10_000,
    )
    selected_depth_source_ids: list[Hash64] = Field(min_length=1, max_length=10_000)
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_SPREAD_EVIDENCE_ONLY"] = "RESEARCH_SPREAD_EVIDENCE_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> MarketDepthSpreadJoinSnapshot:
        if self.data_cutoff > self.as_of:
            raise ValueError("market-depth join data_cutoff cannot exceed as_of")
        symbols = [row.symbol for row in self.observations]
        if symbols != sorted(symbols):
            raise ValueError("market-depth joined observations must be ordered by symbol")
        if len(symbols) != len(set(symbols)):
            raise ValueError("market-depth joined observation symbols must be unique")
        if len(self.selected_depth_source_ids) != len(self.observations):
            raise ValueError("every joined observation must have one selected depth source ID")
        if len(set(self.selected_depth_source_ids)) != len(self.selected_depth_source_ids):
            raise ValueError("selected market-depth source IDs must be unique")
        return self


def parse_market_depth_research_export(
    payload: Mapping[str, object],
) -> AngelOneMarketDepthResearchExport:
    """Parse one backend JSON payload with strict schema/causality validation."""

    return AngelOneMarketDepthResearchExport.model_validate(payload)


def _latest_causal_depth(
    *,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    exports: Iterable[AngelOneMarketDepthResearchExport],
    as_of: datetime,
) -> dict[int, AngelOneMarketDepthEvidence]:
    bindings = {row.catalogue_instrument_id: row for row in identity_bridge.bindings}
    latest: dict[int, AngelOneMarketDepthEvidence] = {}
    latest_clock: dict[int, tuple[datetime, datetime]] = {}

    for export in exports:
        if export.generated_at > as_of or export.data_cutoff > as_of:
            continue
        for row in export.evidence:
            binding = bindings.get(row.instrument_id)
            if binding is None:
                continue
            if row.catalogue_version != identity_bridge.source_catalogue_version:
                raise ValueError("market-depth catalogue version does not match identity bridge")
            if row.exchange != "NSE":
                raise ValueError("market-depth exchange does not match identity bridge")
            if row.trading_symbol != binding.broker_trading_symbol:
                raise ValueError("market-depth trading symbol does not match identity bridge")
            if row.symbol_token != binding.broker_symbol_token:
                raise ValueError("market-depth symbol token does not match identity bridge")
            if row.available_at > as_of:
                continue

            clock = (row.available_at, row.event_at)
            previous_clock = latest_clock.get(row.instrument_id)
            previous = latest.get(row.instrument_id)
            if (
                previous_clock == clock
                and previous is not None
                and previous.source_id != row.source_id
            ):
                raise ValueError("ambiguous market-depth evidence at the same causal timestamp")
            if previous is None or (clock, row.source_id) > (
                previous_clock,
                previous.source_id,
            ):
                latest[row.instrument_id] = row
                latest_clock[row.instrument_id] = clock
    return latest


def attach_market_depth_spreads(
    *,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    base_observations: Iterable[IndiaCrossSectionMemberObservation],
    market_depth_exports: Iterable[AngelOneMarketDepthResearchExport],
    as_of: datetime,
    config: MarketDepthSpreadJoinConfig | None = None,
) -> MarketDepthSpreadJoinSnapshot:
    """Replace placeholders with exact, fresh, causally available FULL-depth spreads."""

    _aware(as_of)
    config = config or MarketDepthSpreadJoinConfig()
    if identity_bridge.as_of != as_of:
        raise ValueError("identity bridge and market-depth join must share decision time")
    if identity_bridge.data_cutoff > as_of:
        raise ValueError("identity bridge data cutoff cannot exceed decision time")

    binding_by_symbol = {row.symbol: row for row in identity_bridge.bindings}
    if len(binding_by_symbol) != len(identity_bridge.bindings):
        raise ValueError("identity bridge symbols must be unique for market-depth join")
    latest_depth = _latest_causal_depth(
        identity_bridge=identity_bridge,
        exports=market_depth_exports,
        as_of=as_of,
    )

    base = list(base_observations)
    if not base:
        raise ValueError("base cross-section observations cannot be empty")
    identities = [(row.exchange, row.symbol) for row in base]
    if len(identities) != len(set(identities)):
        raise ValueError(
            "base cross-section observations must have unique exchange/symbol identities"
        )

    joined: list[IndiaCrossSectionMemberObservation] = []
    selected_source_ids: list[str] = []
    for observation in sorted(base, key=lambda row: row.symbol):
        if observation.exchange != "NSE":
            raise ValueError(
                "Angel One market-depth spread join currently accepts NSE observations only"
            )
        if observation.available_at > as_of:
            raise ValueError("base cross-section observation is not available by decision time")
        binding = binding_by_symbol.get(observation.symbol)
        if binding is None:
            raise ValueError("base cross-section symbol is absent from the identity bridge")
        depth = latest_depth.get(binding.catalogue_instrument_id)
        if depth is None:
            raise ValueError("no causal market-depth evidence exists for a base observation")
        age_seconds = (as_of - depth.available_at).total_seconds()
        if age_seconds < 0 or age_seconds > config.max_quote_age_seconds:
            raise ValueError("market-depth evidence is stale for the decision time")

        available_at = max(observation.available_at, binding.available_at, depth.available_at)
        if available_at > as_of:
            raise ValueError("joined spread evidence is not available by decision time")
        event_at = max(observation.event_at, depth.event_at)
        quality_flags = sorted(set([*observation.quality_flags, OBSERVED_SPREAD_FLAG]))
        source_id = canonical_fingerprint(
            {
                "contract_version": MARKET_DEPTH_SPREAD_JOIN_VERSION,
                "base_observation": observation.model_dump(mode="json"),
                "identity_binding_fingerprint": binding.binding_fingerprint,
                "depth_source_id": depth.source_id,
                "depth_spread_bps": str(depth.spread_bps),
                "config_fingerprint": config.fingerprint,
            }
        )
        joined.append(
            IndiaCrossSectionMemberObservation(
                exchange=observation.exchange,
                symbol=observation.symbol,
                event_at=event_at,
                available_at=available_at,
                return_1=observation.return_1,
                adv_notional=observation.adv_notional,
                spread_bps=float(depth.spread_bps),
                relative_volume=observation.relative_volume,
                source=MARKET_DEPTH_SPREAD_JOIN_VERSION,
                source_id=source_id,
                quality_flags=quality_flags,
            )
        )
        selected_source_ids.append(depth.source_id)

    data_cutoff = max(
        identity_bridge.data_cutoff,
        *(row.available_at for row in joined),
    )
    payload = {
        "contract_version": MARKET_DEPTH_SPREAD_JOIN_VERSION,
        "as_of": as_of.isoformat(),
        "data_cutoff": data_cutoff.isoformat(),
        "identity_bridge_fingerprint": identity_bridge.snapshot_fingerprint,
        "config_fingerprint": config.fingerprint,
        "observations": [row.model_dump(mode="json") for row in joined],
        "selected_depth_source_ids": selected_source_ids,
        "status": "RESEARCH_SPREAD_EVIDENCE_ONLY",
        "can_authorize_capital": False,
    }
    return MarketDepthSpreadJoinSnapshot(
        as_of=as_of,
        data_cutoff=data_cutoff,
        identity_bridge_fingerprint=identity_bridge.snapshot_fingerprint,
        config_fingerprint=config.fingerprint,
        observations=joined,
        selected_depth_source_ids=selected_source_ids,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
