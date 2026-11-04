from __future__ import annotations

import importlib.util
from datetime import timedelta

import pytest
from pydantic import ValidationError
from test_india_bounded_tft import _run as tft_run
from test_india_holdout_freeze import RECORDED, _training
from test_india_independent_holdout import HOLDOUT_DAYS, _export, _fee_book, _target
from test_india_patch_transformer import _run as patch_run
from test_india_time_mixer import _run as mixer_run

from aitrading_intelligence.india_bounded_tft import BoundedTftConfig
from aitrading_intelligence.india_holdout_freeze import freeze_india_holdout_plan
from aitrading_intelligence.india_independent_holdout import IndiaHoldoutConfig
from aitrading_intelligence.india_patch_transformer import PatchForecastConfig
from aitrading_intelligence.india_time_mixer import TimeMixerResearchConfig
from aitrading_intelligence.native_causal_baseline import (
    build_native_causal_baseline,
    build_native_causal_baseline_tournament_submission,
)
from aitrading_intelligence.native_forecast_tournament import (
    build_native_forecast_tournament_submission,
)
from aitrading_intelligence.native_registered_family_audit import (
    NativeRegisteredFamilyAudit,
    audit_native_registered_family,
)
from aitrading_intelligence.registered_trial_audit import (
    RegisteredTrial,
    TrialOutcome,
    register_trial_budget,
)

CPU_TORCH = pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Actual registered neural-family integration runs in pinned CPU PyTorch CI",
)


def _inputs():
    training, holdout, fees, target = (
        _training(), _export(HOLDOUT_DAYS), _fee_book(), _target()
    )
    config = IndiaHoldoutConfig()
    freeze = freeze_india_holdout_plan(
        training, fees, target, config,
        plan_id="nse-synthetic-native-four-family",
        holdout_from=holdout.from_inclusive,
        holdout_to=holdout.to_exclusive,
        recorded_at=RECORDED,
        include_pytorch=True,
    )
    baseline = build_native_causal_baseline_tournament_submission(
        build_native_causal_baseline(training, holdout, fees, target)
    )
    configs = [
        ("bounded-patch-transformer", "india-bounded-patch-transformer.v1",
         PatchForecastConfig().fingerprint),
        ("bounded-time-mixer-research", "india-bounded-time-mixer-cpu.v1",
         TimeMixerResearchConfig().fingerprint),
        ("original-bounded-tft", "india-bounded-tft-quantile-cpu.v1",
         BoundedTftConfig().fingerprint),
    ]
    trials = [RegisteredTrial(
        candidate_id=candidate_id,
        model_version_ref=version,
        family="PYTORCH",
        parameter_spec_fingerprint=digest,
    ) for candidate_id, version, digest in configs]
    budget = register_trial_budget(
        freeze, trials,
        baseline_candidate_id=baseline.directional.candidate.prediction.candidate_id,
        baseline_model_version_ref=(
            baseline.directional.candidate.prediction.model_version_ref
        ),
        registered_at=RECORDED + timedelta(minutes=5),
    )
    return freeze, budget, baseline, training, holdout, fees, target


def _witnesses(training, holdout, target):
    return [
        build_native_forecast_tournament_submission(
            runner(train=training, holdout=holdout, target=target)
        )
        for runner in (patch_run, mixer_run, tft_run)
    ]


def _report(*, budget=None, witness_indices=(0, 1, 2), dispositions=None):
    freeze, registered, baseline, training, holdout, fees, target = _inputs()
    records = _witnesses(training, holdout, target)
    selected = [records[i] for i in witness_indices]
    declared = budget or registered
    actual = {
        item.directional.candidate.prediction.candidate_id: item for item in selected
    }
    states = dispositions if dispositions is not None else [
        TrialOutcome(
            candidate_id=slot.candidate_id,
            disposition="EVALUATED" if slot.candidate_id in actual else "TRAINING_FAILED",
            model_source_result_fingerprint=(
                actual[slot.candidate_id].native_model_report.report_fingerprint
                if slot.candidate_id in actual else None
            ),
        )
        for slot in declared.trials
    ]
    return audit_native_registered_family(
        freeze, declared, baseline, states, selected,
        training_export=training, holdout_export=holdout,
        india_fee_book=fees, target=target,
        evaluation_started_at=freeze.planned_holdout_to + timedelta(minutes=5),
    )


def test_neural_budget_has_to_be_declarable_before_any_future_holdout() -> None:
    freeze, budget, baseline, _, _, _, _ = _inputs()
    assert freeze.recorded_at < budget.registered_at < freeze.planned_holdout_from
    assert freeze.model_families == ["CATBOOST", "LIGHTGBM", "PYTORCH"]
    assert all(trial.family == "PYTORCH" for trial in budget.trials)
    assert budget.can_authorize_capital is False
    assert baseline.directional.candidate.prediction.candidate_id == (
        budget.baseline_candidate_id
    )


@CPU_TORCH
def test_all_three_native_models_execute_under_registered_trial_budget() -> None:
    result = _report()
    assert result.selection_audit.total_declared_trial_budget == 3
    assert result.selection_audit.observed_trial_dispositions == 3
    assert result.selection_audit.evaluated_trial_count == 3
    assert result.selection_audit.confidence_after_declared_trials == pytest.approx(
        1 - 0.05 / 3
    )
    assert result.selection_audit.declared_trials_familywise_adjusted is True
    assert result.selection_audit.tournament_report_fingerprint is not None
    assert result.market_export_plan_binding.result == "SELF_DECLARED_PLAN_CONSISTENT"
    assert all(item.disposition == "EVALUATED" for item in result.trial_dispositions)
    assert result.independently_timestamped_plan_verified is False
    assert result.independently_authenticated_market_source_verified is False
    assert result.fee_and_slippage_independently_verified is False
    assert result.executed_model_binary_independently_attested is False
    assert result.hidden_research_trials_independently_excluded is False
    assert result.can_authorize_capital is False
    assert NativeRegisteredFamilyAudit.model_validate_json(
        result.model_dump_json()
    ) == result


