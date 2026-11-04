from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .incremental_economic_value import IncrementalValueConfig
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

VERSION = "india-native-registered-trial-audit.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


def _model_parameter_fingerprint(report: (
    IndiaPatchForecastReport | TimeMixerResearchReport | BoundedTftReport
)) -> str:
    # Predeclared parameter specifications are the EXACT immutable model
    # configurations, not new fingerprints generated after seeing the holdout.
    if isinstance(report, IndiaPatchForecastReport):
        return report.model_config_fingerprint
    return report.config_fingerprint


def _check_native_registration(
    freeze: IndiaHoldoutFreezePlan,
    plan: RegisteredTrialBudget,
    baseline: NativeCausalBaselineTournamentSubmission,
    submissions: Sequence[NativeForecastTournamentSubmission],
    outcomes: Sequence[TrialOutcome],
) -> None:
    if plan.holdout_freeze_plan_fingerprint != freeze.plan_fingerprint:
        raise ValueError("native audit budget is not tied to this frozen plan")
    if "PYTORCH" not in freeze.model_families:
        raise ValueError("neural trials were absent from frozen model families")
    reference = baseline.baseline
    if (reference.candidate_id != plan.baseline_candidate_id
            or reference.model_version_ref != plan.baseline_model_version_ref):
        raise ValueError("native baseline was not the registered baseline")
    if not freeze.training_export_fingerprint == (
        reference.cohort.training_export_fingerprint
    ):
        raise ValueError("native baseline differs from the frozen training export")
    if freeze.india_fee_book_fingerprint != reference.cohort.fee_book_fingerprint:
        raise ValueError("native baseline fee book differs from frozen fees")
    if freeze.target_spec_fingerprint != reference.target.fingerprint:
        raise ValueError("native baseline target differs from frozen specification")
    if reference.cohort.holdout_export_fingerprint != (
        baseline.directional.cohort.holdout_export_fingerprint
    ):
        raise ValueError("native baseline directional source differs from holdout")
    declared = {trial.candidate_id: trial for trial in plan.trials}
    attempts = [item.candidate_id for item in outcomes]
    if sorted(attempts) != sorted(declared):
        raise ValueError("native audit must account for ALL declared trial dispositions")
    if len(attempts) != len(set(attempts)):
        raise ValueError("native audit contains duplicate trial dispositions")
    evaluated = {
        item.candidate_id: item for item in outcomes
        if item.disposition == "EVALUATED"
    }
    if len(evaluated) != len(submissions):
        raise ValueError("native audit evaluated model report count differs from ledger")
    if len(evaluated) > 5:
        raise ValueError("native tournament supports at most five evaluated challengers")

    actual: set[str] = set()
    for item in submissions:
        report = item.native_model_report
        trace = report.native_trace
        if trace is None:
            raise ValueError("native audit cannot use an inconclusive model report")
        candidate = item.directional.candidate
        candidate_id = candidate.prediction.candidate_id
        if candidate_id in actual:
            raise ValueError("native audit duplicate evaluated model")
        actual.add(candidate_id)
        slot = declared.get(candidate_id)
        if slot is None:
            raise ValueError("native audit contains an undeclared model trial")
        if slot.family != "PYTORCH":
            raise ValueError("native model family differs from frozen PYTORCH registration")
        if (
            slot.model_version_ref != candidate.prediction.model_version_ref
            or slot.parameter_spec_fingerprint != _model_parameter_fingerprint(report)
        ):
            raise ValueError("native model configuration or version was not preregistered")
        if candidate.prediction.source_result_fingerprint != report.report_fingerprint:
            raise ValueError("native audit experiment result differs from model report")
        if trace.cohort != reference.cohort:
            raise ValueError("native audit changed heldout cohort or India costs")
        outcome = evaluated.get(candidate_id)
        if outcome is None or (
            outcome.model_source_result_fingerprint != report.report_fingerprint
        ):
            raise ValueError("native audit evaluated trial ledger differs from model run")
    if actual != set(evaluated):
        raise ValueError("native audit includes missing evaluated trial evidence")


class NativeRegisteredTrialAuditEvidence(BaseModel):
    """Complete self-declared research run, without capital or source attestation."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-native-registered-trial-audit.v1"] = VERSION
    freeze: IndiaHoldoutFreezePlan
    holdout_binding: IndiaHoldoutPlanBinding
    trial_budget: RegisteredTrialBudget
    native_baseline: NativeCausalBaselineTournamentSubmission
    evaluated_native_models: list[NativeForecastTournamentSubmission] = Field(
        max_length=5
    )
    trial_dispositions: list[TrialOutcome] = Field(min_length=1, max_length=20)
    evaluation_started_at: datetime
    economic_policy: IncrementalValueConfig
    audit: RegisteredTrialAuditReport
    independently_verified_market_custody: Literal[False] = False
    independently_excluded_hidden_trials: Literal[False] = False
    externally_attested_preregistration: Literal[False] = False
    promoted_to_paper_or_live: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    evidence_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_native_audit(self) -> NativeRegisteredTrialAuditEvidence:
        if self.holdout_binding.plan_fingerprint != self.freeze.plan_fingerprint:
            raise ValueError("native audit holdout binding differs from plan")
        cohort = self.native_baseline.baseline.cohort
        if (
            self.holdout_binding.training_export_fingerprint
            != cohort.training_export_fingerprint
            or self.holdout_binding.holdout_export_fingerprint
            != cohort.holdout_export_fingerprint
        ):
            raise ValueError("native audit holdout export differs from frozen source")
        _check_native_registration(
            self.freeze, self.trial_budget, self.native_baseline,
            self.evaluated_native_models, self.trial_dispositions,
        )
        recomputed = audit_registered_trials(
            self.freeze, self.trial_budget,
            self.native_baseline.directional.candidate,
            self.trial_dispositions,
            [item.directional.candidate for item in self.evaluated_native_models],
            evaluation_started_at=self.evaluation_started_at,
            economic_config=self.economic_policy,
        )
        if self.audit != recomputed:
            raise ValueError("native registered trial audit differs from recomputed audit")
        if self.evidence_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"evidence_fingerprint"})
        ):
            raise ValueError("native registered trial audit fingerprint mismatch")
        return self


def audit_native_registered_trials(
    freeze: IndiaHoldoutFreezePlan,
    budget: RegisteredTrialBudget,
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    holdout_config: IndiaHoldoutConfig,
    native_baseline: NativeCausalBaselineTournamentSubmission,
    native_challengers: Sequence[NativeForecastTournamentSubmission],
    dispositions: Sequence[TrialOutcome],
    *,
    evaluation_started_at: datetime,
    economic_config: IncrementalValueConfig | None = None,
) -> NativeRegisteredTrialAuditEvidence:
    """Bind prospectively declared trials and native results to source/fee custody.

    Only local consistency is checked; the claimed registration/source times
    are NOT a trusted independent timestamp or broker market-data attestation.
    """
    frozen = IndiaHoldoutFreezePlan.model_validate(freeze.model_dump(mode="python"))
    plan = RegisteredTrialBudget.model_validate(budget.model_dump(mode="python"))
    baseline = NativeCausalBaselineTournamentSubmission.model_validate(
        native_baseline.model_dump(mode="python")
    )
    challengers = [
        NativeForecastTournamentSubmission.model_validate(item.model_dump(mode="python"))
        for item in native_challengers
    ]
    outcomes = [
        TrialOutcome.model_validate(item.model_dump(mode="python"))
        for item in dispositions
    ]
    market_binding = bind_india_holdout_to_plan(
        frozen, training_export, holdout_export,
        fee_book, target, holdout_config,
    )
    if (
        baseline.baseline.cohort.training_export_fingerprint
        != market_binding.training_export_fingerprint
        or baseline.baseline.cohort.holdout_export_fingerprint
        != market_binding.holdout_export_fingerprint
    ):
        raise ValueError("native baseline raw source does not match frozen market exports")
    if baseline.baseline.target.fingerprint != target.fingerprint:
        raise ValueError("native baseline target changed from source binding")
    if baseline.baseline.cohort.fee_book_fingerprint != fee_book.book_fingerprint:
        raise ValueError("native baseline fee schedule changed from market freeze")
    _check_native_registration(frozen, plan, baseline, challengers, outcomes)
    policy = economic_config or IncrementalValueConfig()
    report = audit_registered_trials(
        frozen, plan, baseline.directional.candidate,
        outcomes, [item.directional.candidate for item in challengers],
        evaluation_started_at=evaluation_started_at,
        economic_config=policy,
    )
    payload = {
        "contract_version": VERSION,
        "freeze": frozen.model_dump(mode="json"),
        "holdout_binding": market_binding.model_dump(mode="json"),
        "trial_budget": plan.model_dump(mode="json"),
        "native_baseline": baseline.model_dump(mode="json"),
        "evaluated_native_models": [
            item.model_dump(mode="json") for item in challengers
        ],
        "trial_dispositions": [
            item.model_dump(mode="json") for item in outcomes
        ],
        "evaluation_started_at": evaluation_started_at,
        "economic_policy": policy.model_dump(mode="json"),
        "audit": report.model_dump(mode="json"),
        "independently_verified_market_custody": False,
        "independently_excluded_hidden_trials": False,
        "externally_attested_preregistration": False,
        "promoted_to_paper_or_live": False,
        "can_authorize_capital": False,
    }
    return NativeRegisteredTrialAuditEvidence(
        **payload,
        evidence_fingerprint=canonical_fingerprint({
            **payload,
            "evaluation_started_at": evaluation_started_at.isoformat(),
        }),
    )
