from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_context import IndiaUniverseMembership
from .nifty_constituent_source import (
    NiftyConstituentSnapshot,
    select_nifty50_snapshot,
)

NSE_SECTOR_TAXONOMY_VERSION = "nse-sector-taxonomy.v1"
NIFTY_SECTOR_ENRICHMENT_VERSION = "nifty-sector-enrichment.v1"
NSE_INDUSTRY_CLASSIFICATION_URL = (
    "https://www.niftyindices.com/resources/industry-classification"
)
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
MacroCode = Annotated[str, Field(pattern=r"^IN[0-9]{2}$")]
SectorCode = Annotated[str, Field(pattern=r"^IN[0-9]{4}$")]
IndustryCode = Annotated[str, Field(pattern=r"^IN[0-9]{6}$")]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


def _label_key(value: str) -> str:
    return " ".join(value.split()).casefold()


class NseIndustryClassificationEntry(BaseModel):
    """One explicit official hierarchy row: macro sector -> sector -> industry."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    macro_economic_sector_code: MacroCode
    macro_economic_sector: Annotated[str, Field(min_length=1, max_length=160)]
    sector_code: SectorCode
    sector: Annotated[str, Field(min_length=1, max_length=160)]
    industry_code: IndustryCode
    industry: Annotated[str, Field(min_length=1, max_length=160)]

    @model_validator(mode="after")
    def validate_code_hierarchy(self) -> NseIndustryClassificationEntry:
        if not self.sector_code.startswith(self.macro_economic_sector_code):
            raise ValueError("sector_code must descend from macro_economic_sector_code")
        if not self.industry_code.startswith(self.sector_code):
            raise ValueError("industry_code must descend from sector_code")
        return self


class NseConstituentIndustryAlias(BaseModel):
    """Explicitly binds a constituent CSV Industry label to one hierarchy industry code."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    constituent_industry_label: Annotated[str, Field(min_length=1, max_length=160)]
    industry_code: IndustryCode


