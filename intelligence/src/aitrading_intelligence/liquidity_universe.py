from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from statistics import fmean
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .catalogue_source import CatalogueInstrument, InstrumentCatalogueResearchExport
from .fingerprints import canonical_fingerprint
from .market_source import MarketResearchExport, ResearchCandle

LIQUIDITY_CONTRACT_VERSION = "liquidity-qualified-catalogue.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
RejectionReason = Literal[
    "MISSING_MARKET_HISTORY",
    "INSUFFICIENT_DAILY_HISTORY",
    "STALE_DAILY_HISTORY",
    "ADV_NOTIONAL_BELOW_MINIMUM",
    "ZERO_VOLUME_FRACTION_ABOVE_MAXIMUM",
]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class LiquidityQualificationConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_daily_bars: Annotated[int, Field(ge=2, le=252)] = 20
    evaluation_bars: Annotated[int, Field(ge=2, le=252)] = 60
    minimum_adv_notional: Annotated[float, Field(gt=0)] = 10_000_000.0
    maximum_zero_volume_fraction: Annotated[float, Field(ge=0, le=1)] = 0.05
    maximum_data_age_days: Annotated[int, Field(ge=1, le=30)] = 7

    @model_validator(mode="after")
    def validate_window(self) -> LiquidityQualificationConfig:
        if self.evaluation_bars < self.minimum_daily_bars:
            raise ValueError("evaluation_bars cannot be smaller than minimum_daily_bars")
        return self


class LiquidityCandidateEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    instrument_id: Annotated[int, Field(gt=0)]
    exchange: Literal["NSE", "BSE"]
    trading_symbol: Annotated[str, Field(min_length=1, max_length=100)]
    symbol_token: Annotated[str, Field(min_length=1, max_length=64)]
    accepted: bool
    causal_bar_count: Annotated[int, Field(ge=0, le=252)]
    latest_available_at: datetime | None
    adv_notional: Annotated[float, Field(ge=0)] | None
    zero_volume_fraction: Annotated[float, Field(ge=0, le=1)] | None
    rejection_reasons: list[RejectionReason] = Field(default_factory=list, max_length=5)
    market_evidence_fingerprint: Hash64 | None
    evidence_fingerprint: Hash64

    @field_validator("latest_available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _aware(value) if value is not None else None

    @model_validator(mode="after")
    def validate_evidence(self) -> LiquidityCandidateEvidence:
        if self.rejection_reasons != sorted(self.rejection_reasons):
            raise ValueError("rejection_reasons must be deterministically ordered")
        if self.accepted and self.rejection_reasons:
            raise ValueError("accepted evidence cannot contain rejection reasons")
        if not self.accepted and not self.rejection_reasons:
            raise ValueError("rejected evidence requires at least one reason")
        if self.causal_bar_count == 0 and any(
            value is not None
            for value in (
                self.latest_available_at,
                self.adv_notional,
                self.zero_volume_fraction,
            )
        ):
            raise ValueError("zero causal bars cannot contain derived liquidity metrics")
        return self


class LiquidityQualifiedCatalogue(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["liquidity-qualified-catalogue.v1"] = LIQUIDITY_CONTRACT_VERSION
    as_of: datetime
    data_cutoff: datetime
    source_catalogue_version: str
    source_catalogue_fingerprint: Hash64
    config_fingerprint: Hash64
    evidence: list[LiquidityCandidateEvidence] = Field(min_length=1, max_length=50_000)
    accepted_instrument_ids: list[Annotated[int, Field(gt=0)]] = Field(max_length=50_000)
    accepted_count: Annotated[int, Field(ge=0, le=50_000)]
    rejected_count: Annotated[int, Field(ge=0, le=50_000)]
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_LIQUIDITY_QUALIFICATION_ONLY"] = (
        "RESEARCH_LIQUIDITY_QUALIFICATION_ONLY"
    )
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> LiquidityQualifiedCatalogue:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        identities = [
            (item.exchange, item.trading_symbol, item.symbol_token, item.instrument_id)
            for item in self.evidence
        ]
        if identities != sorted(identities):
            raise ValueError("evidence must use canonical catalogue ordering")
        if len({item.instrument_id for item in self.evidence}) != len(self.evidence):
            raise ValueError("evidence instrument IDs must be unique")
        expected_accepted = [item.instrument_id for item in self.evidence if item.accepted]
        if self.accepted_instrument_ids != expected_accepted:
            raise ValueError("accepted_instrument_ids must exactly match accepted evidence")
        if self.accepted_count != len(expected_accepted):
            raise ValueError("accepted_count does not match accepted evidence")
        if self.rejected_count != len(self.evidence) - len(expected_accepted):
            raise ValueError("rejected_count does not match rejected evidence")
        return self


def _validate_market_export(
    instrument: CatalogueInstrument,
    catalogue: InstrumentCatalogueResearchExport,
    export: MarketResearchExport,
) -> None:
    if export.catalogue_version != catalogue.catalogue_version:
        raise ValueError("market export catalogueVersion does not match the source catalogue")
    if export.instrument_id != instrument.instrument_id:
        raise ValueError("market export instrumentId does not match catalogue evidence")
    if export.exchange != instrument.exchange:
        raise ValueError("market export exchange does not match catalogue evidence")
    if export.trading_symbol != instrument.trading_symbol:
        raise ValueError("market export tradingSymbol does not match catalogue evidence")
    if export.symbol_token != instrument.symbol_token:
        raise ValueError("market export symbolToken does not match catalogue evidence")
    if export.interval != "ONE_DAY":
        raise ValueError("liquidity qualification requires ONE_DAY market history")


def _causal_candles(export: MarketResearchExport, as_of: datetime) -> list[ResearchCandle]:
    return sorted(
        (candle for candle in export.candles if candle.available_at <= as_of),
        key=lambda candle: (candle.available_at, candle.candle_time),
    )


def _market_fingerprint(export: MarketResearchExport, candles: Sequence[ResearchCandle]) -> str:
    causal_sources = sorted({candle.source for candle in candles})
    return canonical_fingerprint(
        {
            "contractVersion": export.contract_version,
            "instrumentId": export.instrument_id,
            "exchange": export.exchange,
            "tradingSymbol": export.trading_symbol,
            "symbolToken": export.symbol_token,
            "catalogueVersion": str(export.catalogue_version),
            "interval": export.interval,
            "providerSources": causal_sources,
            "candles": [candle.model_dump(mode="json", by_alias=True) for candle in candles],
        }
    )


def _missing_evidence(instrument: CatalogueInstrument) -> LiquidityCandidateEvidence:
    payload = {
        "instrumentId": instrument.instrument_id,
        "exchange": instrument.exchange,
        "tradingSymbol": instrument.trading_symbol,
        "symbolToken": instrument.symbol_token,
        "accepted": False,
        "causalBarCount": 0,
        "latestAvailableAt": None,
        "advNotional": None,
        "zeroVolumeFraction": None,
        "rejectionReasons": ["MISSING_MARKET_HISTORY"],
        "marketEvidenceFingerprint": None,
    }
    return LiquidityCandidateEvidence(
        instrument_id=instrument.instrument_id,
        exchange=instrument.exchange,
        trading_symbol=instrument.trading_symbol,
        symbol_token=instrument.symbol_token,
        accepted=False,
        causal_bar_count=0,
        latest_available_at=None,
        adv_notional=None,
        zero_volume_fraction=None,
        rejection_reasons=["MISSING_MARKET_HISTORY"],
        market_evidence_fingerprint=None,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def _qualify(
    *,
    instrument: CatalogueInstrument,
    catalogue: InstrumentCatalogueResearchExport,
    export: MarketResearchExport | None,
    as_of: datetime,
    config: LiquidityQualificationConfig,
) -> LiquidityCandidateEvidence:
    if export is None:
        return _missing_evidence(instrument)

    _validate_market_export(instrument, catalogue, export)
    causal = _causal_candles(export, as_of)[-config.evaluation_bars :]
    market_fingerprint = _market_fingerprint(export, causal)
    latest = causal[-1].available_at if causal else None
    adv_notional = fmean(candle.close * candle.volume for candle in causal) if causal else None
    zero_fraction = (
        sum(1 for candle in causal if candle.volume == 0) / len(causal) if causal else None
    )

    reasons: list[RejectionReason] = []
    if not causal:
        reasons.append("MISSING_MARKET_HISTORY")
    else:
        if len(causal) < config.minimum_daily_bars:
            reasons.append("INSUFFICIENT_DAILY_HISTORY")
        assert latest is not None
        if (as_of - latest).total_seconds() > config.maximum_data_age_days * 86_400:
            reasons.append("STALE_DAILY_HISTORY")
        assert adv_notional is not None
        if adv_notional < config.minimum_adv_notional:
            reasons.append("ADV_NOTIONAL_BELOW_MINIMUM")
        assert zero_fraction is not None
        if zero_fraction > config.maximum_zero_volume_fraction:
            reasons.append("ZERO_VOLUME_FRACTION_ABOVE_MAXIMUM")
    reasons = sorted(reasons)
    accepted = not reasons

    payload = {
        "instrumentId": instrument.instrument_id,
        "exchange": instrument.exchange,
        "tradingSymbol": instrument.trading_symbol,
        "symbolToken": instrument.symbol_token,
        "accepted": accepted,
        "causalBarCount": len(causal),
        "latestAvailableAt": latest.isoformat() if latest is not None else None,
        "advNotional": adv_notional,
        "zeroVolumeFraction": zero_fraction,
        "rejectionReasons": reasons,
        "marketEvidenceFingerprint": market_fingerprint,
    }
    return LiquidityCandidateEvidence(
        instrument_id=instrument.instrument_id,
        exchange=instrument.exchange,
        trading_symbol=instrument.trading_symbol,
        symbol_token=instrument.symbol_token,
        accepted=accepted,
        causal_bar_count=len(causal),
        latest_available_at=latest,
        adv_notional=adv_notional,
        zero_volume_fraction=zero_fraction,
        rejection_reasons=reasons,
        market_evidence_fingerprint=market_fingerprint,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def build_liquidity_qualified_catalogue(
    *,
    catalogue: InstrumentCatalogueResearchExport,
    market_exports: Sequence[MarketResearchExport],
    as_of: datetime,
    config: LiquidityQualificationConfig,
) -> LiquidityQualifiedCatalogue:
    """Qualify cash-equity candidates using only daily evidence available by `as_of`."""

    _aware(as_of)
    if catalogue.data_cutoff > as_of:
        raise ValueError("source catalogue was not available at as_of")

    catalogue_by_id = {item.instrument_id: item for item in catalogue.instruments}
    market_by_id: dict[int, MarketResearchExport] = {}
    for export in market_exports:
        instrument = catalogue_by_id.get(export.instrument_id)
        if instrument is None:
            raise ValueError("market export instrument is not present in the source catalogue")
        if export.instrument_id in market_by_id:
            raise ValueError("duplicate market export for instrumentId")
        _validate_market_export(instrument, catalogue, export)
        market_by_id[export.instrument_id] = export

    evidence = [
        _qualify(
            instrument=instrument,
            catalogue=catalogue,
            export=market_by_id.get(instrument.instrument_id),
            as_of=as_of,
            config=config,
        )
        for instrument in catalogue.instruments
    ]
    accepted_ids = [item.instrument_id for item in evidence if item.accepted]
    config_fingerprint = canonical_fingerprint(config.model_dump(mode="json"))
    causal_cutoffs = [
        item.latest_available_at for item in evidence if item.latest_available_at is not None
    ]
    data_cutoff = max([catalogue.data_cutoff, *causal_cutoffs])

    snapshot_payload = {
        "contractVersion": LIQUIDITY_CONTRACT_VERSION,
        "asOf": as_of.isoformat(),
        "dataCutoff": data_cutoff.isoformat(),
        "sourceCatalogueVersion": str(catalogue.catalogue_version),
        "sourceCatalogueFingerprint": catalogue.catalogue_fingerprint,
        "configFingerprint": config_fingerprint,
        "evidence": [item.model_dump(mode="json") for item in evidence],
        "acceptedInstrumentIds": accepted_ids,
        "acceptedCount": len(accepted_ids),
        "rejectedCount": len(evidence) - len(accepted_ids),
        "status": "RESEARCH_LIQUIDITY_QUALIFICATION_ONLY",
        "canAuthorizeCapital": False,
    }
    return LiquidityQualifiedCatalogue(
        as_of=as_of,
        data_cutoff=data_cutoff,
        source_catalogue_version=str(catalogue.catalogue_version),
        source_catalogue_fingerprint=catalogue.catalogue_fingerprint,
        config_fingerprint=config_fingerprint,
        evidence=evidence,
        accepted_instrument_ids=accepted_ids,
        accepted_count=len(accepted_ids),
        rejected_count=len(evidence) - len(accepted_ids),
        snapshot_fingerprint=canonical_fingerprint(snapshot_payload),
    )
