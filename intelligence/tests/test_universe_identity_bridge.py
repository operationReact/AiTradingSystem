from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from aitrading_intelligence.catalogue_source import (
    CatalogueInstrument,
    InstrumentCatalogueResearchExport,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.liquidity_universe import (
    LiquidityCandidateEvidence,
    LiquidityQualifiedCatalogue,
)
from aitrading_intelligence.nifty_constituent_source import parse_nifty500_constituents
from aitrading_intelligence.nse_sector_taxonomy import (
    NseConstituentIndustryAlias,
    NseIndustryClassificationEntry,
    build_nifty500_sector_enrichment,
    build_nse_sector_taxonomy_snapshot,
)
from aitrading_intelligence.universe_identity_bridge import (
    MAPPING_RULE_VERSION,
    build_nifty500_liquidity_identity_bridge,
)

AS_OF = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)
CATALOGUE_VERSION = UUID("12345678-1234-1234-1234-123456789abc")
CATALOGUE_FP = "a" * 64


def _instrument(
    instrument_id: int,
    exchange: str,
    trading_symbol: str,
    token: str,
) -> CatalogueInstrument:
    return CatalogueInstrument(
        instrument_id=instrument_id,
        exchange_segment=f"{exchange}_EQ",
        exchange=exchange,
        symbol_token=token,
        trading_symbol=trading_symbol,
        name=trading_symbol.removesuffix("-EQ"),
        instrument_type="EQ",
        expiry=None,
        strike=None,
        lot_size=1,
        tick_size=0.05,
    )


def _catalogue(
    *,
    infy_symbol: str = "INFY-EQ",
    duplicate_infy: bool = False,
) -> InstrumentCatalogueResearchExport:
    instruments = [
        _instrument(1, "BSE", "OUTSIDE-EQ", "500001"),
        _instrument(2, "NSE", "EXBANK-EQ", "1002"),
        _instrument(3, "NSE", infy_symbol, "1003"),
        _instrument(4, "NSE", "OTHER-EQ", "1004"),
    ]
    if duplicate_infy:
        instruments.append(_instrument(5, "NSE", "INFY-EQ", "1005"))
    instruments = sorted(
        instruments,
        key=lambda item: (
            item.exchange,
            item.trading_symbol,
            item.symbol_token,
            item.instrument_id,
        ),
    )
    cutoff = AS_OF - timedelta(days=1)
    return InstrumentCatalogueResearchExport(
        contract_version="instrument-catalogue.v1",
        generated_at=AS_OF,
        as_of=AS_OF,
        data_cutoff=cutoff,
        catalogue_version=CATALOGUE_VERSION,
        catalogue_available_at=cutoff,
        source_url="https://example.invalid/OpenAPIScripMaster.json",
        source_accepted_count=len(instruments),
        source_rejected_count=0,
        exchanges=["BSE", "NSE"],
        instrument_type="EQ",
        row_count=len(instruments),
        catalogue_fingerprint=CATALOGUE_FP,
        status="RESEARCH_CATALOGUE_ONLY",
        can_authorize_capital=False,
        instruments=instruments,
    )


