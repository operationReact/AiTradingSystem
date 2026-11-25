from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .incremental_economic_value import IncrementalValueConfig
from .india_holdout_freeze import IndiaHoldoutFreezePlan
from .structured_alpha_tournament import (
    TournamentCandidate,
    screen_structured_alpha_tournament,
)

PLAN_VERSION = "structured-alpha-trial-budget.v1"
REPORT_VERSION = "structured-alpha-trial-selection-audit.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Disposition = Literal["EVALUATED", "TRAINING_FAILED", "CANCELLED_BEFORE_EVALUATION"]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("trial audit timestamps must be timezone-aware")
    return value


class RegisteredTrial(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate_id: Annotated[str, Field(min_length=1, max_length=128)]
    model_version_ref: Annotated[str, Field(min_length=1, max_length=128)]
    family: Literal["LIGHTGBM", "PYTORCH", "CATBOOST"]
    parameter_spec_fingerprint: Hash64


class RegisteredTrialBudget(BaseModel):
    """A declared fixed family, not proof that undisclosed research never occurred."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["structured-alpha-trial-budget.v1"] = PLAN_VERSION
    holdout_freeze_plan_fingerprint: Hash64
    baseline_candidate_id: Annotated[str, Field(min_length=1, max_length=128)]
    baseline_model_version_ref: Annotated[str, Field(min_length=1, max_length=128)]
    registered_at: datetime
    trials: list[RegisteredTrial] = Field(min_length=1, max_length=20)
    externally_timestamped: Literal[False] = False
    repeated_undeclared_trials_excluded: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    budget_fingerprint: Hash64

    @field_validator("registered_at")
    @classmethod
    def aware_registration(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def check_budget(self) -> RegisteredTrialBudget:
        ids = [trial.candidate_id for trial in self.trials]
        if ids != sorted(set(ids)):
            raise ValueError("registered trials must have unique, sorted candidate IDs")
        if self.baseline_candidate_id in ids:
            raise ValueError("baseline cannot be listed as a challenger trial")
        if self.budget_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"budget_fingerprint"})
        ):
            raise ValueError("registered trial budget fingerprint mismatch")
        return self


class TrialOutcome(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate_id: str
    disposition: Disposition
    model_source_result_fingerprint: Hash64 | None = None

    @model_validator(mode="after")
    def validate_outcome(self) -> TrialOutcome:
        if (self.disposition == "EVALUATED") != (
            self.model_source_result_fingerprint is not None
        ):
            raise ValueError("evaluated trials require an experiment-result fingerprint")
        return self


class RegisteredTrialAuditReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["structured-alpha-trial-selection-audit.v1"] = REPORT_VERSION
    trial_budget_fingerprint: Hash64
    freeze_plan_fingerprint: Hash64
    baseline_source_result_fingerprint: Hash64
    trial_outcomes_fingerprint: Hash64
    total_declared_trial_budget: Annotated[int, Field(ge=1, le=20)]
    observed_trial_dispositions: Annotated[int, Field(ge=0, le=20)]
    evaluated_trial_count: Annotated[int, Field(ge=0, le=20)]
    confidence_after_declared_trials: float | None
    tournament_report_fingerprint: Hash64 | None
    research_shortlisted_ids: list[str]
    status: Literal["RESEARCH_SHORTLIST_ONLY", "NO_ADDITIVE_RESEARCH", "INCONCLUSIVE"]
    reason_codes: list[str]
    declared_trials_familywise_adjusted: bool
    undeclared_trials_independently_excluded: Literal[False] = False
    independently_verified_market_source: Literal[False] = False
    externally_timestamped: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_report(self) -> RegisteredTrialAuditReport:
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("trial audit reasons must be canonical")
        if self.research_shortlisted_ids != sorted(set(self.research_shortlisted_ids)):
            raise ValueError("trial audit shortlist must be sorted and unique")
        if self.status == "RESEARCH_SHORTLIST_ONLY":
            if self.reason_codes or not self.research_shortlisted_ids:
                raise ValueError("shortlisting requires no failed gates")
        elif not self.reason_codes or self.research_shortlisted_ids:
            raise ValueError("non-shortlisted audit requires reasons and no shortlist")
        if self.declared_trials_familywise_adjusted != (
            self.tournament_report_fingerprint is not None
        ):
            raise ValueError("adjusted trial audit must bind the actual tournament")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("trial audit report fingerprint mismatch")
        return self


def register_trial_budget(
    freeze: IndiaHoldoutFreezePlan,
    trials: Sequence[RegisteredTrial],
    *,
    baseline_candidate_id: str,
    baseline_model_version_ref: str,
    registered_at: datetime,
) -> RegisteredTrialBudget:
    """Commit a fixed set of challenger configurations before any holdout observations."""
    frozen = IndiaHoldoutFreezePlan.model_validate(freeze.model_dump(mode="python"))
    _aware(registered_at)
    if not baseline_candidate_id or not baseline_model_version_ref:
        raise ValueError("baseline ID and frozen model version must be predeclared")
    if not frozen.recorded_at <= registered_at < frozen.planned_holdout_from:
        raise ValueError("trial budget must be recorded before the holdout opens")
    planned = sorted(trials, key=lambda trial: trial.candidate_id)
    if not set(trial.family for trial in planned).issubset(frozen.model_families):
        raise ValueError("trial model family was not included in the freeze plan")
    if len(planned) != len(set(trial.candidate_id for trial in planned)):
        raise ValueError("trial candidate IDs must be unique")
    payload = {
        "contract_version": PLAN_VERSION,
        "holdout_freeze_plan_fingerprint": frozen.plan_fingerprint,
        "baseline_candidate_id": baseline_candidate_id,
        "baseline_model_version_ref": baseline_model_version_ref,
        "registered_at": registered_at,
        "trials": [trial.model_dump(mode="json") for trial in planned],
        "externally_timestamped": False,
        "repeated_undeclared_trials_excluded": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    dummy = RegisteredTrialBudget.model_construct(**payload, budget_fingerprint="0" * 64)
    return RegisteredTrialBudget(
        **payload,
        budget_fingerprint=canonical_fingerprint(
            dummy.model_dump(mode="json", exclude={"budget_fingerprint"})
        ),
    )


def audit_registered_trials(
    freeze: IndiaHoldoutFreezePlan,
    budget: RegisteredTrialBudget,
    baseline: TournamentCandidate,
    outcomes: Sequence[TrialOutcome],
    evaluated: Sequence[TournamentCandidate],
    *,
    evaluation_started_at: datetime,
    economic_config: IncrementalValueConfig | None = None,
) -> RegisteredTrialAuditReport:
    """Screen a *declared* family including failed/abandoned trials.

    This is not a detector of hidden research attempts: unlogged selection,
    data snooping and independent market verification remain unproven.
    """
    _aware(evaluation_started_at)
    frozen = IndiaHoldoutFreezePlan.model_validate(freeze.model_dump(mode="python"))
    plan = RegisteredTrialBudget.model_validate(budget.model_dump(mode="python"))
    reference = TournamentCandidate.model_validate(baseline.model_dump(mode="python"))
    attempts = [TrialOutcome.model_validate(outcome.model_dump(mode="python"))
                for outcome in outcomes]
    challengers = [TournamentCandidate.model_validate(c.model_dump(mode="python"))
                   for c in evaluated]
    if plan.holdout_freeze_plan_fingerprint != frozen.plan_fingerprint:
        raise ValueError("trial budget is not bound to this frozen India holdout")
    if not frozen.recorded_at <= plan.registered_at < frozen.planned_holdout_from:
        raise ValueError("trial budget was registered after holdout opened")
    if not {trial.family for trial in plan.trials}.issubset(frozen.model_families):
        raise ValueError("trial budget contains a model family absent from frozen plan")
    if evaluation_started_at < frozen.planned_holdout_to:
        raise ValueError("trial screening cannot begin before holdout window ends")
    if (reference.prediction.candidate_id != plan.baseline_candidate_id
            or reference.prediction.model_version_ref != plan.baseline_model_version_ref):
        raise ValueError("baseline does not match registered research budget")
    for candidate in [reference, *challengers]:
        times = candidate.returns.decision_times
        if not frozen.planned_holdout_from <= min(times) or (
            max(times) >= frozen.planned_holdout_to
        ):
            raise ValueError("candidate evidence is outside frozen holdout window")
    if len(attempts) != len({outcome.candidate_id for outcome in attempts}):
        raise ValueError("trial outcomes must have unique candidate identities")
    if len(challengers) != len({c.prediction.candidate_id for c in challengers}):
        raise ValueError("evaluated candidates cannot appear more than once")
    declared = {trial.candidate_id: trial for trial in plan.trials}
    submitted = {outcome.candidate_id: outcome for outcome in attempts}
    actual = {candidate.prediction.candidate_id: candidate for candidate in challengers}
    if not set(submitted).issubset(declared):
        raise ValueError("unregistered trial dispositions are prohibited")
    if not set(actual).issubset(declared):
        raise ValueError("unregistered evaluated candidates are prohibited")
    expected_evaluated = {
        trial_id for trial_id, outcome in submitted.items()
        if outcome.disposition == "EVALUATED"
    }
    if set(actual) != expected_evaluated:
        raise ValueError("evaluated result evidence must match recorded dispositions")
    for trial_id, candidate in actual.items():
        slot = declared[trial_id]
        outcome = submitted[trial_id]
        if (
            candidate.prediction.model_version_ref != slot.model_version_ref
            or candidate.prediction.source_result_fingerprint
            != outcome.model_source_result_fingerprint
        ):
            raise ValueError("trial source result or version differs from registration")

    policy = economic_config or IncrementalValueConfig()
    count = len(declared)
    confidence = 1.0 - (1.0 - policy.confidence_level) / count
    problems: set[str] = set()
    if set(submitted) != set(declared):
        problems.add("UNACCOUNTED_DECLARED_TRIALS")
    if not actual:
        problems.add("NO_EVALUATED_CHALLENGERS")
    if confidence > 0.99 + 1e-12:
        problems.add("TRIAL_BUDGET_EXCEEDS_SUPPORTED_CONFIDENCE")
    tournament = None
    if not problems:
        # The underlying tournament already adjusts over evaluated candidates.
        # Increase its input confidence so its final correction accounts for
        # the entire registered budget, including failed/abandoned attempts.
        base_confidence = 1 - (1 - policy.confidence_level) * len(actual) / count
        adjusted_policy = IncrementalValueConfig.model_validate({
            **policy.model_dump(mode="python"),
            "confidence_level": base_confidence,
        })
        tournament = screen_structured_alpha_tournament(
            reference,
            [actual[trial_id] for trial_id in sorted(actual)],
            economic_config=adjusted_policy,
        )
        if tournament.status != "RESEARCH_SHORTLIST_ONLY":
            problems.add("NO_CLEAR_ADDITIVE_ECONOMIC_EVIDENCE")
    status = (
        "INCONCLUSIVE" if tournament is None or tournament.status == "INCONCLUSIVE"
        else "RESEARCH_SHORTLIST_ONLY"
        if tournament.status == "RESEARCH_SHORTLIST_ONLY"
        else "NO_ADDITIVE_RESEARCH"
    )
    shortlisted = (
        tournament.shortlisted_candidate_ids
        if status == "RESEARCH_SHORTLIST_ONLY" else []
    )
    if status == "INCONCLUSIVE" and tournament is not None:
        problems.add("TOURNAMENT_INCONCLUSIVE")
    payload = {
        "contract_version": REPORT_VERSION,
        "trial_budget_fingerprint": plan.budget_fingerprint,
        "freeze_plan_fingerprint": frozen.plan_fingerprint,
        "baseline_source_result_fingerprint": reference.prediction.source_result_fingerprint,
        "trial_outcomes_fingerprint": canonical_fingerprint([
            outcome.model_dump(mode="json")
            for outcome in sorted(attempts, key=lambda row: row.candidate_id)
        ]),
        "total_declared_trial_budget": count,
        "observed_trial_dispositions": len(attempts),
        "evaluated_trial_count": len(actual),
        "confidence_after_declared_trials": confidence if tournament else None,
        "tournament_report_fingerprint": (
            tournament.report_fingerprint if tournament else None
        ),
        "research_shortlisted_ids": shortlisted,
        "status": status,
        "reason_codes": sorted(problems),
        "declared_trials_familywise_adjusted": tournament is not None,
        "undeclared_trials_independently_excluded": False,
        "independently_verified_market_source": False,
        "externally_timestamped": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return RegisteredTrialAuditReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )
