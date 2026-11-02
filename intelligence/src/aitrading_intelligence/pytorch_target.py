from __future__ import annotations

from collections.abc import Sequence
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .features import FeatureRow
from .fingerprints import canonical_fingerprint

TARGET_VERSION = "forward-return-after-costs.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class ForwardReturnTargetSpec(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    target_version: Literal["forward-return-after-costs.v1"] = TARGET_VERSION
    horizon_bars: Annotated[int, Field(ge=1, le=78)] = 6
    minimum_edge_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 15.0
    round_trip_cost_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 10.0

    @property
    def positive_threshold_return(self) -> float:
        return (self.minimum_edge_bps + self.round_trip_cost_bps) / 10_000.0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class LabeledExample(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    target_version: Literal["forward-return-after-costs.v1"] = TARGET_VERSION
    symbol: str
    as_of: object
    target_observed_at: object
    feature_row_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    label: Literal[0, 1]
    forward_return: float
    threshold_return: float
    features: dict[str, float]
    provenance: list[str]
    example_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def target_must_follow_decision_time(self) -> LabeledExample:
        if self.target_observed_at <= self.as_of:
            raise ValueError("target observation must be later than the input decision time")
        return self


def build_labeled_examples(
    rows: Sequence[FeatureRow],
    spec: ForwardReturnTargetSpec,
) -> list[LabeledExample]:
    if len(rows) <= spec.horizon_bars:
        raise ValueError("not enough feature rows for the configured target horizon")

    ordered = sorted(rows, key=lambda row: (row.symbol, row.as_of))
    symbols = {row.symbol for row in ordered}
    if len(symbols) != 1:
        raise ValueError("target generation currently requires one symbol per series")
    if any("market.close" not in row.features for row in ordered):
        raise ValueError("market.close is required to construct the offline target")
    if len({row.as_of for row in ordered}) != len(ordered):
        raise ValueError("feature rows must have unique as_of timestamps")

    examples: list[LabeledExample] = []
    threshold = spec.positive_threshold_return
    for index in range(len(ordered) - spec.horizon_bars):
        current = ordered[index]
        future = ordered[index + spec.horizon_bars]
        current_close = float(current.features["market.close"])
        future_close = float(future.features["market.close"])
        if current_close <= 0.0 or future_close <= 0.0:
            raise ValueError("market.close must be positive")
        forward_return = (future_close / current_close) - 1.0
        label = 1 if forward_return > threshold else 0

        # The future close is intentionally excluded from model inputs. It appears only in the
        # offline label metadata below and can never enter the frozen feature dictionary.
        feature_snapshot = dict(current.features)
        payload = {
            "target_version": TARGET_VERSION,
            "symbol": current.symbol,
            "as_of": current.as_of.isoformat(),
            "target_observed_at": future.as_of.isoformat(),
            "feature_row_fingerprint": current.fingerprint,
            "target_spec_fingerprint": spec.fingerprint,
            "label": label,
            "forward_return": forward_return,
            "threshold_return": threshold,
            "features": feature_snapshot,
            "provenance": current.provenance,
        }
        examples.append(
            LabeledExample(
                target_version=TARGET_VERSION,
                symbol=current.symbol,
                as_of=current.as_of,
                target_observed_at=future.as_of,
                feature_row_fingerprint=current.fingerprint,
                target_spec_fingerprint=spec.fingerprint,
                label=label,
                forward_return=forward_return,
                threshold_return=threshold,
                features=feature_snapshot,
                provenance=list(current.provenance),
                example_fingerprint=canonical_fingerprint(payload),
            )
        )
    return examples
