from __future__ import annotations

import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .model_fair_comparison import ModelAggregateMetrics, ModelFairComparisonReport

BASELINE_VERSION = "structured-alpha-baseline.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ChallengerFamily = Literal["LIGHTGBM", "PYTORCH"]
GateState = Literal["PROMISING", "BASELINE_NOT_BEATEN", "INCONCLUSIVE"]
ReportState = Literal["INCONCLUSIVE", "BASELINE_RETAINED", "CHALLENGER_PROMISING"]


class StructuredAlphaBaselineConfig(BaseModel):
    """Conservative, versioned RESEARCH selection criteria, never a promotion gate."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_oos_rows: Annotated[int, Field(ge=20, le=1_000_000)] = 60
    minimum_oos_trades: Annotated[int, Field(ge=5, le=1_000_000)] = 20
    minimum_incremental_net_return: Annotated[float, Field(ge=0.0, le=1.0)] = 0.005
    maximum_brier_deterioration: Annotated[float, Field(ge=0.0, le=1.0)] = 0.02
    maximum_drawdown_deterioration: Annotated[float, Field(ge=0.0, le=1.0)] = 0.02

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CandidateResearchGate(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_family: ChallengerFamily
    model_version: str
    source_metrics_fingerprint: Hash64
    evaluated_folds: int
    evaluated_rows: int
    trade_count: int
    net_return_after_costs: float
    incremental_net_return: float
    brier_score: float
    max_drawdown: float
    state: GateState
    reason_codes: list[str]
    gate_fingerprint: Hash64

    @model_validator(mode="after")
    def check_evidence(self) -> CandidateResearchGate:
        if self.state == "PROMISING" and self.reason_codes:
            raise ValueError("promising candidate cannot have failed research gates")
        if self.state != "PROMISING" and not self.reason_codes:
            raise ValueError("non-promising candidate requires explicit reasons")
        payload = self.model_dump(mode="json", exclude={"gate_fingerprint"})
        if self.gate_fingerprint != canonical_fingerprint(payload):
            raise ValueError("candidate research-gate fingerprint mismatch")
        return self


class StructuredAlphaBaselineReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["structured-alpha-baseline.v1"] = BASELINE_VERSION
    source_comparison_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    config_fingerprint: Hash64
    catboost_metrics_fingerprint: Hash64
    catboost_oos_rows: int
    catboost_oos_trades: int
    catboost_net_return_after_costs: float
    status: ReportState
    research_leader: Literal["CATBOOST", "LIGHTGBM", "PYTORCH"] | None
    reason_codes: list[str]
    challengers: list[CandidateResearchGate]
    report_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_report(self) -> StructuredAlphaBaselineReport:
        if not self.challengers or self.challengers[0].model_family != "LIGHTGBM":
            raise ValueError("LightGBM challenger is mandatory and must be first")
        if [candidate.model_family for candidate in self.challengers] not in [
            ["LIGHTGBM"], ["LIGHTGBM", "PYTORCH"]
        ]:
            raise ValueError("challenger list must use canonical family order")
        if self.status == "INCONCLUSIVE" and self.research_leader is not None:
            raise ValueError("inconclusive research cannot select a leader")
        if self.status == "BASELINE_RETAINED" and self.research_leader != "CATBOOST":
            raise ValueError("baseline retained must select CATBOOST only")
        if self.status == "CHALLENGER_PROMISING" and self.research_leader not in {
            "LIGHTGBM", "PYTORCH"
        }:
            raise ValueError("promising challenger must be the research leader")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("structured alpha report fingerprint mismatch")
        return self


def _gate(
    challenger: ModelAggregateMetrics,
    baseline: ModelAggregateMetrics,
    config: StructuredAlphaBaselineConfig,
    baseline_reasons: list[str],
) -> CandidateResearchGate:
    reasons: list[str] = []
    if challenger.evaluated_rows < config.minimum_oos_rows:
        reasons.append("INSUFFICIENT_CHALLENGER_OOS_ROWS")
    if challenger.trade_count < config.minimum_oos_trades:
        reasons.append("INSUFFICIENT_CHALLENGER_TRADES")
    if (challenger.evaluated_folds != baseline.evaluated_folds
            or challenger.evaluated_rows != baseline.evaluated_rows):
        reasons.append("MISMATCHED_EVALUATION_COUNTS")

    delta = challenger.total_net_return - baseline.total_net_return
    insufficient = bool(reasons or baseline_reasons)
    if not insufficient:
        if delta <= config.minimum_incremental_net_return:
            reasons.append("NO_INCREMENTAL_NET_VALUE")
        if challenger.total_net_return <= 0.0:
            reasons.append("NON_POSITIVE_NET_RETURN")
        if challenger.brier_score > baseline.brier_score + config.maximum_brier_deterioration:
            reasons.append("CALIBRATION_REGRESSION")
        if challenger.max_drawdown > baseline.max_drawdown + config.maximum_drawdown_deterioration:
            reasons.append("DRAWDOWN_REGRESSION")

    state: GateState = (
        "INCONCLUSIVE" if insufficient else
        "BASELINE_NOT_BEATEN" if reasons else "PROMISING"
    )
    if baseline_reasons:
        reasons.extend("BASELINE_" + reason for reason in baseline_reasons)
    payload = {
        "model_family": challenger.model_family,
        "model_version": challenger.model_version,
        "source_metrics_fingerprint": challenger.metrics_fingerprint,
        "evaluated_folds": challenger.evaluated_folds,
        "evaluated_rows": challenger.evaluated_rows,
        "trade_count": challenger.trade_count,
        "net_return_after_costs": challenger.total_net_return,
        "incremental_net_return": round(delta, 12),
        "brier_score": challenger.brier_score,
        "max_drawdown": challenger.max_drawdown,
        "state": state,
        "reason_codes": sorted(set(reasons)),
    }
    return CandidateResearchGate(
        **payload,
        gate_fingerprint=canonical_fingerprint(payload),
    )


def evaluate_structured_alpha_baseline(
    source: ModelFairComparisonReport,
    *,
    config: StructuredAlphaBaselineConfig | None = None,
) -> StructuredAlphaBaselineReport:
    """Evaluate CatBoost vs challengers on inherited, identical purged OOS folds.

    Only a research leader may be selected; no PAPER/SHADOW/LIVE or capital authority is granted.
    """
    checked = ModelFairComparisonReport.model_validate(source.model_dump(mode="python"))
    policy = config or StructuredAlphaBaselineConfig()
    by_family = {model.model_family: model for model in checked.models}
    baseline = by_family["CATBOOST"]
    baseline_reasons: list[str] = []
    if baseline.evaluated_rows < policy.minimum_oos_rows:
        baseline_reasons.append("INSUFFICIENT_OOS_ROWS")
    if baseline.trade_count < policy.minimum_oos_trades:
        baseline_reasons.append("INSUFFICIENT_OOS_TRADES")
    if len(checked.folds) != baseline.evaluated_folds:
        baseline_reasons.append("FOLD_COUNT_MISMATCH")

    families: list[ChallengerFamily] = ["LIGHTGBM"]
    if checked.included_pytorch:
        families.append("PYTORCH")
    gates = [_gate(by_family[family], baseline, policy, baseline_reasons) for family in families]
    incomplete = bool(baseline_reasons) or any(gate.state == "INCONCLUSIVE" for gate in gates)
    if incomplete:
        status: ReportState = "INCONCLUSIVE"
        leader = None
    else:
        promising = [gate for gate in gates if gate.state == "PROMISING"]
        if promising:
            winner = sorted(
                promising,
                key=lambda gate: (-gate.net_return_after_costs, gate.model_family),
            )[0]
            status = "CHALLENGER_PROMISING"
            leader = winner.model_family
        else:
            status = "BASELINE_RETAINED"
            leader = "CATBOOST"
    payload = {
        "contract_version": BASELINE_VERSION,
        "source_comparison_fingerprint": checked.report_fingerprint,
        "target_spec_fingerprint": checked.target_spec_fingerprint,
        "config_fingerprint": policy.fingerprint,
        "catboost_metrics_fingerprint": baseline.metrics_fingerprint,
        "catboost_oos_rows": baseline.evaluated_rows,
        "catboost_oos_trades": baseline.trade_count,
        "catboost_net_return_after_costs": baseline.total_net_return,
        "status": status,
        "research_leader": leader,
        "reason_codes": sorted(set(baseline_reasons)),
        "challengers": [gate.model_dump(mode="json") for gate in gates],
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    if not all(math.isfinite(gate.incremental_net_return) for gate in gates):
        raise ValueError("net-return differences must be finite")
    return StructuredAlphaBaselineReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )
