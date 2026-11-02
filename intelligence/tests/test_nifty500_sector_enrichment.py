from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.datasets import MarketBar
from aitrading_intelligence.feature_dataset_v2 import feature_dataset_v2_fingerprint
from aitrading_intelligence.feature_v2 import build_feature_row_v2
from aitrading_intelligence.features import build_feature_row
from aitrading_intelligence.india_context import (
    IndiaContextObservation,
    build_india_market_context_snapshot,
    build_india_universe_snapshot,
)
from aitrading_intelligence.leakage_guard_v3 import verify_future_mutation_invariant
from aitrading_intelligence.nifty_constituent_source import parse_nifty500_constituents
from aitrading_intelligence.nse_sector_taxonomy import (
    NseConstituentIndustryAlias,
    NseIndustryClassificationEntry,
    build_nifty500_sector_enrichment,
    build_nse_sector_taxonomy_snapshot,
)

DECISION_AT = datetime(2026, 11, 2, 10, 30, tzinfo=UTC)


def _constituent_snapshot(
    *,
    observed_at: datetime,
    industry_label: str = "Software Services",
    include_future_member: bool = False,
):
    rows = [
        "Company Name,Industry,Symbol,Series,ISIN Code",
        f"Example Technology Limited,{industry_label},INFY,EQ,INE009A01021",
    ]
    if include_future_member:
        rows.append("Example Bank Limited,Banks,EXBANK,EQ,INE001A01010")
    return parse_nifty500_constituents(
        ("\n".join(rows) + "\n").encode(),
        observed_at=observed_at,
    )


def _taxonomy(
    *,
    observed_at: datetime,
    taxonomy_version: str = "fixture-2026-11",
    sector_name: str = "Information Technology",
):
    return build_nse_sector_taxonomy_snapshot(
        taxonomy_version=taxonomy_version,
        observed_at=observed_at,
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
                sector=sector_name,
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


def test_nifty500_sector_enrichment_emits_causal_canonical_universe_membership() -> None:
    constituent = _constituent_snapshot(observed_at=DECISION_AT - timedelta(hours=2))
    taxonomy = _taxonomy(observed_at=DECISION_AT - timedelta(hours=1))

    enriched = build_nifty500_sector_enrichment(
        constituent_snapshots=[constituent],
        taxonomy_snapshots=[taxonomy],
        as_of=DECISION_AT,
    )

    assert enriched.contract_version == "nifty500-sector-enrichment.v1"
    assert enriched.status == "RESEARCH_SECTOR_ENRICHMENT_ONLY"
    assert enriched.can_authorize_capital is False
    assert enriched.constituents[0].symbol == "INFY"
    assert enriched.constituents[0].source_industry_label == "Software Services"
    assert enriched.constituents[0].industry == "Software & Services"
    assert enriched.constituents[0].sector == "Information Technology"
    assert enriched.data_cutoff == DECISION_AT - timedelta(hours=1)

    memberships = enriched.to_universe_memberships()
    assert memberships[0].instrument_id == "NSE:INE009A01021"
    assert memberships[0].sector == "Information Technology"
    assert memberships[0].index_memberships == ["NIFTY_500"]
    assert memberships[0].available_at == enriched.data_cutoff


def test_nifty500_sector_enrichment_fails_closed_on_unmapped_industry() -> None:
    constituent = _constituent_snapshot(
        observed_at=DECISION_AT - timedelta(hours=2),
        industry_label="Unmapped New Classification",
    )
    taxonomy = _taxonomy(observed_at=DECISION_AT - timedelta(hours=1))

    with pytest.raises(ValueError, match="not explicitly mapped"):
        build_nifty500_sector_enrichment(
            constituent_snapshots=[constituent],
            taxonomy_snapshots=[taxonomy],
            as_of=DECISION_AT,
        )


def _feature_row_with_sources(constituents, taxonomies):
    enriched = build_nifty500_sector_enrichment(
        constituent_snapshots=constituents,
        taxonomy_snapshots=taxonomies,
        as_of=DECISION_AT,
    )
    universe = build_india_universe_snapshot(
        universe_id="NIFTY500_SECTOR_TEST",
        as_of=DECISION_AT,
        memberships=enriched.to_universe_memberships(),
        source_versions={
            "nifty500-constituents": enriched.constituent_snapshot_fingerprint,
            "nse-sector-taxonomy": enriched.taxonomy_snapshot_fingerprint,
        },
    )
    context = build_india_market_context_snapshot(
        as_of=DECISION_AT,
        observations=[
            IndiaContextObservation(
                context_key="benchmark.NIFTY50.close",
                event_at=DECISION_AT - timedelta(minutes=6),
                available_at=DECISION_AT - timedelta(minutes=5),
                value=25_000.0,
                source="fixture",
                source_id="nifty50-close",
            )
        ],
        source_versions={"benchmark": "fixture.v1"},
    )
    bar = MarketBar(
        symbol="INFY",
        interval="5m",
        observed_at=DECISION_AT - timedelta(minutes=1),
        event_at=DECISION_AT - timedelta(minutes=1, seconds=2),
        ingested_at=DECISION_AT - timedelta(minutes=1),
        open=1495.0,
        high=1505.0,
        low=1490.0,
        close=1500.0,
        volume=1_000_000.0,
        source="fixture",
    )
    base = build_feature_row(symbol="INFY", as_of=DECISION_AT, bars=[bar])
    return build_feature_row_v2(
        base_row=base,
        universe=universe,
        context=context,
        expected_context_keys=["benchmark.NIFTY50.close"],
        required_context_keys=["benchmark.NIFTY50.close"],
    )


def test_future_nifty500_and_taxonomy_revisions_cannot_mutate_historical_features() -> None:
    baseline_constituent = _constituent_snapshot(
        observed_at=DECISION_AT - timedelta(hours=2)
    )
    future_constituent = _constituent_snapshot(
        observed_at=DECISION_AT + timedelta(minutes=1),
        include_future_member=True,
    )
    baseline_taxonomy = _taxonomy(observed_at=DECISION_AT - timedelta(hours=1))
    future_taxonomy = _taxonomy(
        observed_at=DECISION_AT + timedelta(minutes=2),
        taxonomy_version="future-corruption",
        sector_name="Corrupted Future Sector",
    )

    baseline_row = _feature_row_with_sources([baseline_constituent], [baseline_taxonomy])
    mutated_row = _feature_row_with_sources(
        [baseline_constituent, future_constituent],
        [baseline_taxonomy, future_taxonomy],
    )

    report = verify_future_mutation_invariant(
        decision_at=DECISION_AT,
        baseline_rows=[baseline_row],
        future_mutated_rows=[mutated_row],
        mutated_source_families=["NIFTY500_CONSTITUENTS", "SECTOR_TAXONOMY"],
    )

    assert mutated_row == baseline_row
    assert report.invariant_passed is True
    assert report.mutated_source_families == [
        "NIFTY500_CONSTITUENTS",
        "SECTOR_TAXONOMY",
    ]
    assert report.baseline_dataset_fingerprint == feature_dataset_v2_fingerprint([baseline_row])
    assert report.mutated_dataset_fingerprint == report.baseline_dataset_fingerprint
