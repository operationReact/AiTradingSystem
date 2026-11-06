"""V4-08.2 bounded offline matrix execution over V4-02 Java experiment specs.

This invokes an injected local research runner once per candidate in canonical
order. It never calls an AWS worker, Java HTTP service, broker or a production
execution path. Source metrics and trial receipts are self-declared; no
external custody or model promotion is inferred from successful local calls.
"""
from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal, Protocol
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from .fingerprints import canonical_fingerprint
from .v4_model_pareto_tournament import (
    Hash64,
    MultiobjectiveTournamentConfig,
    MultiobjectiveTournamentReportV1,
    TournamentCandidateV1,
    TournamentMetricsV1,
    TournamentRosterV1,
    evaluate_research_pareto_tournament,
)

MATRIX_VERSION = "v4-offline-experiment-matrix.v1"
RUN_VERSION = "v4-offline-experiment-matrix-run.v1"


class JavaDatasetBinding(BaseModel):
    """V4-02 binding: rejecting non-point-in-time sources is mandatory."""

    model_config = ConfigDict(extra="ignore", alias_generator=to_camel, populate_by_name=True)

    point_in_time: Literal[True]


class JavaExperimentSpecView(BaseModel):
    """Only immutable fields from ExperimentSpecService.ExperimentSpecView."""

    model_config = ConfigDict(
        extra="ignore", frozen=True, alias_generator=to_camel, populate_by_name=True
    )

    contract_version: Literal["experiment-spec.v1"]
    experiment_id: UUID
    model_version_id: UUID
    from_at: datetime
    to_at: datetime
    seed: int
    dataset: JavaDatasetBinding
    dataset_binding_fingerprint: Hash64
    target_binding_fingerprint: Hash64
    fold_binding_fingerprint: Hash64
    economic_cost_binding_fingerprint: Hash64
    spec_fingerprint: Hash64
    created_at: datetime
    can_authorize_capital: Literal[False]

    @model_validator(mode="after")
    def validate_times(self) -> JavaExperimentSpecView:
        for value in (self.from_at, self.to_at, self.created_at):
            if value.tzinfo is None or value.utcoffset() is None:
                raise ValueError("Java experiment spec timestamps must be timezone-aware")
        if not self.from_at < self.to_at:
            raise ValueError("experiment evaluation window must be increasing")
        if not -(2**63) <= self.seed < 2**63:
            raise ValueError("experiment seed must be Java signed long")
        return self


class MatrixSlot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9._-]{1,64}$")]
    experiment: JavaExperimentSpecView


class OfflineExperimentMatrixV1(BaseModel):
    """Immutable prospective linkage from V4-08.1 to V4-02 experiment specs."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-offline-experiment-matrix.v1"] = MATRIX_VERSION
    roster: TournamentRosterV1
    slots: list[MatrixSlot] = Field(min_length=2, max_length=12)
    matrix_fingerprint: Hash64

    @model_validator(mode="after")
    def frozen_cohort(self) -> OfflineExperimentMatrixV1:
        roster = self.roster
        if [slot.candidate_id for slot in self.slots] != roster.declared_candidate_ids:
            raise ValueError("matrix must cover the exact sorted registered roster")
        if len({slot.experiment.experiment_id for slot in self.slots}) != len(self.slots):
            raise ValueError("matrix cannot reuse an experiment spec for multiple candidates")
        if len({slot.experiment.model_version_id for slot in self.slots}) != len(self.slots):
            raise ValueError("matrix candidates must reference distinct model versions")

        baseline = next(
            slot.experiment for slot in self.slots
            if slot.candidate_id == roster.baseline_candidate_id
        )
        if baseline.spec_fingerprint != roster.source_experiment_spec_fingerprint:
            raise ValueError("baseline V4-02 spec fingerprint differs from frozen roster")

        for slot in self.slots:
            spec = slot.experiment
            if spec.created_at > roster.registered_at:
                raise ValueError("matrix cannot register an experiment created after roster freeze")
            for java_field, frozen_field in (
                ("dataset_binding_fingerprint", "dataset_fingerprint"),
                ("target_binding_fingerprint", "target_fingerprint"),
                ("fold_binding_fingerprint", "fold_fingerprint"),
                ("economic_cost_binding_fingerprint", "economic_cost_fingerprint"),
            ):
                if getattr(spec, java_field) != getattr(roster, frozen_field):
                    raise ValueError(f"matrix cohort mismatch: {java_field}")
            if (spec.from_at, spec.to_at, spec.seed) != (
                baseline.from_at, baseline.to_at, baseline.seed
            ):
                raise ValueError("matrix experiment window or seed differs from baseline")

        payload = self.model_dump(mode="json", exclude={"matrix_fingerprint"})
        if canonical_fingerprint(payload) != self.matrix_fingerprint:
            raise ValueError("frozen matrix fingerprint mismatch")
        return self


def freeze_offline_experiment_matrix(
    roster: TournamentRosterV1,
    slots: Sequence[MatrixSlot],
) -> OfflineExperimentMatrixV1:
    """Validate the exact predeclared experiment matrix without dispatching jobs."""
    payload = {
        "contract_version": MATRIX_VERSION,
        "roster": roster.model_dump(mode="json"),
        "slots": [
            slot.model_dump(mode="json") for slot in sorted(
                slots, key=lambda entry: entry.candidate_id
            )
        ],
    }
    return OfflineExperimentMatrixV1(
        **payload,
        matrix_fingerprint=canonical_fingerprint(payload),
    )


class OfflineRunnerReceipt(BaseModel):
    """Receipt supplied by a local runner; **not** independently witnessed."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    experiment_id: UUID
    model_version_id: UUID
    spec_fingerprint: Hash64
    source_result_fingerprint: Hash64
    completed_at: datetime
    metrics: TournamentMetricsV1
    execution_mode: Literal["LOCAL_OFFLINE_RESEARCH"] = "LOCAL_OFFLINE_RESEARCH"
    receipt_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_receipt(self) -> OfflineRunnerReceipt:
        if self.completed_at.tzinfo is None or self.completed_at.utcoffset() is None:
            raise ValueError("runner completion timestamp must be timezone-aware")
        payload = self.model_dump(mode="python", exclude={"receipt_fingerprint"})
        payload["experiment_id"] = str(self.experiment_id)
        payload["model_version_id"] = str(self.model_version_id)
        payload["completed_at"] = self.completed_at.isoformat()
        payload["metrics"] = self.metrics.model_dump(mode="json")
        if canonical_fingerprint(payload) != self.receipt_fingerprint:
            raise ValueError("runner receipt fingerprint mismatch")
        return self


class OfflineResearchRunner(Protocol):
    """Explicit local research entrypoint; never a broker worker or implicit retry."""

    def run(
        self, *, candidate_id: str, experiment: JavaExperimentSpecView
    ) -> OfflineRunnerReceipt: ...


class MatrixRunSlotResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate_id: str
    experiment_id: UUID
    status: Literal["LOCAL_RECEIPT_ACCEPTED", "RESEARCH_RUN_FAILED"]
    receipt_fingerprint: Hash64 | None
    failure_reason: Literal[
        "LOCAL_RUNNER_FAILED_OR_INVALID_RECEIPT", "NONE"
    ]


class OfflineExperimentMatrixReportV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-offline-experiment-matrix-run.v1"] = RUN_VERSION
    evaluated_as_of: datetime
    matrix_fingerprint: Hash64
    slot_results: list[MatrixRunSlotResult] = Field(min_length=2, max_length=12)
    pareto_report: MultiobjectiveTournamentReportV1 | None
    status: Literal["OFFLINE_RESEARCH_COMPARISON", "INCONCLUSIVE"]
    execution_mode: Literal["LOCAL_OFFLINE_RESEARCH"] = "LOCAL_OFFLINE_RESEARCH"
    external_worker_verified: Literal[False] = False
    experiment_result_db_verified: Literal[False] = False
    independent_market_custody_verified: Literal[False] = False
    automatic_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def report_integrity(self) -> OfflineExperimentMatrixReportV1:
        if self.evaluated_as_of.tzinfo is None or self.evaluated_as_of.utcoffset() is None:
            raise ValueError("matrix cutoff must be timezone-aware")
        if self.slot_results != sorted(
            self.slot_results, key=lambda result: result.candidate_id
        ):
            raise ValueError("matrix run result order is not canonical")
        if self.pareto_report is None:
            raise ValueError("matrix must retain a complete inconclusive/Pareto report")
        if (self.status == "OFFLINE_RESEARCH_COMPARISON") != (
            self.pareto_report.status == "RESEARCH_PARETO_FRONT_ONLY"
        ):
            raise ValueError("comparison status must match attached Pareto report")
        payload = self.model_dump(mode="python", exclude={"report_fingerprint"})
        payload["evaluated_as_of"] = self.evaluated_as_of.isoformat()
        payload["slot_results"] = [
            slot.model_dump(mode="json") for slot in self.slot_results
        ]
        payload["pareto_report"] = (
            self.pareto_report.model_dump(mode="json")
            if self.pareto_report is not None else None
        )
        if canonical_fingerprint(payload) != self.report_fingerprint:
            raise ValueError("matrix run report fingerprint mismatch")
        return self


