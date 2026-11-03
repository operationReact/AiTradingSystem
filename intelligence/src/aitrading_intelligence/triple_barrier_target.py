from __future__ import annotations

import math
from collections import defaultdict
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .datasets import MarketBar
from .features import FeatureRow
from .fingerprints import canonical_fingerprint

TRIPLE_BARRIER_TARGET_VERSION = "triple-barrier-target.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
BarInterval = Literal["1m", "3m", "5m", "10m", "15m", "30m", "1h", "1d"]
BarrierOutcome = Literal["UPPER", "LOWER", "VERTICAL", "AMBIGUOUS"]
TiePolicy = Literal["ABSTAIN_ON_SAME_BAR_TOUCH"]


class TripleBarrierTargetSpecV1(BaseModel):
    """Versioned offline label policy for causal triple-barrier research targets."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    target_version: Literal["triple-barrier-target.v1"] = (
        TRIPLE_BARRIER_TARGET_VERSION
    )
    bar_interval: BarInterval = "5m"
    horizon_bars: Annotated[int, Field(ge=1, le=390)] = 12
    upper_barrier_bps: Annotated[float, Field(gt=0.0, le=5_000.0)] = 50.0
    lower_barrier_bps: Annotated[float, Field(gt=0.0, le=5_000.0)] = 30.0
    round_trip_cost_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 10.0
    tie_policy: TiePolicy = "ABSTAIN_ON_SAME_BAR_TOUCH"

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class TripleBarrierTargetV1(BaseModel):
    """Immutable target evidence from one decision row and its future OHLC path."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    target_version: Literal["triple-barrier-target.v1"] = (
        TRIPLE_BARRIER_TARGET_VERSION
    )
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    as_of: datetime
    target_observed_at: datetime
    bar_interval: BarInterval
    horizon_bars: Annotated[int, Field(ge=1, le=390)]
    observed_bar_count: Annotated[int, Field(ge=1, le=390)]
    upper_barrier_bps: Annotated[float, Field(gt=0.0, le=5_000.0)]
    lower_barrier_bps: Annotated[float, Field(gt=0.0, le=5_000.0)]
    round_trip_cost_bps: Annotated[float, Field(ge=0.0, le=500.0)]
    tie_policy: TiePolicy
    reference_price: Annotated[float, Field(gt=0.0)]
    upper_barrier_price: Annotated[float, Field(gt=0.0)]
    lower_barrier_price: Annotated[float, Field(gt=0.0)]
    barrier_outcome: BarrierOutcome
    label: Literal[-1, 0, 1] | None
    first_touch_bar_index: Annotated[int | None, Field(ge=1, le=390)] = None
    first_touch_bar_at: datetime | None = None
    exit_price: Annotated[float | None, Field(gt=0.0)] = None
    gross_return: float | None = None
    net_return: float | None = None
    source_feature_row_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    source_bar_fingerprints: list[Hash64] = Field(min_length=1, max_length=390)
    target_fingerprint: Hash64
    status: Literal["RESEARCH_TARGET_ONLY"] = "RESEARCH_TARGET_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "target_observed_at", "first_touch_bar_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("triple-barrier timestamps must be timezone-aware")
        return value

    @field_validator("gross_return", "net_return")
    @classmethod
    def optional_returns_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("triple-barrier returns must be finite")
        return value

    @model_validator(mode="after")
    def validate_target(self) -> TripleBarrierTargetV1:
        if self.target_observed_at <= self.as_of:
            raise ValueError("target observation must follow decision time")
        if self.observed_bar_count != len(self.source_bar_fingerprints):
            raise ValueError("observed_bar_count must match source bar lineage")
        if len(set(self.source_bar_fingerprints)) != len(self.source_bar_fingerprints):
            raise ValueError("source bar fingerprints must be unique")
        if self.observed_bar_count > self.horizon_bars:
            raise ValueError("observed bars cannot exceed the configured horizon")

        expected_upper = self.reference_price * (1.0 + self.upper_barrier_bps / 10_000.0)
        expected_lower = self.reference_price * (1.0 - self.lower_barrier_bps / 10_000.0)
        if expected_lower <= 0.0:
            raise ValueError("lower barrier must remain positive")
        if not math.isclose(
            self.upper_barrier_price, expected_upper, rel_tol=0.0, abs_tol=1e-12
        ):
            raise ValueError("upper barrier price does not match the target policy")
        if not math.isclose(
            self.lower_barrier_price, expected_lower, rel_tol=0.0, abs_tol=1e-12
        ):
            raise ValueError("lower barrier price does not match the target policy")

        spec = TripleBarrierTargetSpecV1(
            bar_interval=self.bar_interval,
            horizon_bars=self.horizon_bars,
            upper_barrier_bps=self.upper_barrier_bps,
            lower_barrier_bps=self.lower_barrier_bps,
            round_trip_cost_bps=self.round_trip_cost_bps,
            tie_policy=self.tie_policy,
        )
        if self.target_spec_fingerprint != spec.fingerprint:
            raise ValueError("target spec fingerprint mismatch")

        touching_outcome = self.barrier_outcome in {"UPPER", "LOWER", "AMBIGUOUS"}
        if touching_outcome:
            if self.first_touch_bar_index is None or self.first_touch_bar_at is None:
                raise ValueError("barrier touch requires exact first-touch evidence")
            if self.first_touch_bar_index != self.observed_bar_count:
                raise ValueError("first touch must terminate observed target lineage")
            if self.first_touch_bar_at != self.target_observed_at:
                raise ValueError("first-touch time must equal target observation time")
        elif self.first_touch_bar_index is not None or self.first_touch_bar_at is not None:
            raise ValueError("vertical outcome cannot contain first-touch evidence")

        if self.barrier_outcome == "UPPER":
            self._validate_resolved_outcome(
                expected_label=1,
                expected_exit=self.upper_barrier_price,
                expected_gross=self.upper_barrier_bps / 10_000.0,
            )
        elif self.barrier_outcome == "LOWER":
            self._validate_resolved_outcome(
                expected_label=-1,
                expected_exit=self.lower_barrier_price,
                expected_gross=-self.lower_barrier_bps / 10_000.0,
            )
        elif self.barrier_outcome == "VERTICAL":
            if self.label != 0:
                raise ValueError("vertical outcome must use neutral label 0")
            if self.observed_bar_count != self.horizon_bars:
                raise ValueError("vertical outcome requires the full configured horizon")
            if self.exit_price is None or self.gross_return is None or self.net_return is None:
                raise ValueError("vertical outcome requires exit and return evidence")
            expected_gross = (self.exit_price / self.reference_price) - 1.0
            if not math.isclose(
                self.gross_return, expected_gross, rel_tol=0.0, abs_tol=1e-12
            ):
                raise ValueError("vertical gross return mismatch")
            self._validate_net_return()
        else:
            if self.label is not None:
                raise ValueError("ambiguous same-bar touch must abstain from a label")
            if self.exit_price is not None or self.gross_return is not None or self.net_return is not None:
                raise ValueError("ambiguous same-bar touch cannot invent realized returns")

        if self.target_fingerprint != canonical_fingerprint(_target_payload(self)):
            raise ValueError("triple-barrier target fingerprint mismatch")
        return self

    def _validate_resolved_outcome(
        self,
        *,
        expected_label: Literal[-1, 1],
        expected_exit: float,
        expected_gross: float,
    ) -> None:
        if self.label != expected_label:
            raise ValueError("barrier outcome label mismatch")
        if self.exit_price is None or self.gross_return is None or self.net_return is None:
            raise ValueError("resolved barrier outcome requires exit and return evidence")
        if not math.isclose(self.exit_price, expected_exit, rel_tol=0.0, abs_tol=1e-12):
            raise ValueError("barrier exit price mismatch")
        if not math.isclose(
            self.gross_return, expected_gross, rel_tol=0.0, abs_tol=1e-12
        ):
            raise ValueError("barrier gross return mismatch")
        self._validate_net_return()

    def _validate_net_return(self) -> None:
        assert self.gross_return is not None
        assert self.net_return is not None
        expected_net = self.gross_return - self.round_trip_cost_bps / 10_000.0
        if not math.isclose(
            self.net_return, expected_net, rel_tol=0.0, abs_tol=1e-12
        ):
            raise ValueError("net return must equal gross return minus round-trip cost")


