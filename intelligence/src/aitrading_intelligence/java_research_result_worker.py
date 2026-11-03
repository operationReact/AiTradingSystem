from __future__ import annotations

import argparse
import json
import os
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Literal
from urllib.parse import urlsplit
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .india_independent_holdout import IndiaHoldoutReport
from .java_research_registration import (
    JavaExperimentJobSnapshot,
    JavaExperimentSnapshot,
    JavaResearchRegistrationEnvelope,
    build_java_research_result_registration,
)
from .xgboost_benchmark import XGBoostConditionalBenchmarkReport

API_PREFIX = "/api/admin/model-governance"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class JavaRegistrationError(RuntimeError):
    """Deliberately excludes HTTP bodies, credentials and lease tokens."""


class JavaExperimentResultSnapshot(BaseModel):
    """Subset of the authoritative Java experiment-result.v1 record."""

    model_config = ConfigDict(extra="ignore", frozen=True)

    contractVersion: Literal["experiment-result.v1"]
    resultId: UUID
    jobId: UUID
    experimentId: UUID
    modelVersionId: UUID
    specFingerprint: Hash64
    orchestrationFingerprint: Hash64
    assessment: Literal["INCONCLUSIVE"]
    sampleCount: Annotated[int, Field(ge=0)]
    resultArtifactFingerprint: Hash64
    metricsFingerprint: Hash64
    evidenceFingerprint: Hash64
    resultFingerprint: Hash64
    canAuthorizeCapital: Literal[False]


class JavaResearchResultReceipt(BaseModel):
    """Read-back-verified evidence of a Java result record, not market acceptance."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["java-research-result-receipt.v1"] = (
        "java-research-result-receipt.v1"
    )
    job_id: UUID
    experiment_id: UUID
    result_id: UUID
    result_fingerprint: Hash64
    evidence_fingerprint: Hash64
    assessment: Literal["INCONCLUSIVE"] = "INCONCLUSIVE"
    readback_verified: Literal[True] = True
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    receipt_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_receipt(self) -> JavaResearchResultReceipt:
        if self.receipt_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"receipt_fingerprint"})
        ):
            raise ValueError("Java research receipt fingerprint mismatch")
        return self


def _validated_origin(value: str) -> str:
    """No token-bearing HTTP to untrusted plaintext, or into redirect targets."""
    parsed = urlsplit(value)
    if parsed.scheme not in {"https", "http"}:
        raise ValueError("Java backend must use HTTPS, or HTTP on loopback only")
    if not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Java backend URL cannot contain embedded credentials")
    if parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise ValueError("Java backend URL must be an origin without path/query/fragment")
    if parsed.scheme == "http" and parsed.hostname.lower() not in {
        "localhost", "127.0.0.1", "::1"
    }:
        raise ValueError("Plaintext Java backend connections are loopback-only")
    return value.rstrip("/")


def _get_json(client: httpx.Client, path: str) -> dict:
    try:
        response = client.get(path)
    except httpx.RequestError:
        raise JavaRegistrationError("Java registry GET was unavailable") from None
    if response.status_code != 200:
        raise JavaRegistrationError(
            f"Java registry GET rejected with HTTP {response.status_code}"
        )
    if len(response.content) > 128_000:
        raise JavaRegistrationError("Java registry response exceeded size limit")
    try:
        parsed = response.json()
    except ValueError:
        raise JavaRegistrationError("Java registry returned invalid JSON") from None
    if not isinstance(parsed, dict):
        raise JavaRegistrationError("Java registry returned an unexpected JSON shape")
    return parsed


def _verify_result(
    result_payload: dict,
    *,
    envelope: JavaResearchRegistrationEnvelope,
    experiment: JavaExperimentSnapshot,
    job: JavaExperimentJobSnapshot,
) -> JavaExperimentResultSnapshot:
    try:
        result = JavaExperimentResultSnapshot.model_validate(result_payload)
    except ValueError:
        raise JavaRegistrationError("Java registry returned invalid research evidence") from None
    expected = envelope.request
    if (
        result.jobId != job.jobId
        or result.experimentId != experiment.experimentId
        or result.modelVersionId != experiment.modelVersionId
        or result.specFingerprint != experiment.specFingerprint
        or result.orchestrationFingerprint != job.orchestrationFingerprint
        or result.assessment != expected.assessment
        or result.sampleCount != expected.sampleCount
        or result.resultArtifactFingerprint != expected.resultArtifactFingerprint
        or result.metricsFingerprint != expected.metricsFingerprint
        or result.evidenceFingerprint != expected.evidenceFingerprint
    ):
        raise JavaRegistrationError("Java registry result lineage disagrees with Python evidence")
    return result


def _persist_once(
    client: httpx.Client,
    result_path: str,
    envelope: JavaResearchRegistrationEnvelope,
) -> dict:
    """Never automatically retry a non-idempotent Java result POST."""
    response: httpx.Response | None = None
    ambiguous = False
    try:
        response = client.post(
            result_path,
            json=envelope.request.model_dump(mode="json"),
        )
    except httpx.RequestError:
        ambiguous = True
    if response is not None and response.status_code == 201:
        if len(response.content) > 128_000:
            raise JavaRegistrationError("Java result creation response exceeded size limit")
        try:
            payload = response.json()
        except ValueError:
            raise JavaRegistrationError("Java result creation returned invalid JSON") from None
        if not isinstance(payload, dict):
            raise JavaRegistrationError("Java result creation returned unexpected data")
        return payload
    if response is not None and response.status_code in {409, 500, 502, 503, 504}:
        ambiguous = True
    if not ambiguous:
        code = response.status_code if response is not None else 0
        raise JavaRegistrationError(
            f"Java registry refused result registration with HTTP {code}"
        )
    # A timeout or conflict may mean the Java transaction *already* committed.
    # Inspect the existing immutable record; never duplicate the POST.
    try:
        return _get_json(client, result_path)
    except JavaRegistrationError:
        raise JavaRegistrationError(
            "Java result POST outcome is ambiguous; inspect the server before retrying"
        ) from None


def submit_governed_java_research_result(
    *,
    backend_url: str,
    admin_session_cookie: str,
    admin_csrf_token: str,
    job_id: UUID,
    holdout: IndiaHoldoutReport,
    xgboost: XGBoostConditionalBenchmarkReport | None = None,
    now: datetime | None = None,
    transport: httpx.BaseTransport | None = None,
) -> JavaResearchResultReceipt:
    """Submit one precomputed V4-03 report using Java's existing ADMIN control plane.

    The job must already be RUNNING under an unexpired worker claim. No queue
    claiming, training, retry, model promotion or broker order occurs here.
    The output proves an immutable Java *INCONCLUSIVE* record was read back.
    """
    origin = _validated_origin(backend_url)
    # Java's ADMIN control plane uses ATS_SESSION + X-CSRF-Token. Legacy X-API-Key
    # supplies only ROLE_OPERATOR and a bearer token has no auth filter.
    for secret, label in (
        (admin_session_cookie, "ADMIN session cookie"),
        (admin_csrf_token, "ADMIN CSRF token"),
    ):
        if (
            not secret
            or secret != secret.strip()
            or ";" in secret
            or any(ord(c) < 0x20 or ord(c) == 0x7F for c in secret)
            or any(c.isspace() for c in secret)
            or len(secret) > 4096
        ):
            raise ValueError(f"A valid {label} is required")
    if job_id.int == 0:
        raise ValueError("An actual experiment job ID is required")
    timestamp = now if now is not None else datetime.now(UTC)
    if timestamp.tzinfo is None or timestamp.utcoffset() is None:
        raise ValueError("now must be timezone-aware")

    job_path = f"{API_PREFIX}/experiment-jobs/{job_id}"
    result_path = f"{job_path}/result"
    headers = {"Accept": "application/json", "X-CSRF-Token": admin_csrf_token}
    with httpx.Client(
        base_url=origin,
        headers=headers,
        cookies={"ATS_SESSION": admin_session_cookie},
        follow_redirects=False,
        timeout=httpx.Timeout(10.0),
        trust_env=False,
        transport=transport,
    ) as client:
        try:
            job = JavaExperimentJobSnapshot.model_validate(_get_json(client, job_path))
        except ValueError:
            raise JavaRegistrationError("Java job is not a RUNNING V4-03 Python job") from None
        if job.jobId != job_id:
            raise JavaRegistrationError("Java job ID differs from requested job")
        experiment_path = f"{API_PREFIX}/experiments/{job.experimentId}"
        try:
            experiment = JavaExperimentSnapshot.model_validate(
                _get_json(client, experiment_path)
            )
        except ValueError:
            raise JavaRegistrationError("Java experiment snapshot is invalid") from None
        envelope = build_java_research_result_registration(
            experiment=experiment, job=job, holdout=holdout, xgboost=xgboost,
            now=timestamp,
        )
        created = _persist_once(client, result_path, envelope)
        first = _verify_result(
            created, envelope=envelope, experiment=experiment, job=job
        )
        readback = _verify_result(
            _get_json(client, result_path),
            envelope=envelope, experiment=experiment, job=job,
        )
        if first.resultId != readback.resultId or (
            first.resultFingerprint != readback.resultFingerprint
        ):
            raise JavaRegistrationError("Java registry readback differs from created result")
        finished_job = _get_json(client, job_path)
        if (
            finished_job.get("jobId") != str(job_id)
            or finished_job.get("status") != "SUCCEEDED"
            or finished_job.get("canAuthorizeCapital") is not False
        ):
            raise JavaRegistrationError(
                "Java job did not enter SUCCEEDED after result registration"
            )

    receipt = {
        "contract_version": "java-research-result-receipt.v1",
        "job_id": job_id,
        "experiment_id": experiment.experimentId,
        "result_id": readback.resultId,
        "result_fingerprint": readback.resultFingerprint,
        "evidence_fingerprint": readback.evidenceFingerprint,
        "assessment": "INCONCLUSIVE",
        "readback_verified": True,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    serialized = JavaResearchResultReceipt.model_construct(
        **receipt, receipt_fingerprint="0" * 64
    )
    return JavaResearchResultReceipt(
        **receipt,
        receipt_fingerprint=canonical_fingerprint(
            serialized.model_dump(mode="json", exclude={"receipt_fingerprint"})
        ),
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Submit one precomputed research result to a RUNNING Java experiment job"
    )
    parser.add_argument("backend_origin", help="HTTPS Java origin or loopback for local tests")
    parser.add_argument("job_id", type=UUID)
    parser.add_argument("holdout_report", type=Path)
    parser.add_argument("--xgboost-report", type=Path)
    args = parser.parse_args()
    session = os.environ.get("AITS_JAVA_ADMIN_SESSION", "")
    csrf = os.environ.get("AITS_JAVA_ADMIN_CSRF_TOKEN", "")
    if not session or not csrf:
        parser.error(
            "AITS_JAVA_ADMIN_SESSION and AITS_JAVA_ADMIN_CSRF_TOKEN are required "
            "(never pass authentication secrets as CLI arguments)"
        )
    holdout = IndiaHoldoutReport.model_validate_json(
        args.holdout_report.read_text(encoding="utf-8")
    )
    xgboost = (
        XGBoostConditionalBenchmarkReport.model_validate_json(
            args.xgboost_report.read_text(encoding="utf-8")
        ) if args.xgboost_report is not None else None
    )
    receipt = submit_governed_java_research_result(
        backend_url=args.backend_origin,
        admin_session_cookie=session, admin_csrf_token=csrf,
        job_id=args.job_id, holdout=holdout, xgboost=xgboost,
    )
    # The receipt is safe to log; it never includes the claim token or bearer secret.
    print(json.dumps(receipt.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
