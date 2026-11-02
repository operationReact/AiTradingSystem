from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from statistics import fmean
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .market_source import MarketResearchExport, ResearchCandle
from .universe_identity_bridge import Nifty500LiquidityIdentityBridge

MARKET_HISTORY_CROSS_SECTION_VERSION = "market-history-cross-section-input.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class MarketHistoryCrossSectionConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evaluation_bars: Annotated[int, Field(ge=5, le=252)] = 20
    maximum_data_age_days: Annotated[int, Field(ge=1, le=30)] = 7

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class MarketHistoryCrossSectionInput(BaseModel):
    """Causal per-member market inputs before observed spread is attached."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    exchange: Literal["NSE"] = "NSE"
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    instrument_id: Annotated[int, Field(gt=0)]
    event_at: datetime
    available_at: datetime
    return_1: float
    adv_notional: Annotated[float, Field(ge=0)]
    relative_volume: Annotated[float, Field(ge=0)]
    source: Literal["MARKET_HISTORY_DAILY_CAUSAL"] = "MARKET_HISTORY_DAILY_CAUSAL"
    source_id: Hash64
    quality_flags: list[Annotated[str, Field(min_length=1, max_length=80)]] = Field(
        default_factory=lambda: ["CAUSAL_DAILY_MARKET_HISTORY"],
        max_length=16,
    )

    @field_validator("event_at", "available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_timing(self) -> MarketHistoryCrossSectionInput:
        if self.available_at < self.event_at:
            raise ValueError("market-history input availability cannot precede event time")
        if len(set(self.quality_flags)) != len(self.quality_flags):
            raise ValueError("market-history quality flags must be unique")
        return self


class MarketHistoryCrossSectionSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["market-history-cross-section-input.v1"] = (
        MARKET_HISTORY_CROSS_SECTION_VERSION
    )
    as_of: datetime
    data_cutoff: datetime
    identity_bridge_fingerprint: Hash64
    config_fingerprint: Hash64
    inputs: list[MarketHistoryCrossSectionInput] = Field(min_length=1, max_length=10_000)
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_PRE_DEPTH_CROSS_SECTION_ONLY"] = (
        "RESEARCH_PRE_DEPTH_CROSS_SECTION_ONLY"
    )
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> MarketHistoryCrossSectionSnapshot:
        if self.data_cutoff > self.as_of:
            raise ValueError("market-history cross-section cutoff cannot exceed as_of")
        identities = [(row.exchange, row.symbol) for row in self.inputs]
        if identities != sorted(identities):
            raise ValueError("market-history cross-section inputs must be canonically ordered")
        if len(identities) != len(set(identities)):
            raise ValueError("market-history cross-section identities must be unique")
        return self


def _validate_export(
    *,
    export: MarketResearchExport,
    instrument_id: int,
    trading_symbol: str,
    symbol_token: str,
    catalogue_version: str,
) -> None:
    if export.interval != "ONE_DAY":
        raise ValueError("cross-section market history requires ONE_DAY exports")
    if export.instrument_id != instrument_id:
        raise ValueError("market-history instrument ID does not match identity bridge")
    if export.exchange != "NSE":
        raise ValueError("cross-section market history requires NSE exports")
    if export.trading_symbol != trading_symbol:
        raise ValueError("market-history trading symbol does not match identity bridge")
    if export.symbol_token != symbol_token:
        raise ValueError("market-history symbol token does not match identity bridge")
    if str(export.catalogue_version) != catalogue_version:
        raise ValueError("market-history catalogue version does not match identity bridge")


def _causal(export: MarketResearchExport, as_of: datetime) -> list[ResearchCandle]:
    return sorted(
        (row for row in export.candles if row.available_at <= as_of),
        key=lambda row: (row.available_at, row.candle_time),
    )


def build_market_history_cross_section_inputs(
    *,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    market_exports: Sequence[MarketResearchExport],
    as_of: datetime,
    config: MarketHistoryCrossSectionConfig | None = None,
) -> MarketHistoryCrossSectionSnapshot:
    """Build exact-identity causal return/ADV/relative-volume inputs for every bridge member."""

    _aware(as_of)
    config = config or MarketHistoryCrossSectionConfig()
    if identity_bridge.as_of != as_of:
        raise ValueError("identity bridge and market-history cross-section must share as_of")

    exports_by_id: dict[int, MarketResearchExport] = {}
    for export in market_exports:
        if export.instrument_id in exports_by_id:
            raise ValueError("market-history exports must be unique by instrument ID")
        exports_by_id[export.instrument_id] = export

    inputs: list[MarketHistoryCrossSectionInput] = []
    required_bars = config.evaluation_bars + 1
    for binding in identity_bridge.bindings:
        export = exports_by_id.get(binding.catalogue_instrument_id)
        if export is None:
            raise ValueError("missing market-history export for identity-bridge member")
        _validate_export(
            export=export,
            instrument_id=binding.catalogue_instrument_id,
            trading_symbol=binding.broker_trading_symbol,
            symbol_token=binding.broker_symbol_token,
            catalogue_version=identity_bridge.source_catalogue_version,
        )
        candles = _causal(export, as_of)
        if len(candles) < required_bars:
            raise ValueError("insufficient causal daily history for cross-section member")
        selected = candles[-required_bars:]
        latest = selected[-1]
        previous = selected[-2]
        if (as_of - latest.available_at).total_seconds() > config.maximum_data_age_days * 86_400:
            raise ValueError("daily market history is stale for cross-section member")
        prior_volumes = [row.volume for row in selected[:-1]]
        mean_prior_volume = fmean(prior_volumes)
        if mean_prior_volume <= 0:
            raise ValueError("prior daily volume mean must be positive")
        evaluation = selected[-config.evaluation_bars :]
        adv_notional = fmean(row.close * row.volume for row in evaluation)
        return_1 = latest.close / previous.close - 1.0
        relative_volume = latest.volume / mean_prior_volume
        source_id = canonical_fingerprint(
            {
                "contract_version": MARKET_HISTORY_CROSS_SECTION_VERSION,
                "instrument_id": binding.catalogue_instrument_id,
                "canonical_instrument_id": binding.canonical_instrument_id,
                "binding_fingerprint": binding.binding_fingerprint,
                "config_fingerprint": config.fingerprint,
                "candles": [row.model_dump(mode="json", by_alias=True) for row in selected],
            }
        )
        inputs.append(
            MarketHistoryCrossSectionInput(
                symbol=binding.symbol,
                instrument_id=binding.catalogue_instrument_id,
                event_at=latest.candle_time,
                available_at=max(latest.available_at, binding.available_at),
                return_1=return_1,
                adv_notional=adv_notional,
                relative_volume=relative_volume,
                source_id=source_id,
            )
        )

    inputs.sort(key=lambda row: (row.exchange, row.symbol))
    data_cutoff = max(identity_bridge.data_cutoff, *(row.available_at for row in inputs))
    payload = {
        "contract_version": MARKET_HISTORY_CROSS_SECTION_VERSION,
        "as_of": as_of.isoformat(),
        "data_cutoff": data_cutoff.isoformat(),
        "identity_bridge_fingerprint": identity_bridge.snapshot_fingerprint,
        "config_fingerprint": config.fingerprint,
        "inputs": [row.model_dump(mode="json") for row in inputs],
        "status": "RESEARCH_PRE_DEPTH_CROSS_SECTION_ONLY",
        "can_authorize_capital": False,
    }
    return MarketHistoryCrossSectionSnapshot(
        as_of=as_of,
        data_cutoff=data_cutoff,
        identity_bridge_fingerprint=identity_bridge.snapshot_fingerprint,
        config_fingerprint=config.fingerprint,
        inputs=inputs,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