def build_triple_barrier_targets(
    rows: Sequence[FeatureRow],
    bars: Sequence[MarketBar],
    spec: TripleBarrierTargetSpecV1,
    *,
    label_as_of: datetime,
) -> list[TripleBarrierTargetV1]:
    """Build only fully observable targets at one frozen label cutoff."""

    if label_as_of.tzinfo is None or label_as_of.utcoffset() is None:
        raise ValueError("label_as_of must be timezone-aware")

    checked_rows = [_validate_feature_row(row) for row in rows]
    checked_bars = [MarketBar.model_validate(bar.model_dump(mode="python")) for bar in bars]

    grouped_bars: dict[str, list[MarketBar]] = defaultdict(list)
    for bar in checked_bars:
        if bar.interval != spec.bar_interval or bar.observed_at > label_as_of:
            continue
        grouped_bars[bar.symbol].append(bar)
    for symbol, symbol_bars in grouped_bars.items():
        symbol_bars.sort(key=lambda item: item.observed_at)
        if len({item.observed_at for item in symbol_bars}) != len(symbol_bars):
            raise ValueError(f"duplicate {spec.bar_interval} bar timestamp for {symbol}")

    targets: list[TripleBarrierTargetV1] = []
    for row in sorted(checked_rows, key=lambda item: (item.symbol, item.as_of)):
        if "market.close" not in row.features:
            raise ValueError("market.close is required for triple-barrier targets")
        reference_price = float(row.features["market.close"])
        if reference_price <= 0.0:
            raise ValueError("market.close must be positive")

        future_bars = [
            bar for bar in grouped_bars.get(row.symbol, []) if bar.observed_at > row.as_of
        ]
        if len(future_bars) < spec.horizon_bars:
            continue
        horizon = future_bars[: spec.horizon_bars]
        targets.append(_build_target(row, horizon, spec, reference_price))

    if not targets:
        raise ValueError("no complete triple-barrier targets exist at label_as_of")
    return targets


