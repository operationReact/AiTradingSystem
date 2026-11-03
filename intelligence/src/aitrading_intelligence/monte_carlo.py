from __future__ import annotations

import math
import random
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

from .fingerprints import canonical_fingerprint
from .walk_forward import WalkForwardEvidence

MONTE_CARLO_VERSION = "monte-carlo.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Assessment = Literal[
    "V2_09_GOVERNANCE_CANDIDATE",
    "V2_08_RESEARCH_REVIEW",
]
BootstrapMethod = Literal["stationary", "moving_block"]


class MonteCarloConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    monte_carlo_version: Literal["monte-carlo.v1"] = MONTE_CARLO_VERSION
    simulations: Annotated[int, Field(ge=100, le=100_000)] = 2_000
    seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42
    bootstrap_method: BootstrapMethod = "stationary"
    block_size: Annotated[int, Field(ge=2, le=100)] = 5
    minimum_oos_trades: Annotated[int, Field(ge=5, le=100_000)] = 20
    ruin_equity_fraction: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.50
    drawdown_limit: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.25
    maximum_ruin_probability: Annotated[float, Field(ge=0.0, le=1.0)] = 0.01
    maximum_drawdown_breach_probability: Annotated[float, Field(ge=0.0, le=1.0)] = 0.25
    minimum_p05_total_return: Annotated[float, Field(ge=-1.0, le=10.0)] = -0.10
    minimum_profitable_fold_fraction: Annotated[float, Field(ge=0.0, le=1.0)] = 0.50

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class MonteCarloSummary(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    simulations: Annotated[int, Field(ge=100)]
    oos_trade_count: Annotated[int, Field(ge=5)]
    p05_total_return: float
    median_total_return: float
    p95_total_return: float
    p50_max_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]
    p95_max_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]
    probability_of_loss: Annotated[float, Field(ge=0.0, le=1.0)]
    probability_of_ruin: Annotated[float, Field(ge=0.0, le=1.0)]
    probability_drawdown_exceeds_limit: Annotated[float, Field(ge=0.0, le=1.0)]
    profitable_fold_fraction: Annotated[float, Field(ge=0.0, le=1.0)]
    worst_observed_fold_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]


class MonteCarloEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    monte_carlo_version: Literal["monte-carlo.v1"] = MONTE_CARLO_VERSION
    config_fingerprint: Hash64
    source_walk_forward_fingerprint: Hash64
    source_trade_fingerprints: list[Hash64]
    returns_distribution_fingerprint: Hash64
    drawdowns_distribution_fingerprint: Hash64
    summary: MonteCarloSummary
    assessment: Assessment
    next_gate: Literal[
        "V2_09_OUTCOME_GOVERNANCE",
        "V2_08_RESEARCH_REVIEW",
    ]
    evidence_fingerprint: Hash64
    status: Literal["RESEARCH_MONTE_CARLO_ONLY"] = "RESEARCH_MONTE_CARLO_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


def _quantile(values: list[float], probability: float) -> float:
    if not values:
        raise ValueError("quantile requires at least one value")
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * probability
    lower_index = math.floor(position)
    upper_index = math.ceil(position)
    if lower_index == upper_index:
        return ordered[lower_index]
    weight = position - lower_index
    return ordered[lower_index] + (ordered[upper_index] - ordered[lower_index]) * weight


def _unique_oos_portfolio_returns(
    evidence: WalkForwardEvidence,
) -> tuple[list[float], list[str]]:
    trades_by_fingerprint = {}
    for fold in evidence.folds:
        for trade in fold.replay.trades:
            trades_by_fingerprint.setdefault(trade.trade_fingerprint, trade)
    trades = sorted(
        trades_by_fingerprint.values(),
        key=lambda trade: (trade.entry_at, trade.symbol, trade.trade_fingerprint),
    )
    returns = [trade.pnl / trade.equity_before for trade in trades]
    if any(not math.isfinite(value) or value <= -1.0 for value in returns):
        raise ValueError("out-of-sample trade returns must be finite and greater than -100%")
    return returns, [trade.trade_fingerprint for trade in trades]


def _moving_block_bootstrap_sequence(
    values: list[float],
    *,
    block_size: int,
    rng: random.Random,
) -> list[float]:
    sampled: list[float] = []
    while len(sampled) < len(values):
        start = rng.randrange(len(values))
        for offset in range(block_size):
            sampled.append(values[(start + offset) % len(values)])
            if len(sampled) == len(values):
                break
    return sampled


def _stationary_bootstrap_sequence(
    values: list[float],
    *,
    mean_block_size: int,
    rng: random.Random,
) -> list[float]:
    if not values:
        return []

    restart_probability = 1.0 / mean_block_size
    index = rng.randrange(len(values))
    sampled = [values[index]]
    while len(sampled) < len(values):
        if rng.random() < restart_probability:
            index = rng.randrange(len(values))
        else:
            index = (index + 1) % len(values)
        sampled.append(values[index])
    return sampled