@CPU_TORCH
def test_failed_and_cancelled_trials_still_count_toward_corrected_confidence() -> None:
    freeze, budget, baseline, training, holdout, fees, target = _inputs()
    selected = _witnesses(training, holdout, target)[:1]
    actual_id = selected[0].directional.candidate.prediction.candidate_id
    outcomes = [
        TrialOutcome(
            candidate_id=slot.candidate_id,
            disposition="EVALUATED" if slot.candidate_id == actual_id
            else "TRAINING_FAILED" if slot.candidate_id == "original-bounded-tft"
            else "CANCELLED_BEFORE_EVALUATION",
            model_source_result_fingerprint=(
                selected[0].native_model_report.report_fingerprint
                if slot.candidate_id == actual_id else None
            ),
        )
        for slot in budget.trials
    ]
    report = audit_native_registered_family(
        freeze, budget, baseline, outcomes, selected,
        training_export=training, holdout_export=holdout,
        india_fee_book=fees, target=target,
        evaluation_started_at=freeze.planned_holdout_to + timedelta(minutes=5),
    )
    assert report.selection_audit.total_declared_trial_budget == 3
    assert report.selection_audit.evaluated_trial_count == 1
    assert report.selection_audit.confidence_after_declared_trials == pytest.approx(
        1 - 0.05 / 3
    )
    assert report.selection_audit.promotion_allowed is False


@CPU_TORCH
def test_missing_or_unknown_trial_results_rejected_before_ranking() -> None:
    freeze, budget, baseline, training, holdout, fees, target = _inputs()
    first = _witnesses(training, holdout, target)[0]
    claimed = TrialOutcome(
        candidate_id=first.directional.candidate.prediction.candidate_id,
        disposition="EVALUATED",
        model_source_result_fingerprint=first.native_model_report.report_fingerprint,
    )
    with pytest.raises(ValueError, match="every registered attempt"):
        audit_native_registered_family(
            freeze, budget, baseline, [claimed], [first],
            training_export=training, holdout_export=holdout,
            india_fee_book=fees, target=target,
            evaluation_started_at=freeze.planned_holdout_to + timedelta(minutes=5),
        )


@CPU_TORCH
def test_model_configuration_mismatch_rejected_even_with_matching_report_id() -> None:
    freeze, budget, baseline, training, holdout, fees, target = _inputs()
    changed_slots = [
        slot.model_copy(update={
            "parameter_spec_fingerprint": "f" * 64
        }) if slot.candidate_id == "bounded-patch-transformer" else slot
        for slot in budget.trials
    ]
    changed_budget = register_trial_budget(
        freeze, changed_slots,
        baseline_candidate_id=budget.baseline_candidate_id,
        baseline_model_version_ref=budget.baseline_model_version_ref,
        registered_at=budget.registered_at,
    )
    models = _witnesses(training, holdout, target)
    outcomes = [
        TrialOutcome(
            candidate_id=item.directional.candidate.prediction.candidate_id,
            disposition="EVALUATED",
            model_source_result_fingerprint=(
                item.native_model_report.report_fingerprint
            ),
        ) for item in models
    ]
    with pytest.raises(ValueError, match="configuration was not predeclared"):
        audit_native_registered_family(
            freeze, changed_budget, baseline, outcomes, models,
            training_export=training, holdout_export=holdout,
            india_fee_book=fees, target=target,
            evaluation_started_at=freeze.planned_holdout_to + timedelta(minutes=5),
        )


@CPU_TORCH
def test_native_evidence_and_external_verification_flags_cannot_be_rewritten() -> None:
    report = _report(witness_indices=(0,))
    broken = report.model_dump(mode="json")
    broken["independently_authenticated_market_source_verified"] = True
    with pytest.raises(ValidationError):
        NativeRegisteredFamilyAudit.model_validate(broken)
    broken = report.model_dump(mode="json")
    broken["selection_audit"]["evaluated_trial_count"] = 3
    with pytest.raises(ValidationError):
        NativeRegisteredFamilyAudit.model_validate(broken)
    broken = report.model_dump(mode="json")
    broken["evaluated_native_models"][0]["native_model_report"][
        "model_state_fingerprint"
    ] = "f" * 64
    with pytest.raises(ValidationError):
        NativeRegisteredFamilyAudit.model_validate(broken)


@CPU_TORCH
def test_holdout_cannot_be_evaluated_before_frozen_window_closes() -> None:
    freeze, budget, baseline, training, holdout, fees, target = _inputs()
    models = _witnesses(training, holdout, target)
    outcomes = [
        TrialOutcome(
            candidate_id=item.directional.candidate.prediction.candidate_id,
            disposition="EVALUATED",
            model_source_result_fingerprint=item.native_model_report.report_fingerprint,
        ) for item in models
    ]
    with pytest.raises(ValueError, match="before holdout window ends"):
        audit_native_registered_family(
            freeze, budget, baseline, outcomes, models,
            training_export=training, holdout_export=holdout,
            india_fee_book=fees, target=target,
            evaluation_started_at=freeze.planned_holdout_from,
        )