def _build_target(
    row: FeatureRow,
    horizon: Sequence[MarketBar],
    spec: TripleBarrierTargetSpecV1,
    reference_price: float,
) -> TripleBarrierTargetV1:
    upper_price = reference_price * (1.0 + spec.upper_barrier_bps / 10_000.0)
    lower_price = reference_price * (1.0 - spec.lower_barrier_bps / 10_000.0)
    if lower_price <= 0.0:
        raise ValueError("configured lower barrier must remain positive")

    outcome: BarrierOutcome = "VERTICAL"
    label: Literal[-1, 0, 1] | None = 0
    first_touch_index: int | None = None
    first_touch_at: datetime | None = None
    exit_price: float | None = horizon[-1].close
    gross_return: float | None = (exit_price / reference_price) - 1.0
    used_bars = list(horizon)

    for index, bar in enumerate(horizon, start=1):
        hit_upper = bar.high >= upper_price
        hit_lower = bar.low <= lower_price
        if not hit_upper and not hit_lower:
            continue

        used_bars = list(horizon[:index])
        first_touch_index = index
        first_touch_at = bar.observed_at
        if hit_upper and hit_lower:
            outcome = "AMBIGUOUS"
            label = None
            exit_price = None
            gross_return = None
        elif hit_upper:
            outcome = "UPPER"
            label = 1
            exit_price = upper_price
            gross_return = spec.upper_barrier_bps / 10_000.0
        else:
            outcome = "LOWER"
            label = -1
            exit_price = lower_price
            gross_return = -spec.lower_barrier_bps / 10_000.0
        break

    target_observed_at = used_bars[-1].observed_at
    net_return = (
        None
        if gross_return is None
        else gross_return - spec.round_trip_cost_bps / 10_000.0
    )
    source_bar_fingerprints = [canonical_fingerprint(_bar_payload(bar)) for bar in used_bars]
    payload = {
        "target_version": TRIPLE_BARRIER_TARGET_VERSION,
        "symbol": row.symbol,
        "as_of": row.as_of.isoformat(),
        "target_observed_at": target_observed_at.isoformat(),
        "bar_interval": spec.bar_interval,
        "horizon_bars": spec.horizon_bars,
        "observed_bar_count": len(used_bars),
        "upper_barrier_bps": spec.upper_barrier_bps,
        "lower_barrier_bps": spec.lower_barrier_bps,
        "round_trip_cost_bps": spec.round_trip_cost_bps,
        "tie_policy": spec.tie_policy,
        "reference_price": reference_price,
        "upper_barrier_price": upper_price,
        "lower_barrier_price": lower_price,
        "barrier_outcome": outcome,
        "label": label,
        "first_touch_bar_index": first_touch_index,
        "first_touch_bar_at": first_touch_at.isoformat() if first_touch_at else None,
        "exit_price": exit_price,
        "gross_return": gross_return,
        "net_return": net_return,
        "source_feature_row_fingerprint": row.fingerprint,
        "target_spec_fingerprint": spec.fingerprint,
        "source_bar_fingerprints": source_bar_fingerprints,
        "status": "RESEARCH_TARGET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TripleBarrierTargetV1(
        **payload,
        target_fingerprint=canonical_fingerprint(payload),
    )


