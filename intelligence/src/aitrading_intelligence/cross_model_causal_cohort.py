from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .india_causal_forecast_baseline import (
    CausalForecastBaselineReport,
    CausalForecastRow,
)
from .pytorch_target import ForwardReturnTargetSpec

VERSION = "india-v4-cross-model-causal-cohort.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class CrossModelCausalCohort(BaseModel):
    """One immutable cross-model fold and fee identity, never trading authorization.

    This is a precondition for a future registered model-family tournament.
    It does NOT certify source authenticity, pre-registration or the economics
    of a model; its fields only establish exact common experiment membership.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-v4-cross-model-causal-cohort.v1"] = VERSION
    training_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    fee_book_fingerprint: Hash64
    target_binding_fingerprint: Hash64
    parent_holdout_membership_fingerprint: Hash64
    fold_binding_fingerprint: Hash64
    economic_cost_binding_fingerprint: Hash64
    observation_fingerprints: list[Hash64] = Field(min_length=2, max_length=10_000)
    decision_times: list[datetime] = Field(min_length=2, max_length=10_000)
    independent_market_source_verified: Literal[False] = False
    external_preregistration_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    binding_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_binding(self) -> CrossModelCausalCohort:
        if len(self.observation_fingerprints) != len(self.decision_times):
            raise ValueError("cohort identities and decision times must align")
        if len(set(self.observation_fingerprints)) != len(self.observation_fingerprints):
            raise ValueError("cohort observation identities must be unique")
        if any(time.tzinfo is None or time.utcoffset() is None
               for time in self.decision_times):
            raise ValueError("cohort decision times must be timezone-aware")
        if any(later <= earlier for earlier, later in zip(
            self.decision_times[:-1], self.decision_times[1:], strict=True
        )):
            raise ValueError("cohort decision times must be strictly increasing")
        if self.fold_binding_fingerprint != canonical_fingerprint({
            "version": VERSION,
            "training": self.training_export_fingerprint,
            "holdout": self.holdout_export_fingerprint,
            "holdout_membership": self.parent_holdout_membership_fingerprint,
            "observations": self.observation_fingerprints,
            "decision_times": [
                time.isoformat() for time in self.decision_times
            ],
        }):
            raise ValueError("cross-model fold fingerprint mismatch")
        if self.economic_cost_binding_fingerprint != canonical_fingerprint({
            "version": VERSION,
            "fee_book": self.fee_book_fingerprint,
            "target": self.target_binding_fingerprint,
        }):
            raise ValueError("cross-model fee and target fingerprint mismatch")
        if self.binding_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"binding_fingerprint"})
        ):
            raise ValueError("cross-model cohort binding fingerprint mismatch")
        return self

    def series_kwargs(self) -> dict[str, Any]:
        """Explicit common arguments for the existing NetReturnSeries constructor."""
        return {
            "target_binding_fingerprint": self.target_binding_fingerprint,
            "fold_binding_fingerprint": self.fold_binding_fingerprint,
            "economic_cost_binding_fingerprint": self.economic_cost_binding_fingerprint,
            "observation_fingerprints": list(self.observation_fingerprints),
            "decision_times": list(self.decision_times),
        }


def bind_common_causal_tournament_cohort(
    parent: CausalForecastBaselineReport,
    heldout: Sequence[CausalForecastRow],
    target: ForwardReturnTargetSpec,
) -> CrossModelCausalCohort:
    """Bind the SAME parent, fee book, horizon and decision-level membership.

    Call this in each V4-04 model, not merely in the tournament. A different
    lookback, target, market export, fee book or observation set must reject
    cross-model pooling instead of silently selecting an incomparable winner.
    """
    benchmark = CausalForecastBaselineReport.model_validate(
        parent.model_dump(mode="python")
    )
    rows = [CausalForecastRow.model_validate(row.model_dump(mode="python"))
            for row in heldout]
    target = ForwardReturnTargetSpec.model_validate(target.model_dump(mode="python"))
    if benchmark.status != "RESEARCH_FORECAST_METRICS_ONLY":
        raise ValueError("common cohort requires a completed causal baseline")
    if target.fingerprint != benchmark.target_spec_fingerprint:
        raise ValueError("common cohort target differs from baseline")
    if len(rows) != benchmark.independent_holdout_rows:
        raise ValueError("common cohort holdout row count differs from baseline")
    membership = canonical_fingerprint([row.row_fingerprint for row in rows])
    if membership != benchmark.holdout_example_membership_fingerprint:
        raise ValueError("common cohort observation membership differs from baseline")
    if len({row.session_date for row in rows}) != benchmark.independent_holdout_sessions:
        raise ValueError("common cohort trading sessions differ from baseline")
    observations = [
        canonical_fingerprint({
            "history": row.history_fingerprint,
            "target": row.target_fingerprint,
            "time": row.decision_at.isoformat(),
        })
        for row in rows
    ]
    times = [row.decision_at for row in rows]
    fold = canonical_fingerprint({
        "version": VERSION,
        "training": benchmark.train_export_fingerprint,
        "holdout": benchmark.holdout_export_fingerprint,
        "holdout_membership": membership,
        "observations": observations,
        "decision_times": [time.isoformat() for time in times],
    })
    cost = canonical_fingerprint({
        "version": VERSION,
        "fee_book": benchmark.india_fee_book_fingerprint,
        "target": target.fingerprint,
    })
    payload = {
        "contract_version": VERSION,
        "training_export_fingerprint": benchmark.train_export_fingerprint,
        "holdout_export_fingerprint": benchmark.holdout_export_fingerprint,
        "fee_book_fingerprint": benchmark.india_fee_book_fingerprint,
        "target_binding_fingerprint": target.fingerprint,
        "parent_holdout_membership_fingerprint": membership,
        "fold_binding_fingerprint": fold,
        "economic_cost_binding_fingerprint": cost,
        "observation_fingerprints": observations,
        "decision_times": times,
        "independent_market_source_verified": False,
        "external_preregistration_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    untrusted = CrossModelCausalCohort.model_construct(
        **payload, binding_fingerprint="0" * 64
    )
    return CrossModelCausalCohort(
        **payload,
        binding_fingerprint=canonical_fingerprint(
            untrusted.model_dump(mode="json", exclude={"binding_fingerprint"})
        ),
    )
