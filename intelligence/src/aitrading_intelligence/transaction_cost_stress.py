from __future__ import annotations

import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .replay import HistoricalReplay, ReplayConfig

TRANSACTION_COST_STRESS_VERSION = "transaction-cost-stress.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
StressState = Literal["EVALUATED", "INCONCLUSIVE"]
Multiplier = Annotated[float, Field(ge=1.0, le=10.0)]


class TransactionCostStressConfigV1(BaseModel):
    """Frozen-path grid for transaction-cost and slippage sensitivity."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    stress_version: Literal["transaction-cost-stress.v1"] = (
        TRANSACTION_COST_STRESS_VERSION
    )
    transaction_cost_multipliers: list[Multiplier] = Field(
        default_factory=lambda: [1.0, 1.5, 2.0]
    )
    slippage_multipliers: list[Multiplier] = Field(
        default_factory=lambda: [1.0, 1.5, 2.0]
    )
    maximum_scenarios: Annotated[int, Field(ge=1, le=1_000)] = 100

    @field_validator("transaction_cost_multipliers", "slippage_multipliers")
    @classmethod
    def multipliers_must_be_unique_sorted_and_include_baseline(
        cls,
        values: list[float],
    ) -> list[float]:
        if not values:
            raise ValueError("stress multipliers cannot be empty")
        if values != sorted(set(values)):
            raise ValueError("stress multipliers must be unique and sorted")
        if 1.0 not in values:
            raise ValueError("stress multipliers must include the 1.0 baseline")
        return values

    @model_validator(mode="after")
    def scenario_count_must_be_bounded(self) -> TransactionCostStressConfigV1:
        if self.scenario_count > self.maximum_scenarios:
            raise ValueError("transaction-cost stress grid exceeds maximum_scenarios")
        return self

    @property
    def scenario_count(self) -> int:
        return len(self.transaction_cost_multipliers) * len(self.slippage_multipliers)

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class TransactionCostStressScenarioV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    stress_version: Literal["transaction-cost-stress.v1"] = (
        TRANSACTION_COST_STRESS_VERSION
    )
    scenario_index: Annotated[int, Field(ge=0)]
    transaction_cost_multiplier: Multiplier
    slippage_multiplier: Multiplier
    transaction_cost_bps: Annotated[float, Field(ge=0.0, le=5_000.0)]
    slippage_bps_per_side: Annotated[float, Field(ge=0.0, le=5_000.0)]
    round_trip_friction_bps: Annotated[float, Field(ge=0.0, le=15_000.0)]
    source_replay_fingerprint: Hash64
    decision_path_fingerprint: Hash64
    capital_path_fingerprint: Hash64
    trade_count: Annotated[int, Field(ge=1)]
    final_equity: float
    total_return: float
    max_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]
    minimum_equity: float
    total_friction_cost: Annotated[float, Field(ge=0.0)]
    incremental_friction_cost: Annotated[float, Field(ge=0.0)]
    average_trade_net_return: float
    ruin_occurred: bool
    scenario_fingerprint: Hash64
    status: Literal["RESEARCH_COST_STRESS_SCENARIO_ONLY"] = (
        "RESEARCH_COST_STRESS_SCENARIO_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator(
        "transaction_cost_bps",
        "slippage_bps_per_side",
        "round_trip_friction_bps",
        "final_equity",
        "total_return",
        "max_drawdown",
        "minimum_equity",
        "total_friction_cost",
        "incremental_friction_cost",
        "average_trade_net_return",
    )
    @classmethod
    def metrics_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("transaction-cost stress metrics must be finite")
        return value

    @model_validator(mode="after")
    def validate_scenario(self) -> TransactionCostStressScenarioV1:
        expected_friction = self.transaction_cost_bps + (
            2.0 * self.slippage_bps_per_side
        )
        if not math.isclose(
            self.round_trip_friction_bps,
            expected_friction,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ):
            raise ValueError("round-trip friction does not match scenario components")
        if self.scenario_fingerprint != canonical_fingerprint(_scenario_payload(self)):
            raise ValueError("transaction-cost stress scenario fingerprint mismatch")
        return self


class TransactionCostStressReportV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    stress_version: Literal["transaction-cost-stress.v1"] = (
        TRANSACTION_COST_STRESS_VERSION
    )
    state: StressState
    reason_codes: list[str]
    config_fingerprint: Hash64
    source_replay_fingerprint: Hash64
    baseline_replay_config_fingerprint: Hash64
    decision_path_fingerprint: Hash64
    capital_path_fingerprint: Hash64
    source_trade_fingerprints: list[Hash64]
    baseline_trade_count: Annotated[int, Field(ge=0)]
    scenario_count: Annotated[int, Field(ge=0)]
    scenarios: list[TransactionCostStressScenarioV1]
    worst_total_return: float | None = None
    worst_max_drawdown: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    maximum_total_friction_cost: Annotated[float | None, Field(ge=0.0)] = None
    profitable_scenario_fraction: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    report_fingerprint: Hash64
    status: Literal["RESEARCH_TRANSACTION_COST_STRESS_ONLY"] = (
        "RESEARCH_TRANSACTION_COST_STRESS_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("worst_total_return")
    @classmethod
    def optional_return_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("worst_total_return must be finite")
        return value

    @model_validator(mode="after")
    def validate_report(self) -> TransactionCostStressReportV1:
        if self.baseline_trade_count != len(self.source_trade_fingerprints):
            raise ValueError("baseline trade count does not match source trade lineage")
        if self.scenario_count != len(self.scenarios):
            raise ValueError("transaction-cost stress scenario_count mismatch")
        if self.state == "EVALUATED":
            metrics = (
                self.worst_total_return,
                self.worst_max_drawdown,
                self.maximum_total_friction_cost,
                self.profitable_scenario_fraction,
            )
            if not self.scenarios or any(value is None for value in metrics):
                raise ValueError("evaluated transaction-cost stress requires complete scenarios")
        else:
            if self.scenarios or any(
                value is not None
                for value in (
                    self.worst_total_return,
                    self.worst_max_drawdown,
                    self.maximum_total_friction_cost,
                    self.profitable_scenario_fraction,
                )
            ):
                raise ValueError("inconclusive transaction-cost stress cannot expose metrics")
        for scenario in self.scenarios:
            if scenario.source_replay_fingerprint != self.source_replay_fingerprint:
                raise ValueError("stress scenario replay lineage mismatch")
            if scenario.decision_path_fingerprint != self.decision_path_fingerprint:
                raise ValueError("stress scenario decision path changed")
            if scenario.capital_path_fingerprint != self.capital_path_fingerprint:
                raise ValueError("stress scenario capital path changed")
            if scenario.trade_count != self.baseline_trade_count:
                raise ValueError("stress scenario trade count changed")
        if self.report_fingerprint != canonical_fingerprint(_report_payload(self)):
            raise ValueError("transaction-cost stress report fingerprint mismatch")
        return self


def _decision_path_payload(replay: HistoricalReplay) -> list[dict[str, object]]:
    return [
        {
            "trade_fingerprint": trade.trade_fingerprint,
            "symbol": trade.symbol,
            "entry_at": trade.entry_at.isoformat(),
            "exit_at": trade.exit_at.isoformat(),
            "gross_return": trade.gross_return,
        }
        for trade in replay.trades
    ]


def _capital_path_payload(replay: HistoricalReplay) -> list[dict[str, object]]:
    return [
        {
            "trade_fingerprint": trade.trade_fingerprint,
            "allocated_capital": trade.allocated_capital,
        }
        for trade in replay.trades
    ]


def _scenario_payload(scenario: TransactionCostStressScenarioV1) -> dict[str, object]:
    return {
        "stress_version": TRANSACTION_COST_STRESS_VERSION,
        "scenario_index": scenario.scenario_index,
        "transaction_cost_multiplier": scenario.transaction_cost_multiplier,
        "slippage_multiplier": scenario.slippage_multiplier,
        "transaction_cost_bps": scenario.transaction_cost_bps,
        "slippage_bps_per_side": scenario.slippage_bps_per_side,
        "round_trip_friction_bps": scenario.round_trip_friction_bps,
        "source_replay_fingerprint": scenario.source_replay_fingerprint,
        "decision_path_fingerprint": scenario.decision_path_fingerprint,
        "capital_path_fingerprint": scenario.capital_path_fingerprint,
        "trade_count": scenario.trade_count,
        "final_equity": scenario.final_equity,
        "total_return": scenario.total_return,
        "max_drawdown": scenario.max_drawdown,
        "minimum_equity": scenario.minimum_equity,
        "total_friction_cost": scenario.total_friction_cost,
        "incremental_friction_cost": scenario.incremental_friction_cost,
        "average_trade_net_return": scenario.average_trade_net_return,
        "ruin_occurred": scenario.ruin_occurred,
        "status": "RESEARCH_COST_STRESS_SCENARIO_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def _report_payload(report: TransactionCostStressReportV1) -> dict[str, object]:
    return {
        "stress_version": TRANSACTION_COST_STRESS_VERSION,
        "state": report.state,
        "reason_codes": report.reason_codes,
        "config_fingerprint": report.config_fingerprint,
        "source_replay_fingerprint": report.source_replay_fingerprint,
        "baseline_replay_config_fingerprint": report.baseline_replay_config_fingerprint,
        "decision_path_fingerprint": report.decision_path_fingerprint,
        "capital_path_fingerprint": report.capital_path_fingerprint,
        "source_trade_fingerprints": report.source_trade_fingerprints,
        "baseline_trade_count": report.baseline_trade_count,
        "scenario_count": report.scenario_count,
        "scenarios": [item.model_dump(mode="json") for item in report.scenarios],
        "worst_total_return": report.worst_total_return,
        "worst_max_drawdown": report.worst_max_drawdown,
        "maximum_total_friction_cost": report.maximum_total_friction_cost,
        "profitable_scenario_fraction": report.profitable_scenario_fraction,
        "status": "RESEARCH_TRANSACTION_COST_STRESS_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def _validate_baseline_replay(
    replay: HistoricalReplay,
    baseline_config: ReplayConfig,
) -> None:
    if replay.config_fingerprint != baseline_config.fingerprint:
        raise ValueError("baseline ReplayConfig fingerprint does not match source replay")
    expected_friction = baseline_config.round_trip_friction_bps
    if any(
        not math.isclose(
            trade.friction_bps,
            expected_friction,
            rel_tol=1e-12,
            abs_tol=1e-12,
        )
        for trade in replay.trades
    ):
        raise ValueError("source replay trade friction does not match baseline config")


def _inconclusive_report(
    replay: HistoricalReplay,
    baseline_config: ReplayConfig,
    stress_config: TransactionCostStressConfigV1,
    reason: str,
) -> TransactionCostStressReportV1:
    decision_path_fingerprint = canonical_fingerprint(_decision_path_payload(replay))
    capital_path_fingerprint = canonical_fingerprint(_capital_path_payload(replay))
    source_trade_fingerprints = [trade.trade_fingerprint for trade in replay.trades]
    payload = {
        "stress_version": TRANSACTION_COST_STRESS_VERSION,
        "state": "INCONCLUSIVE",
        "reason_codes": [reason],
        "config_fingerprint": stress_config.fingerprint,
        "source_replay_fingerprint": replay.replay_fingerprint,
        "baseline_replay_config_fingerprint": baseline_config.fingerprint,
        "decision_path_fingerprint": decision_path_fingerprint,
        "capital_path_fingerprint": capital_path_fingerprint,
        "source_trade_fingerprints": source_trade_fingerprints,
        "baseline_trade_count": len(replay.trades),
        "scenario_count": 0,
        "scenarios": [],
        "worst_total_return": None,
        "worst_max_drawdown": None,
        "maximum_total_friction_cost": None,
        "profitable_scenario_fraction": None,
        "status": "RESEARCH_TRANSACTION_COST_STRESS_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TransactionCostStressReportV1(
        state="INCONCLUSIVE",
        reason_codes=[reason],
        config_fingerprint=stress_config.fingerprint,
        source_replay_fingerprint=replay.replay_fingerprint,
        baseline_replay_config_fingerprint=baseline_config.fingerprint,
        decision_path_fingerprint=decision_path_fingerprint,
        capital_path_fingerprint=capital_path_fingerprint,
        source_trade_fingerprints=source_trade_fingerprints,
        baseline_trade_count=len(replay.trades),
        scenario_count=0,
        scenarios=[],
        report_fingerprint=canonical_fingerprint(payload),
    )


def _evaluate_scenario(
    replay: HistoricalReplay,
    *,
    baseline_config: ReplayConfig,
    stress_config: TransactionCostStressConfigV1,
    scenario_index: int,
    transaction_cost_multiplier: float,
    slippage_multiplier: float,
    decision_path_fingerprint: str,
    capital_path_fingerprint: str,
) -> TransactionCostStressScenarioV1:
    transaction_cost_bps = (
        baseline_config.transaction_cost_bps * transaction_cost_multiplier
    )
    slippage_bps_per_side = (
        baseline_config.slippage_bps_per_side * slippage_multiplier
    )
    round_trip_friction_bps = transaction_cost_bps + (2.0 * slippage_bps_per_side)
    friction_fraction = round_trip_friction_bps / 10_000.0

    equity = replay.metrics.initial_equity
    peak_equity = equity
    minimum_equity = equity
    max_drawdown = 0.0
    total_friction_cost = 0.0
    net_returns: list[float] = []
    for trade in replay.trades:
        net_return = trade.gross_return - friction_fraction
        friction_cost = trade.allocated_capital * friction_fraction
        pnl = trade.allocated_capital * net_return
        equity += pnl
        peak_equity = max(peak_equity, equity)
        minimum_equity = min(minimum_equity, equity)
        drawdown = (
            1.0
            if equity <= 0.0
            else max(0.0, min(1.0, (peak_equity - equity) / peak_equity))
        )
        max_drawdown = max(max_drawdown, drawdown)
        total_friction_cost += friction_cost
        net_returns.append(net_return)

    incremental_friction_cost = max(
        0.0,
        total_friction_cost - replay.metrics.total_friction_cost,
    )
    scenario_payload = {
        "stress_version": TRANSACTION_COST_STRESS_VERSION,
        "scenario_index": scenario_index,
        "transaction_cost_multiplier": transaction_cost_multiplier,
        "slippage_multiplier": slippage_multiplier,
        "transaction_cost_bps": transaction_cost_bps,
        "slippage_bps_per_side": slippage_bps_per_side,
        "round_trip_friction_bps": round_trip_friction_bps,
        "source_replay_fingerprint": replay.replay_fingerprint,
        "decision_path_fingerprint": decision_path_fingerprint,
        "capital_path_fingerprint": capital_path_fingerprint,
        "trade_count": len(replay.trades),
        "final_equity": equity,
        "total_return": (equity / replay.metrics.initial_equity) - 1.0,
        "max_drawdown": max_drawdown,
        "minimum_equity": minimum_equity,
        "total_friction_cost": total_friction_cost,
        "incremental_friction_cost": incremental_friction_cost,
        "average_trade_net_return": sum(net_returns) / len(net_returns),
        "ruin_occurred": minimum_equity <= 0.0,
        "status": "RESEARCH_COST_STRESS_SCENARIO_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TransactionCostStressScenarioV1(
        **scenario_payload,
        scenario_fingerprint=canonical_fingerprint(scenario_payload),
    )


def run_transaction_cost_stress_grid(
    replay: HistoricalReplay,
    baseline_config: ReplayConfig,
    *,
    config: TransactionCostStressConfigV1 | None = None,
) -> TransactionCostStressReportV1:
    """Reprice the exact replay decision/capital path across friction assumptions."""

    checked_replay = HistoricalReplay.model_validate(replay.model_dump(mode="python"))
    checked_baseline = ReplayConfig.model_validate(baseline_config.model_dump(mode="python"))
    stress_config = config or TransactionCostStressConfigV1()
    _validate_baseline_replay(checked_replay, checked_baseline)

    if not checked_replay.trades:
        return _inconclusive_report(
            checked_replay,
            checked_baseline,
            stress_config,
            "NO_BASELINE_TRADES",
        )
    if checked_baseline.round_trip_friction_bps <= 0.0:
        return _inconclusive_report(
            checked_replay,
            checked_baseline,
            stress_config,
            "ZERO_BASELINE_FRICTION_CANNOT_STRESS",
        )

    decision_path_fingerprint = canonical_fingerprint(
        _decision_path_payload(checked_replay)
    )
    capital_path_fingerprint = canonical_fingerprint(_capital_path_payload(checked_replay))
    source_trade_fingerprints = [
        trade.trade_fingerprint for trade in checked_replay.trades
    ]

    scenarios: list[TransactionCostStressScenarioV1] = []
    for transaction_cost_multiplier in stress_config.transaction_cost_multipliers:
        for slippage_multiplier in stress_config.slippage_multipliers:
            scenarios.append(
                _evaluate_scenario(
                    checked_replay,
                    baseline_config=checked_baseline,
                    stress_config=stress_config,
                    scenario_index=len(scenarios),
                    transaction_cost_multiplier=transaction_cost_multiplier,
                    slippage_multiplier=slippage_multiplier,
                    decision_path_fingerprint=decision_path_fingerprint,
                    capital_path_fingerprint=capital_path_fingerprint,
                )
            )

    baseline_scenario = next(
        item
        for item in scenarios
        if item.transaction_cost_multiplier == 1.0 and item.slippage_multiplier == 1.0
    )
    baseline_checks = (
        math.isclose(
            baseline_scenario.final_equity,
            checked_replay.metrics.final_equity,
            rel_tol=1e-12,
            abs_tol=1e-9,
        ),
        math.isclose(
            baseline_scenario.total_return,
            checked_replay.metrics.total_return,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ),
        math.isclose(
            baseline_scenario.max_drawdown,
            checked_replay.metrics.max_drawdown,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ),
        math.isclose(
            baseline_scenario.total_friction_cost,
            checked_replay.metrics.total_friction_cost,
            rel_tol=1e-12,
            abs_tol=1e-9,
        ),
    )
    if not all(baseline_checks):
        raise ValueError("1.0x stress scenario does not reproduce source replay")

    worst_total_return = min(item.total_return for item in scenarios)
    worst_max_drawdown = max(item.max_drawdown for item in scenarios)
    maximum_total_friction_cost = max(item.total_friction_cost for item in scenarios)
    profitable_scenario_fraction = sum(
        item.total_return > 0.0 for item in scenarios
    ) / len(scenarios)
    payload = {
        "stress_version": TRANSACTION_COST_STRESS_VERSION,
        "state": "EVALUATED",
        "reason_codes": [],
        "config_fingerprint": stress_config.fingerprint,
        "source_replay_fingerprint": checked_replay.replay_fingerprint,
        "baseline_replay_config_fingerprint": checked_baseline.fingerprint,
        "decision_path_fingerprint": decision_path_fingerprint,
        "capital_path_fingerprint": capital_path_fingerprint,
        "source_trade_fingerprints": source_trade_fingerprints,
        "baseline_trade_count": len(checked_replay.trades),
        "scenario_count": len(scenarios),
        "scenarios": [item.model_dump(mode="json") for item in scenarios],
        "worst_total_return": worst_total_return,
        "worst_max_drawdown": worst_max_drawdown,
        "maximum_total_friction_cost": maximum_total_friction_cost,
        "profitable_scenario_fraction": profitable_scenario_fraction,
        "status": "RESEARCH_TRANSACTION_COST_STRESS_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TransactionCostStressReportV1(
        state="EVALUATED",
        reason_codes=[],
        config_fingerprint=stress_config.fingerprint,
        source_replay_fingerprint=checked_replay.replay_fingerprint,
        baseline_replay_config_fingerprint=checked_baseline.fingerprint,
        decision_path_fingerprint=decision_path_fingerprint,
        capital_path_fingerprint=capital_path_fingerprint,
        source_trade_fingerprints=source_trade_fingerprints,
        baseline_trade_count=len(checked_replay.trades),
        scenario_count=len(scenarios),
        scenarios=scenarios,
        worst_total_return=worst_total_return,
        worst_max_drawdown=worst_max_drawdown,
        maximum_total_friction_cost=maximum_total_friction_cost,
        profitable_scenario_fraction=profitable_scenario_fraction,
        report_fingerprint=canonical_fingerprint(payload),
    )
