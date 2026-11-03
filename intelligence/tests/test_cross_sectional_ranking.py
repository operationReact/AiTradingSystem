from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.cross_sectional_ranking import (
    CrossSectionalRankingDatasetV1,
    build_cross_sectional_ranking_dataset,
    evaluate_cross_sectional_ranking,
)
from aitrading_intelligence.cross_sectional_scan import build_cross_sectional_scan_plan
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion import FusionEvidence, FusionRequest, fuse_signals
from aitrading_intelligence.india_context import (
    IndiaUniverseMembership,
    build_india_universe_snapshot,
)
from aitrading_intelligence.production_universe import (
    NIFTY500_INDEX_MEMBERSHIP,
    NIFTY500_LIQUID_UNIVERSE_ID,
)
from aitrading_intelligence.pytorch_target import LabeledExample

AS_OF = datetime(2026, 11, 3, 6, 0, tzinfo=UTC)
TARGET_SPEC = "f" * 64


def _universe(count: int = 4):
    return build_india_universe_snapshot(
        universe_id=NIFTY500_LIQUID_UNIVERSE_ID,
        as_of=AS_OF,
        memberships=[
            IndiaUniverseMembership(
                instrument_id=f"NSE:INE{index:09d}",
                exchange="NSE",
                symbol=f"SYM{index}",
                series="EQ",
                sector="Information Technology",
                index_memberships=[NIFTY500_INDEX_MEMBERSHIP],
                effective_from=AS_OF - timedelta(days=30),
                effective_to=None,
                available_at=AS_OF - timedelta(minutes=5),
                source="fixture",
                source_id=f"binding-{index}",
            )
            for index in range(1, count + 1)
        ],
        source_versions={"fixture": "a" * 64},
    )


def _evidence(family: str, score: float, seed: str) -> FusionEvidence:
    return FusionEvidence(
        family=family,
        provider=f"fixture-{family.lower()}",
        version="v1",
        score=score,
        confidence=1.0,
        observed_at=AS_OF,
        fingerprint=(seed * 64)[:64],
        reasons=["fixture"],
    )


def _opportunity(symbol: str, score: float, seed: str = "a"):
    return fuse_signals(
        FusionRequest(
            symbol=symbol,
            as_of=AS_OF,
            deterministic=_evidence("DETERMINISTIC", score, seed),
            quant=_evidence("QUANT", score, chr(ord(seed) + 1)),
        )
    )


def _outcome(symbol: str, forward_return: float, *, observed_at: datetime):
    threshold_return = 0.0025
    payload = {
        "target_version": "forward-return-after-costs.v1",
        "symbol": symbol,
        "as_of": AS_OF.isoformat(),
        "target_observed_at": observed_at.isoformat(),
        "feature_row_fingerprint": canonical_fingerprint({"symbol": symbol}),
        "target_spec_fingerprint": TARGET_SPEC,
        "label": 1 if forward_return > threshold_return else 0,
        "forward_return": forward_return,
        "threshold_return": threshold_return,
        "features": {"fixture": float(len(symbol))},
        "provenance": ["fixture"],
    }
    return LabeledExample(
        **payload,
        example_fingerprint=canonical_fingerprint(payload),
    )


def test_dataset_orders_candidates_by_score_with_deterministic_tie_breaks() -> None:
    plan = build_cross_sectional_scan_plan(_universe(), batch_size=2)
    opportunities = [
        _opportunity("SYM3", 0.40, "c"),
        _opportunity("SYM1", 0.80, "a"),
        _opportunity("SYM4", 0.40, "e"),
        _opportunity("SYM2", 0.60, "g"),
    ]

    dataset = build_cross_sectional_ranking_dataset(plan, opportunities)

    assert [row.symbol for row in dataset.rows] == ["SYM1", "SYM2", "SYM3", "SYM4"]
    assert [row.rank for row in dataset.rows] == [1, 2, 3, 4]
    assert dataset.coverage == 1.0
    assert dataset.missing_member_count == 0
    assert dataset.source_scan_plan_fingerprint == plan.plan_fingerprint
    assert dataset.can_authorize_capital is False


def test_dataset_records_missing_scan_members_without_substituting_another_universe() -> None:
    plan = build_cross_sectional_scan_plan(_universe(), batch_size=2)
    dataset = build_cross_sectional_ranking_dataset(
        plan,
        [
            _opportunity("SYM1", 0.80, "a"),
            _opportunity("SYM2", 0.60, "c"),
            _opportunity("SYM3", 0.40, "e"),
        ],
    )

    assert dataset.ranked_member_count == 3
    assert dataset.missing_member_count == 1
    assert dataset.coverage == pytest.approx(0.75)
    assert dataset.missing_member_fingerprints == [
        plan.batches[1].members[1].member_fingerprint
    ]


