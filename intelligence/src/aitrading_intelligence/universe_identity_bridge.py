from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .catalogue_source import InstrumentCatalogueResearchExport
from .fingerprints import canonical_fingerprint
from .liquidity_universe import LiquidityCandidateEvidence, LiquidityQualifiedCatalogue
from .nse_sector_taxonomy import SectorEnrichedNifty500Snapshot

IDENTITY_BRIDGE_VERSION = "nifty500-liquidity-identity-bridge.v1"
MAPPING_RULE_VERSION = "nse-symbol-series-to-angel-trading-symbol.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Isin = Annotated[str, Field(pattern=r"^[A-Z]{2}[A-Z0-9]{9}[0-9]$")]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class Nifty500LiquidityIdentityBinding(BaseModel):
    """Exact identity evidence from official NIFTY/ISIN lineage to one broker catalogue row."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    canonical_instrument_id: Annotated[str, Field(pattern=r"^NSE:[A-Z]{2}[A-Z0-9]{9}[0-9]$")]
    isin: Isin
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    series: Annotated[str, Field(min_length=1, max_length=24)]
    sector: Annotated[str, Field(min_length=1, max_length=160)]
    expected_trading_symbol: Annotated[str, Field(min_length=3, max_length=100)]
    catalogue_instrument_id: Annotated[int, Field(gt=0)]
    broker_trading_symbol: Annotated[str, Field(min_length=1, max_length=100)]
    broker_symbol_token: Annotated[str, Field(min_length=1, max_length=64)]
    liquidity_evidence_fingerprint: Hash64
    sector_mapping_evidence_fingerprint: Hash64
    available_at: datetime
    mapping_rule: Literal["nse-symbol-series-to-angel-trading-symbol.v1"] = MAPPING_RULE_VERSION
    binding_fingerprint: Hash64

    @field_validator("available_at")
    @classmethod
    def available_at_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_binding(self) -> Nifty500LiquidityIdentityBinding:
        if self.canonical_instrument_id != f"NSE:{self.isin}":
            raise ValueError("canonical instrument identity must be NSE:<ISIN>")
        if self.expected_trading_symbol != f"{self.symbol}-{self.series}":
            raise ValueError("expected trading symbol must exactly equal <symbol>-<series>")
        if self.broker_trading_symbol != self.expected_trading_symbol:
            raise ValueError("broker trading symbol must exactly match the deterministic NSE mapping")
        return self


class Nifty500LiquidityIdentityBridge(BaseModel):
    """Auditable exact join between NIFTY 500 sector evidence and liquidity-qualified broker IDs."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["nifty500-liquidity-identity-bridge.v1"] = IDENTITY_BRIDGE_VERSION
    as_of: datetime
    data_cutoff: datetime
    mapping_rule: Literal["nse-symbol-series-to-angel-trading-symbol.v1"] = MAPPING_RULE_VERSION
    source_catalogue_version: Annotated[str, Field(min_length=1, max_length=80)]
    source_catalogue_fingerprint: Hash64
    source_liquidity_snapshot_fingerprint: Hash64
    source_sector_enrichment_fingerprint: Hash64
    bindings: list[Nifty500LiquidityIdentityBinding] = Field(min_length=1, max_length=500)
    binding_count: Annotated[int, Field(ge=1, le=500)]
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_IDENTITY_BRIDGE_ONLY"] = "RESEARCH_IDENTITY_BRIDGE_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> Nifty500LiquidityIdentityBridge:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        if self.binding_count != len(self.bindings):
            raise ValueError("binding_count must equal bindings length")
        identities = [item.canonical_instrument_id for item in self.bindings]
        if identities != sorted(identities):
            raise ValueError("bindings must use canonical instrument ordering")
        if len(identities) != len(set(identities)):
            raise ValueError("canonical instrument identities must be unique")
        catalogue_ids = [item.catalogue_instrument_id for item in self.bindings]
        if len(catalogue_ids) != len(set(catalogue_ids)):
            raise ValueError("catalogue instrument identities must be unique")
        if self.data_cutoff != max(item.available_at for item in self.bindings):
            raise ValueError("data_cutoff must equal the latest binding availability")
        return self


def _validate_liquidity_catalogue_lineage(
    *,
    catalogue: InstrumentCatalogueResearchExport,
    liquidity: LiquidityQualifiedCatalogue,
) -> dict[int, LiquidityCandidateEvidence]:
    if liquidity.source_catalogue_version != str(catalogue.catalogue_version):
        raise ValueError("liquidity source catalogue version does not match catalogue")
    if liquidity.source_catalogue_fingerprint != catalogue.catalogue_fingerprint:
        raise ValueError("liquidity source catalogue fingerprint does not match catalogue")

    catalogue_by_id = {item.instrument_id: item for item in catalogue.instruments}
    liquidity_by_id = {item.instrument_id: item for item in liquidity.evidence}
    if set(liquidity_by_id) != set(catalogue_by_id):
        raise ValueError("liquidity evidence must cover the exact source catalogue identity set")

    for instrument_id, evidence in liquidity_by_id.items():
        instrument = catalogue_by_id[instrument_id]
        if evidence.exchange != instrument.exchange:
            raise ValueError("liquidity evidence exchange does not match catalogue")
        if evidence.trading_symbol != instrument.trading_symbol:
            raise ValueError("liquidity evidence trading symbol does not match catalogue")
        if evidence.symbol_token != instrument.symbol_token:
            raise ValueError("liquidity evidence symbol token does not match catalogue")
    return liquidity_by_id