def _bootstrap_sequence(
    values: list[float],
    *,
    method: BootstrapMethod,
    block_size: int,
    rng: random.Random,
) -> list[float]:
    if method == "stationary":
        return _stationary_bootstrap_sequence(
            values,
            mean_block_size=block_size,
            rng=rng,
        )
    return _moving_block_bootstrap_sequence(
        values,
        block_size=block_size,
        rng=rng,
    )


def _simulate_path(
    returns: list[float],
    *,
    ruin_equity_fraction: float,
    drawdown_limit: float,
) -> tuple[float, float, bool, bool]:
    equity = 1.0
    peak = 1.0
    max_drawdown = 0.0
    ruined = False
    breached_drawdown = False
    for value in returns:
        equity *= 1.0 + value
        peak = max(peak, equity)
        drawdown = max(0.0, (peak - equity) / peak)
        max_drawdown = max(max_drawdown, drawdown)
        if equity <= ruin_equity_fraction:
            ruined = True
        if drawdown >= drawdown_limit:
            breached_drawdown = True
    return equity - 1.0, max_drawdown, ruined, breached_drawdown


def run_monte_carlo_robustness(
    evidence: WalkForwardEvidence,
    *,
    config: MonteCarloConfig | None = None,
) -> MonteCarloEvidence:
    config = config or MonteCarloConfig()
    portfolio_returns, trade_fingerprints = _unique_oos_portfolio_returns(evidence)
    if len(portfolio_returns) < config.minimum_oos_trades:
        raise ValueError("not enough unique out-of-sample trades for Monte Carlo")

    rng = random.Random(config.seed)
    total_returns: list[float] = []
    max_drawdowns: list[float] = []
    ruin_count = 0
    drawdown_breach_count = 0
    for _ in range(config.simulations):
        sequence = _bootstrap_sequence(
            portfolio_returns,
            method=config.bootstrap_method,
            block_size=config.block_size,
            rng=rng,
        )
        total_return, max_drawdown, ruined, breached = _simulate_path(
            sequence,
            ruin_equity_fraction=config.ruin_equity_fraction,
            drawdown_limit=config.drawdown_limit,
        )
        total_returns.append(total_return)
        max_drawdowns.append(max_drawdown)
        ruin_count += int(ruined)
        drawdown_breach_count += int(breached)

    profitable_fold_fraction = (
        evidence.summary.profitable_folds / evidence.summary.fold_count
    )
    probability_of_ruin = ruin_count / config.simulations
    probability_drawdown_exceeds_limit = drawdown_breach_count / config.simulations
    summary = MonteCarloSummary(
        simulations=config.simulations,
        oos_trade_count=len(portfolio_returns),
        p05_total_return=_quantile(total_returns, 0.05),
        median_total_return=_quantile(total_returns, 0.50),
        p95_total_return=_quantile(total_returns, 0.95),
        p50_max_drawdown=_quantile(max_drawdowns, 0.50),
        p95_max_drawdown=_quantile(max_drawdowns, 0.95),
        probability_of_loss=sum(value < 0.0 for value in total_returns) / config.simulations,
        probability_of_ruin=probability_of_ruin,
        probability_drawdown_exceeds_limit=probability_drawdown_exceeds_limit,
        profitable_fold_fraction=profitable_fold_fraction,
        worst_observed_fold_drawdown=evidence.summary.worst_fold_drawdown,
    )

    passes = (
        summary.p05_total_return >= config.minimum_p05_total_return
        and summary.probability_of_ruin <= config.maximum_ruin_probability
        and summary.probability_drawdown_exceeds_limit
        <= config.maximum_drawdown_breach_probability
        and summary.profitable_fold_fraction >= config.minimum_profitable_fold_fraction
        and summary.worst_observed_fold_drawdown <= config.drawdown_limit
    )
    assessment: Assessment = (
        "V2_09_GOVERNANCE_CANDIDATE" if passes else "V2_08_RESEARCH_REVIEW"
    )
    next_gate = (
        "V2_09_OUTCOME_GOVERNANCE" if passes else "V2_08_RESEARCH_REVIEW"
    )
    returns_distribution_fingerprint = canonical_fingerprint(total_returns)
    drawdowns_distribution_fingerprint = canonical_fingerprint(max_drawdowns)
    payload = {
        "monte_carlo_version": MONTE_CARLO_VERSION,
        "config_fingerprint": config.fingerprint,
        "source_walk_forward_fingerprint": evidence.evidence_fingerprint,
        "source_trade_fingerprints": trade_fingerprints,
        "returns_distribution_fingerprint": returns_distribution_fingerprint,
        "drawdowns_distribution_fingerprint": drawdowns_distribution_fingerprint,
        "summary": summary.model_dump(mode="json"),
        "assessment": assessment,
        "next_gate": next_gate,
        "status": "RESEARCH_MONTE_CARLO_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return MonteCarloEvidence(
        config_fingerprint=config.fingerprint,
        source_walk_forward_fingerprint=evidence.evidence_fingerprint,
        source_trade_fingerprints=trade_fingerprints,
        returns_distribution_fingerprint=returns_distribution_fingerprint,
        drawdowns_distribution_fingerprint=drawdowns_distribution_fingerprint,
        summary=summary,
        assessment=assessment,
        next_gate=next_gate,
        evidence_fingerprint=canonical_fingerprint(payload),
    )
