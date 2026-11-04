from __future__ import annotations

import importlib.util
from datetime import timedelta

import pytest
from pydantic import ValidationError
from test_india_holdout_freeze import RECORDED, _training
from test_india_independent_holdout import HOLDOUT_DAYS, _export, _fee_book, _target

from aitrading_intelligence.india_bounded_tft import evaluate_bounded_tft
from aitrading_intelligence.india_holdout_freeze import freeze_india_holdout_plan
from aitrading_intelligence.india_independent_holdout import IndiaHoldoutConfig
from aitrading_intelligence.india_patch_transformer import (
    evaluate_bounded_patch_forecast,
)
from aitrading_intelligence.india_time_mixer import evaluate_bounded_time_mixer
from aitrading_intelligence.native_causal_baseline import (
    build_native_causal_baseline,
    build_native_causal_baseline_tournament_submission,
)
from aitrading_intelligence.native_forecast_tournament import (
    build_native_forecast_tournament_submission,
)
from aitrading_intelligence.native_registered_trial_audit import (
    NativeRegisteredTrialAuditEvidence,
    audit_native_registered_trials,
)
from aitrading_intelligence.registered_trial_audit import (
    RegisteredTrial,
    TrialOutcome,
    register_trial_budget,
)

CPU_TORCH = pytest.mark.skipif(
    importlib.util.find_spec("torch") is None,
    reason="Actual model training runs in dedicated pinned CPU PyTorch CI",
)


@pytest.fixture(scope="module")
def native_trials():
    training = _training()
    holdout = _export(HOLDOUT_DAYS)
    fee_book = _fee_book()
    target = _target()
    holdout_config = IndiaHoldoutConfig()
    freeze = freeze_india_holdout_plan(
        training, fee_book, target, holdout_config,
        plan_id="synthetic-four-family-registration-test",
        holdout_from=holdout.from_inclusive,
        holdout_to=holdout.to_exclusive,
        recorded_at=RECORDED,
        include_pytorch=True,
    )
    baseline = build_native_causal_baseline_tournament_submission(
        build_native_causal_baseline(training, holdout, fee_book, target)
    )
    candidates = [
        build_native_forecast_tournament_submission(runner(
            training, holdout, fee_book, target
        ))
        for runner in (
            evaluate_bounded_patch_forecast,
            evaluate_bounded_time_mixer,
            evaluate_bounded_tft,
        )
    ]
    registered = [
        RegisteredTrial(
            candidate_id=item.directional.candidate.prediction.candidate_id,
            model_version_ref=item.directional.candidate.prediction.model_version_ref,
            family="PYTORCH",
            parameter_spec_fingerprint=(
                item.native_model_report.model_config_fingerprint
                if hasattr(item.native_model_report, "model_config_fingerprint")
                else item.native_model_report.config_fingerprint
            ),
        )
        for item in candidates
    ]
    budget = register_trial_budget(
        freeze, registered,
        baseline_candidate_id=baseline.baseline.candidate_id,
        baseline_model_version_ref=baseline.baseline.model_version_ref,
        registered_at=freeze.recorded_at + timedelta(minutes=5),
    )
    outcomes = [
        TrialOutcome(
            candidate_id=item.directional.candidate.prediction.candidate_id,
            disposition="EVALUATED",
            model_source_result_fingerprint=(
                item.native_model_report.report_fingerprint
            ),
        )
        for item in candidates
    ]
    return (
        freeze, budget, training, holdout, fee_book, target, holdout_config,
        baseline, candidates, outcomes,
    )


def _audit(data, *, candidates=None, outcomes=None, budget=None, freeze=None,
           target=None, holdout=None, evaluation_started_at=None):
    (frozen, plan, training, later, fees, spec, config, baseline,
     evaluated, disposition) = data
    return audit_native_registered_trials(
        freeze or frozen, budget or plan,
        training, holdout or later, fees, target or spec, config,
        baseline,
        evaluated if candidates is None else candidates,
        disposition if outcomes is None else outcomes,
        evaluation_started_at=(
            evaluation_started_at or frozen.planned_holdout_to + timedelta(minutes=10)
        ),
    )


@CPU_TORCH
def test_actual_three_cpu_model_runs_enter_full_declared_trial_audit(native_trials):
    result = _audit(native_trials)
    assert result.audit.total_declared_trial_budget == 3
    assert result.audit.observed_trial_dispositions == 3
    assert result.audit.evaluated_trial_count == 3
    assert result.audit.declared_trials_familywise_adjusted is True
    assert result.audit.confidence_after_declared_trials == pytest.approx(
        1 - 0.05 / 3
    )
    assert result.audit.tournament_report_fingerprint is not None
    assert result.holdout_binding.result == "SELF_DECLARED_PLAN_CONSISTENT"
    assert len(result.evaluated_native_models) == 3
    assert all(
        item.directional.calibration.holdout_fitted_or_tuned is False
        for item in result.evaluated_native_models
    )
    assert result.independently_verified_market_custody is False
    assert result.independently_excluded_hidden_trials is False
    assert result.externally_attested_preregistration is False
    assert result.promoted_to_paper_or_live is False
    assert result.can_authorize_capital is False
    assert NativeRegisteredTrialAuditEvidence.model_validate_json(
        result.model_dump_json()
    ) == result


