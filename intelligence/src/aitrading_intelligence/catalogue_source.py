from __future__ import annotations

from datetime import date, datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

CATALOGUE_EXPORT_CONTRACT = "instrument-catalogue.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class CatalogueInstrument(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    instrument_id: Annotated[int, Field(gt=0)] = Field(alias="instrumentId")
    exchange_segment: Annotated[str, Field(min_length=1, max_length=40)] = Field(
        alias="exchangeSegment"
    )
    exchange: Literal["NSE", "BSE"]
    symbol_token: Annotated[str, Field(min_length=1, max_length=64)] = Field(
        alias="symbolToken"
    )
    trading_symbol: Annotated[str, Field(min_length=1, max_length=100)] = Field(
        alias="tradingSymbol"
    )
    name: Annotated[str, Field(min_length=1, max_length=240)] | None = None
    instrument_type: Literal["EQ"] = Field(alias="instrumentType")
    expiry: date | None = None
    strike: float | None = None
    lot_size: Annotated[int, Field(gt=0)] = Field(alias="lotSize")
    tick_size: Annotated[float, Field(gt=0)] = Field(alias="tickSize")


class InstrumentCatalogueResearchExport(BaseModel):
    """Strict mirror of Java `instrument-catalogue.v1` research evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    contract_version: Literal["instrument-catalogue.v1"] = Field(
        alias="contractVersion"
    )
    generated_at: datetime = Field(alias="generatedAt")
    as_of: datetime = Field(alias="asOf")
    data_cutoff: datetime = Field(alias="dataCutoff")
    catalogue_version: UUID = Field(alias="catalogueVersion")
    catalogue_available_at: datetime = Field(alias="catalogueAvailableAt")
    source_url: Annotated[str, Field(min_length=1, max_length=2048)] = Field(
        alias="sourceUrl"
    )
    source_accepted_count: Annotated[int, Field(ge=0)] = Field(
        alias="sourceAcceptedCount"
    )
    source_rejected_count: Annotated[int, Field(ge=0)] = Field(
        alias="sourceRejectedCount"
    )
    exchanges: list[Literal["BSE", "NSE"]] = Field(min_length=2, max_length=2)
    instrument_type: Literal["EQ"] = Field(alias="instrumentType")
    row_count: Annotated[int, Field(ge=1, le=50_000)] = Field(alias="rowCount")
    catalogue_fingerprint: Hash64 = Field(alias="catalogueFingerprint")
    status: Literal["RESEARCH_CATALOGUE_ONLY"]
    can_authorize_capital: Literal[False] = Field(alias="canAuthorizeCapital")
    instruments: list[CatalogueInstrument] = Field(min_length=1, max_length=50_000)

    @field_validator("generated_at", "as_of", "data_cutoff", "catalogue_available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_export(self) -> InstrumentCatalogueResearchExport:
        if self.exchanges != ["BSE", "NSE"]:
            raise ValueError("exchanges must be the canonical BSE,NSE cash-market set")
        if self.catalogue_available_at != self.data_cutoff:
            raise ValueError("catalogueAvailableAt must equal dataCutoff")
        if self.data_cutoff > self.as_of:
            raise ValueError("catalogue dataCutoff cannot exceed asOf")
        if self.generated_at < self.data_cutoff:
            raise ValueError("generatedAt cannot precede the catalogue dataCutoff")
        if self.row_count != len(self.instruments):
            raise ValueError("rowCount must equal the number of instruments")

        ids = [item.instrument_id for item in self.instruments]
        if len(ids) != len(set(ids)):
            raise ValueError("instrumentId values must be unique within the catalogue")

        identities = [
            (item.exchange, item.trading_symbol, item.symbol_token, item.instrument_id)
            for item in self.instruments
        ]
        if identities != sorted(identities):
            raise ValueError("catalogue instruments must use canonical deterministic ordering")
        return self
