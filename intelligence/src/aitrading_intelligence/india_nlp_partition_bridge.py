from __future__ import annotations

import argparse
import json
import math
import os
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .india_financial_nlp_corpus import (
    IndiaFinancialNlpCorpusReport,
    IndiaNlpCausalCase,
)
from .india_nlp_event_corpus import (
    IndiaFinancialEventCorpus,
    IndiaFinancialEventSample,
)

VERSION = "india-nlp-prospective-partition-bridge.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Partition = Literal["TRAIN", "CALIBRATION", "INDEPENDENT_HOLDOUT"]
SPLITS: tuple[Partition, ...] = ("TRAIN", "CALIBRATION", "INDEPENDENT_HOLDOUT")


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("prospective research timestamps must include a timezone")
    return value


class NlpPartitionWindow(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    split: Partition
    from_inclusive: datetime
    to_exclusive: datetime

    @model_validator(mode="after")
    def check_window(self) -> NlpPartitionWindow:
        if _aware(self.from_inclusive) >= _aware(self.to_exclusive):
            raise ValueError("NLP partition window must have a positive duration")
        return self


class IndiaNlpProspectivePartitionPlan(BaseModel):
    """Self-declared BEFORE-data plan; hash alone is not a trusted timestamp."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-nlp-prospective-partition-bridge.v1"] = VERSION
    registered_at: datetime
    target_symbol: Annotated[str, Field(min_length=2, max_length=32)]
    source_config_fingerprint: Hash64
    minimum_cases_per_split: Annotated[int, Field(ge=1, le=500)] = 1
    windows: list[NlpPartitionWindow] = Field(min_length=3, max_length=3)
    independently_witnessed_registration: Literal[False] = False
    plan_fingerprint: Hash64

    @model_validator(mode="after")
    def check_plan(self) -> IndiaNlpProspectivePartitionPlan:
        _aware(self.registered_at)
        if tuple(window.split for window in self.windows) != SPLITS:
            raise ValueError("NLP plan requires TRAIN, CALIBRATION, INDEPENDENT_HOLDOUT")
        if self.registered_at >= self.windows[0].from_inclusive:
            raise ValueError("prospective plan must predate first TRAIN capture window")
        for first, second in zip(self.windows[:-1], self.windows[1:], strict=True):
            if first.to_exclusive > second.from_inclusive:
                raise ValueError("research partition windows overlap")
        if self.plan_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"plan_fingerprint"})
        ):
            raise ValueError("NLP prospective partition plan fingerprint mismatch")
        return self


def freeze_nlp_partition_plan(
    *,
    registered_at: datetime,
    target_symbol: str,
    source_config_fingerprint: str,
    windows: list[NlpPartitionWindow],
    minimum_cases_per_split: int = 1,
) -> IndiaNlpProspectivePartitionPlan:
    payload = {
        "contract_version": VERSION,
        "registered_at": _aware(registered_at).isoformat(),
        "target_symbol": target_symbol,
        "source_config_fingerprint": source_config_fingerprint,
        "minimum_cases_per_split": minimum_cases_per_split,
        "windows": [window.model_dump(mode="json") for window in windows],
        "independently_witnessed_registration": False,
    }
    return IndiaNlpProspectivePartitionPlan(
        **payload, plan_fingerprint=canonical_fingerprint(payload)
    )


class NlpPartitionJoinRow(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    source_id: str
    split: Partition
    decision_at: datetime
    label_available_at: datetime
    event_sample_fingerprint: Hash64
    market_case_fingerprint: Hash64
    row_fingerprint: Hash64

    @model_validator(mode="after")
    def check_join(self) -> NlpPartitionJoinRow:
        if _aware(self.decision_at) >= _aware(self.label_available_at):
            raise ValueError("joined market label must follow decision")
        if self.row_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"row_fingerprint"})
        ):
            raise ValueError("NLP partition row fingerprint mismatch")
        return self


class IndiaNlpPartitionBridgeReport(BaseModel):
    """Full cohort audit plus a holdout-only input for PR #376's evaluator."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-nlp-prospective-partition-bridge.v1"] = VERSION
    plan: IndiaNlpProspectivePartitionPlan
    annotated_corpus_fingerprint: Hash64
    source_causal_corpus_fingerprint: Hash64
    joined_rows: list[NlpPartitionJoinRow] = Field(min_length=3, max_length=1000)
    counts_by_split: dict[Partition, int]
    holdout_causal_slice: IndiaFinancialNlpCorpusReport
    status: Literal["SELF_DECLARED_CAUSAL_PARTITION_ONLY"] = (
        "SELF_DECLARED_CAUSAL_PARTITION_ONLY"
    )
    external_timestamp_witness_verified: Literal[False] = False
    independent_news_rights_verified: Literal[False] = False
    market_data_custody_verified: Literal[False] = False
    independent_human_annotation_verified: Literal[False] = False
    actual_gemini_provider_call_verified: Literal[False] = False
    paper_shadow_live_approval: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def check_report(self) -> IndiaNlpPartitionBridgeReport:
        if set(self.counts_by_split) != set(SPLITS):
            raise ValueError("partition report must count each defined split")
        if len({row.source_id for row in self.joined_rows}) != len(self.joined_rows):
            raise ValueError("partition joins must not duplicate source IDs")
        expected_order = sorted(
            self.joined_rows,
            key=lambda row: (SPLITS.index(row.split), row.decision_at, row.source_id),
        )
        if self.joined_rows != expected_order:
            raise ValueError("partition join rows must be chronologically sorted")
        if any(
            self.counts_by_split[split] != sum(row.split == split for row in self.joined_rows)
            or self.counts_by_split[split] < self.plan.minimum_cases_per_split
            for split in SPLITS
        ):
            raise ValueError("partition sample counts do not satisfy frozen plan")
        expected_ids = {
            row.source_id for row in self.joined_rows
            if row.split == "INDEPENDENT_HOLDOUT"
        }
        if expected_ids != {case.source_id for case in self.holdout_causal_slice.cases}:
            raise ValueError("holdout slice contains non-holdout or missing source IDs")
        if self.holdout_causal_slice.config_fingerprint != (
            self.plan.source_config_fingerprint
        ):
            raise ValueError("holdout source configuration does not match frozen plan")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("partition bridge report fingerprint mismatch")
        return self


def _validate_join(
    sample: IndiaFinancialEventSample,
    case: IndiaNlpCausalCase,
    plan: IndiaNlpProspectivePartitionPlan,
) -> None:
    window = next(window for window in plan.windows if window.split == sample.split)
    if not window.from_inclusive <= sample.decision_at < window.to_exclusive:
        raise ValueError("annotated event decision falls outside frozen partition window")
    if case.target_symbol != plan.target_symbol:
        raise ValueError("NLP causal case instrument differs from frozen plan")
    if (
        sample.source_url != case.source_url
        or sample.decision_at != case.decision_at
        or sample.market_label_available_at != case.label_available_at
        or sample.captured_at < case.licensed_text_captured_at
        or sample.published_at > case.news_available_at
    ):
        raise ValueError("NLP event source or point-in-time label lineage mismatch")
    if not math.isclose(
        sample.forward_return_bps / 10_000.0,
        case.observed_forward_return,
        rel_tol=1e-8, abs_tol=1e-10,
    ):
        raise ValueError("NLP event and causal market returns do not match")


def bridge_nlp_prospective_partitions(
    plan: IndiaNlpProspectivePartitionPlan,
    annotated_events: IndiaFinancialEventCorpus,
    full_market_corpus: IndiaFinancialNlpCorpusReport,
) -> IndiaNlpPartitionBridgeReport:
    """Never reassign a split using outcomes or silently sample the holdout.

    The builder validates data consistency, not independently witnessed provenance.
    The output's holdout subset is the exact causal input expected by PR #376;
    TRAIN/CALIBRATION records are NOT passed into that holdout evaluator.
    """
    frozen = IndiaNlpProspectivePartitionPlan.model_validate(plan.model_dump(mode="python"))
    events = IndiaFinancialEventCorpus.model_validate(
        annotated_events.model_dump(mode="python")
    )
    source = IndiaFinancialNlpCorpusReport.model_validate(
        full_market_corpus.model_dump(mode="python")
    )
    if source.status != "CAUSAL_RESEARCH_CORPUS_ONLY":
        raise ValueError("NLP partition bridge requires complete causal market corpus")
    if frozen.source_config_fingerprint != source.config_fingerprint:
        raise ValueError("NLP source configuration differs from frozen partition plan")
    annotated_ids = {item.event_id for item in events.samples}
    actual_ids = {item.source_id for item in source.cases}
    if annotated_ids != actual_ids:
        raise ValueError("partitioned events must cover every eligible causal source")
    by_id = {case.source_id: case for case in source.cases}
    ordered = sorted(
        events.samples,
        key=lambda sample: (SPLITS.index(sample.split), sample.decision_at, sample.event_id),
    )
    rows = []
    for sample in ordered:
        case = by_id[sample.event_id]
        _validate_join(sample, case, frozen)
        payload = {
            "source_id": sample.event_id,
            "split": sample.split,
            "decision_at": sample.decision_at.isoformat(),
            "label_available_at": sample.market_label_available_at.isoformat(),
            "event_sample_fingerprint": sample.sample_fingerprint,
            "market_case_fingerprint": case.case_fingerprint,
        }
        rows.append(NlpPartitionJoinRow(
            **payload, row_fingerprint=canonical_fingerprint(payload)
        ))
    counts = {split: sum(item.split == split for item in rows) for split in SPLITS}
    if any(count < frozen.minimum_cases_per_split for count in counts.values()):
        raise ValueError("prospective NLP split has insufficient eligible samples")
    holdout_ids = {item.source_id for item in rows if item.split == "INDEPENDENT_HOLDOUT"}
    source_payload = source.model_dump(mode="json", exclude={"report_fingerprint"})
    source_payload["cases"] = [
        case.model_dump(mode="json")
        for case in source.cases if case.source_id in holdout_ids
    ]
    # Parent excluded rows are audited by the parent fingerprint; don't attribute
    # non-holdout exclusions to the holdout slice's coverage metric.
    source_payload["excluded"] = {}
    source_payload["reason_codes"] = []
    holdout_slice = IndiaFinancialNlpCorpusReport(
        **source_payload, report_fingerprint=canonical_fingerprint(source_payload)
    )
    payload = {
        "contract_version": VERSION,
        "plan": frozen.model_dump(mode="json"),
        "annotated_corpus_fingerprint": events.corpus_fingerprint,
        "source_causal_corpus_fingerprint": source.report_fingerprint,
        "joined_rows": [row.model_dump(mode="json") for row in rows],
        "counts_by_split": counts,
        "holdout_causal_slice": holdout_slice.model_dump(mode="json"),
        "status": "SELF_DECLARED_CAUSAL_PARTITION_ONLY",
        "external_timestamp_witness_verified": False,
        "independent_news_rights_verified": False,
        "market_data_custody_verified": False,
        "independent_human_annotation_verified": False,
        "actual_gemini_provider_call_verified": False,
        "paper_shadow_live_approval": False,
        "can_authorize_capital": False,
    }
    return IndiaNlpPartitionBridgeReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Offline research-only frozen NLP partition and holdout bridge"
    )
    parser.add_argument("prospective_plan", type=Path)
    parser.add_argument("frozen_event_corpus", type=Path)
    parser.add_argument("full_causal_news_market_corpus", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    report = bridge_nlp_prospective_partitions(
        IndiaNlpProspectivePartitionPlan.model_validate_json(
            args.prospective_plan.read_text(encoding="utf-8")
        ),
        IndiaFinancialEventCorpus.model_validate_json(
            args.frozen_event_corpus.read_text(encoding="utf-8")
        ),
        IndiaFinancialNlpCorpusReport.model_validate_json(
            args.full_causal_news_market_corpus.read_text(encoding="utf-8")
        ),
    )
    # No stdout of copyrighted article titles or market evidence; no overwrite.
    fd = os.open(args.output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        json.dump(report.model_dump(mode="json"), output, sort_keys=True)
        output.write("\n")
    print(report.report_fingerprint)


if __name__ == "__main__":
    main()
