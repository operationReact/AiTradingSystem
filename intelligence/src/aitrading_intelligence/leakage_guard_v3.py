from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .feature_dataset_v2 import feature_dataset_v2_fingerprint
from .feature_v2 import FeatureRowV2
from .fingerprints import canonical_fingerprint

FUTURE_MUTATION_INVARIANT_VERSION = "future-mutation-invariant.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
SourceFamily = Literal[
    "MARKET",
    "EVENT",
    "FUNDAMENTAL",
    "UNIVERSE",
    "INDIA_CONTEXT",
]


class FutureMutationInvariantReport(BaseModel):
    """Immutable evidence that future-source corruption did not alter historical features."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["future-mutation-invariant.v1"] = (
        FUTURE_MUTATION_INVARIANT_VERSION
    )
    decision_at: datetime
    row_count: Annotated[int, Field(gt=0)]
    mutated_source_families: list[SourceFamily] = Field(min_length=1, max_length=5)
    baseline_dataset_fingerprint: Hash64
    mutated_dataset_fingerprint: Hash64
    historical_rows_fingerprint: Hash64
    invariant_passed: Literal[True] = True
    status: Literal["LEAKAGE_INVARIANT_EVIDENCE_ONLY"] = (
        "LEAKAGE_INVARIANT_EVIDENCE_ONLY"
    )
    report_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_at")
    @classmethod
    def decision_time_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("decision_at must be timezone-aware")
        return value


def _identity(row: FeatureRowV2) -> tuple[str, str, datetime]:
    return row.exchange, row.symbol, row.as_of


def _ordered(rows: Sequence[FeatureRowV2]) -> list[FeatureRowV2]:
    return sorted(rows, key=lambda row: (*_identity(row), row.instrument_id))


def _validate_historical_rows(
    rows: list[FeatureRowV2],
    *,
    decision_at: datetime,
    label: str,
) -> None:
    if not rows:
        raise ValueError(f"{label} historical rows cannot be empty")
    identities = [_identity(row) for row in rows]
    if len(set(identities)) != len(identities):
        raise ValueError(f"{label} historical decision identities must be unique")
    if any(row.as_of > decision_at for row in rows):
        raise ValueError(f"{label} contains a row after decision_at")
    if any(row.data_cutoff > row.as_of for row in rows):
        raise ValueError(f"{label} contains a non-causal row")


def verify_future_mutation_invariant(
    *,
    decision_at: datetime,
    baseline_rows: Sequence[FeatureRowV2],
    future_mutated_rows: Sequence[FeatureRowV2],
    mutated_source_families: Sequence[SourceFamily],
) -> FutureMutationInvariantReport:
    """Fail closed unless future-source mutation leaves every historical row byte-stable.

    The function deliberately compares full canonical row payloads in addition to the stored
    row and dataset fingerprints. This prevents a stale or forged fingerprint from masking a
    historical feature mutation.
    """

    if decision_at.tzinfo is None or decision_at.utcoffset() is None:
        raise ValueError("decision_at must be timezone-aware")

    families = sorted(set(mutated_source_families))
    if not families:
        raise ValueError("at least one mutated source family is required")
    if len(families) != len(mutated_source_families):
        raise ValueError("mutated source families must be unique")

    baseline = _ordered(baseline_rows)
    mutated = _ordered(future_mutated_rows)
    _validate_historical_rows(baseline, decision_at=decision_at, label="baseline")
    _validate_historical_rows(mutated, decision_at=decision_at, label="mutated")

    baseline_by_identity = {_identity(row): row for row in baseline}
    mutated_by_identity = {_identity(row): row for row in mutated}
    if set(baseline_by_identity) != set(mutated_by_identity):
        raise ValueError("future mutation changed the historical decision-row identity set")

    for identity in sorted(baseline_by_identity):
        baseline_row = baseline_by_identity[identity]
        mutated_row = mutated_by_identity[identity]
        if baseline_row.model_dump(mode="json") != mutated_row.model_dump(mode="json"):
            raise ValueError(
                "future mutation changed a historical FeatureRow V2 payload: "
                + "/".join((identity[0], identity[1], identity[2].isoformat()))
            )

    baseline_dataset_fingerprint = feature_dataset_v2_fingerprint(baseline)
    mutated_dataset_fingerprint = feature_dataset_v2_fingerprint(mutated)
    if baseline_dataset_fingerprint != mutated_dataset_fingerprint:
        raise ValueError("future mutation changed the historical Dataset V2 fingerprint")

    row_payloads = [row.model_dump(mode="json") for row in baseline]
    historical_rows_fingerprint = canonical_fingerprint(row_payloads)
    payload = {
        "contract_version": FUTURE_MUTATION_INVARIANT_VERSION,
        "decision_at": decision_at.isoformat(),
        "row_count": len(baseline),
        "mutated_source_families": families,
        "baseline_dataset_fingerprint": baseline_dataset_fingerprint,
        "mutated_dataset_fingerprint": mutated_dataset_fingerprint,
        "historical_rows_fingerprint": historical_rows_fingerprint,
        "invariant_passed": True,
        "status": "LEAKAGE_INVARIANT_EVIDENCE_ONLY",
        "can_authorize_capital": False,
    }
    return FutureMutationInvariantReport(
        decision_at=decision_at,
        row_count=len(baseline),
        mutated_source_families=families,
        baseline_dataset_fingerprint=baseline_dataset_fingerprint,
        mutated_dataset_fingerprint=mutated_dataset_fingerprint,
        historical_rows_fingerprint=historical_rows_fingerprint,
        report_fingerprint=canonical_fingerprint(payload),
    )