def test_top_k_metrics_use_frozen_rank_and_after_cost_returns() -> None:
    plan = build_cross_sectional_scan_plan(_universe(), batch_size=2)
    dataset = build_cross_sectional_ranking_dataset(
        plan,
        [
            _opportunity("SYM1", 0.80, "a"),
            _opportunity("SYM2", 0.60, "c"),
            _opportunity("SYM3", 0.40, "e"),
            _opportunity("SYM4", 0.20, "g"),
        ],
    )
    cutoff = AS_OF + timedelta(hours=1)
    outcomes = [
        _outcome("SYM1", 0.030, observed_at=cutoff),
        _outcome("SYM2", 0.020, observed_at=cutoff),
        _outcome("SYM3", -0.010, observed_at=cutoff),
        _outcome("SYM4", -0.020, observed_at=cutoff),
    ]

    evaluation = evaluate_cross_sectional_ranking(
        dataset,
        outcomes,
        evaluation_as_of=cutoff,
        top_k=(1, 2, 4),
        round_trip_cost_bps=10.0,
    )

    top1, top2, top4 = evaluation.top_k_metrics
    assert top1.state == "COMPLETE"
    assert top1.expectancy_bps == pytest.approx(290.0)
    assert top2.expectancy_bps == pytest.approx(240.0)
    assert top4.expectancy_bps == pytest.approx(40.0)
    assert evaluation.score_return_spearman == pytest.approx(1.0)
    assert evaluation.promotion_allowed is False
    assert evaluation.can_authorize_capital is False


def test_unresolved_top_rank_is_not_backfilled_from_lower_rank() -> None:
    plan = build_cross_sectional_scan_plan(_universe(), batch_size=2)
    dataset = build_cross_sectional_ranking_dataset(
        plan,
        [
            _opportunity("SYM1", 0.80, "a"),
            _opportunity("SYM2", 0.60, "c"),
            _opportunity("SYM3", 0.40, "e"),
            _opportunity("SYM4", 0.20, "g"),
        ],
    )
    cutoff = AS_OF + timedelta(hours=1)
    outcomes = [
        _outcome("SYM1", 0.99, observed_at=cutoff + timedelta(minutes=1)),
        _outcome("SYM2", 0.02, observed_at=cutoff),
        _outcome("SYM3", 0.01, observed_at=cutoff),
        _outcome("SYM4", 0.00, observed_at=cutoff),
    ]

    evaluation = evaluate_cross_sectional_ranking(
        dataset,
        outcomes,
        evaluation_as_of=cutoff,
        top_k=(1, 2),
    )

    assert evaluation.resolved_member_count == 3
    assert evaluation.unresolved_member_count == 1
    assert evaluation.top_k_metrics[0].state == "INCOMPLETE"
    assert evaluation.top_k_metrics[0].resolved_count == 0
    assert evaluation.top_k_metrics[1].state == "INCOMPLETE"
    assert evaluation.top_k_metrics[1].resolved_count == 1
    assert evaluation.score_return_spearman is None


def test_future_outcome_cannot_rewrite_historical_ranking_evaluation() -> None:
    plan = build_cross_sectional_scan_plan(_universe(2), batch_size=2)
    dataset = build_cross_sectional_ranking_dataset(
        plan,
        [
            _opportunity("SYM1", 0.80, "a"),
            _opportunity("SYM2", 0.60, "c"),
        ],
    )
    cutoff = AS_OF + timedelta(hours=1)
    first = _outcome("SYM1", 0.02, observed_at=cutoff)
    future = _outcome("SYM2", 0.99, observed_at=cutoff + timedelta(hours=1))

    baseline = evaluate_cross_sectional_ranking(
        dataset,
        [first],
        evaluation_as_of=cutoff,
        top_k=(1, 2),
    )
    mutated = evaluate_cross_sectional_ranking(
        dataset,
        [first, future],
        evaluation_as_of=cutoff,
        top_k=(1, 2),
    )

    assert mutated.model_dump(mode="json") == baseline.model_dump(mode="json")
    assert mutated.evaluation_fingerprint == baseline.evaluation_fingerprint


def test_rejects_candidate_outside_scan_plan_or_wrong_time() -> None:
    plan = build_cross_sectional_scan_plan(_universe(2), batch_size=2)

    with pytest.raises(ValueError, match="not a member"):
        build_cross_sectional_ranking_dataset(
            plan,
            [_opportunity("OTHER", 0.80, "a")],
        )

    wrong_time = _opportunity("SYM1", 0.80, "c").model_copy(
        update={"as_of": AS_OF + timedelta(seconds=1)}
    )
    with pytest.raises(ValueError):
        build_cross_sectional_ranking_dataset(plan, [wrong_time])


def test_tampered_candidate_or_dataset_fingerprint_fails_closed() -> None:
    plan = build_cross_sectional_scan_plan(_universe(2), batch_size=2)
    tampered_candidate = _opportunity("SYM1", 0.80, "a").model_copy(
        update={"opportunity_fingerprint": "0" * 64}
    )
    with pytest.raises(ValueError, match="opportunity fingerprint mismatch"):
        build_cross_sectional_ranking_dataset(plan, [tampered_candidate])

    dataset = build_cross_sectional_ranking_dataset(
        plan,
        [_opportunity("SYM1", 0.80, "c")],
    )
    tampered = dataset.model_dump(mode="python")
    tampered["rows"][0]["ranking_score"] = 0.01
    with pytest.raises(ValidationError, match="ranking row fingerprint mismatch"):
        CrossSectionalRankingDatasetV1.model_validate(tampered)
