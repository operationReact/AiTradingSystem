from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from aitrading_intelligence.cross_sectional_ranking import (
    CrossSectionalRankingDatasetV1,
    CrossSectionalRankingRowV1,
)
from aitrading_intelligence.expected_edge_risk_ranking import (
    ExpectedEdgeRiskRankingConfigV1,
    ExpectedEdgeRiskRankingV1,
    rerank_by_expected_net_edge_risk,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.selective_policy import SelectiveDecisionV1

AS_OF = datetime(2026, 11, 3, 4, 0, tzinfo=UTC)


def _row(
    rank: int,
    symbol: str,
    raw_score: float,
    fused_score: float,
    seed: str,
    *,
    action: str = "BUY",
) -> CrossSectionalRankingRowV1:
    payload = {
        "contract_version": "cross-sectional-ranking-row.v1",
        "rank": rank,
        "instrument_id": f"NSE:{symbol}",
        "symbol": symbol,
        "as_of": AS_OF.isoformat(),
        "candidate_action": action,
        "ranking_score": raw_score,
        "fused_score": fused_score,
        "confidence": 0.8,
        "source_scan_member_fingerprint": seed.lower() * 64,
        "source_candidate_fingerprint": seed * 64,
        "can_authorize_capital": False,
    }
    return CrossSectionalRankingRowV1(
        **payload,
        row_fingerprint=canonical_fingerprint(payload),
    )


def _dataset() -> CrossSectionalRankingDatasetV1:
    rows = [
        _row(1, "AAA", 0.95, 0.90, "1"),
        _row(2, "BBB", 0.90, 0.80, "2"),
        _row(3, "CCC", 0.85, 0.70, "3"),
    ]
    payload = {
        "contract_version": "cross-sectional-ranking-dataset.v1",
        "ranking_policy_id": "signal-fusion-rank-score.v1",
        "as_of": AS_OF.isoformat(),
        "source_scan_plan_fingerprint": "e" * 64,
        "source_universe_fingerprint": "f" * 64,
        "source_universe_member_count": 4,
        "ranked_member_count": 3,
        "missing_member_count": 1,
        "coverage": 0.75,
        "rows": [row.model_dump(mode="json") for row in rows],
        "missing_member_fingerprints": ["d" * 64],
        "status": "RESEARCH_RANKING_DATASET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CrossSectionalRankingDatasetV1(
        **payload,
        dataset_fingerprint=canonical_fingerprint(payload),
    )


def _decision(
    row: CrossSectionalRankingRowV1,
    *,
    net_edge_bps: float,
    uncertainty: float,
    coverage: float = 1.0,
    policy_decision: str = "TRADE",
    fused_score: float | None = None,
    source_candidate_fingerprint: str | None = None,
) -> SelectiveDecisionV1:
    payload = {
        "contract_version": "selective-policy.v1",
        "symbol": row.symbol,
        "decision_as_of": AS_OF.isoformat(),
        "policy_config_fingerprint": "9" * 64,
        "source_opportunity_fingerprint": (
            source_candidate_fingerprint or row.source_candidate_fingerprint
        ),
        "source_uncertainty_fingerprint": canonical_fingerprint(
            {"symbol": row.symbol, "kind": "uncertainty"}
        ),
        "source_uncertainty_age_seconds": 0,
        "calibrated_probability": 0.70,
        "uncertainty_score": uncertainty,
        "uncertainty_level": "LOW" if uncertainty < 0.35 else "MEDIUM",
        "evidence_quality": "COMPLETE" if coverage == 1.0 else "DEGRADED",
        "expert_coverage": coverage,
        "fused_score": row.fused_score if fused_score is None else fused_score,
        "expected_gross_edge_bps": net_edge_bps + 10.0,
        "estimated_round_trip_cost_bps": 10.0,
        "expected_net_edge_bps": net_edge_bps,
        "policy_decision": policy_decision,
        "state": "CANDIDATE" if policy_decision == "TRADE" else "ABSTAIN",
        "reason_codes": [
            "SELECTIVE_POLICY_CANDIDATE"
            if policy_decision == "TRADE"
            else "TEST_ABSTAIN"
        ],
        "required_next_gate": "V3_05_COVERAGE_EXPECTANCY_EVALUATION",
        "status": "RESEARCH_ELIGIBILITY_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return SelectiveDecisionV1(
        **payload,
        decision_fingerprint=canonical_fingerprint(payload),
    )


def _decisions(source: CrossSectionalRankingDatasetV1) -> list[SelectiveDecisionV1]:
    return [
        _decision(source.rows[0], net_edge_bps=30.0, uncertainty=0.30),
        _decision(source.rows[1], net_edge_bps=25.0, uncertainty=0.10),
        _decision(source.rows[2], net_edge_bps=20.0, uncertainty=0.20),
    ]


def test_edge_over_risk_reorders_raw_score_ranking() -> None:
    source = _dataset()
    result = rerank_by_expected_net_edge_risk(source, _decisions(source))

    assert [row.symbol for row in source.rows] == ["AAA", "BBB", "CCC"]
    assert [row.symbol for row in result.reranked_dataset.rows] == [
        "BBB",
        "AAA",
        "CCC",
    ]
    top = result.scores[0]
    assert top.symbol == "BBB"
    assert top.expected_net_edge_bps == 25.0
    assert top.risk_score == pytest.approx(0.10)
    assert top.edge_to_risk_ratio == pytest.approx(2.5)
    assert top.ranking_score == pytest.approx(2.5 / 3.5)
    assert result.reranked_dataset.ranking_policy_id == "expected-net-edge-risk.v1"
    assert result.can_authorize_capital is False


def test_coverage_shortfall_is_explicit_risk_penalty() -> None:
    source = _dataset()
    decisions = [
        _decision(source.rows[0], net_edge_bps=30.0, uncertainty=0.10),
        _decision(
            source.rows[1],
            net_edge_bps=30.0,
            uncertainty=0.10,
            coverage=0.5,
        ),
        _decision(source.rows[2], net_edge_bps=10.0, uncertainty=0.50),
    ]

    result = rerank_by_expected_net_edge_risk(source, decisions)
    scores = {score.symbol: score for score in result.scores}

    assert scores["AAA"].coverage_penalty == pytest.approx(0.0)
    assert scores["AAA"].risk_score == pytest.approx(0.10)
    assert scores["BBB"].coverage_penalty == pytest.approx(0.5)
    assert scores["BBB"].risk_score == pytest.approx(0.60)
    assert scores["AAA"].ranking_score > scores["BBB"].ranking_score


def test_selective_abstention_is_zero_not_fake_trade() -> None:
    source = _dataset()
    decisions = [
        _decision(source.rows[0], net_edge_bps=30.0, uncertainty=0.30),
        _decision(
            source.rows[1],
            net_edge_bps=500.0,
            uncertainty=0.01,
            policy_decision="ABSTAIN",
        ),
        _decision(source.rows[2], net_edge_bps=20.0, uncertainty=0.20),
    ]

    result = rerank_by_expected_net_edge_risk(source, decisions)
    abstain = next(score for score in result.scores if score.symbol == "BBB")

    assert abstain.state == "ABSTAIN_ZERO"
    assert abstain.ranking_score == 0.0
    assert abstain.edge_to_risk_ratio == 0.0
    assert result.reranked_dataset.rows[-1].symbol == "BBB"


def test_every_source_row_requires_selective_evidence() -> None:
    source = _dataset()
    with pytest.raises(ValueError, match="every source ranking row"):
        rerank_by_expected_net_edge_risk(source, _decisions(source)[:2])


def test_outside_source_candidate_fails_closed() -> None:
    source = _dataset()
    outside = _row(1, "OUT", 0.5, 0.5, "8")
    decisions = [
        *_decisions(source),
        _decision(outside, net_edge_bps=40.0, uncertainty=0.1),
    ]
    with pytest.raises(ValueError, match="outside the source ranking dataset"):
        rerank_by_expected_net_edge_risk(source, decisions)


def test_selective_fingerprint_is_revalidated() -> None:
    source = _dataset()
    decisions = _decisions(source)
    decisions[0] = decisions[0].model_copy(
        update={"decision_fingerprint": "0" * 64}
    )
    with pytest.raises(ValueError, match="selective decision fingerprint mismatch"):
        rerank_by_expected_net_edge_risk(source, decisions)


def test_selective_fused_score_must_match_frozen_ranking_row() -> None:
    source = _dataset()
    decisions = _decisions(source)
    decisions[0] = _decision(
        source.rows[0],
        net_edge_bps=30.0,
        uncertainty=0.30,
        fused_score=0.89,
    )
    with pytest.raises(ValueError, match="fused score does not match"):
        rerank_by_expected_net_edge_risk(source, decisions)


def test_selective_trade_cannot_attach_to_source_watch() -> None:
    watch = _row(1, "WATCH", 0.9, 0.8, "7", action="WATCH")
    payload = {
        "contract_version": "cross-sectional-ranking-dataset.v1",
        "ranking_policy_id": "signal-fusion-rank-score.v1",
        "as_of": AS_OF.isoformat(),
        "source_scan_plan_fingerprint": "e" * 64,
        "source_universe_fingerprint": "f" * 64,
        "source_universe_member_count": 1,
        "ranked_member_count": 1,
        "missing_member_count": 0,
        "coverage": 1.0,
        "rows": [watch.model_dump(mode="json")],
        "missing_member_fingerprints": [],
        "status": "RESEARCH_RANKING_DATASET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    source = CrossSectionalRankingDatasetV1(
        **payload,
        dataset_fingerprint=canonical_fingerprint(payload),
    )
    decision = _decision(watch, net_edge_bps=30.0, uncertainty=0.1)

    with pytest.raises(ValueError, match="source candidate action BUY"):
        rerank_by_expected_net_edge_risk(source, [decision])


def test_input_order_cannot_change_ranking_identity() -> None:
    source = _dataset()
    decisions = _decisions(source)
    forward = rerank_by_expected_net_edge_risk(source, decisions)
    reverse = rerank_by_expected_net_edge_risk(source, list(reversed(decisions)))

    assert reverse.model_dump(mode="json") == forward.model_dump(mode="json")
    assert reverse.ranking_fingerprint == forward.ranking_fingerprint


def test_source_scan_coverage_and_missing_lineage_are_preserved() -> None:
    source = _dataset()
    reranked = rerank_by_expected_net_edge_risk(
        source,
        _decisions(source),
    ).reranked_dataset

    assert reranked.source_scan_plan_fingerprint == source.source_scan_plan_fingerprint
    assert reranked.source_universe_fingerprint == source.source_universe_fingerprint
    assert reranked.source_universe_member_count == 4
    assert reranked.ranked_member_count == 3
    assert reranked.missing_member_count == 1
    assert reranked.coverage == 0.75
    assert reranked.missing_member_fingerprints == source.missing_member_fingerprints


def test_ties_use_stable_instrument_identity() -> None:
    source = _dataset()
    decisions = [
        _decision(row, net_edge_bps=20.0, uncertainty=0.20)
        for row in source.rows
    ]
    result = rerank_by_expected_net_edge_risk(source, list(reversed(decisions)))
    assert [row.instrument_id for row in result.reranked_dataset.rows] == [
        "NSE:AAA",
        "NSE:BBB",
        "NSE:CCC",
    ]


def test_trade_with_non_positive_net_edge_fails_closed() -> None:
    source = _dataset()
    decisions = _decisions(source)
    decisions[0] = _decision(
        source.rows[0],
        net_edge_bps=0.0,
        uncertainty=0.10,
    )
    with pytest.raises(ValueError, match="positive net edge"):
        rerank_by_expected_net_edge_risk(source, decisions)


def test_tampered_bundle_fingerprint_fails_validation() -> None:
    source = _dataset()
    result = rerank_by_expected_net_edge_risk(source, _decisions(source))
    tampered = result.model_dump(mode="python")
    tampered["ranking_fingerprint"] = "0" * 64
    with pytest.raises(ValidationError, match="ranking fingerprint mismatch"):
        ExpectedEdgeRiskRankingV1.model_validate(tampered)


def test_config_changes_are_part_of_ranking_identity() -> None:
    source = _dataset()
    decisions = _decisions(source)
    default = rerank_by_expected_net_edge_risk(source, decisions)
    changed = rerank_by_expected_net_edge_risk(
        source,
        decisions,
        config=ExpectedEdgeRiskRankingConfigV1(edge_scale_bps=50.0),
    )

    assert changed.config.fingerprint != default.config.fingerprint
    assert changed.ranking_fingerprint != default.ranking_fingerprint
    assert (
        changed.reranked_dataset.dataset_fingerprint
        != default.reranked_dataset.dataset_fingerprint
    )
