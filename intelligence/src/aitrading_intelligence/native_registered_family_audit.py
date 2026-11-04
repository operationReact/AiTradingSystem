from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .india_bounded_tft import BoundedTftReport
from .india_fee_tax import IndiaFeeTaxBookV1
from .india_holdout_freeze import (
    IndiaHoldoutFreezePlan,
    IndiaHoldoutPlanBinding,
    bind_india_holdout_to_plan,
)
from .india_independent_holdout import IndiaHoldoutConfig
from .india_patch_transformer import IndiaPatchForecastReport
from .india_time_mixer import TimeMixerResearchReport
from .market_source import MarketResearchExport
from .native_causal_baseline import NativeCausalBaselineTournamentSubmission
from .native_forecast_tournament import NativeForecastTournamentSubmission
from .pytorch_target import ForwardReturnTargetSpec
from .registered_trial_audit import (
    RegisteredTrialAuditReport,
    RegisteredTrialBudget,
    TrialOutcome,
    audit_registered_trials,
)

VERSION = "india-v4-native-registered-family-audit.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


def _research_model_config_fingerprint(
    report: IndiaPatchForecastReport | TimeMixerResearchReport | BoundedTftReport,
) -> str:
    if isinstance(report, IndiaPatchForecastReport):
        return report.model_config_fingerprint
    return report.config_fingerprint


