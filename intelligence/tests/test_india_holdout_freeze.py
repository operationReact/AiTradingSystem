from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError
from test_india_independent_holdout import (
    HOLDOUT_DAYS,
    TRAIN_DAYS,
    _export,
    _fee_book,
    _target,
)

from aitrading_intelligence.india_holdout_freeze import (
    IndiaHoldoutFreezePlan,
    IndiaHoldoutPlanBinding,
    bind_india_holdout_to_plan,
    freeze_india_holdout_plan,
)
from aitrading_intelligence.india_independent_holdout import (
    IndiaHoldoutConfig,
    evaluate_india_independent_holdout,
)
from aitrading_intelligence.market_source import MarketResearchExport

RECORDED = datetime(2026, 10, 28, 12, 0, tzinfo=UTC)


def _training() -> MarketResearchExport:
    original = _export(TRAIN_DAYS).model_dump(mode="json", by_alias=True)
    original["generatedAt"] = datetime(2026, 10, 28, 6, tzinfo=UTC).isoformat()
    return MarketResearchExport.model_validate(original)


def _inputs():
    train = _training()
    holdout = _export(HOLDOUT_DAYS)
    fees = _fee_book()
    target = _target()
    config = IndiaHoldoutConfig()
    plan = freeze_india_holdout_plan(
        train, fees, target, config, plan_id="nse-2026-oct-test",
        holdout_from=holdout.from_inclusive, holdout_to=holdout.to_exclusive,
        recorded_at=RECORDED,
    )
    return plan, train, holdout, fees, target, config


def _bind(plan=None, train=None, holdout=None, fees=None, target=None, config=None,
          report=None):
    original = _inputs()
    return bind_india_holdout_to_plan(
        plan or original[0],
        train or original[1],
        holdout or original[2],
        fees or original[3],
        target or original[4],
        config or original[5],
        report=report,
    )


def test_prospective_plan_commits_immutable_fingerprints_and_denies_authority() -> None:
    plan, train, holdout, fees, target, config = _inputs()
    first = bind_india_holdout_to_plan(plan, train, holdout, fees, target, config)
    second = bind_india_holdout_to_plan(plan, train, holdout, fees, target, config)
    assert first == second
    assert first.result == "SELF_DECLARED_PLAN_CONSISTENT"
    assert plan.recorded_at < plan.planned_holdout_from
    assert plan.model_families == ["CATBOOST", "LIGHTGBM"]
    assert first.registered_at_independently_attested is False
    assert first.external_market_source_verified is False
    assert first.fees_independently_verified is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_evaluated_holdout_report_must_match_locked_inputs() -> None:
    plan, train, holdout, fees, target, config = _inputs()
    report = evaluate_india_independent_holdout(train, holdout, fees, target, config=config)
    binding = bind_india_holdout_to_plan(
        plan, train, holdout, fees, target, config, report=report,
    )
    assert report.status == "RESEARCH_HOLDOUT_METRICS_ONLY"
    assert binding.holdout_report_fingerprint == report.report_fingerprint
    assert binding.can_authorize_capital is False


def test_post_hoc_registration_is_rejected() -> None:
    _, train, holdout, fees, target, config = _inputs()
    with pytest.raises(ValueError, match="before holdout"):
        freeze_india_holdout_plan(
            train, fees, target, config, plan_id="late-plan",
            holdout_from=holdout.from_inclusive,
            holdout_to=holdout.to_exclusive,
            recorded_at=holdout.from_inclusive + timedelta(minutes=5),
        )


def test_plan_rejects_training_data_not_yet_collected_at_registration() -> None:
    _, _, holdout, fees, target, config = _inputs()
    with pytest.raises(ValueError, match="not fully captured"):
        freeze_india_holdout_plan(
            _export(TRAIN_DAYS), fees, target, config,
            plan_id="retroactive-registration",
            holdout_from=holdout.from_inclusive,
            holdout_to=holdout.to_exclusive,
            recorded_at=RECORDED,
        )