def _validate_feature_row(row: FeatureRow) -> FeatureRow:
    checked = FeatureRow.model_validate(row.model_dump(mode="python"))
    payload = {
        "feature_schema_version": checked.feature_schema_version,
        "symbol": checked.symbol,
        "as_of": checked.as_of.isoformat(),
        "data_cutoff": checked.data_cutoff.isoformat(),
        "features": checked.features,
        "provenance": checked.provenance,
    }
    if checked.fingerprint != canonical_fingerprint(payload):
        raise ValueError("feature row fingerprint mismatch")
    return checked


def _bar_payload(bar: MarketBar) -> dict[str, object]:
    return {
        "symbol": bar.symbol,
        "interval": bar.interval,
        "observed_at": bar.observed_at.isoformat(),
        "event_at": bar.event_at.isoformat() if bar.event_at else None,
        "ingested_at": bar.ingested_at.isoformat() if bar.ingested_at else None,
        "open": bar.open,
        "high": bar.high,
        "low": bar.low,
        "close": bar.close,
        "volume": bar.volume,
        "source": bar.source,
    }


def _target_payload(target: TripleBarrierTargetV1) -> dict[str, object]:
    return {
        "target_version": target.target_version,
        "symbol": target.symbol,
        "as_of": target.as_of.isoformat(),
        "target_observed_at": target.target_observed_at.isoformat(),
        "bar_interval": target.bar_interval,
        "horizon_bars": target.horizon_bars,
        "observed_bar_count": target.observed_bar_count,
        "upper_barrier_bps": target.upper_barrier_bps,
        "lower_barrier_bps": target.lower_barrier_bps,
        "round_trip_cost_bps": target.round_trip_cost_bps,
        "tie_policy": target.tie_policy,
        "reference_price": target.reference_price,
        "upper_barrier_price": target.upper_barrier_price,
        "lower_barrier_price": target.lower_barrier_price,
        "barrier_outcome": target.barrier_outcome,
        "label": target.label,
        "first_touch_bar_index": target.first_touch_bar_index,
        "first_touch_bar_at": (
            target.first_touch_bar_at.isoformat() if target.first_touch_bar_at else None
        ),
        "exit_price": target.exit_price,
        "gross_return": target.gross_return,
        "net_return": target.net_return,
        "source_feature_row_fingerprint": target.source_feature_row_fingerprint,
        "target_spec_fingerprint": target.target_spec_fingerprint,
        "source_bar_fingerprints": list(target.source_bar_fingerprints),
        "status": target.status,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
