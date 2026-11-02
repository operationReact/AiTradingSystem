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
from aitrading_intelligence.nifty_constituent_source import parse_nifty50_constituents
from aitrading_intelligence.nse_sector_taxonomy import (
    NseConstituentIndustryAlias,
    NseIndustryClassificationEntry,
    build_nifty50_sector_enrichment,
    build_nse_sector_taxonomy_snapshot,
)

DECISION_AT = datetime(2026, 11, 2, 10, 30, tzinfo=UTC)


def _constituent_snapshot(*, observed_at: datetime, industry_label: str = "Software Services"):
    source = (
        "Company Name,Industry,Symbol,Series,ISIN Code\n"
        f"Example Technology Limited,{industry_label},INFY,EQ,INE009A01021\n"
    ).encode()
    return parse_nifty50_constituents(source, observed_at=observed_at)


def _taxonomy(
    *,
    observed_at: datetime,
    taxonomy_version: str = "fixture-2026-10",
    macro_code: str = "IN07",
    macro_name: str = "Information Technology",
    sector_code: str = "IN0701",
    sector_name: str = "Information Technology",
    industry_code: str = "IN070101",
    industry_name: str = "Software & Services",
):
    return build_nse_sector_taxonomy_snapshot(
        taxonomy_version=taxonomy_version,
        observed_at=observed_at,
        entries=[
            NseIndustryClassificationEntry(
                macro_economic_sector_code=macro_code,
                macro_economic_sector=macro_name,
                sector_code=sector_code,
                sector=sector_name,
                industry_code=industry_code,
                industry=industry_name,
            )
        ],
        constituent_aliases=[
            NseConstituentIndustryAlias(
                constituent_industry_label="Software Services",
                industry_code=industry_code,
            )
        ],
    )


def test_explicit_taxonomy_enriches_raw_constituent_label_without_promoting_it_directly() -> None:
    constituent = _constituent_snapshot(observed_at=DECISION_AT - timedelta(hours=2))
    taxonomy = _taxonomy(observed_at=DECISION_AT - timedelta(hours=1))

    enriched = build_nifty50_sector_enrichment(
        constituent_snapshots=[constituent],
        taxonomy_snapshots=[taxonomy],
        as_of=DECISION_AT,
    )

    assert enriched.contract_version == "nifty-sector-enrichment.v1"
    assert enriched.status == "RESEARCH_SECTOR_ENRICHMENT_ONLY"
    assert enriched.can_authorize_capital is False
    assert enriched.constituents[0].source_industry_label == "Software Services"
    assert enriched.constituents[0].industry == "Software & Services"
    assert enriched.constituents[0].sector == "Information Technology"
    assert enriched.constituents[0].macro_economic_sector == "Information Technology"
    assert enriched.data_cutoff == DECISION_AT - timedelta(hours=1)

    memberships = enriched.to_universe_memberships()
    assert memberships[0].instrument_id == "NSE:INE009A01021"
    assert memberships[0].sector == "Information Technology"
    assert memberships[0].index_memberships == ["NIFTY_50"]
    assert memberships[0].available_at == enriched.data_cutoff


def test_unmapped_constituent_industry_fails_closed() -> None:
    constituent = _constituent_snapshot(
        observed_at=DECISION_AT - timedelta(hours=2),
        industry_label="Unmapped New Classification",
    )
    taxonomy = _taxonomy(observed_at=DECISION_AT - timedelta(hours=1))

    with pytest.raises(ValueError, match="not explicitly mapped"):
        build_nifty50_sector_enrichment(
            constituent_snapshots=[constituent],
            taxonomy_snapshots=[taxonomy],
            as_of=DECISION_AT,
        )


def test_alias_to_unknown_official_industry_code_is_rejected() -> None:
    with pytest.raises(ValueError, match="unknown industry codes"):
        build_nse_sector_taxonomy_snapshot(
            taxonomy_version="bad-fixture",
            observed_at=DECISION_AT - timedelta(hours=1),
            entries=[
                NseIndustryClassificationEntry(
                    macro_economic_sector_code="IN07",
                    macro_economic_sector="Information Technology",
                    sector_code="IN0701",
                    sector="Information Technology",
                    industry_code="IN070101",
                    industry="Software & Services",
                )
            ],
            constituent_aliases=[
                NseConstituentIndustryAlias(
                    constituent_industry_label="Software Services",
                    industry_code="IN070199",
                )
            ],
        )


def _feature_row_with_taxonomies(taxonomies):
    constituent = _constituent_snapshot(observed_at=DECISION_AT - timedelta(hours=2))
    enriched = build_nifty50_sector_enrichment(
        constituent_snapshots=[constituent],
        taxonomy_snapshots=taxonomies,
        as_of=DECISION_AT,
    )
    universe = build_india_universe_snapshot(
        universe_id="NIFTY50_SECTOR_TEST",
        as_of=DECISION_AT,
        memberships=enriched.to_universe_memberships(),
        source_versions={
            "nifty50-constituents": enriched.constituent_snapshot_fingerprint,
            "nse-sector-taxonomy": enriched.taxonomy_snapshot_fingerprint,
        },
    )
    context_observation = IndiaContextObservation(
        context_key="benchmark.NIFTY50.close",
        event_at=DECISION_AT - timedelta(minutes=6),
        available_at=DECISION_AT - timedelta(minutes=5),
        value=25_000.0,
        source="fixture",
        source_id="nifty50-close",
    )
    context = build_india_market_context_snapshot(
        as_of=DECISION_AT,
        observations=[context_observation],
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


def test_future_taxonomy_revision_cannot_mutate_historical_feature_row() -> None:
    baseline_taxonomy = _taxonomy(observed_at=DECISION_AT - timedelta(hours=1))
    future_taxonomy = _taxonomy(
        observed_at=DECISION_AT + timedelta(minutes=1),
        taxonomy_version="future-corruption",
        macro_code="IN08",
        macro_name="Corrupted Future Macro",
        sector_code="IN0801",
        sector_name="Corrupted Future Sector",
        industry_code="IN080101",
        industry_name="Corrupted Future Industry",
    )

    baseline_row = _feature_row_with_taxonomies([baseline_taxonomy])
    mutated_row = _feature_row_with_taxonomies([baseline_taxonomy, future_taxonomy])

    report = verify_future_mutation_invariant(
        decision_at=DECISION_AT,
        baseline_rows=[baseline_row],
        future_mutated_rows=[mutated_row],
        mutated_source_families=["SECTOR_TAXONOMY"],
    )

    assert mutated_row == baseline_row
    assert report.invariant_passed is True
    assert report.mutated_source_families == ["SECTOR_TAXONOMY"]
    assert report.baseline_dataset_fingerprint == feature_dataset_v2_fingerprint([baseline_row])
    assert report.mutated_dataset_fingerprint == report.baseline_dataset_fingerprint