def test_export_change_after_freeze_fails_closed() -> None:
    plan, train, holdout, fees, target, config = _inputs()
    payload = train.model_dump(mode="json", by_alias=True)
    payload["candles"][10]["volume"] += 500
    changed = MarketResearchExport.model_validate(payload)
    with pytest.raises(ValueError, match="training export changed"):
        bind_india_holdout_to_plan(plan, changed, holdout, fees, target, config)


def test_holdout_window_cannot_be_selected_after_results_are_known() -> None:
    plan, train, holdout, fees, target, config = _inputs()
    payload = holdout.model_dump(mode="json", by_alias=True)
    payload["fromInclusive"] = (
        holdout.from_inclusive - timedelta(minutes=5)
    ).isoformat()
    changed = MarketResearchExport.model_validate(payload)
    with pytest.raises(ValueError, match="window differs"):
        bind_india_holdout_to_plan(plan, train, changed, fees, target, config)


def test_source_and_instrument_mismatch_cannot_be_bound_to_plan() -> None:
    plan, train, holdout, fees, target, config = _inputs()
    payload = holdout.model_dump(mode="json", by_alias=True)
    payload["symbolToken"] = "9999"
    with pytest.raises(ValueError, match="instrument/source lineage"):
        bind_india_holdout_to_plan(
            plan, train, MarketResearchExport.model_validate(payload),
            fees, target, config,
        )


def test_unregistered_target_or_fee_policy_is_rejected() -> None:
    plan, train, holdout, fees, _, config = _inputs()
    with pytest.raises(ValueError, match="target changed"):
        bind_india_holdout_to_plan(
            plan, train, holdout, fees, _target(cost_bps=30), config
        )
    with pytest.raises(ValueError, match="model configuration changed"):
        bind_india_holdout_to_plan(
            plan, train, holdout, fees, _target(),
            IndiaHoldoutConfig(seed=17),
        )


def test_pytorch_requires_prospective_declaration_before_report() -> None:
    plan, train, holdout, fees, target, config = _inputs()
    with pytest.raises(ValueError, match="evaluated model families differ"):
        report = evaluate_india_independent_holdout(train, holdout, fees, target)
        pretend = plan.model_dump(mode="json")
        pretend["model_families"] = ["CATBOOST", "LIGHTGBM", "PYTORCH"]
        from aitrading_intelligence.fingerprints import canonical_fingerprint

        pretend["plan_fingerprint"] = canonical_fingerprint({
            k: v for k, v in pretend.items() if k != "plan_fingerprint"
        })
        bind_india_holdout_to_plan(
            IndiaHoldoutFreezePlan.model_validate(pretend),
            train, holdout, fees, target, config, report=report,
        )


def test_tampered_freeze_plan_or_binding_is_rejected() -> None:
    plan, train, holdout, fees, target, config = _inputs()
    mutated = plan.model_dump(mode="json")
    mutated["plan_id"] = "rewritten-after-fact"
    with pytest.raises(ValidationError, match="holdout freeze plan fingerprint mismatch"):
        IndiaHoldoutFreezePlan.model_validate(mutated)
    binding = bind_india_holdout_to_plan(plan, train, holdout, fees, target, config)
    altered = binding.model_dump(mode="json")
    altered["holdout_row_count"] += 1
    with pytest.raises(ValidationError, match="holdout plan binding fingerprint mismatch"):
        IndiaHoldoutPlanBinding.model_validate(altered)


def test_fetched_after_generation_is_rejected() -> None:
    plan, train, holdout, fees, target, config = _inputs()
    payload = holdout.model_dump(mode="json", by_alias=True)
    payload["candles"][0]["fetchedAt"] = (
        holdout.generated_at + timedelta(minutes=1)
    ).isoformat()
    changed = MarketResearchExport.model_validate(payload)
    with pytest.raises(ValueError, match="violates export generation"):
        bind_india_holdout_to_plan(plan, train, changed, fees, target, config)