@CPU_TORCH
def test_outcome_order_does_not_change_exact_model_audit(native_trials):
    expected = _audit(native_trials)
    reversed_model_records = list(reversed(native_trials[8]))
    reversed_ledger = list(reversed(native_trials[9]))
    actual = _audit(
        native_trials, candidates=reversed_model_records,
        outcomes=reversed_ledger,
    )
    assert actual == expected


@CPU_TORCH
def test_failed_attempt_is_counted_and_not_silently_dropped(native_trials):
    remaining = native_trials[8][:2]
    dispositions = [
        *native_trials[9][:2],
        TrialOutcome(
            candidate_id=native_trials[9][2].candidate_id,
            disposition="TRAINING_FAILED",
        ),
    ]
    result = _audit(native_trials, candidates=remaining, outcomes=dispositions)
    assert result.audit.total_declared_trial_budget == 3
    assert result.audit.evaluated_trial_count == 2
    assert result.audit.observed_trial_dispositions == 3
    assert result.audit.declared_trials_familywise_adjusted is True
    assert result.audit.confidence_after_declared_trials == pytest.approx(
        1 - 0.05 / 3
    )
    assert result.can_authorize_capital is False


@CPU_TORCH
def test_missing_or_undeclared_model_attempt_rejected(native_trials):
    with pytest.raises(ValueError, match="ALL declared trial dispositions"):
        _audit(native_trials, outcomes=native_trials[9][:2])
    duplicated = [*native_trials[9], native_trials[9][0]]
    with pytest.raises(ValueError, match="ALL declared|duplicate trial dispositions"):
        _audit(native_trials, outcomes=duplicated)
    forged = [
        *native_trials[9],
        TrialOutcome(candidate_id="not-registered", disposition="TRAINING_FAILED"),
    ]
    with pytest.raises(ValueError, match="ALL declared trial dispositions"):
        _audit(native_trials, outcomes=forged)


@CPU_TORCH
def test_changed_model_config_or_forged_model_source_is_rejected(native_trials):
    budget = native_trials[1].model_dump(mode="json")
    budget["trials"][0]["parameter_spec_fingerprint"] = "f" * 64
    from aitrading_intelligence.fingerprints import canonical_fingerprint
    from aitrading_intelligence.registered_trial_audit import RegisteredTrialBudget

    budget["budget_fingerprint"] = canonical_fingerprint({
        k: v for k, v in budget.items() if k != "budget_fingerprint"
    })
    changed_budget = RegisteredTrialBudget.model_validate(budget)
    with pytest.raises(ValueError, match="not preregistered"):
        _audit(native_trials, budget=changed_budget)
    forged = native_trials[9][0].model_copy(update={
        "model_source_result_fingerprint": "e" * 64
    })
    with pytest.raises(ValueError, match="ledger differs from model run"):
        _audit(native_trials, outcomes=[forged, *native_trials[9][1:]])


@CPU_TORCH
def test_wrong_market_fee_target_or_preclosure_audit_fails(native_trials):
    with pytest.raises(ValueError, match="target changed after research plan freeze"):
        _audit(native_trials, target=_target(cost_bps=30))
    with pytest.raises(ValueError, match="before holdout window ends"):
        _audit(
            native_trials,
            evaluation_started_at=native_trials[0].planned_holdout_from,
        )


@CPU_TORCH
def test_evidence_replay_detects_tampered_source_ledger_and_authority(native_trials):
    result = _audit(native_trials)
    modified = result.model_dump(mode="json")
    modified["trial_dispositions"][0]["disposition"] = "TRAINING_FAILED"
    modified["trial_dispositions"][0]["model_source_result_fingerprint"] = None
    with pytest.raises(ValidationError):
        NativeRegisteredTrialAuditEvidence.model_validate(modified)
    modified = result.model_dump(mode="json")
    modified["holdout_binding"]["holdout_export_fingerprint"] = "f" * 64
    with pytest.raises(ValidationError):
        NativeRegisteredTrialAuditEvidence.model_validate(modified)
    modified = result.model_dump(mode="json")
    modified["training_export"]["candles"][10]["volume"] += 250
    with pytest.raises(ValidationError, match="training export changed"):
        NativeRegisteredTrialAuditEvidence.model_validate(modified)
    modified = result.model_dump(mode="json")
    modified["holdout_export"]["candles"][10]["volume"] += 250
    with pytest.raises(ValidationError, match="binding differs from raw market evidence"):
        NativeRegisteredTrialAuditEvidence.model_validate(modified)
    modified = result.model_dump(mode="json")
    modified["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        NativeRegisteredTrialAuditEvidence.model_validate(modified)