def _evidence(
    instrument: CatalogueInstrument,
    *,
    accepted: bool,
    trading_symbol: str | None = None,
) -> LiquidityCandidateEvidence:
    latest = AS_OF - timedelta(minutes=10)
    reasons = [] if accepted else ["ADV_NOTIONAL_BELOW_MINIMUM"]
    payload = {
        "instrumentId": instrument.instrument_id,
        "exchange": instrument.exchange,
        "tradingSymbol": trading_symbol or instrument.trading_symbol,
        "symbolToken": instrument.symbol_token,
        "accepted": accepted,
        "causalBarCount": 20,
        "latestAvailableAt": latest.isoformat(),
        "advNotional": 20_000_000.0 if accepted else 100_000.0,
        "zeroVolumeFraction": 0.0,
        "rejectionReasons": reasons,
        "marketEvidenceFingerprint": canonical_fingerprint(
            {"instrument": instrument.instrument_id, "market": "fixture"}
        ),
    }
    return LiquidityCandidateEvidence(
        instrument_id=instrument.instrument_id,
        exchange=instrument.exchange,
        trading_symbol=trading_symbol or instrument.trading_symbol,
        symbol_token=instrument.symbol_token,
        accepted=accepted,
        causal_bar_count=20,
        latest_available_at=latest,
        adv_notional=20_000_000.0 if accepted else 100_000.0,
        zero_volume_fraction=0.0,
        rejection_reasons=reasons,
        market_evidence_fingerprint=payload["marketEvidenceFingerprint"],
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def _liquidity(
    catalogue: InstrumentCatalogueResearchExport,
    *,
    tamper_infy_symbol: bool = False,
    as_of: datetime = AS_OF,
) -> LiquidityQualifiedCatalogue:
    evidence = []
    for instrument in catalogue.instruments:
        accepted = instrument.instrument_id in {1, 3, 4, 5}
        tampered = "INFY" if tamper_infy_symbol and instrument.instrument_id == 3 else None
        evidence.append(
            _evidence(
                instrument,
                accepted=accepted,
                trading_symbol=tampered,
            )
        )
    evidence = sorted(
        evidence,
        key=lambda item: (
            item.exchange,
            item.trading_symbol,
            item.symbol_token,
            item.instrument_id,
        ),
    )
    accepted_ids = [item.instrument_id for item in evidence if item.accepted]
    data_cutoff = AS_OF - timedelta(minutes=10)
    return LiquidityQualifiedCatalogue(
        as_of=as_of,
        data_cutoff=data_cutoff,
        source_catalogue_version=str(catalogue.catalogue_version),
        source_catalogue_fingerprint=catalogue.catalogue_fingerprint,
        config_fingerprint="b" * 64,
        evidence=evidence,
        accepted_instrument_ids=accepted_ids,
        accepted_count=len(accepted_ids),
        rejected_count=len(evidence) - len(accepted_ids),
        snapshot_fingerprint="c" * 64,
        status="RESEARCH_LIQUIDITY_QUALIFICATION_ONLY",
        can_authorize_capital=False,
    )


def _sector_enrichment(*, as_of: datetime = AS_OF):
    constituent = parse_nifty500_constituents(
        (
            b"Company Name,Industry,Symbol,Series,ISIN Code\n"
            b"Example Bank Limited,Banks,EXBANK,EQ,INE001A01010\n"
            b"Infosys Limited,Software Services,INFY,EQ,INE009A01021\n"
        ),
        observed_at=AS_OF - timedelta(hours=2),
    )
    taxonomy = build_nse_sector_taxonomy_snapshot(
        taxonomy_version="fixture-2026-11",
        observed_at=AS_OF - timedelta(hours=1),
        entries=[
            NseIndustryClassificationEntry(
                macro_economic_sector_code="IN01",
                macro_economic_sector="Financial Services",
                sector_code="IN0101",
                sector="Financial Services",
                industry_code="IN010101",
                industry="Banks",
            ),
            NseIndustryClassificationEntry(
                macro_economic_sector_code="IN07",
                macro_economic_sector="Information Technology",
                sector_code="IN0701",
                sector="Information Technology",
                industry_code="IN070101",
                industry="Software & Services",
            ),
        ],
        constituent_aliases=[
            NseConstituentIndustryAlias(
                constituent_industry_label="Banks",
                industry_code="IN010101",
            ),
            NseConstituentIndustryAlias(
                constituent_industry_label="Software Services",
                industry_code="IN070101",
            ),
        ],
    )
    return build_nifty500_sector_enrichment(
        constituent_snapshots=[constituent],
        taxonomy_snapshots=[taxonomy],
        as_of=as_of,
    )


def test_exact_nse_symbol_series_bridge_preserves_isin_and_broker_identity() -> None:
    catalogue = _catalogue()
    bridge = build_nifty500_liquidity_identity_bridge(
        catalogue=catalogue,
        liquidity=_liquidity(catalogue),
        sector_enrichment=_sector_enrichment(),
        as_of=AS_OF,
    )

    assert bridge.contract_version == "nifty500-liquidity-identity-bridge.v1"
    assert bridge.mapping_rule == MAPPING_RULE_VERSION
    assert bridge.binding_count == 1
    assert bridge.status == "RESEARCH_IDENTITY_BRIDGE_ONLY"
    assert bridge.can_authorize_capital is False

    binding = bridge.bindings[0]
    assert binding.canonical_instrument_id == "NSE:INE009A01021"
    assert binding.isin == "INE009A01021"
    assert binding.symbol == "INFY"
    assert binding.series == "EQ"
    assert binding.expected_trading_symbol == "INFY-EQ"
    assert binding.catalogue_instrument_id == 3
    assert binding.broker_trading_symbol == "INFY-EQ"
    assert binding.broker_symbol_token == "1003"
    assert binding.sector == "Information Technology"
    assert binding.membership_effective_from == AS_OF - timedelta(hours=2)
    assert bridge.snapshot_fingerprint


def test_does_not_fuzzy_match_symbol_without_exact_series_suffix() -> None:
    catalogue = _catalogue(infy_symbol="INFY")

    with pytest.raises(ValueError, match="no exact NSE catalogue trading symbol: INFY-EQ"):
        build_nifty500_liquidity_identity_bridge(
            catalogue=catalogue,
            liquidity=_liquidity(catalogue),
            sector_enrichment=_sector_enrichment(),
            as_of=AS_OF,
        )


def test_duplicate_exact_broker_trading_symbol_fails_closed_as_ambiguous() -> None:
    catalogue = _catalogue(duplicate_infy=True)

    with pytest.raises(ValueError, match="ambiguous exact NSE catalogue trading symbol: INFY-EQ"):
        build_nifty500_liquidity_identity_bridge(
            catalogue=catalogue,
            liquidity=_liquidity(catalogue),
            sector_enrichment=_sector_enrichment(),
            as_of=AS_OF,
        )


def test_tampered_liquidity_identity_cannot_cross_catalogue_boundary() -> None:
    catalogue = _catalogue()

    with pytest.raises(
        ValueError,
        match="liquidity evidence trading symbol does not match catalogue",
    ):
        build_nifty500_liquidity_identity_bridge(
            catalogue=catalogue,
            liquidity=_liquidity(catalogue, tamper_infy_symbol=True),
            sector_enrichment=_sector_enrichment(),
            as_of=AS_OF,
        )


def test_point_in_time_inputs_must_share_exact_decision_time() -> None:
    catalogue = _catalogue()
    future_as_of = AS_OF + timedelta(minutes=1)

    with pytest.raises(ValueError, match="liquidity as_of must exactly match bridge as_of"):
        build_nifty500_liquidity_identity_bridge(
            catalogue=catalogue,
            liquidity=_liquidity(catalogue, as_of=future_as_of),
            sector_enrichment=_sector_enrichment(),
            as_of=AS_OF,
        )


def test_rejected_nifty_member_and_accepted_non_member_do_not_enter_bridge() -> None:
    catalogue = _catalogue()
    bridge = build_nifty500_liquidity_identity_bridge(
        catalogue=catalogue,
        liquidity=_liquidity(catalogue),
        sector_enrichment=_sector_enrichment(),
        as_of=AS_OF,
    )

    assert [item.symbol for item in bridge.bindings] == ["INFY"]
    assert all(item.broker_trading_symbol != "OTHER-EQ" for item in bridge.bindings)
    assert all(item.symbol != "EXBANK" for item in bridge.bindings)