class NativeRegisteredFamilyAudit(BaseModel):
    """Complete self-declared research lineage; never externally attested evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-v4-native-registered-family-audit.v1"] = VERSION
    frozen_holdout: IndiaHoldoutFreezePlan
    registered_budget: RegisteredTrialBudget
    market_export_plan_binding: IndiaHoldoutPlanBinding
    native_baseline: NativeCausalBaselineTournamentSubmission
    trial_dispositions: list[TrialOutcome] = Field(min_length=1, max_length=20)
    evaluated_native_models: list[NativeForecastTournamentSubmission] = Field(
        max_length=5
    )
    evaluation_started_at: datetime
    selection_audit: RegisteredTrialAuditReport
    independently_timestamped_plan_verified: Literal[False] = False
    independently_authenticated_market_source_verified: Literal[False] = False
    fee_and_slippage_independently_verified: Literal[False] = False
    executed_model_binary_independently_attested: Literal[False] = False
    hidden_research_trials_independently_excluded: Literal[False] = False
    production_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    audit_fingerprint: Hash64

    @model_validator(mode="after")
    def check_lineage(self) -> NativeRegisteredFamilyAudit:
        plan = self.frozen_holdout
        budget = self.registered_budget
        linked = self.market_export_plan_binding
        baseline = self.native_baseline
        reference = baseline.directional.candidate
        if (
            linked.plan_fingerprint != plan.plan_fingerprint
            or linked.training_export_fingerprint
            != baseline.baseline.cohort.training_export_fingerprint
            or linked.holdout_export_fingerprint
            != baseline.baseline.cohort.holdout_export_fingerprint
        ):
            raise ValueError("native market exports differ from frozen holdout binding")
        if budget.holdout_freeze_plan_fingerprint != plan.plan_fingerprint:
            raise ValueError("native registered budget does not match frozen holdout")
        if (
            budget.baseline_candidate_id != reference.prediction.candidate_id
            or budget.baseline_model_version_ref
            != reference.prediction.model_version_ref
        ):
            raise ValueError("native baseline differs from registered model identity")
        if plan.india_fee_book_fingerprint != baseline.baseline.cohort.fee_book_fingerprint:
            raise ValueError("native baseline fee book differs from frozen plan")
        if plan.target_spec_fingerprint != baseline.baseline.target.fingerprint:
            raise ValueError("native baseline target differs from frozen plan")
        if self.evaluation_started_at.tzinfo is None or (
            self.evaluation_started_at.utcoffset() is None
        ):
            raise ValueError("native audit evaluation timestamp must be timezone-aware")
        if self.evaluation_started_at < plan.planned_holdout_to:
            raise ValueError("native audit cannot evaluate before holdout closes")
        declared = {trial.candidate_id: trial for trial in budget.trials}
        witnessed = {
            item.directional.candidate.prediction.candidate_id: item
            for item in self.evaluated_native_models
        }
        if len(witnessed) != len(self.evaluated_native_models):
            raise ValueError("native evaluated model identity cannot repeat")
        if not set(witnessed).issubset(declared):
            raise ValueError("native evaluated model was never preregistered")
        for identifier, submission in witnessed.items():
            trial = declared[identifier]
            model = submission.native_model_report
            prediction = submission.directional.candidate.prediction
            if trial.family != "PYTORCH":
                raise ValueError("native neural trial must be declared PYTORCH")
            if (
                trial.model_version_ref != prediction.model_version_ref
                or trial.parameter_spec_fingerprint
                != _research_model_config_fingerprint(model)
            ):
                raise ValueError("native model configuration differs from frozen trial")
            if (
                model.report_fingerprint != prediction.source_result_fingerprint
                or submission.directional.cohort != baseline.directional.cohort
            ):
                raise ValueError("native model result or cohort differs from baseline")
            if submission.directional.candidate.returns.series_fingerprint == (
                reference.returns.series_fingerprint
            ):
                raise ValueError("native challenger cannot impersonate baseline returns")
        actual = sorted(self.trial_dispositions, key=lambda row: row.candidate_id)
        if len({row.candidate_id for row in actual}) != len(actual):
            raise ValueError("native trial disposition identity cannot repeat")
        if set(row.candidate_id for row in actual) != set(declared):
            raise ValueError("native audit must account for every registered trial")
        evaluated_identifiers = {
            row.candidate_id for row in actual if row.disposition == "EVALUATED"
        }
        if evaluated_identifiers != set(witnessed):
            raise ValueError("native evaluations differ from declared dispositions")
        if any(
            row.model_source_result_fingerprint
            != witnessed[row.candidate_id].native_model_report.report_fingerprint
            for row in actual if row.disposition == "EVALUATED"
        ):
            raise ValueError("native trial result digest differs from original model")
        if (
            self.selection_audit.trial_budget_fingerprint != budget.budget_fingerprint
            or self.selection_audit.freeze_plan_fingerprint != plan.plan_fingerprint
            or self.selection_audit.baseline_source_result_fingerprint
            != baseline.baseline.baseline_report.report_fingerprint
            or self.selection_audit.trial_outcomes_fingerprint
            != canonical_fingerprint([
                item.model_dump(mode="json") for item in actual
            ])
            or self.selection_audit.total_declared_trial_budget != len(declared)
            or self.selection_audit.observed_trial_dispositions != len(actual)
            or self.selection_audit.evaluated_trial_count != len(witnessed)
        ):
            raise ValueError("native registered audit summary mismatches supplied evidence")
        if self.audit_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"audit_fingerprint"})
        ):
            raise ValueError("native registered family audit fingerprint mismatch")
        return self


def audit_native_registered_family(
    freeze: IndiaHoldoutFreezePlan,
    budget: RegisteredTrialBudget,
    native_baseline: NativeCausalBaselineTournamentSubmission,
    dispositions: Sequence[TrialOutcome],
    native_challengers: Sequence[NativeForecastTournamentSubmission],
    *,
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    india_fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    evaluation_started_at: datetime,
    holdout_config: IndiaHoldoutConfig | None = None,
) -> NativeRegisteredFamilyAudit:
    """Run the PR #360 all-declared-trials gate on native in-process evidence.

    None of the records demonstrate an external timestamp, model binary
    attestation, broker-market custody, genuine fees, or verified profitability.
    """
    frozen = IndiaHoldoutFreezePlan.model_validate(freeze.model_dump(mode="python"))
    planned = RegisteredTrialBudget.model_validate(budget.model_dump(mode="python"))
    reference = NativeCausalBaselineTournamentSubmission.model_validate(
        native_baseline.model_dump(mode="python")
    )
    actual = [NativeForecastTournamentSubmission.model_validate(
        item.model_dump(mode="python")
    ) for item in native_challengers]
    outcomes = [TrialOutcome.model_validate(row.model_dump(mode="python"))
                for row in dispositions]
    if "PYTORCH" not in frozen.model_families:
        raise ValueError("native neural tournament was not prospectively declared")
    binding = bind_india_holdout_to_plan(
        frozen, training_export, holdout_export, india_fee_book, target,
        holdout_config or IndiaHoldoutConfig(),
    )
    # Validate all lineage BEFORE expensive bootstrap screening and before
    # exposing any research ranking. No unknown or hidden trial is invented.
    declared = {row.candidate_id: row for row in planned.trials}
    if len(declared) != len(planned.trials):
        raise ValueError("registered trial identities cannot repeat")
    witnessed = {
        item.directional.candidate.prediction.candidate_id: item
        for item in actual
    }
    if len(witnessed) != len(actual):
        raise ValueError("evaluated model identities cannot repeat")
    if set(row.candidate_id for row in outcomes) != set(declared):
        raise ValueError("every registered attempt needs a disposition")
    for identifier, item in witnessed.items():
        trial = declared.get(identifier)
        if trial is None:
            raise ValueError("unregistered native model cannot join the tournament")
        if trial.family != "PYTORCH":
            raise ValueError("native neural trials must be registered PYTORCH")
        if (
            trial.model_version_ref != item.directional.candidate.prediction.model_version_ref
            or trial.parameter_spec_fingerprint
            != _research_model_config_fingerprint(item.native_model_report)
        ):
            raise ValueError("native model configuration was not predeclared")
        if item.directional.cohort != reference.directional.cohort:
            raise ValueError("native candidate and baseline must share the frozen cohort")
    if (
        reference.baseline.cohort.training_export_fingerprint
        != binding.training_export_fingerprint
        or reference.baseline.cohort.holdout_export_fingerprint
        != binding.holdout_export_fingerprint
        or reference.baseline.target.fingerprint != frozen.target_spec_fingerprint
        or reference.baseline.cohort.fee_book_fingerprint
        != frozen.india_fee_book_fingerprint
    ):
        raise ValueError("native baseline does not match frozen source, fee or target")
    for row in outcomes:
        if row.disposition == "EVALUATED" and row.candidate_id in witnessed and (
            row.model_source_result_fingerprint
            != witnessed[row.candidate_id].native_model_report.report_fingerprint
        ):
            raise ValueError("reported trial source digest is not the actual model report")
    audited = audit_registered_trials(
        frozen, planned, reference.directional.candidate,
        outcomes, [item.directional.candidate for item in actual],
        evaluation_started_at=evaluation_started_at,
    )
    payload = {
        "contract_version": VERSION,
        "frozen_holdout": frozen.model_dump(mode="json"),
        "registered_budget": planned.model_dump(mode="json"),
        "market_export_plan_binding": binding.model_dump(mode="json"),
        "native_baseline": reference.model_dump(mode="json"),
        "trial_dispositions": [
            row.model_dump(mode="json")
            for row in sorted(outcomes, key=lambda item: item.candidate_id)
        ],
        "evaluated_native_models": [
            item.model_dump(mode="json")
            for item in sorted(
                actual, key=lambda item: item.directional.candidate.prediction.candidate_id
            )
        ],
        "evaluation_started_at": evaluation_started_at,
        "selection_audit": audited.model_dump(mode="json"),
        "independently_timestamped_plan_verified": False,
        "independently_authenticated_market_source_verified": False,
        "fee_and_slippage_independently_verified": False,
        "executed_model_binary_independently_attested": False,
        "hidden_research_trials_independently_excluded": False,
        "production_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return NativeRegisteredFamilyAudit(
        **payload, audit_fingerprint=canonical_fingerprint(payload)
    )
