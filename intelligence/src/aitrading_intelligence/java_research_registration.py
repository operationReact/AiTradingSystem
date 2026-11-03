from __future__ import annotations

import argparse
import json
import os
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .india_independent_holdout import IndiaHoldoutReport
from .xgboost_benchmark import XGBoostConditionalBenchmarkReport

BRIDGE_VERSION = "python-research-result-bridge.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
RunnerVersion = Literal[
    "india-independent-holdout.v1",
    "conditional-xgboost-benchmark.v1",
]


class JavaDatasetBinding(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    datasetFingerprint: Hash64
    pointInTime: Literal[True]
    asOf: datetime


class JavaTargetBinding(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    targetName: Literal["forward-return-after-costs.v1"]
    labelDefinitionFingerprint: Hash64
    horizonBars: Annotated[int, Field(ge=1, le=78)]


class JavaFoldBinding(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    protocol: Literal["CHRONOLOGICAL"]
    folds: Annotated[int, Field(ge=2)]
    purgeBars: Literal[0] = 0
    embargoBars: Literal[0] = 0


class JavaExperimentSnapshot(BaseModel):
    """Required subset of GET /api/admin/model-governance/experiments/{id}."""

    model_config = ConfigDict(extra="ignore", frozen=True)

    experimentId: UUID
    modelVersionId: UUID
    specFingerprint: Hash64
    fromAt: datetime
    toAt: datetime
    dataset: JavaDatasetBinding
    target: JavaTargetBinding
    folds: JavaFoldBinding
    canAuthorizeCapital: Literal[False]

    @model_validator(mode="after")
    def check_window(self) -> JavaExperimentSnapshot:
        if self.fromAt >= self.toAt:
            raise ValueError("Java experiment fromAt must precede toAt")
        return self


class JavaExperimentJobSnapshot(BaseModel):
    """Required subset of a claimed and started Java orchestration job."""

    model_config = ConfigDict(extra="ignore", frozen=True)

    jobId: UUID
    experimentId: UUID
    specFingerprint: Hash64
    runnerType: Literal["PYTHON_INTELLIGENCE"]
    runnerVersion: RunnerVersion
    orchestrationFingerprint: Hash64
    status: Literal["RUNNING"]
    claimToken: UUID
    leaseExpiresAt: datetime
    canAuthorizeCapital: Literal[False]


class JavaRegisterResultRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    claimToken: UUID
    assessment: Literal["INCONCLUSIVE"] = "INCONCLUSIVE"
    sampleCount: Annotated[int, Field(ge=0)]
    resultArtifactFingerprint: Hash64
    metricsFingerprint: Hash64
    evidenceFingerprint: Hash64


class JavaResearchRegistrationEnvelope(BaseModel):
    """Non-network handoff; the Java server remains authoritative at POST."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["python-research-result-bridge.v1"] = BRIDGE_VERSION
    job_id: UUID
    experiment_id: UUID
    spec_fingerprint: Hash64
    dataset_fingerprint: Hash64
    runner_version: RunnerVersion
    holdout_report_fingerprint: Hash64
    primary_report_fingerprint: Hash64
    request: JavaRegisterResultRequest
    external_market_source_verified: Literal[False] = False
    holdout_pre_registration_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    envelope_fingerprint: Hash64

    @model_validator(mode="after")
    def check_envelope(self) -> JavaResearchRegistrationEnvelope:
        # Never hash or log the Java worker lease's claim token.
        public = self.model_dump(mode="json", exclude={
            "envelope_fingerprint": True, "request": {"claimToken"},
        })
        if self.envelope_fingerprint != canonical_fingerprint(public):
            raise ValueError("research registration envelope fingerprint mismatch")
        if self.request.resultArtifactFingerprint != self.primary_report_fingerprint:
            raise ValueError("registered artifact must be the actual research report")
        if self.request.assessment != "INCONCLUSIVE":
            raise ValueError("unverified research may never register PASS")
        return self


def _aware(value: datetime, field: str) -> None:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{field} must be timezone-aware")


def derive_holdout_dataset_fingerprint(report: IndiaHoldoutReport) -> str:
    """Stable dataset identity for a pre-registered Java experiment spec.

    Not a proof of provider authenticity; only binds a fixed pair of exports.
    """
    verified = IndiaHoldoutReport.model_validate(report.model_dump(mode="python"))
    return canonical_fingerprint({
        "contract_version": "independent-holdout-dataset-binding.v1",
        "training_export_fingerprint": verified.train_export_fingerprint,
        "holdout_export_fingerprint": verified.holdout_export_fingerprint,
        "exchange": verified.exchange,
        "trading_symbol": verified.trading_symbol,
        "instrument_id": verified.instrument_id,
    })


def build_java_research_result_registration(
    *,
    experiment: JavaExperimentSnapshot,
    job: JavaExperimentJobSnapshot,
    holdout: IndiaHoldoutReport,
    xgboost: XGBoostConditionalBenchmarkReport | None = None,
    now: datetime,
) -> JavaResearchRegistrationEnvelope:
    """Validate frozen Python research evidence against Java's immutable job/spec.

    The returned payload is for the existing ADMIN-only POST /experiment-jobs/{id}/result.
    No HTTP request is made. Java must recheck the lease token, RBAC, job state
    and model governance when the authorized worker submits the payload.
    """
    _aware(now, "now")
    exp = JavaExperimentSnapshot.model_validate(experiment.model_dump(mode="python"))
    current_job = JavaExperimentJobSnapshot.model_validate(job.model_dump(mode="python"))
    source = IndiaHoldoutReport.model_validate(holdout.model_dump(mode="python"))
    model = (
        XGBoostConditionalBenchmarkReport.model_validate(xgboost.model_dump(mode="python"))
        if xgboost is not None else None
    )
    for name, date_value in (
        ("fromAt", exp.fromAt), ("toAt", exp.toAt), ("dataset.asOf", exp.dataset.asOf),
        ("leaseExpiresAt", current_job.leaseExpiresAt),
    ):
        _aware(date_value, name)
    if current_job.leaseExpiresAt <= now:
        raise ValueError("Java worker claim lease has expired")
    if current_job.experimentId != exp.experimentId:
        raise ValueError("Java job must belong to the registered experiment")
    if current_job.specFingerprint != exp.specFingerprint:
        raise ValueError("Java job/spec fingerprint mismatch")
    required_runner: RunnerVersion = (
        "conditional-xgboost-benchmark.v1"
        if model is not None else "india-independent-holdout.v1"
    )
    if current_job.runnerVersion != required_runner:
        raise ValueError("Java runner version must match the exact source report contract")
    if model is not None and model.india_holdout_report_fingerprint != source.report_fingerprint:
        raise ValueError("XGBoost source must reference the supplied immutable holdout")
    dataset_fingerprint = derive_holdout_dataset_fingerprint(source)
    if exp.dataset.datasetFingerprint != dataset_fingerprint:
        raise ValueError("Java dataset fingerprint does not match the frozen market exports")
    if exp.target.labelDefinitionFingerprint != source.target_spec_fingerprint:
        raise ValueError("Java target label fingerprint does not match the Python target")
    if source.holdout_first_decision_at is not None:
        _aware(source.holdout_first_decision_at, "holdout_first_decision_at")
        if not exp.fromAt <= source.holdout_first_decision_at < exp.toAt:
            raise ValueError("independent holdout starts outside registered experiment window")
        if exp.dataset.asOf < source.holdout_first_decision_at:
            raise ValueError("Java dataset cutoff precedes the independent holdout")
    if source.training_last_label_available_at is not None:
        _aware(source.training_last_label_available_at, "training_last_label_available_at")
        if (
            source.holdout_first_decision_at is not None
            and source.training_last_label_available_at >= source.holdout_first_decision_at
        ):
            raise ValueError("training label lineage leaks into independent holdout")

    primary = model if model is not None else source
    primary_fingerprint = primary.report_fingerprint
    metric_payload: object = (
        [metric.model_dump(mode="json") for metric in source.results]
        if model is None else {
            "status": model.status,
            "paired_screen": model.joint_screen_report_fingerprint,
            "net_uplift": model.total_net_uplift_after_costs,
            "calibration": model.xgboost_brier_score,
            "max_drawdown": model.xgboost_max_drawdown,
            "inference_ms_per_1000": model.xgboost_inference_ms_per_1000_rows,
        }
    )
    sample_count = source.holdout_rows if model is None else model.evaluated_holdout_rows
    if model is not None and sample_count != source.holdout_rows:
        raise ValueError("XGBoost evaluated rows must match the registered held-out dataset")
    provenance = {
        "contract_version": BRIDGE_VERSION,
        "experiment_id": str(exp.experimentId),
        "model_version_id": str(exp.modelVersionId),
        "spec_fingerprint": exp.specFingerprint,
        "orchestration_fingerprint": current_job.orchestrationFingerprint,
        "runner_version": current_job.runnerVersion,
        "dataset_fingerprint": dataset_fingerprint,
        "india_fee_book_fingerprint": source.india_fee_book_fingerprint,
        "target_spec_fingerprint": source.target_spec_fingerprint,
        "config_fingerprint": source.config_fingerprint,
        "holdout_report_fingerprint": source.report_fingerprint,
        "primary_report_fingerprint": primary_fingerprint,
        "sample_count": sample_count,
        "external_market_source_verified": False,
        "holdout_pre_registration_verified": False,
        "fold_protocol_independently_verified": False,
        "assessment": "INCONCLUSIVE",
        "can_authorize_capital": False,
    }
    request = JavaRegisterResultRequest(
        claimToken=current_job.claimToken,
        sampleCount=sample_count,
        resultArtifactFingerprint=primary_fingerprint,
        metricsFingerprint=canonical_fingerprint(metric_payload),
        evidenceFingerprint=canonical_fingerprint(provenance),
    )
    public = {
        "contract_version": BRIDGE_VERSION,
        "job_id": current_job.jobId,
        "experiment_id": exp.experimentId,
        "spec_fingerprint": exp.specFingerprint,
        "dataset_fingerprint": dataset_fingerprint,
        "runner_version": current_job.runnerVersion,
        "holdout_report_fingerprint": source.report_fingerprint,
        "primary_report_fingerprint": primary_fingerprint,
        "request": request,
        "external_market_source_verified": False,
        "holdout_pre_registration_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    serialized = JavaResearchRegistrationEnvelope.model_construct(
        **public, envelope_fingerprint="0" * 64
    )
    public_hash = serialized.model_dump(mode="json", exclude={
        "envelope_fingerprint": True, "request": {"claimToken"},
    })
    return JavaResearchRegistrationEnvelope(
        **public, envelope_fingerprint=canonical_fingerprint(public_hash)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Prepare an INCONCLUSIVE, not-submitted Java research-result request"
    )
    parser.add_argument("experiment_json", type=Path)
    parser.add_argument("running_job_json", type=Path)
    parser.add_argument("holdout_report_json", type=Path)
    parser.add_argument("output_json", type=Path)
    parser.add_argument("--xgboost-report", type=Path)
    args = parser.parse_args()
    if args.output_json.exists():
        parser.error("refusing to overwrite an existing claim-token-bearing request")
    from datetime import UTC

    report = build_java_research_result_registration(
        experiment=JavaExperimentSnapshot.model_validate_json(
            args.experiment_json.read_text(encoding="utf-8")
        ),
        job=JavaExperimentJobSnapshot.model_validate_json(
            args.running_job_json.read_text(encoding="utf-8")
        ),
        holdout=IndiaHoldoutReport.model_validate_json(
            args.holdout_report_json.read_text(encoding="utf-8")
        ),
        xgboost=(
            XGBoostConditionalBenchmarkReport.model_validate_json(
                args.xgboost_report.read_text(encoding="utf-8")
            )
            if args.xgboost_report is not None else None
        ),
        now=datetime.now(UTC),
    )
    # The request body contains a short-lived worker claim token. Keep it
    # out of stdout, and write the file with restrictive creation permissions.
    handle = os.open(str(args.output_json), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(handle, "w", encoding="utf-8") as target:
        json.dump(report.request.model_dump(mode="json"), target, sort_keys=True)
        target.write("\n")
    print("Created INCONCLUSIVE research request; no network call was made.")


if __name__ == "__main__":
    main()