def run_offline_experiment_matrix(
    matrix: OfflineExperimentMatrixV1,
    *,
    runner: OfflineResearchRunner,
    evaluated_as_of: datetime,
    tournament_config: MultiobjectiveTournamentConfig | None = None,
) -> OfflineExperimentMatrixReportV1:
    """Invoke each predeclared local runner exactly once; fail closed per slot.

    Does not authenticate HTTP worker credentials, create Java experiment result
    records, or persist job state. A failed runner is recorded, never retried.
    """
    frozen = OfflineExperimentMatrixV1.model_validate(matrix.model_dump(mode="python"))
    if evaluated_as_of.tzinfo is None or evaluated_as_of.utcoffset() is None:
        raise ValueError("evaluation cutoff must be timezone-aware")
    if evaluated_as_of < frozen.roster.registered_at:
        raise ValueError("matrix cutoff precedes frozen roster registration")

    entries: list[MatrixRunSlotResult] = []
    candidates: list[TournamentCandidateV1] = []
    for slot in frozen.slots:
        try:
            raw = runner.run(candidate_id=slot.candidate_id, experiment=slot.experiment)
            receipt = OfflineRunnerReceipt.model_validate(raw.model_dump(mode="python"))
            if (
                receipt.experiment_id != slot.experiment.experiment_id
                or receipt.model_version_id != slot.experiment.model_version_id
                or receipt.spec_fingerprint != slot.experiment.spec_fingerprint
            ):
                raise ValueError("runner receipt was not bound to this registered spec")
            if not frozen.roster.registered_at < receipt.completed_at <= evaluated_as_of:
                raise ValueError("runner receipt outside causal tournament window")
            payload = {
                "candidate_id": slot.candidate_id,
                "model_version_fingerprint": canonical_fingerprint({
                    "v4_02_model_version_id": str(slot.experiment.model_version_id)
                }),
                "source_result_fingerprint": receipt.source_result_fingerprint,
                "registration_fingerprint": frozen.roster.registration_fingerprint,
                "dataset_fingerprint": frozen.roster.dataset_fingerprint,
                "target_fingerprint": frozen.roster.target_fingerprint,
                "fold_fingerprint": frozen.roster.fold_fingerprint,
                "economic_cost_fingerprint": frozen.roster.economic_cost_fingerprint,
                "holdout_cohort_fingerprint": frozen.roster.holdout_cohort_fingerprint,
                "evaluated_at": receipt.completed_at.isoformat(),
                "metrics": receipt.metrics.model_dump(mode="json"),
                "evidence_status": "SELF_DECLARED_RESEARCH_ONLY",
                "promotion_allowed": False,
                "can_authorize_capital": False,
            }
            candidate = TournamentCandidateV1(
                **payload, evidence_fingerprint=canonical_fingerprint(payload)
            )
            candidates.append(candidate)
            entries.append(MatrixRunSlotResult(
                candidate_id=slot.candidate_id,
                experiment_id=slot.experiment.experiment_id,
                status="LOCAL_RECEIPT_ACCEPTED",
                receipt_fingerprint=receipt.receipt_fingerprint,
                failure_reason="NONE",
            ))
        except Exception:
            # No automatic retry, no exception detail/secret leakage to output.
            entries.append(MatrixRunSlotResult(
                candidate_id=slot.candidate_id,
                experiment_id=slot.experiment.experiment_id,
                status="RESEARCH_RUN_FAILED",
                receipt_fingerprint=None,
                failure_reason="LOCAL_RUNNER_FAILED_OR_INVALID_RECEIPT",
            ))

    # The V4-08.1 comparator itself also marks missing/sparse evidence
    # INCONCLUSIVE and must never silently pick a winner.
    pareto = evaluate_research_pareto_tournament(
        frozen.roster, candidates, evaluated_as_of=evaluated_as_of,
        config=tournament_config,
    )
    status: Literal["OFFLINE_RESEARCH_COMPARISON", "INCONCLUSIVE"] = (
        "OFFLINE_RESEARCH_COMPARISON"
        if pareto.status == "RESEARCH_PARETO_FRONT_ONLY" else "INCONCLUSIVE"
    )
    payload = {
        "contract_version": RUN_VERSION,
        "evaluated_as_of": evaluated_as_of.isoformat(),
        "matrix_fingerprint": frozen.matrix_fingerprint,
        "slot_results": [entry.model_dump(mode="json") for entry in entries],
        "pareto_report": pareto.model_dump(mode="json"),
        "status": status,
        "execution_mode": "LOCAL_OFFLINE_RESEARCH",
        "external_worker_verified": False,
        "experiment_result_db_verified": False,
        "independent_market_custody_verified": False,
        "automatic_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return OfflineExperimentMatrixReportV1(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )
