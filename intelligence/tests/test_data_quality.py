from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.data_quality import (
    SourceHealthRule,
    SourceHealthSample,
    build_source_health_report,
)

AS_OF = datetime(2026, 11, 2, 9, 15, tzinfo=UTC)


def _sample(
    family: str,
    *,
    source: str,
    source_id: str,
    available_at: datetime,
    event_at: datetime | None = None,
    quality_flags: list[str] | None = None,
) -> SourceHealthSample:
    return SourceHealthSample(
        family=family,
        source=source,
        source_id=source_id,
        event_at=event_at or available_at - timedelta(seconds=1),
        available_at=available_at,
        quality_flags=quality_flags or [],
    )


def test_report_is_deterministic_healthy_and_research_only() -> None:
    rules = [
        SourceHealthRule(
            family="MARKET_CONTEXT",
            max_age_seconds=60,
            minimum_records=2,
        ),
        SourceHealthRule(
            family="UNIVERSE",
            max_age_seconds=86_400,
        ),
    ]
    samples = [
        _sample(
            "UNIVERSE",
            source="nse-catalogue",
            source_id="universe-1",
            available_at=AS_OF - timedelta(hours=1),
        ),
        _sample(
            "MARKET_CONTEXT",
            source="nse-index",
            source_id="nifty-1",
            available_at=AS_OF - timedelta(seconds=20),
        ),
        _sample(
            "MARKET_CONTEXT",
            source="india-vix",
            source_id="vix-1",
            available_at=AS_OF - timedelta(seconds=5),
        ),
    ]

    first = build_source_health_report(
        as_of=AS_OF,
        rules=rules,
        samples=samples,
    )
    second = build_source_health_report(
        as_of=AS_OF,
        rules=list(reversed(rules)),
        samples=list(reversed(samples)),
    )

    assert first == second
    assert first.report_fingerprint == second.report_fingerprint
    assert [family.family for family in first.families] == [
        "MARKET_CONTEXT",
        "UNIVERSE",
    ]
    assert first.health == "HEALTHY"
    assert first.status == "RESEARCH_DATA_QUALITY_ONLY"
    assert first.can_authorize_capital is False
    assert first.data_cutoff == AS_OF - timedelta(seconds=5)


def test_missing_required_family_blocks_report() -> None:
    report = build_source_health_report(
        as_of=AS_OF,
        rules=[
            SourceHealthRule(family="MARKET_CONTEXT", max_age_seconds=60),
            SourceHealthRule(family="UNIVERSE", max_age_seconds=86_400),
        ],
        samples=[
            _sample(
                "UNIVERSE",
                source="nse-catalogue",
                source_id="universe-1",
                available_at=AS_OF - timedelta(hours=1),
            )
        ],
    )

    assert report.health == "BLOCKED"
    assert report.missing_families == ["MARKET_CONTEXT"]
    market = next(item for item in report.families if item.family == "MARKET_CONTEXT")
    assert market.health == "MISSING"
    assert market.reasons == ["NO_CAUSAL_SAMPLES"]


def test_stale_required_family_blocks_report() -> None:
    report = build_source_health_report(
        as_of=AS_OF,
        rules=[SourceHealthRule(family="MARKET_CONTEXT", max_age_seconds=30)],
        samples=[
            _sample(
                "MARKET_CONTEXT",
                source="nse-index",
                source_id="nifty-stale",
                available_at=AS_OF - timedelta(seconds=31),
            )
        ],
    )

    assert report.health == "BLOCKED"
    assert report.stale_families == ["MARKET_CONTEXT"]
    assert report.families[0].health == "STALE"
    assert report.families[0].age_seconds == 31.0


def test_quality_flags_and_low_coverage_degrade_without_fabricating_health() -> None:
    report = build_source_health_report(
        as_of=AS_OF,
        rules=[
            SourceHealthRule(
                family="MARKET_CONTEXT",
                max_age_seconds=60,
                minimum_records=3,
            )
        ],
        samples=[
            _sample(
                "MARKET_CONTEXT",
                source="nse-index",
                source_id="nifty-1",
                available_at=AS_OF - timedelta(seconds=10),
                quality_flags=["PARTIAL_COVERAGE"],
            ),
            _sample(
                "MARKET_CONTEXT",
                source="india-vix",
                source_id="vix-1",
                available_at=AS_OF - timedelta(seconds=5),
            ),
        ],
    )

    family = report.families[0]
    assert report.health == "DEGRADED"
    assert report.degraded_families == ["MARKET_CONTEXT"]
    assert family.health == "DEGRADED"
    assert family.quality_flags == ["PARTIAL_COVERAGE"]
    assert family.reasons == [
        "INSUFFICIENT_CAUSAL_RECORDS",
        "QUALITY_FLAGS_PRESENT",
    ]


def test_future_mutation_cannot_change_historical_health_report() -> None:
    rules = [SourceHealthRule(family="MARKET_CONTEXT", max_age_seconds=60)]
    historical = [
        _sample(
            "MARKET_CONTEXT",
            source="nse-index",
            source_id="nifty-current",
            available_at=AS_OF - timedelta(seconds=5),
        )
    ]
    before = build_source_health_report(
        as_of=AS_OF,
        rules=rules,
        samples=historical,
    )

    future_corruption = _sample(
        "MARKET_CONTEXT",
        source="nse-index",
        source_id="future-corruption",
        available_at=AS_OF + timedelta(seconds=1),
        event_at=AS_OF,
        quality_flags=["CORRUPTED"],
    )
    after = build_source_health_report(
        as_of=AS_OF,
        rules=rules,
        samples=[*historical, future_corruption],
    )

    assert after == before
    assert after.report_fingerprint == before.report_fingerprint


def test_unenrolled_source_family_fails_closed() -> None:
    with pytest.raises(ValueError, match="not enrolled"):
        build_source_health_report(
            as_of=AS_OF,
            rules=[SourceHealthRule(family="UNIVERSE", max_age_seconds=86_400)],
            samples=[
                _sample(
                    "NEW_PROVIDER_FAMILY",
                    source="future-provider",
                    source_id="new-1",
                    available_at=AS_OF - timedelta(seconds=1),
                )
            ],
        )


def test_sample_rejects_noncausal_timestamp_contract() -> None:
    with pytest.raises(ValueError, match="available_at cannot precede event_at"):
        _sample(
            "MARKET_CONTEXT",
            source="nse-index",
            source_id="bad-time",
            available_at=AS_OF - timedelta(seconds=2),
            event_at=AS_OF - timedelta(seconds=1),
        )


def test_policy_requires_unique_families() -> None:
    rule = SourceHealthRule(family="UNIVERSE", max_age_seconds=86_400)
    with pytest.raises(ValueError, match="unique families"):
        build_source_health_report(
            as_of=AS_OF,
            rules=[rule, rule],
            samples=[],
        )