def build_nifty500_liquidity_identity_bridge(
    *,
    catalogue: InstrumentCatalogueResearchExport,
    liquidity: LiquidityQualifiedCatalogue,
    sector_enrichment: SectorEnrichedNifty500Snapshot,
    as_of: datetime,
) -> Nifty500LiquidityIdentityBridge:
    """Join only exact NSE `<symbol>-<series>` broker identities; never guess or fuzz-match."""

    _aware(as_of)
    if catalogue.as_of != as_of:
        raise ValueError("catalogue as_of must exactly match bridge as_of")
    if liquidity.as_of != as_of:
        raise ValueError("liquidity as_of must exactly match bridge as_of")
    if sector_enrichment.as_of != as_of:
        raise ValueError("sector enrichment as_of must exactly match bridge as_of")

    liquidity_by_id = _validate_liquidity_catalogue_lineage(
        catalogue=catalogue,
        liquidity=liquidity,
    )

    catalogue_by_trading_symbol: dict[str, list] = {}
    for instrument in catalogue.instruments:
        if instrument.exchange != "NSE":
            continue
        catalogue_by_trading_symbol.setdefault(instrument.trading_symbol, []).append(instrument)

    bindings: list[Nifty500LiquidityIdentityBinding] = []
    for row in sector_enrichment.constituents:
        expected_trading_symbol = f"{row.symbol}-{row.series}"
        matches = catalogue_by_trading_symbol.get(expected_trading_symbol, [])
        if not matches:
            raise ValueError(
                "NIFTY 500 constituent has no exact NSE catalogue trading symbol: "
                + expected_trading_symbol
            )
        if len(matches) != 1:
            raise ValueError(
                "NIFTY 500 constituent has ambiguous exact NSE catalogue trading symbol: "
                + expected_trading_symbol
            )
        instrument = matches[0]
        evidence = liquidity_by_id[instrument.instrument_id]
        if not evidence.accepted:
            continue
        if evidence.latest_available_at is None:
            raise ValueError("accepted liquidity evidence must have latest_available_at")

        available_at = max(
            catalogue.data_cutoff,
            liquidity.data_cutoff,
            sector_enrichment.data_cutoff,
            evidence.latest_available_at,
        )
        if available_at > as_of:
            raise ValueError("identity binding availability cannot exceed as_of")

        binding_payload = {
            "canonicalInstrumentId": f"NSE:{row.isin}",
            "isin": row.isin,
            "symbol": row.symbol,
            "series": row.series,
            "sector": row.sector,
            "expectedTradingSymbol": expected_trading_symbol,
            "catalogueInstrumentId": instrument.instrument_id,
            "brokerTradingSymbol": instrument.trading_symbol,
            "brokerSymbolToken": instrument.symbol_token,
            "liquidityEvidenceFingerprint": evidence.evidence_fingerprint,
            "sectorMappingEvidenceFingerprint": row.mapping_evidence_fingerprint,
            "availableAt": available_at.isoformat(),
            "mappingRule": MAPPING_RULE_VERSION,
        }
        bindings.append(
            Nifty500LiquidityIdentityBinding(
                canonical_instrument_id=f"NSE:{row.isin}",
                isin=row.isin,
                symbol=row.symbol,
                series=row.series,
                sector=row.sector,
                expected_trading_symbol=expected_trading_symbol,
                catalogue_instrument_id=instrument.instrument_id,
                broker_trading_symbol=instrument.trading_symbol,
                broker_symbol_token=instrument.symbol_token,
                liquidity_evidence_fingerprint=evidence.evidence_fingerprint,
                sector_mapping_evidence_fingerprint=row.mapping_evidence_fingerprint,
                available_at=available_at,
                binding_fingerprint=canonical_fingerprint(binding_payload),
            )
        )

    if not bindings:
        raise ValueError("no NIFTY 500 constituents passed liquidity qualification")

    ordered = sorted(bindings, key=lambda item: item.canonical_instrument_id)
    data_cutoff = max(item.available_at for item in ordered)
    snapshot_payload = {
        "contractVersion": IDENTITY_BRIDGE_VERSION,
        "asOf": as_of.isoformat(),
        "dataCutoff": data_cutoff.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
        "sourceCatalogueVersion": str(catalogue.catalogue_version),
        "sourceCatalogueFingerprint": catalogue.catalogue_fingerprint,
        "sourceLiquiditySnapshotFingerprint": liquidity.snapshot_fingerprint,
        "sourceSectorEnrichmentFingerprint": sector_enrichment.snapshot_fingerprint,
        "bindings": [item.model_dump(mode="json") for item in ordered],
        "bindingCount": len(ordered),
        "status": "RESEARCH_IDENTITY_BRIDGE_ONLY",
        "canAuthorizeCapital": False,
    }
    return Nifty500LiquidityIdentityBridge(
        as_of=as_of,
        data_cutoff=data_cutoff,
        source_catalogue_version=str(catalogue.catalogue_version),
        source_catalogue_fingerprint=catalogue.catalogue_fingerprint,
        source_liquidity_snapshot_fingerprint=liquidity.snapshot_fingerprint,
        source_sector_enrichment_fingerprint=sector_enrichment.snapshot_fingerprint,
        bindings=ordered,
        binding_count=len(ordered),
        snapshot_fingerprint=canonical_fingerprint(snapshot_payload),
    )
