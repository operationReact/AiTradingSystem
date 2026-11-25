from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.regime import REGIME_LABELS
from aitrading_intelligence.stability_report import (
    StabilityObservationV1,
    StabilityReportConfigV1,
    build_stability_observation,
    build_stability_report,
)

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)
SECTORS = ("Financial Services", "Information Technology", "Energy")
LIQUIDITY_PERCENTILES = (0.20, 0.50, 0.85)


def _observation(index: int, *, source_return: int | None = None):
    regime = REGIME_LABELS[index % len(REGIME_LABELS)]
    return build_stability_observation(
        decision_at=START + timedelta(minutes=5 * index),
        symbol=f"SYM{index}",
        net_return=0.004 + (0.001 * ((index % 7) - 3)),
        regime=regime,
        liquidity_percentile=LIQUIDITY_PERCENTILES[index % 3],
        sector=SECTORS[index % 3],
        source_return_fingerprint=f"{source_return or index + 1000:064x}",
        source_regime_snapshot_fingerprint=f"{index + 2000:064x}",
        source_sector_liquidity_score_fingerprint=f"{index + 3000:064x}",
        source_liquidity_evidence_fingerprint=f"{index + 4000:064x}",
    )


def test_stability_report_is_deterministic_and_input_order_invariant() -> None:
    observations = [_observation(index) for index in range(48)]
    config = StabilityReportConfigV1(
        minimum_bucket_observations=3,
        minimum_evaluated_buckets_per_dimension=2,
    )

    first = build_stability_report(observations, config=config)
    second = build_stability_report(list(reversed(observations)), config=config)

    assert first == second
    assert first.state == "EVALUATED"
    assert first.reason_codes == []
    assert first.observation_count == 48
    assert first.regime.state == "EVALUATED"
    assert first.regime.evaluated_bucket_count == 4
    assert first.liquidity.state == "EVALUATED"
    assert first.liquidity.evaluated_bucket_count == 3
    assert first.sector.state == "EVALUATED"
    assert first.sector.evaluated_bucket_count == 3
    assert first.status == "RESEARCH_STABILITY_REPORT_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_sparse_dimensions_are_explicitly_inconclusive() -> None:
    observations = [
        build_stability_observation(
            decision_at=START + timedelta(minutes=5 * index),
            symbol=f"ONE{index}",
            net_return=0.003 + (index * 0.0001),
            regime="TREND_UP",
            liquidity_percentile=0.90,
            sector="Energy",
            source_return_fingerprint=f"{index + 5000:064x}",
            source_regime_snapshot_fingerprint=f"{index + 5100:064x}",
            source_sector_liquidity_score_fingerprint=f"{index + 5200:064x}",
            source_liquidity_evidence_fingerprint=f"{index + 5300:064x}",
        )
        for index in range(4)
    ]
    report = build_stability_report(
        observations,
        config=StabilityReportConfigV1(
            minimum_bucket_observations=3,
            minimum_evaluated_buckets_per_dimension=2,
        ),
    )

    assert report.state == "INCONCLUSIVE"
    assert report.reason_codes == [
        "LIQUIDITY_DIMENSION_INCONCLUSIVE",
        "REGIME_DIMENSION_INCONCLUSIVE",
        "SECTOR_DIMENSION_INCONCLUSIVE",
    ]
    assert report.regime.evaluated_bucket_count == 1
    assert report.liquidity.evaluated_bucket_count == 1
    assert report.sector.evaluated_bucket_count == 1
    assert report.regime.bucket_mean_return_spread is None
    assert report.liquidity.bucket_mean_return_spread is None
    assert report.sector.bucket_mean_return_spread is None

    absent_regime = next(
        bucket for bucket in report.regime.buckets if bucket.bucket == "TREND_DOWN"
    )
    assert absent_regime.state == "INCONCLUSIVE"
    assert absent_regime.observation_count == 0
    assert absent_regime.reason_codes == ["INSUFFICIENT_BUCKET_OBSERVATIONS"]
    assert absent_regime.mean_net_return is None


def test_bucket_metrics_are_hidden_until_minimum_sample_is_met() -> None:
    observations = [_observation(index) for index in range(6)]
    report = build_stability_report(
        observations,
        config=StabilityReportConfigV1(
            minimum_bucket_observations=4,
            minimum_evaluated_buckets_per_dimension=2,
        ),
    )

    assert report.state == "INCONCLUSIVE"
    assert all(bucket.state == "INCONCLUSIVE" for bucket in report.liquidity.buckets)
    assert all(bucket.mean_net_return is None for bucket in report.liquidity.buckets)
    assert report.liquidity.evaluated_bucket_count == 0


def test_liquidity_boundaries_partition_every_observation_once() -> None:
    percentiles = (1.0 / 3.0, 0.34, 2.0 / 3.0, 0.67, 0.99, 1.0)
    observations = [
        build_stability_observation(
            decision_at=START + timedelta(minutes=5 * index),
            symbol=f"BND{index}",
            net_return=0.002 + (index * 0.0001),
            regime=REGIME_LABELS[index % 4],
            liquidity_percentile=percentile,
            sector=SECTORS[index % 3],
            source_return_fingerprint=f"{index + 6000:064x}",
            source_regime_snapshot_fingerprint=f"{index + 6100:064x}",
            source_sector_liquidity_score_fingerprint=f"{index + 6200:064x}",
            source_liquidity_evidence_fingerprint=f"{index + 6300:064x}",
        )
        for index, percentile in enumerate(percentiles)
    ]
    report = build_stability_report(
        observations,
        config=StabilityReportConfigV1(
            minimum_bucket_observations=2,
            minimum_evaluated_buckets_per_dimension=2,
        ),
    )

    counts = {bucket.bucket: bucket.observation_count for bucket in report.liquidity.buckets}
    assert counts == {"LOW": 1, "MEDIUM": 2, "HIGH": 3}
    assert sum(counts.values()) == len(observations)


def test_duplicate_source_returns_are_rejected() -> None:
    first = _observation(0, source_return=9999)
    second = _observation(1, source_return=9999)

    with pytest.raises(ValueError, match="unique source returns"):
        build_stability_report([first, second])


def test_tampered_observation_fingerprint_is_rejected() -> None:
    observation = _observation(0)
    payload = observation.model_dump(mode="python")
    payload["observation_fingerprint"] = "0" * 64

    with pytest.raises(ValidationError, match="stability observation fingerprint mismatch"):
        StabilityObservationV1.model_validate(payload)


def test_empty_input_is_an_explicit_inconclusive_report() -> None:
    report = build_stability_report([])

    assert report.state == "INCONCLUSIVE"
    assert report.observation_count == 0
    assert report.source_observation_fingerprints == []
    assert report.regime.state == "INCONCLUSIVE"
    assert report.liquidity.state == "INCONCLUSIVE"
    assert report.sector.state == "INCONCLUSIVE"
    assert report.sector.bucket_count == 0
