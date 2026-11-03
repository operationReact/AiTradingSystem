from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.cross_sectional_ranking import (
    CrossSectionalRankingDatasetV1,
    CrossSectionalRankingRowV1,
)
from aitrading_intelligence.cross_sectional_scan import (
    CrossSectionalScanBatchV1,
    CrossSectionalScanMemberV1,
    CrossSectionalScanPlanV1,
)
from aitrading_intelligence.expected_edge_risk_ranking import (
    rerank_by_expected_net_edge_risk,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.liquidity_universe import (
    LiquidityCandidateEvidence,
    LiquidityQualifiedCatalogue,
)
from aitrading_intelligence.sector_liquidity_adjustment import (
    SectorLiquidityAdjustmentConfigV1,
    SectorLiquidityAdjustmentV1,
    adjust_sector_liquidity_concentration,
)
from aitrading_intelligence.selective_policy import SelectiveDecisionV1
from aitrading_intelligence.universe_identity_bridge import (
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

AS_OF = datetime(2026, 11, 3, 4, 0, tzinfo=UTC)
AVAILABLE_AT = AS_OF - timedelta(minutes=10)
MEMBERSHIP_FROM = AS_OF - timedelta(days=30)
CATALOGUE_FP = "a" * 64
LIQ_CONFIG_FP = "b" * 64
SECTOR_FP = "c" * 64
UNIVERSE_FP = "d" * 64

IDENTITIES = [
    ("NSE:INE001A01010", "INE001A01010", "AAA", "Technology"),
    ("NSE:INE002A01018", "INE002A01018", "BBB", "Technology"),
    ("NSE:INE003A01016", "INE003A01016", "CCC", "Financial Services"),
]


def _liquidity_evidence(
    index: int,
    symbol: str,
    adv: float,
    *,
    accepted: bool = True,
) -> LiquidityCandidateEvidence:
    reasons = [] if accepted else ["ADV_NOTIONAL_BELOW_MINIMUM"]
    payload = {
        "instrumentId": index,
        "exchange": "NSE",
        "tradingSymbol": f"{symbol}-EQ",
        "symbolToken": str(1000 + index),
        "accepted": accepted,
        "causalBarCount": 20,
        "latestAvailableAt": AVAILABLE_AT.isoformat(),
        "advNotional": adv,
        "zeroVolumeFraction": 0.0,
        "rejectionReasons": reasons,
        "marketEvidenceFingerprint": canonical_fingerprint(
            {"instrument": index, "fixture": "market"}
        ),
    }
    return LiquidityCandidateEvidence(
        instrument_id=index,
        exchange="NSE",
        trading_symbol=f"{symbol}-EQ",
        symbol_token=str(1000 + index),
        accepted=accepted,
        causal_bar_count=20,
        latest_available_at=AVAILABLE_AT,
        adv_notional=adv,
        zero_volume_fraction=0.0,
        rejection_reasons=reasons,
        market_evidence_fingerprint=payload["marketEvidenceFingerprint"],
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def _liquidity(
    advs: tuple[float, float, float],
    *,
    rejected_index: int | None = None,
) -> LiquidityQualifiedCatalogue:
    evidence = [
        _liquidity_evidence(
            index,
            symbol,
            advs[index - 1],
            accepted=index != rejected_index,
        )
        for index, (_, _, symbol, _) in enumerate(IDENTITIES, start=1)
    ]
    accepted_ids = [item.instrument_id for item in evidence if item.accepted]
    payload = {
        "contractVersion": "liquidity-qualified-catalogue.v1",
        "asOf": AS_OF.isoformat(),
        "dataCutoff": AVAILABLE_AT.isoformat(),
        "sourceCatalogueVersion": "fixture-v1",
        "sourceCatalogueFingerprint": CATALOGUE_FP,
        "configFingerprint": LIQ_CONFIG_FP,
        "evidence": [item.model_dump(mode="json") for item in evidence],
        "acceptedInstrumentIds": accepted_ids,
        "acceptedCount": len(accepted_ids),
        "rejectedCount": len(evidence) - len(accepted_ids),
        "status": "RESEARCH_LIQUIDITY_QUALIFICATION_ONLY",
        "canAuthorizeCapital": False,
    }
    return LiquidityQualifiedCatalogue(
        as_of=AS_OF,
        data_cutoff=AVAILABLE_AT,
        source_catalogue_version="fixture-v1",
        source_catalogue_fingerprint=CATALOGUE_FP,
        config_fingerprint=LIQ_CONFIG_FP,
        evidence=evidence,
        accepted_instrument_ids=accepted_ids,
        accepted_count=len(accepted_ids),
        rejected_count=len(evidence) - len(accepted_ids),
        snapshot_fingerprint=canonical_fingerprint(payload),
    )


def _binding(
    index: int,
    identity: tuple[str, str, str, str],
    evidence: LiquidityCandidateEvidence,
) -> Nifty500LiquidityIdentityBinding:
    canonical_id, isin, symbol, sector = identity
    payload = {
        "canonicalInstrumentId": canonical_id,
        "isin": isin,
        "symbol": symbol,
        "series": "EQ",
        "sector": sector,
        "expectedTradingSymbol": f"{symbol}-EQ",
        "catalogueInstrumentId": index,
        "brokerTradingSymbol": f"{symbol}-EQ",
        "brokerSymbolToken": str(1000 + index),
        "liquidityEvidenceFingerprint": evidence.evidence_fingerprint,
        "sectorMappingEvidenceFingerprint": canonical_fingerprint(
            {"symbol": symbol, "sector": sector}
        ),
        "membershipEffectiveFrom": MEMBERSHIP_FROM.isoformat(),
        "availableAt": AVAILABLE_AT.isoformat(),
        "mappingRule": "nse-symbol-series-to-angel-trading-symbol.v1",
    }
    return Nifty500LiquidityIdentityBinding(
        canonical_instrument_id=canonical_id,
        isin=isin,
        symbol=symbol,
        series="EQ",
        sector=sector,
        expected_trading_symbol=f"{symbol}-EQ",
        catalogue_instrument_id=index,
        broker_trading_symbol=f"{symbol}-EQ",
        broker_symbol_token=str(1000 + index),
        liquidity_evidence_fingerprint=evidence.evidence_fingerprint,
        sector_mapping_evidence_fingerprint=payload[
            "sectorMappingEvidenceFingerprint"
        ],
        membership_effective_from=MEMBERSHIP_FROM,
        available_at=AVAILABLE_AT,
        binding_fingerprint=canonical_fingerprint(payload),
    )


def _bridge(liquidity: LiquidityQualifiedCatalogue) -> Nifty500LiquidityIdentityBridge:
    bindings = [
        _binding(index, identity, liquidity.evidence[index - 1])
        for index, identity in enumerate(IDENTITIES, start=1)
    ]
    payload = {
        "contractVersion": "nifty500-liquidity-identity-bridge.v1",
        "asOf": AS_OF.isoformat(),
        "dataCutoff": AVAILABLE_AT.isoformat(),
        "mappingRule": "nse-symbol-series-to-angel-trading-symbol.v1",
        "sourceCatalogueVersion": "fixture-v1",
        "sourceCatalogueFingerprint": CATALOGUE_FP,
        "sourceLiquiditySnapshotFingerprint": liquidity.snapshot_fingerprint,
        "sourceSectorEnrichmentFingerprint": SECTOR_FP,
        "bindings": [item.model_dump(mode="json") for item in bindings],
        "bindingCount": len(bindings),
        "status": "RESEARCH_IDENTITY_BRIDGE_ONLY",
        "canAuthorizeCapital": False,
    }
    return Nifty500LiquidityIdentityBridge(
        as_of=AS_OF,
        data_cutoff=AVAILABLE_AT,
        source_catalogue_version="fixture-v1",
        source_catalogue_fingerprint=CATALOGUE_FP,
        source_liquidity_snapshot_fingerprint=liquidity.snapshot_fingerprint,
        source_sector_enrichment_fingerprint=SECTOR_FP,
        bindings=bindings,
        binding_count=len(bindings),
        snapshot_fingerprint=canonical_fingerprint(payload),
    )


def _scan_member(
    identity: tuple[str, str, str, str],
    binding: Nifty500LiquidityIdentityBinding,
) -> CrossSectionalScanMemberV1:
    canonical_id, _, symbol, sector = identity
    payload = {
        "instrument_id": canonical_id,
        "exchange": "NSE",
        "symbol": symbol,
        "series": "EQ",
        "sector": sector,
        "index_memberships": ["NIFTY500"],
        "source_membership_id": binding.binding_fingerprint,
    }
    return CrossSectionalScanMemberV1(
        **payload,
        member_fingerprint=canonical_fingerprint(payload),
    )


def _scan_plan(bridge: Nifty500LiquidityIdentityBridge) -> CrossSectionalScanPlanV1:
    members = [
        _scan_member(identity, bridge.bindings[index - 1])
        for index, identity in enumerate(IDENTITIES, start=1)
    ]
    batch_payload = {
        "contract_version": "cross-sectional-scan-batch.v1",
        "batch_index": 0,
        "start_offset": 0,
        "member_count": len(members),
        "members": [item.model_dump(mode="json") for item in members],
    }
    batch = CrossSectionalScanBatchV1(
        **batch_payload,
        batch_fingerprint=canonical_fingerprint(batch_payload),
    )
    plan_payload = {
        "contract_version": "cross-sectional-scan-plan.v1",
        "universe_contract_version": "india-universe-snapshot.v1",
        "universe_id": "nifty500-liquid.v1",
        "scan_as_of": AS_OF.isoformat(),
        "universe_data_cutoff": AVAILABLE_AT.isoformat(),
        "source_universe_fingerprint": UNIVERSE_FP,
        "source_universe_member_count": len(members),
        "batch_size": len(members),
        "batch_count": 1,
        "batches": [batch.model_dump(mode="json")],
        "status": "RESEARCH_SCAN_PLAN_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CrossSectionalScanPlanV1(
        **plan_payload,
        plan_fingerprint=canonical_fingerprint(plan_payload),
    )


def _ranking_row(
    rank: int,
    member: CrossSectionalScanMemberV1,
    raw_score: float,
) -> CrossSectionalRankingRowV1:
    payload = {
        "contract_version": "cross-sectional-ranking-row.v1",
        "rank": rank,
        "instrument_id": member.instrument_id,
        "symbol": member.symbol,
        "as_of": AS_OF.isoformat(),
        "candidate_action": "BUY",
        "ranking_score": raw_score,
        "fused_score": raw_score,
        "confidence": 0.9,
        "source_scan_member_fingerprint": member.member_fingerprint,
        "source_candidate_fingerprint": canonical_fingerprint(
            {"symbol": member.symbol, "candidate": "fixture"}
        ),
        "can_authorize_capital": False,
    }
    return CrossSectionalRankingRowV1(
        **payload,
        row_fingerprint=canonical_fingerprint(payload),
    )


def _source_ranking(plan: CrossSectionalScanPlanV1) -> CrossSectionalRankingDatasetV1:
    members = plan.batches[0].members
    rows = [
        _ranking_row(1, members[0], 0.90),
        _ranking_row(2, members[1], 0.80),
        _ranking_row(3, members[2], 0.70),
    ]
    payload = {
        "contract_version": "cross-sectional-ranking-dataset.v1",
        "ranking_policy_id": "signal-fusion-rank-score.v1",
        "as_of": AS_OF.isoformat(),
        "source_scan_plan_fingerprint": plan.plan_fingerprint,
        "source_universe_fingerprint": UNIVERSE_FP,
        "source_universe_member_count": 3,
        "ranked_member_count": 3,
        "missing_member_count": 0,
        "coverage": 1.0,
        "rows": [row.model_dump(mode="json") for row in rows],
        "missing_member_fingerprints": [],
        "status": "RESEARCH_RANKING_DATASET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CrossSectionalRankingDatasetV1(
        **payload,
        dataset_fingerprint=canonical_fingerprint(payload),
    )


def _selective_decision(
    row: CrossSectionalRankingRowV1,
    *,
    net_edge_bps: float,
    uncertainty: float = 0.10,
    policy_decision: str = "TRADE",
) -> SelectiveDecisionV1:
    payload = {
        "contract_version": "selective-policy.v1",
        "symbol": row.symbol,
        "decision_as_of": AS_OF.isoformat(),
        "policy_config_fingerprint": "9" * 64,
        "source_opportunity_fingerprint": row.source_candidate_fingerprint,
        "source_uncertainty_fingerprint": canonical_fingerprint(
            {"symbol": row.symbol, "uncertainty": "fixture"}
        ),
        "source_uncertainty_age_seconds": 0,
        "calibrated_probability": 0.70,
        "uncertainty_score": uncertainty,
        "uncertainty_level": "LOW",
        "evidence_quality": "COMPLETE",
        "expert_coverage": 1.0,
        "fused_score": row.fused_score,
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


def _fixture(
    *,
    advs: tuple[float, float, float] = (20_000_000.0,) * 3,
    rejected_index: int | None = None,
    abstain_index: int | None = None,
):
    liquidity = _liquidity(advs, rejected_index=rejected_index)
    bridge = _bridge(liquidity)
    plan = _scan_plan(bridge)
    source = _source_ranking(plan)
    net_edges = (30.0, 25.0, 22.0)
    decisions = [
        _selective_decision(
            row,
            net_edge_bps=net_edges[index],
            policy_decision="ABSTAIN" if index + 1 == abstain_index else "TRADE",
        )
        for index, row in enumerate(source.rows)
    ]
    edge_risk = rerank_by_expected_net_edge_risk(source, decisions)
    return edge_risk, plan, bridge, liquidity


def test_repeated_sector_is_demoted_behind_comparable_cross_sector_candidate() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    result = adjust_sector_liquidity_concentration(
        edge_risk,
        plan,
        bridge,
        liquidity,
    )

    assert [row.symbol for row in edge_risk.reranked_dataset.rows] == [
        "AAA",
        "BBB",
        "CCC",
    ]
    assert [row.symbol for row in result.reranked_dataset.rows] == [
        "AAA",
        "CCC",
        "BBB",
    ]
    assert result.scores[0].prior_selected_sector_count == 0
    assert result.scores[1].sector == "Financial Services"
    assert result.scores[1].prior_selected_sector_count == 0
    assert result.scores[2].sector == "Technology"
    assert result.scores[2].prior_selected_sector_count == 1
    assert result.scores[2].sector_multiplier == pytest.approx(0.85)
    assert result.reranked_dataset.ranking_policy_id == (
        "sector-liquidity-concentration.v1"
    )
    assert result.can_authorize_capital is False


def test_lower_adv_receives_lower_liquidity_multiplier() -> None:
    edge_risk, plan, bridge, liquidity = _fixture(
        advs=(30_000_000.0, 10_000_000.0, 20_000_000.0)
    )
    result = adjust_sector_liquidity_concentration(
        edge_risk,
        plan,
        bridge,
        liquidity,
    )
    scores = {score.symbol: score for score in result.scores}

    assert scores["AAA"].liquidity_percentile == pytest.approx(1.0)
    assert scores["CCC"].liquidity_percentile == pytest.approx(2 / 3)
    assert scores["BBB"].liquidity_percentile == pytest.approx(1 / 3)
    assert scores["AAA"].liquidity_multiplier > scores["CCC"].liquidity_multiplier
    assert scores["CCC"].liquidity_multiplier > scores["BBB"].liquidity_multiplier


def test_equal_adv_uses_equal_liquidity_percentile_without_symbol_bias() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    result = adjust_sector_liquidity_concentration(
        edge_risk,
        plan,
        bridge,
        liquidity,
    )

    assert {score.liquidity_percentile for score in result.scores} == {1.0}
    assert {score.liquidity_multiplier for score in result.scores} == {1.0}


def test_selective_abstain_remains_zero_after_concentration_adjustment() -> None:
    edge_risk, plan, bridge, liquidity = _fixture(abstain_index=2)
    result = adjust_sector_liquidity_concentration(
        edge_risk,
        plan,
        bridge,
        liquidity,
    )
    score = next(item for item in result.scores if item.symbol == "BBB")

    assert score.base_ranking_score == 0.0
    assert score.adjusted_ranking_score == 0.0
    assert result.reranked_dataset.rows[-1].symbol == "BBB"


def test_rejected_liquidity_cannot_be_used_for_ranked_candidate() -> None:
    edge_risk, plan, bridge, liquidity = _fixture(rejected_index=2)

    with pytest.raises(ValueError, match="accepted liquidity"):
        adjust_sector_liquidity_concentration(edge_risk, plan, bridge, liquidity)


def test_tampered_liquidity_evidence_fingerprint_fails_closed() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    bad_evidence = list(liquidity.evidence)
    bad_evidence[0] = bad_evidence[0].model_copy(
        update={"evidence_fingerprint": "0" * 64}
    )
    tampered = liquidity.model_copy(update={"evidence": bad_evidence})

    with pytest.raises(ValueError, match="liquidity evidence fingerprint mismatch"):
        adjust_sector_liquidity_concentration(edge_risk, plan, bridge, tampered)


def test_tampered_identity_binding_fingerprint_fails_closed() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    bad_bindings = list(bridge.bindings)
    bad_bindings[0] = bad_bindings[0].model_copy(
        update={"binding_fingerprint": "0" * 64}
    )
    tampered = bridge.model_copy(update={"bindings": bad_bindings})

    with pytest.raises(ValueError, match="identity binding fingerprint mismatch"):
        adjust_sector_liquidity_concentration(edge_risk, plan, tampered, liquidity)


def test_wrong_scan_plan_fails_closed_before_any_identity_guessing() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    tampered = plan.model_copy(update={"plan_fingerprint": "0" * 64})

    with pytest.raises(ValidationError, match="scan plan fingerprint mismatch"):
        adjust_sector_liquidity_concentration(edge_risk, tampered, bridge, liquidity)


def test_liquidity_snapshot_must_match_identity_bridge() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    other = _liquidity((30_000_000.0, 20_000_000.0, 10_000_000.0))

    with pytest.raises(ValueError, match="does not belong to supplied liquidity snapshot"):
        adjust_sector_liquidity_concentration(edge_risk, plan, bridge, other)


def test_source_universe_coverage_and_missing_lineage_are_preserved() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    result = adjust_sector_liquidity_concentration(
        edge_risk,
        plan,
        bridge,
        liquidity,
    )
    source = edge_risk.reranked_dataset
    reranked = result.reranked_dataset

    assert reranked.source_scan_plan_fingerprint == source.source_scan_plan_fingerprint
    assert reranked.source_universe_fingerprint == source.source_universe_fingerprint
    assert reranked.source_universe_member_count == source.source_universe_member_count
    assert reranked.ranked_member_count == source.ranked_member_count
    assert reranked.missing_member_count == source.missing_member_count
    assert reranked.coverage == source.coverage
    assert reranked.missing_member_fingerprints == source.missing_member_fingerprints


def test_config_is_fingerprinted_and_changes_adjustment_identity() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    default = adjust_sector_liquidity_concentration(
        edge_risk,
        plan,
        bridge,
        liquidity,
    )
    stronger = adjust_sector_liquidity_concentration(
        edge_risk,
        plan,
        bridge,
        liquidity,
        config=SectorLiquidityAdjustmentConfigV1(sector_repeat_penalty=0.30),
    )

    assert stronger.config.fingerprint != default.config.fingerprint
    assert stronger.adjustment_fingerprint != default.adjustment_fingerprint
    assert stronger.reranked_dataset.dataset_fingerprint != (
        default.reranked_dataset.dataset_fingerprint
    )


def test_tampered_adjustment_fingerprint_fails_schema_validation() -> None:
    edge_risk, plan, bridge, liquidity = _fixture()
    result = adjust_sector_liquidity_concentration(
        edge_risk,
        plan,
        bridge,
        liquidity,
    )
    tampered = result.model_dump(mode="python")
    tampered["adjustment_fingerprint"] = "0" * 64

    with pytest.raises(ValidationError, match="adjustment fingerprint mismatch"):
        SectorLiquidityAdjustmentV1.model_validate(tampered)