class NseSectorTaxonomySnapshot(BaseModel):
    """Versioned observed NSE classification evidence; it never authorizes capital."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["nse-sector-taxonomy.v1"] = NSE_SECTOR_TAXONOMY_VERSION
    taxonomy_version: Annotated[str, Field(min_length=1, max_length=80)]
    source_url: Literal[
        "https://www.niftyindices.com/resources/industry-classification"
    ] = NSE_INDUSTRY_CLASSIFICATION_URL
    observed_at: datetime
    entries: list[NseIndustryClassificationEntry] = Field(min_length=1, max_length=256)
    constituent_aliases: list[NseConstituentIndustryAlias] = Field(
        min_length=1,
        max_length=256,
    )
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_SECTOR_TAXONOMY_ONLY"] = "RESEARCH_SECTOR_TAXONOMY_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("observed_at")
    @classmethod
    def observed_at_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> NseSectorTaxonomySnapshot:
        entry_order = [
            (
                entry.macro_economic_sector_code,
                entry.sector_code,
                entry.industry_code,
            )
            for entry in self.entries
        ]
        if entry_order != sorted(entry_order):
            raise ValueError("classification entries must use canonical code ordering")
        industry_codes = [entry.industry_code for entry in self.entries]
        if len(industry_codes) != len(set(industry_codes)):
            raise ValueError("classification industry codes must be unique")

        alias_order = [
            (_label_key(alias.constituent_industry_label), alias.industry_code)
            for alias in self.constituent_aliases
        ]
        if alias_order != sorted(alias_order):
            raise ValueError("constituent aliases must use canonical label ordering")
        alias_keys = [item[0] for item in alias_order]
        if len(alias_keys) != len(set(alias_keys)):
            raise ValueError("constituent industry aliases must be unique")
        known_codes = set(industry_codes)
        unknown_codes = sorted(
            alias.industry_code
            for alias in self.constituent_aliases
            if alias.industry_code not in known_codes
        )
        if unknown_codes:
            raise ValueError(
                "constituent aliases reference unknown industry codes: "
                + ",".join(unknown_codes)
            )
        return self


class SectorEnrichedNiftyConstituent(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    company_name: Annotated[str, Field(min_length=1, max_length=240)]
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    series: Annotated[str, Field(min_length=1, max_length=24)]
    isin: Annotated[str, Field(pattern=r"^[A-Z]{2}[A-Z0-9]{9}[0-9]$")]
    source_industry_label: Annotated[str, Field(min_length=1, max_length=160)]
    macro_economic_sector_code: MacroCode
    macro_economic_sector: Annotated[str, Field(min_length=1, max_length=160)]
    sector_code: SectorCode
    sector: Annotated[str, Field(min_length=1, max_length=160)]
    industry_code: IndustryCode
    industry: Annotated[str, Field(min_length=1, max_length=160)]
    mapping_evidence_fingerprint: Hash64


class SectorEnrichedNifty50Snapshot(BaseModel):
    """Causal NIFTY 50 membership enriched with explicit NSE sector hierarchy evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["nifty-sector-enrichment.v1"] = NIFTY_SECTOR_ENRICHMENT_VERSION
    as_of: datetime
    data_cutoff: datetime
    constituent_observed_at: datetime
    taxonomy_observed_at: datetime
    constituent_snapshot_fingerprint: Hash64
    taxonomy_snapshot_fingerprint: Hash64
    taxonomy_version: Annotated[str, Field(min_length=1, max_length=80)]
    constituents: list[SectorEnrichedNiftyConstituent] = Field(min_length=1, max_length=500)
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_SECTOR_ENRICHMENT_ONLY"] = "RESEARCH_SECTOR_ENRICHMENT_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff", "constituent_observed_at", "taxonomy_observed_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> SectorEnrichedNifty50Snapshot:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        if self.data_cutoff != max(self.constituent_observed_at, self.taxonomy_observed_at):
            raise ValueError("data_cutoff must equal the latest selected source availability")
        identities = [(row.symbol, row.series, row.isin) for row in self.constituents]
        if identities != sorted(identities):
            raise ValueError("sector-enriched constituents must use canonical ordering")
        if len({row.symbol for row in self.constituents}) != len(self.constituents):
            raise ValueError("sector-enriched constituent symbols must be unique")
        return self

    def to_universe_memberships(self) -> list[IndiaUniverseMembership]:
        """Create the causally observed NIFTY 50 subset; this is not the full liquid universe."""

        return [
            IndiaUniverseMembership(
                instrument_id=f"NSE:{row.isin}",
                exchange="NSE",
                symbol=row.symbol,
                series=row.series,
                sector=row.sector,
                index_memberships=["NIFTY_50"],
                effective_from=self.constituent_observed_at,
                available_at=self.data_cutoff,
                source="NSE_INDICES_CLASSIFICATION",
                source_id=row.mapping_evidence_fingerprint,
            )
            for row in self.constituents
        ]


def build_nse_sector_taxonomy_snapshot(
    *,
    taxonomy_version: str,
    observed_at: datetime,
    entries: Sequence[NseIndustryClassificationEntry],
    constituent_aliases: Sequence[NseConstituentIndustryAlias],
) -> NseSectorTaxonomySnapshot:
    """Freeze explicitly curated NSE hierarchy evidence observed at one point in time."""

    _aware(observed_at)
    ordered_entries = sorted(
        entries,
        key=lambda entry: (
            entry.macro_economic_sector_code,
            entry.sector_code,
            entry.industry_code,
        ),
    )
    ordered_aliases = sorted(
        constituent_aliases,
        key=lambda alias: (_label_key(alias.constituent_industry_label), alias.industry_code),
    )
    payload = {
        "contractVersion": NSE_SECTOR_TAXONOMY_VERSION,
        "taxonomyVersion": taxonomy_version,
        "sourceUrl": NSE_INDUSTRY_CLASSIFICATION_URL,
        "observedAt": observed_at.isoformat(),
        "entries": [entry.model_dump(mode="json") for entry in ordered_entries],
        "constituentAliases": [alias.model_dump(mode="json") for alias in ordered_aliases],
        "status": "RESEARCH_SECTOR_TAXONOMY_ONLY",
        "canAuthorizeCapital": False,
    }
    return NseSectorTaxonomySnapshot(
        taxonomy_version=taxonomy_version,
        observed_at=observed_at,
        entries=ordered_entries,
        constituent_aliases=ordered_aliases,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )


def select_nse_sector_taxonomy_snapshot(
    snapshots: Sequence[NseSectorTaxonomySnapshot],
    *,
    as_of: datetime,
) -> NseSectorTaxonomySnapshot:
    """Select only taxonomy evidence that had actually been observed by the decision time."""

    _aware(as_of)
    causal = [snapshot for snapshot in snapshots if snapshot.observed_at <= as_of]
    if not causal:
        raise ValueError("no NSE sector taxonomy snapshot was observed by as_of")
    return max(
        causal,
        key=lambda snapshot: (
            snapshot.observed_at,
            snapshot.taxonomy_version,
            snapshot.snapshot_fingerprint,
        ),
    )


def build_nifty50_sector_enrichment(
    *,
    constituent_snapshots: Sequence[NiftyConstituentSnapshot],
    taxonomy_snapshots: Sequence[NseSectorTaxonomySnapshot],
    as_of: datetime,
) -> SectorEnrichedNifty50Snapshot:
    """Resolve every selected NIFTY 50 raw Industry label through explicit taxonomy evidence."""

    _aware(as_of)
    constituents = select_nifty50_snapshot(list(constituent_snapshots), as_of=as_of)
    taxonomy = select_nse_sector_taxonomy_snapshot(taxonomy_snapshots, as_of=as_of)
    entries_by_code = {entry.industry_code: entry for entry in taxonomy.entries}
    aliases_by_label = {
        _label_key(alias.constituent_industry_label): alias
        for alias in taxonomy.constituent_aliases
    }

    enriched: list[SectorEnrichedNiftyConstituent] = []
    unmapped: list[str] = []
    for row in constituents.constituents:
        alias = aliases_by_label.get(_label_key(row.industry_label))
        if alias is None:
            unmapped.append(row.industry_label)
            continue
        entry = entries_by_code[alias.industry_code]
        mapping_payload = {
            "constituentSnapshotFingerprint": constituents.snapshot_fingerprint,
            "taxonomySnapshotFingerprint": taxonomy.snapshot_fingerprint,
            "symbol": row.symbol,
            "isin": row.isin,
            "sourceIndustryLabel": row.industry_label,
            "industryCode": entry.industry_code,
            "sectorCode": entry.sector_code,
            "macroEconomicSectorCode": entry.macro_economic_sector_code,
        }
        enriched.append(
            SectorEnrichedNiftyConstituent(
                company_name=row.company_name,
                symbol=row.symbol,
                series=row.series,
                isin=row.isin,
                source_industry_label=row.industry_label,
                macro_economic_sector_code=entry.macro_economic_sector_code,
                macro_economic_sector=entry.macro_economic_sector,
                sector_code=entry.sector_code,
                sector=entry.sector,
                industry_code=entry.industry_code,
                industry=entry.industry,
                mapping_evidence_fingerprint=canonical_fingerprint(mapping_payload),
            )
        )

    if unmapped:
        labels = sorted(set(unmapped), key=str.casefold)
        raise ValueError(
            "NIFTY constituent industry labels are not explicitly mapped: "
            + ",".join(labels)
        )

    ordered = sorted(enriched, key=lambda row: (row.symbol, row.series, row.isin))
    data_cutoff = max(constituents.observed_at, taxonomy.observed_at)
    payload = {
        "contractVersion": NIFTY_SECTOR_ENRICHMENT_VERSION,
        "asOf": as_of.isoformat(),
        "dataCutoff": data_cutoff.isoformat(),
        "constituentObservedAt": constituents.observed_at.isoformat(),
        "taxonomyObservedAt": taxonomy.observed_at.isoformat(),
        "constituentSnapshotFingerprint": constituents.snapshot_fingerprint,
        "taxonomySnapshotFingerprint": taxonomy.snapshot_fingerprint,
        "taxonomyVersion": taxonomy.taxonomy_version,
        "constituents": [row.model_dump(mode="json") for row in ordered],
        "status": "RESEARCH_SECTOR_ENRICHMENT_ONLY",
        "canAuthorizeCapital": False,
    }
    return SectorEnrichedNifty50Snapshot(
        as_of=as_of,
        data_cutoff=data_cutoff,
        constituent_observed_at=constituents.observed_at,
        taxonomy_observed_at=taxonomy.observed_at,
        constituent_snapshot_fingerprint=constituents.snapshot_fingerprint,
        taxonomy_snapshot_fingerprint=taxonomy.snapshot_fingerprint,
        taxonomy_version=taxonomy.taxonomy_version,
        constituents=ordered,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
