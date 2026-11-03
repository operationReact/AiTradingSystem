from __future__ import annotations

import math
from collections import Counter
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .cross_sectional_ranking import (
    CROSS_SECTIONAL_RANKING_DATASET_VERSION,
    CROSS_SECTIONAL_RANKING_ROW_VERSION,
    CrossSectionalRankingDatasetV1,
    CrossSectionalRankingRowV1,
)
from .cross_sectional_scan import CrossSectionalScanMemberV1, CrossSectionalScanPlanV1
from .expected_edge_risk_ranking import ExpectedEdgeRiskRankingV1
from .fingerprints import canonical_fingerprint
from .liquidity_universe import LiquidityCandidateEvidence, LiquidityQualifiedCatalogue
from .universe_identity_bridge import (
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

SECTOR_LIQUIDITY_ADJUSTMENT_VERSION = "sector-liquidity-adjustment.v1"
SECTOR_LIQUIDITY_SCORE_VERSION = "sector-liquidity-score.v1"
SECTOR_LIQUIDITY_POLICY_ID = "sector-liquidity-concentration.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class SectorLiquidityAdjustmentConfigV1(BaseModel):
    """Frozen concentration transform applied after expected-edge/risk ranking."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_id: Literal["sector-liquidity-concentration.v1"] = SECTOR_LIQUIDITY_POLICY_ID
    liquidity_floor: Annotated[float, Field(gt=0.0, le=1.0)] = 0.50
    sector_repeat_penalty: Annotated[float, Field(ge=0.0, le=1.0)] = 0.15
    sector_floor: Annotated[float, Field(gt=0.0, le=1.0)] = 0.50

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class SectorLiquidityScoreV1(BaseModel):
    """One auditable marginal rank score with exact sector/liquidity lineage."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["sector-liquidity-score.v1"] = SECTOR_LIQUIDITY_SCORE_VERSION
    rank: Annotated[int, Field(ge=1, le=1_000)]
    instrument_id: Annotated[str, Field(min_length=1, max_length=120)]
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    as_of: datetime
    sector: Annotated[str, Field(min_length=1, max_length=160)]
    adv_notional: Annotated[float, Field(gt=0.0)]
    liquidity_percentile: Annotated[float, Field(gt=0.0, le=1.0)]
    liquidity_multiplier: Annotated[float, Field(gt=0.0, le=1.0)]
    prior_selected_sector_count: Annotated[int, Field(ge=0, le=999)]
    sector_multiplier: Annotated[float, Field(gt=0.0, le=1.0)]
    base_ranking_score: Annotated[float, Field(ge=0.0, le=1.0)]
    adjusted_ranking_score: Annotated[float, Field(ge=0.0, le=1.0)]
    source_expected_edge_risk_ranking_fingerprint: Hash64
    source_ranking_row_fingerprint: Hash64
    source_candidate_fingerprint: Hash64
    source_scan_member_fingerprint: Hash64
    source_identity_binding_fingerprint: Hash64
    source_liquidity_evidence_fingerprint: Hash64
    config_fingerprint: Hash64
    score_fingerprint: Hash64
    status: Literal["RESEARCH_CONCENTRATION_SCORE_ONLY"] = (
        "RESEARCH_CONCENTRATION_SCORE_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("sector/liquidity score as_of must be timezone-aware")
        return value

    @field_validator("adv_notional")
    @classmethod
    def adv_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("ADV notional must be finite")
        return value

    @model_validator(mode="after")
    def validate_score(self) -> SectorLiquidityScoreV1:
        expected = self.base_ranking_score * self.liquidity_multiplier * self.sector_multiplier
        if not math.isclose(
            self.adjusted_ranking_score,
            expected,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise ValueError("adjusted ranking score must equal base * liquidity * sector")
        if self.score_fingerprint != canonical_fingerprint(_score_payload(self)):
            raise ValueError("sector/liquidity score fingerprint mismatch")
        return self


class SectorLiquidityAdjustmentV1(BaseModel):
    """Research-only greedy concentration adjustment over one frozen cross-section."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["sector-liquidity-adjustment.v1"] = (
        SECTOR_LIQUIDITY_ADJUSTMENT_VERSION
    )
    as_of: datetime
    config: SectorLiquidityAdjustmentConfigV1
    source_expected_edge_risk_ranking_fingerprint: Hash64
    source_ranking_dataset_fingerprint: Hash64
    source_scan_plan_fingerprint: Hash64
    source_identity_bridge_fingerprint: Hash64
    source_liquidity_snapshot_fingerprint: Hash64
    ranked_member_count: Annotated[int, Field(ge=1, le=1_000)]
    scores: list[SectorLiquidityScoreV1] = Field(min_length=1, max_length=1_000)
    reranked_dataset: CrossSectionalRankingDatasetV1
    adjustment_fingerprint: Hash64
    status: Literal["RESEARCH_CONCENTRATION_ADJUSTMENT_ONLY"] = (
        "RESEARCH_CONCENTRATION_ADJUSTMENT_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("sector/liquidity adjustment as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_bundle(self) -> SectorLiquidityAdjustmentV1:
        if self.ranked_member_count != len(self.scores):
            raise ValueError("ranked_member_count must match concentration scores")
        if self.reranked_dataset.ranked_member_count != self.ranked_member_count:
            raise ValueError("reranked dataset must preserve ranked member count")
        if self.reranked_dataset.as_of != self.as_of:
            raise ValueError("reranked dataset as_of must match adjustment")
        if self.reranked_dataset.ranking_policy_id != SECTOR_LIQUIDITY_POLICY_ID:
            raise ValueError("reranked dataset must use sector/liquidity policy")
        expected_config = self.config.fingerprint
        seen_rows: set[str] = set()
        seen_candidates: set[str] = set()
        seen_instruments: set[str] = set()
        sector_counts: Counter[str] = Counter()
        prior_adjusted = math.inf
        for expected_rank, score in enumerate(self.scores, start=1):
            if score.rank != expected_rank:
                raise ValueError("concentration scores must have contiguous ranks")
            if score.as_of != self.as_of:
                raise ValueError("concentration score as_of must match adjustment")
            if score.config_fingerprint != expected_config:
                raise ValueError("concentration score config fingerprint mismatch")
            if score.source_expected_edge_risk_ranking_fingerprint != (
                self.source_expected_edge_risk_ranking_fingerprint
            ):
                raise ValueError("concentration score source ranking fingerprint mismatch")
            if score.source_ranking_row_fingerprint in seen_rows:
                raise ValueError("concentration scores must be unique by source row")
            if score.source_candidate_fingerprint in seen_candidates:
                raise ValueError("concentration scores must be unique by source candidate")
            if score.instrument_id in seen_instruments:
                raise ValueError("concentration scores must be unique by instrument")
            if score.prior_selected_sector_count != sector_counts[score.sector]:
                raise ValueError("prior sector count does not match greedy rank order")
            if score.adjusted_ranking_score > prior_adjusted + 1e-12:
                raise ValueError("adjusted ranking scores must be non-increasing")
            _validate_score_derivations(score, self.config)
            sector_counts[score.sector] += 1
            prior_adjusted = score.adjusted_ranking_score
            seen_rows.add(score.source_ranking_row_fingerprint)
            seen_candidates.add(score.source_candidate_fingerprint)
            seen_instruments.add(score.instrument_id)

        for row, score in zip(self.reranked_dataset.rows, self.scores, strict=True):
            if row.rank != score.rank:
                raise ValueError("reranked row rank must match concentration score")
            if row.instrument_id != score.instrument_id or row.symbol != score.symbol:
                raise ValueError("reranked row identity must match concentration score")
            if row.source_candidate_fingerprint != score.source_candidate_fingerprint:
                raise ValueError("reranked row candidate lineage must match concentration score")
            if not math.isclose(
                row.ranking_score,
                score.adjusted_ranking_score,
                rel_tol=0.0,
                abs_tol=1e-12,
            ):
                raise ValueError("reranked row score must match concentration score")
        if self.adjustment_fingerprint != canonical_fingerprint(_bundle_payload(self)):
            raise ValueError("sector/liquidity adjustment fingerprint mismatch")
        return self


def adjust_sector_liquidity_concentration(
    edge_risk_ranking: ExpectedEdgeRiskRankingV1,
    scan_plan: CrossSectionalScanPlanV1,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    liquidity: LiquidityQualifiedCatalogue,
    *,
    config: SectorLiquidityAdjustmentConfigV1 | None = None,
) -> SectorLiquidityAdjustmentV1:
    """Greedily re-rank by edge/risk while penalizing sector crowding and thin ADV."""

    cfg = config or SectorLiquidityAdjustmentConfigV1()
    ranking = ExpectedEdgeRiskRankingV1.model_validate(
        edge_risk_ranking.model_dump(mode="python")
    )
    plan = CrossSectionalScanPlanV1.model_validate(scan_plan.model_dump(mode="python"))
    bridge = _validate_bridge(identity_bridge)
    liquidity_snapshot = _validate_liquidity_snapshot(liquidity)
    source = ranking.reranked_dataset

    if source.source_scan_plan_fingerprint != plan.plan_fingerprint:
        raise ValueError("edge/risk ranking does not belong to the supplied scan plan")
    if source.as_of != plan.scan_as_of:
        raise ValueError("ranking and scan plan must share the same frozen as_of")
    if bridge.as_of != source.as_of or liquidity_snapshot.as_of != source.as_of:
        raise ValueError("ranking, identity bridge and liquidity must share exact as_of")
    if bridge.source_liquidity_snapshot_fingerprint != liquidity_snapshot.snapshot_fingerprint:
        raise ValueError("identity bridge does not belong to supplied liquidity snapshot")

    members_by_fingerprint = _scan_members_by_fingerprint(plan)
    bindings_by_fingerprint = {item.binding_fingerprint: item for item in bridge.bindings}
    liquidity_by_id = {item.instrument_id: item for item in liquidity_snapshot.evidence}

    candidates: list[_Candidate] = []
    for row in source.rows:
        member = members_by_fingerprint.get(row.source_scan_member_fingerprint)
        if member is None:
            raise ValueError("ranking row source scan member is missing from scan plan")
        if row.instrument_id != member.instrument_id or row.symbol != member.symbol:
            raise ValueError("ranking row identity does not match exact scan member")
        binding = bindings_by_fingerprint.get(member.source_membership_id)
        if binding is None:
            raise ValueError("scan member source membership is missing from identity bridge")
        _validate_member_binding(member, binding)
        evidence = liquidity_by_id.get(binding.catalogue_instrument_id)
        if evidence is None:
            raise ValueError("identity binding liquidity evidence is missing")
        _validate_binding_liquidity(binding, evidence, source.as_of)
        candidates.append(
            _Candidate(row=row, member=member, binding=binding, evidence=evidence)
        )

    if len({item.binding.binding_fingerprint for item in candidates}) != len(candidates):
        raise ValueError("ranked candidates must map to unique identity bindings")
    if len({item.evidence.evidence_fingerprint for item in candidates}) != len(candidates):
        raise ValueError("ranked candidates must map to unique liquidity evidence")

    percentiles = _liquidity_percentiles(candidates)
    remaining = list(candidates)
    sector_counts: Counter[str] = Counter()
    scores: list[SectorLiquidityScoreV1] = []
    while remaining:
        scored = [
            _marginal_score(
                candidate,
                rank=len(scores) + 1,
                prior_sector_count=sector_counts[candidate.member.sector],
                liquidity_percentile=percentiles[candidate.row.source_candidate_fingerprint],
                config=cfg,
                source_ranking_fingerprint=ranking.ranking_fingerprint,
            )
            for candidate in remaining
        ]
        selected = min(
            scored,
            key=lambda item: (
                -item.adjusted_ranking_score,
                item.instrument_id,
                item.source_candidate_fingerprint,
            ),
        )
        scores.append(selected)
        sector_counts[selected.sector] += 1
        remaining = [
            item
            for item in remaining
            if item.row.row_fingerprint != selected.source_ranking_row_fingerprint
        ]

    source_rows = {row.row_fingerprint: row for row in source.rows}
    reranked_rows: list[CrossSectionalRankingRowV1] = []
    for score in scores:
        original = source_rows[score.source_ranking_row_fingerprint]
        row_payload = {
            "contract_version": CROSS_SECTIONAL_RANKING_ROW_VERSION,
            "rank": score.rank,
            "instrument_id": original.instrument_id,
            "symbol": original.symbol,
            "as_of": original.as_of.isoformat(),
            "candidate_action": original.candidate_action,
            "ranking_score": score.adjusted_ranking_score,
            "fused_score": original.fused_score,
            "confidence": original.confidence,
            "source_scan_member_fingerprint": original.source_scan_member_fingerprint,
            "source_candidate_fingerprint": original.source_candidate_fingerprint,
            "can_authorize_capital": False,
        }
        reranked_rows.append(
            CrossSectionalRankingRowV1(
                **row_payload,
                row_fingerprint=canonical_fingerprint(row_payload),
            )
        )

    dataset_payload = {
        "contract_version": CROSS_SECTIONAL_RANKING_DATASET_VERSION,
        "ranking_policy_id": SECTOR_LIQUIDITY_POLICY_ID,
        "as_of": source.as_of.isoformat(),
        "source_scan_plan_fingerprint": source.source_scan_plan_fingerprint,
        "source_universe_fingerprint": source.source_universe_fingerprint,
        "source_universe_member_count": source.source_universe_member_count,
        "ranked_member_count": source.ranked_member_count,
        "missing_member_count": source.missing_member_count,
        "coverage": source.coverage,
        "rows": [row.model_dump(mode="json") for row in reranked_rows],
        "missing_member_fingerprints": list(source.missing_member_fingerprints),
        "status": "RESEARCH_RANKING_DATASET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    reranked_dataset = CrossSectionalRankingDatasetV1(
        **dataset_payload,
        dataset_fingerprint=canonical_fingerprint(dataset_payload),
    )
    bundle_payload = {
        "contract_version": SECTOR_LIQUIDITY_ADJUSTMENT_VERSION,
        "as_of": source.as_of.isoformat(),
        "config": cfg.model_dump(mode="json"),
        "source_expected_edge_risk_ranking_fingerprint": ranking.ranking_fingerprint,
        "source_ranking_dataset_fingerprint": source.dataset_fingerprint,
        "source_scan_plan_fingerprint": plan.plan_fingerprint,
        "source_identity_bridge_fingerprint": bridge.snapshot_fingerprint,
        "source_liquidity_snapshot_fingerprint": liquidity_snapshot.snapshot_fingerprint,
        "ranked_member_count": source.ranked_member_count,
        "scores": [item.model_dump(mode="json") for item in scores],
        "reranked_dataset": reranked_dataset.model_dump(mode="json"),
        "status": "RESEARCH_CONCENTRATION_ADJUSTMENT_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return SectorLiquidityAdjustmentV1(
        **bundle_payload,
        adjustment_fingerprint=canonical_fingerprint(bundle_payload),
    )


class _Candidate:
    def __init__(
        self,
        *,
        row: CrossSectionalRankingRowV1,
        member: CrossSectionalScanMemberV1,
        binding: Nifty500LiquidityIdentityBinding,
        evidence: LiquidityCandidateEvidence,
    ) -> None:
        self.row = row
        self.member = member
        self.binding = binding
        self.evidence = evidence


def _liquidity_percentiles(candidates: list[_Candidate]) -> dict[str, float]:
    values = [item.evidence.adv_notional for item in candidates]
    if any(value is None or value <= 0.0 for value in values):
        raise ValueError("ranked liquidity evidence requires positive ADV notional")
    numeric = [float(value) for value in values if value is not None]
    count = len(numeric)
    return {
        item.row.source_candidate_fingerprint: (
            sum(1 for value in numeric if value <= float(item.evidence.adv_notional)) / count
        )
        for item in candidates
    }


def _marginal_score(
    candidate: _Candidate,
    *,
    rank: int,
    prior_sector_count: int,
    liquidity_percentile: float,
    config: SectorLiquidityAdjustmentConfigV1,
    source_ranking_fingerprint: str,
) -> SectorLiquidityScoreV1:
    liquidity_multiplier = config.liquidity_floor + (
        1.0 - config.liquidity_floor
    ) * liquidity_percentile
    sector_multiplier = max(
        config.sector_floor,
        1.0 - config.sector_repeat_penalty * prior_sector_count,
    )
    adjusted = candidate.row.ranking_score * liquidity_multiplier * sector_multiplier
    payload = {
        "contract_version": SECTOR_LIQUIDITY_SCORE_VERSION,
        "rank": rank,
        "instrument_id": candidate.row.instrument_id,
        "symbol": candidate.row.symbol,
        "as_of": candidate.row.as_of.isoformat(),
        "sector": candidate.member.sector,
        "adv_notional": candidate.evidence.adv_notional,
        "liquidity_percentile": liquidity_percentile,
        "liquidity_multiplier": liquidity_multiplier,
        "prior_selected_sector_count": prior_sector_count,
        "sector_multiplier": sector_multiplier,
        "base_ranking_score": candidate.row.ranking_score,
        "adjusted_ranking_score": adjusted,
        "source_expected_edge_risk_ranking_fingerprint": source_ranking_fingerprint,
        "source_ranking_row_fingerprint": candidate.row.row_fingerprint,
        "source_candidate_fingerprint": candidate.row.source_candidate_fingerprint,
        "source_scan_member_fingerprint": candidate.member.member_fingerprint,
        "source_identity_binding_fingerprint": candidate.binding.binding_fingerprint,
        "source_liquidity_evidence_fingerprint": candidate.evidence.evidence_fingerprint,
        "config_fingerprint": config.fingerprint,
        "status": "RESEARCH_CONCENTRATION_SCORE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return SectorLiquidityScoreV1(
        **payload,
        score_fingerprint=canonical_fingerprint(payload),
    )


def _validate_score_derivations(
    score: SectorLiquidityScoreV1,
    config: SectorLiquidityAdjustmentConfigV1,
) -> None:
    expected_liquidity = config.liquidity_floor + (
        1.0 - config.liquidity_floor
    ) * score.liquidity_percentile
    if not math.isclose(
        score.liquidity_multiplier,
        expected_liquidity,
        rel_tol=0.0,
        abs_tol=1e-12,
    ):
        raise ValueError("liquidity multiplier does not match percentile/config")
    expected_sector = max(
        config.sector_floor,
        1.0 - config.sector_repeat_penalty * score.prior_selected_sector_count,
    )
    if not math.isclose(
        score.sector_multiplier,
        expected_sector,
        rel_tol=0.0,
        abs_tol=1e-12,
    ):
        raise ValueError("sector multiplier does not match concentration config")


def _scan_members_by_fingerprint(
    plan: CrossSectionalScanPlanV1,
) -> dict[str, CrossSectionalScanMemberV1]:
    members = [member for batch in plan.batches for member in batch.members]
    by_fingerprint = {item.member_fingerprint: item for item in members}
    if len(by_fingerprint) != len(members):
        raise ValueError("scan plan member fingerprints must be unique")
    return by_fingerprint


def _validate_member_binding(
    member: CrossSectionalScanMemberV1,
    binding: Nifty500LiquidityIdentityBinding,
) -> None:
    if member.instrument_id != binding.canonical_instrument_id:
        raise ValueError("scan member instrument does not match identity binding")
    if member.symbol != binding.symbol or member.series != binding.series:
        raise ValueError("scan member symbol/series does not match identity binding")
    if member.sector != binding.sector:
        raise ValueError("scan member sector does not match identity binding")
    if member.source_membership_id != binding.binding_fingerprint:
        raise ValueError("scan member source membership must equal binding fingerprint")


def _validate_binding_liquidity(
    binding: Nifty500LiquidityIdentityBinding,
    evidence: LiquidityCandidateEvidence,
    as_of: datetime,
) -> None:
    if binding.liquidity_evidence_fingerprint != evidence.evidence_fingerprint:
        raise ValueError("binding liquidity evidence fingerprint mismatch")
    if evidence.instrument_id != binding.catalogue_instrument_id:
        raise ValueError("binding catalogue instrument does not match liquidity evidence")
    if evidence.exchange != "NSE":
        raise ValueError("ranked concentration evidence requires NSE liquidity")
    if evidence.trading_symbol != binding.broker_trading_symbol:
        raise ValueError("binding trading symbol does not match liquidity evidence")
    if evidence.symbol_token != binding.broker_symbol_token:
        raise ValueError("binding symbol token does not match liquidity evidence")
    if not evidence.accepted:
        raise ValueError("ranked concentration candidate must have accepted liquidity")
    if evidence.latest_available_at is None or evidence.latest_available_at > as_of:
        raise ValueError("liquidity evidence must be causally available at ranking as_of")
    if evidence.adv_notional is None or evidence.adv_notional <= 0.0:
        raise ValueError("accepted ranked liquidity evidence requires positive ADV notional")


def _validate_bridge(
    item: Nifty500LiquidityIdentityBridge,
) -> Nifty500LiquidityIdentityBridge:
    bridge = Nifty500LiquidityIdentityBridge.model_validate(item.model_dump(mode="python"))
    for binding in bridge.bindings:
        payload = {
            "canonicalInstrumentId": binding.canonical_instrument_id,
            "isin": binding.isin,
            "symbol": binding.symbol,
            "series": binding.series,
            "sector": binding.sector,
            "expectedTradingSymbol": binding.expected_trading_symbol,
            "catalogueInstrumentId": binding.catalogue_instrument_id,
            "brokerTradingSymbol": binding.broker_trading_symbol,
            "brokerSymbolToken": binding.broker_symbol_token,
            "liquidityEvidenceFingerprint": binding.liquidity_evidence_fingerprint,
            "sectorMappingEvidenceFingerprint": binding.sector_mapping_evidence_fingerprint,
            "membershipEffectiveFrom": binding.membership_effective_from.isoformat(),
            "availableAt": binding.available_at.isoformat(),
            "mappingRule": binding.mapping_rule,
        }
        if binding.binding_fingerprint != canonical_fingerprint(payload):
            raise ValueError("identity binding fingerprint mismatch")
    snapshot_payload = {
        "contractVersion": bridge.contract_version,
        "asOf": bridge.as_of.isoformat(),
        "dataCutoff": bridge.data_cutoff.isoformat(),
        "mappingRule": bridge.mapping_rule,
        "sourceCatalogueVersion": bridge.source_catalogue_version,
        "sourceCatalogueFingerprint": bridge.source_catalogue_fingerprint,
        "sourceLiquiditySnapshotFingerprint": bridge.source_liquidity_snapshot_fingerprint,
        "sourceSectorEnrichmentFingerprint": bridge.source_sector_enrichment_fingerprint,
        "bindings": [item.model_dump(mode="json") for item in bridge.bindings],
        "bindingCount": bridge.binding_count,
        "status": bridge.status,
        "canAuthorizeCapital": False,
    }
    if bridge.snapshot_fingerprint != canonical_fingerprint(snapshot_payload):
        raise ValueError("identity bridge fingerprint mismatch")
    return bridge


def _validate_liquidity_snapshot(
    item: LiquidityQualifiedCatalogue,
) -> LiquidityQualifiedCatalogue:
    snapshot = LiquidityQualifiedCatalogue.model_validate(item.model_dump(mode="python"))
    for evidence in snapshot.evidence:
        payload = {
            "instrumentId": evidence.instrument_id,
            "exchange": evidence.exchange,
            "tradingSymbol": evidence.trading_symbol,
            "symbolToken": evidence.symbol_token,
            "accepted": evidence.accepted,
            "causalBarCount": evidence.causal_bar_count,
            "latestAvailableAt": (
                evidence.latest_available_at.isoformat()
                if evidence.latest_available_at is not None
                else None
            ),
            "advNotional": evidence.adv_notional,
            "zeroVolumeFraction": evidence.zero_volume_fraction,
            "rejectionReasons": list(evidence.rejection_reasons),
            "marketEvidenceFingerprint": evidence.market_evidence_fingerprint,
        }
        if evidence.evidence_fingerprint != canonical_fingerprint(payload):
            raise ValueError("liquidity evidence fingerprint mismatch")
    snapshot_payload = {
        "contractVersion": snapshot.contract_version,
        "asOf": snapshot.as_of.isoformat(),
        "dataCutoff": snapshot.data_cutoff.isoformat(),
        "sourceCatalogueVersion": snapshot.source_catalogue_version,
        "sourceCatalogueFingerprint": snapshot.source_catalogue_fingerprint,
        "configFingerprint": snapshot.config_fingerprint,
        "evidence": [item.model_dump(mode="json") for item in snapshot.evidence],
        "acceptedInstrumentIds": list(snapshot.accepted_instrument_ids),
        "acceptedCount": snapshot.accepted_count,
        "rejectedCount": snapshot.rejected_count,
        "status": snapshot.status,
        "canAuthorizeCapital": False,
    }
    if snapshot.snapshot_fingerprint != canonical_fingerprint(snapshot_payload):
        raise ValueError("liquidity snapshot fingerprint mismatch")
    return snapshot


def _score_payload(score: SectorLiquidityScoreV1) -> dict[str, object]:
    payload = score.model_dump(mode="json", exclude={"score_fingerprint"})
    payload["as_of"] = score.as_of.isoformat()
    return payload


def _bundle_payload(bundle: SectorLiquidityAdjustmentV1) -> dict[str, object]:
    payload = bundle.model_dump(mode="json", exclude={"adjustment_fingerprint"})
    payload["as_of"] = bundle.as_of.isoformat()
    return payload


__all__ = [
    "SECTOR_LIQUIDITY_ADJUSTMENT_VERSION",
    "SECTOR_LIQUIDITY_POLICY_ID",
    "SECTOR_LIQUIDITY_SCORE_VERSION",
    "SectorLiquidityAdjustmentConfigV1",
    "SectorLiquidityAdjustmentV1",
    "SectorLiquidityScoreV1",
    "adjust_sector_liquidity_concentration",
]
