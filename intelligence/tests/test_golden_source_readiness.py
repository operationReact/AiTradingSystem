"""Synthetic contract tests only; no actual provider rights are asserted."""
from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from pydantic import ValidationError

from aitrading_intelligence.golden_source_readiness import (
    ModelFeatureSlice,
    ModelSourceRequirements,
    SourceSliceEvidence,
    evaluate_model_data_readiness,
    requirements_from_reviewed_catalogue,
)

AS_OF = datetime(2026, 11, 9, 10, 0, tzinfo=UTC)


def requirements() -> ModelSourceRequirements:
    return ModelSourceRequirements(
        model_name="LightGBM",
        model_version="research.v1",
        requested_use="MODEL_INFERENCE",
        required_source_ids=["daily_bars"],
        required_feature_names=["fundamental.roce", "market.return_1"],
        expected_feature_schema_digest="a" * 64,
        minimum_valid_rows={"daily_bars": 64},
        maximum_age_seconds={"daily_bars": 3600},
    )


def snapshot() -> ModelFeatureSlice:
    return ModelFeatureSlice(
        decision_at=AS_OF,
        scope_id="LISTING:NSE:INTERNAL-42",
        data_cutoff=AS_OF - timedelta(minutes=2),
        feature_schema_digest="a" * 64,
        feature_digest="b" * 64,
        features={"fundamental.roce": 0.0, "market.return_1": 0.05},
        source_digests={"daily_bars": "c" * 64},
    )


def source() -> SourceSliceEvidence:
    # This is fabricated *test evidence*, never a claim of a real vendor licence.
    return SourceSliceEvidence(
        source_id="daily_bars",
        scope_id="LISTING:NSE:INTERNAL-42",
        source_kind="EXTERNAL",
        dataset_digest="c" * 64,
        latest_event_at=AS_OF - timedelta(minutes=11),
        latest_available_at=AS_OF - timedelta(minutes=10),
        ingested_at=AS_OF,
        valid_rows=64,
        expected_rows=64,
        quality="VERIFIED",
        permitted_uses=frozenset({"MODEL_TRAINING", "MODEL_INFERENCE"}),
        rights_receipt_digest="e" * 64,
        rights_verified_upstream=True,
    )


def inspect(
    *,
    features: ModelFeatureSlice | None = None,
    evidence: list[SourceSliceEvidence] | None = None,
    req: ModelSourceRequirements | None = None,
    artifact_verified: bool = True,
):
    return evaluate_model_data_readiness(
        requirements=req or requirements(),
        features=features or snapshot(),
        source_slices=[source()] if evidence is None else evidence,
        model_artifact_digest="d" * 64,
        model_artifact_verified=artifact_verified,
    )


def test_complete_synthetic_receipt_is_research_only_and_zero_is_real() -> None:
    result = inspect()
    assert result.status == "RESEARCH_READY"
    assert result.blockers == []
    assert result.can_authorize_capital is False
    assert snapshot().features["fundamental.roce"] == 0.0


def test_missing_required_feature_blocks_and_does_not_invent_zero() -> None:
    broken = snapshot().model_copy(
        update={"features": {"market.return_1": 0.05}},
    )
    result = inspect(features=broken)
    assert result.status == "BLOCKED"
    assert "MISSING_REQUIRED_FEATURE:fundamental.roce" in result.blockers


def test_missing_source_never_means_no_event() -> None:
    result = inspect(evidence=[])
    assert result.status == "BLOCKED"
    assert "SOURCE_UNAVAILABLE:daily_bars" in result.blockers


def test_missing_or_wrong_licensing_blocks() -> None:
    no_rights = source().model_copy(
        update={"permitted_uses": frozenset(), "rights_verified_upstream": False,
                "rights_receipt_digest": None},
    )
    assert "NOT_LICENSED:daily_bars" in inspect(evidence=[no_rights]).blockers
    display_only = source().model_copy(
        update={"permitted_uses": frozenset({"END_USER_DISPLAY"})},
    )
    assert "NOT_LICENSED:daily_bars" in inspect(evidence=[display_only]).blockers


def test_synthetic_source_and_features_always_block() -> None:
    test_data = source().model_copy(update={"source_kind": "SYNTHETIC_TEST"})
    result = inspect(evidence=[test_data], features=snapshot().model_copy(update={"synthetic": True}))
    assert result.status == "BLOCKED"
    assert "SYNTHETIC_SOURCE:daily_bars" in result.blockers
    assert "SYNTHETIC_FEATURE_SNAPSHOT" in result.blockers


def test_late_published_data_cannot_be_used_in_retrospective_prediction() -> None:
    late = source().model_copy(
        update={
            "latest_available_at": AS_OF,
            "ingested_at": AS_OF + timedelta(minutes=2),
        }
    )
    result = inspect(evidence=[late])
    assert "FUTURE_SOURCE_EVIDENCE:daily_bars" in result.blockers
    with pytest.raises(ValidationError, match="feature cutoff"):
        snapshot().model_copy(
            update={"data_cutoff": AS_OF + timedelta(minutes=1)},
        ).__class__.model_validate(
            snapshot().model_dump() | {"data_cutoff": AS_OF + timedelta(minutes=1)}
        )


def test_incomplete_stale_quality_or_unbound_data_block() -> None:
    few = source().model_copy(update={"valid_rows": 63})
    assert "INSUFFICIENT_HISTORY:daily_bars" in inspect(evidence=[few]).blockers
    old = source().model_copy(
        update={
            "latest_event_at": AS_OF - timedelta(days=3),
            "latest_available_at": AS_OF - timedelta(days=3),
        }
    )
    assert "STALE_SOURCE:daily_bars" in inspect(evidence=[old]).blockers
    bad = source().model_copy(update={"quality": "QUARANTINED"})
    assert "SOURCE_QUALITY:daily_bars" in inspect(evidence=[bad]).blockers
    mismatch = snapshot().model_copy(update={"source_digests": {"daily_bars": "f" * 64}})
    assert "FEATURE_SOURCE_MISMATCH:daily_bars" in inspect(features=mismatch).blockers


def test_unverified_model_and_schema_version_block() -> None:
    assert "UNTRAINED_OR_UNVERIFIED_MODEL" in inspect(artifact_verified=False).blockers
    wrong = snapshot().model_copy(update={"feature_schema_digest": "1" * 64})
    assert "INCOMPATIBLE_FEATURE_SCHEMA" in inspect(features=wrong).blockers


def test_duplicate_sources_and_unbounded_contracts_rejected() -> None:
    result = inspect(evidence=[source(), source()])
    assert "DUPLICATE_SOURCE:daily_bars" in result.blockers
    with pytest.raises(ValidationError, match="required data sources cannot repeat"):
        requirements().model_copy(
            update={"required_source_ids": ["daily_bars", "daily_bars"]}
        ).__class__.model_validate(
            requirements().model_dump() | {
                "required_source_ids": ["daily_bars", "daily_bars"]
            }
        )
    with pytest.raises(ValidationError, match="rights claim requires"):
        SourceSliceEvidence.model_validate(
            source().model_dump() | {"rights_receipt_digest": None}
        )
    with pytest.raises(ValidationError, match="cannot precede"):
        SourceSliceEvidence.model_validate(
            source().model_dump() | {
                "latest_event_at": AS_OF,
                "latest_available_at": AS_OF - timedelta(minutes=5),
            }
        )


def test_catalogue_resolves_all_18_models_without_inventing_trainability() -> None:
    root = Path(__file__).resolve().parents[2]
    contract = json.loads(
        (root / "docs/architecture/mintick-mind-golden-source-requirements.v1.json").read_text()
    )
    assert len(contract["models"]) == 18
    for model in contract["models"]:
        required = requirements_from_reviewed_catalogue(
            catalogue=contract,
            model_name=model["name"],
            model_version="untrained-contract-only",
            feature_names=["sample.expected_feature"],
            feature_schema_digest="a" * 64,
            requested_use="MODEL_TRAINING",
        )
        assert required.required_source_ids == model["required_sources"]
        assert required.model_name == model["name"]

    with pytest.raises(ValueError, match="uniquely present"):
        requirements_from_reviewed_catalogue(
            catalogue=contract,
            model_name="NotRegistered",
            model_version="v1",
            feature_names=["x"],
            feature_schema_digest="a" * 64,
            requested_use="MODEL_TRAINING",
        )


def test_no_capital_permission_can_enter_contracts() -> None:
    with pytest.raises(ValidationError):
        ModelFeatureSlice.model_validate(
            snapshot().model_dump() | {"can_authorize_capital": True}
        )


def test_source_scope_binding_prevents_cross_stock_data_contamination() -> None:
    from_another_stock = source().model_copy(
        update={"scope_id": "LISTING:NSE:INTERNAL-999"}
    )
    outcome = inspect(evidence=[from_another_stock])
    assert "WRONG_SOURCE_SCOPE:daily_bars" in outcome.blockers

    market_scope = source().model_copy(update={"scope_id": "MARKET:NSE"})
    allowed_global = requirements().model_copy(
        update={"source_scope_overrides": {"daily_bars": "MARKET:NSE"}}
    )
    assert inspect(req=allowed_global, evidence=[market_scope]).status == "RESEARCH_READY"
    assert inspect(evidence=[market_scope]).status == "BLOCKED"
