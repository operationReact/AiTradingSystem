from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.execution_plan import ExecutionCostEstimateV1, ExecutionPlanV1
from aitrading_intelligence.fill_simulator import (
    FillSimulationRequestV1,
    FillSimulationResultV1,
    SimulatedFillV1,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.paper_smart_reprice import (
    PaperSmartRepriceConfigV1,
    decide_paper_smart_limit,
)

AS_OF = datetime(2026, 11, 3, 4, 30, tzinfo=UTC)


def _cost(*, slippage_bps: float = 20.0) -> ExecutionCostEstimateV1:
    payload = {
        "cost_model_version": "execution-cost-model.v1",
        "fee_tax_config_version": "india-fee-tax-config.v1",
        "slippage_model_version": "state-dependent-slippage.v1",
        "fill_simulator_version": "fill-simulator.v1",
        "fee_schedule_fingerprint": "1" * 64,
        "slippage_estimate_fingerprint": "2" * 64,
        "slippage_config_fingerprint": "3" * 64,
        "fill_config_fingerprint": "4" * 64,
        "notional_inr": 10_000.0,
        "brokerage_bps": 1.0,
        "exchange_transaction_charge_bps": 0.2,
        "stt_bps": 0.3,
        "sebi_turnover_charge_bps": 0.1,
        "stamp_duty_bps": 0.1,
        "gst_bps": 0.3,
        "total_fee_tax_bps": 2.0,
        "expected_slippage_bps": slippage_bps,
        "expected_total_cost_bps": 2.0 + slippage_bps,
    }
    provisional = ExecutionCostEstimateV1.model_construct(
        **payload,
        cost_model_fingerprint="0" * 64,
    )
    return ExecutionCostEstimateV1(
        **payload,
        cost_model_fingerprint=canonical_fingerprint(
            provisional.model_dump(mode="json", exclude={"cost_model_fingerprint"})
        ),
    )


def _plan(
    *,
    side: str = "BUY",
    order_type: str = "MARKET",
    limit_price: float | None = None,
    slippage_bps: float = 20.0,
    quantity: int = 100,
) -> ExecutionPlanV1:
    cost = _cost(slippage_bps=slippage_bps)
    payload = {
        "execution_plan_version": "execution-plan.v1",
        "state": "PLANNED",
        "reason_codes": [],
        "plan_as_of": AS_OF,
        "candidate_fingerprint": "a" * 64,
        "canonical_instrument_id": "NSE:INE002A01018",
        "canonical_symbol": "RELIANCE",
        "broker_trading_symbol": "RELIANCE-EQ",
        "broker_symbol_token": "2885",
        "exchange": "NSE",
        "product": "EQUITY_INTRADAY",
        "side": side,
        "order_type": order_type,
        "quantity": quantity,
        "reference_price": 100.0,
        "limit_price": limit_price,
        "identity_bridge_fingerprint": "5" * 64,
        "identity_binding_fingerprint": "6" * 64,
        "fee_schedule_id": "NSE-INTRADAY-FIXTURE",
        "fee_schedule_fingerprint": "1" * 64,
        "slippage_estimate_fingerprint": "2" * 64,
        "fill_config_fingerprint": "4" * 64,
        "expected_cost": cost,
        "status": "RESEARCH_EXECUTION_PLAN_ONLY",
        "executable": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = ExecutionPlanV1.model_construct(
        **payload,
        plan_fingerprint="0" * 64,
    )
    return ExecutionPlanV1(
        **payload,
        plan_fingerprint=canonical_fingerprint(
            provisional.model_dump(mode="json", exclude={"plan_fingerprint"})
        ),
    )


def _request_payload(request: FillSimulationRequestV1) -> dict[str, object]:
    return request.model_dump(mode="json", exclude={"request_fingerprint"})


def _fill_payload(fill: SimulatedFillV1) -> dict[str, object]:
    return fill.model_dump(mode="json", exclude={"fill_fingerprint"})


def _result_payload(result: FillSimulationResultV1) -> dict[str, object]:
    return result.model_dump(mode="json", exclude={"result_fingerprint"})


def _fill_result(
    plan: ExecutionPlanV1,
    previous_limit: float,
    *,
    quantity: int,
    filled_quantity: int = 0,
    state: str = "UNFILLED",
    request_instrument: str | None = None,
) -> FillSimulationResultV1:
    request_payload = {
        "simulator_version": "fill-simulator.v1",
        "order_id": "paper-smart-limit",
        "canonical_symbol": plan.canonical_symbol,
        "canonical_instrument_id": request_instrument or plan.canonical_instrument_id,
        "broker_trading_symbol": plan.broker_trading_symbol,
        "side": plan.side,
        "order_type": "LIMIT",
        "quantity": quantity,
        "submitted_at": AS_OF,
        "reference_price": plan.reference_price,
        "limit_price": previous_limit,
        "config_fingerprint": plan.fill_config_fingerprint,
        "slippage_estimate_fingerprint": plan.slippage_estimate_fingerprint,
        "identity_bridge_fingerprint": plan.identity_bridge_fingerprint,
        "identity_binding_fingerprint": plan.identity_binding_fingerprint,
        "market_export_fingerprint": "7" * 64,
        "status": "RESEARCH_FILL_REQUEST_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    request_provisional = FillSimulationRequestV1.model_construct(
        **request_payload,
        request_fingerprint="0" * 64,
    )
    request = FillSimulationRequestV1(
        **request_payload,
        request_fingerprint=canonical_fingerprint(_request_payload(request_provisional)),
    )

    fills: list[SimulatedFillV1] = []
    average_fill_price = None
    if filled_quantity:
        fill_payload = {
            "fill_index": 0,
            "candle_time": AS_OF + timedelta(minutes=1),
            "candle_source_fingerprint": "8" * 64,
            "quantity": filled_quantity,
            "price": previous_limit,
            "bar_open": 100.0,
            "bar_high": max(101.0, previous_limit),
            "bar_low": min(99.0, previous_limit),
            "bar_volume": 1_000.0,
            "capacity_quantity": max(filled_quantity, 100),
            "slippage_bps_applied": 0.0,
            "price_bound_applied": True,
        }
        fill_provisional = SimulatedFillV1.model_construct(
            **fill_payload,
            fill_fingerprint="0" * 64,
        )
        fills = [
            SimulatedFillV1(
                **fill_payload,
                fill_fingerprint=canonical_fingerprint(_fill_payload(fill_provisional)),
            )
        ]
        average_fill_price = previous_limit

    remaining = quantity - filled_quantity
    if state == "FULLY_FILLED":
        reasons: list[str] = []
    elif state == "PARTIALLY_FILLED":
        reasons = ["WAIT_HORIZON_EXHAUSTED"]
    elif state == "INCONCLUSIVE":
        reasons = ["STALE_SLIPPAGE_EVIDENCE"]
    else:
        reasons = ["WAIT_HORIZON_EXHAUSTED"]

    result_payload = {
        "simulator_version": "fill-simulator.v1",
        "state": state,
        "reason_codes": reasons,
        "request": request,
        "eligible_at": AS_OF + timedelta(milliseconds=250),
        "latency_skipped_bar_count": 0,
        "considered_bar_count": 1,
        "requested_quantity": quantity,
        "filled_quantity": filled_quantity,
        "remaining_quantity": remaining,
        "fill_fraction": filled_quantity / quantity,
        "average_fill_price": average_fill_price,
        "arrival_gap_bps": 0.0,
        "fills": fills,
        "status": "RESEARCH_FILL_SIMULATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    result_provisional = FillSimulationResultV1.model_construct(
        **result_payload,
        result_fingerprint="0" * 64,
    )
    return FillSimulationResultV1(
        **result_payload,
        result_fingerprint=canonical_fingerprint(_result_payload(result_provisional)),
    )


def test_initial_smart_limit_is_bounded_deterministic_and_paper_only() -> None:
    plan = _plan()
    config = PaperSmartRepriceConfigV1()

    first = decide_paper_smart_limit(plan, config=config)
    second = decide_paper_smart_limit(plan, config=config)

    assert first == second
    assert first.state == "PLACE_LIMIT"
    assert first.reason_codes == ["INITIAL_SMART_LIMIT"]
    assert first.attempt_index == 0
    assert first.quantity_to_submit == 100
    assert first.proposed_limit_price == pytest.approx(100.10)
    assert first.concession_bps == pytest.approx(10.0)
    assert first.maximum_concession_bps == pytest.approx(20.0)
    assert first.cost_model_fingerprint == plan.expected_cost.cost_model_fingerprint
    assert first.paper_only is True
    assert first.executable is False
    assert first.broker_submission_allowed is False
    assert first.market_fallback_allowed is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_unfilled_limit_reprices_more_aggressively_within_budget() -> None:
    plan = _plan()
    config = PaperSmartRepriceConfigV1()
    initial = decide_paper_smart_limit(plan, config=config)
    fill = _fill_result(
        plan,
        initial.proposed_limit_price,
        quantity=initial.quantity_to_submit,
    )

    reprice = decide_paper_smart_limit(
        plan,
        config=config,
        previous_decision=initial,
        prior_fill_result=fill,
    )

    assert reprice.state == "REPRICE_LIMIT"
    assert reprice.reason_codes == ["PRIOR_UNFILLED"]
    assert reprice.attempt_index == 1
    assert reprice.quantity_to_submit == 100
    assert reprice.previous_limit_price == pytest.approx(100.10)
    assert reprice.proposed_limit_price == pytest.approx(100.15)
    assert reprice.concession_bps == pytest.approx(15.0)
    assert reprice.concession_bps <= reprice.maximum_concession_bps
    assert reprice.previous_decision_fingerprint == initial.decision_fingerprint
    assert reprice.prior_fill_result_fingerprint == fill.result_fingerprint


def test_partial_fill_reprices_only_remaining_quantity() -> None:
    plan = _plan(quantity=100)
    initial = decide_paper_smart_limit(plan)
    fill = _fill_result(
        plan,
        initial.proposed_limit_price,
        quantity=100,
        filled_quantity=40,
        state="PARTIALLY_FILLED",
    )

    reprice = decide_paper_smart_limit(
        plan,
        previous_decision=initial,
        prior_fill_result=fill,
    )

    assert reprice.state == "REPRICE_LIMIT"
    assert reprice.reason_codes == ["PRIOR_PARTIAL_FILL"]
    assert reprice.quantity_to_submit == 60


def test_max_reprice_count_stops_without_market_fallback() -> None:
    plan = _plan()
    config = PaperSmartRepriceConfigV1(max_reprices=1)
    initial = decide_paper_smart_limit(plan, config=config)
    first_fill = _fill_result(
        plan,
        initial.proposed_limit_price,
        quantity=100,
    )
    reprice = decide_paper_smart_limit(
        plan,
        config=config,
        previous_decision=initial,
        prior_fill_result=first_fill,
    )
    second_fill = _fill_result(
        plan,
        reprice.proposed_limit_price,
        quantity=100,
    )

    stopped = decide_paper_smart_limit(
        plan,
        config=config,
        previous_decision=reprice,
        prior_fill_result=second_fill,
    )

    assert stopped.state == "STOP"
    assert stopped.reason_codes == ["MAX_REPRICES_REACHED"]
    assert stopped.quantity_to_submit is None
    assert stopped.proposed_limit_price is None
    assert stopped.market_fallback_allowed is False
    assert stopped.broker_submission_allowed is False


def test_original_limit_is_tick_safe_hard_bound_and_can_stop_repricing() -> None:
    plan = _plan(order_type="LIMIT", limit_price=100.12)
    initial = decide_paper_smart_limit(plan)
    assert initial.proposed_limit_price == pytest.approx(100.10)

    fill = _fill_result(
        plan,
        initial.proposed_limit_price,
        quantity=100,
    )
    stopped = decide_paper_smart_limit(
        plan,
        previous_decision=initial,
        prior_fill_result=fill,
    )

    assert stopped.state == "STOP"
    assert stopped.reason_codes == ["NO_FURTHER_PRICE_CONCESSION"]
    assert stopped.previous_limit_price == pytest.approx(100.10)


def test_sell_policy_moves_downward_without_exceeding_slippage_budget() -> None:
    plan = _plan(side="SELL")
    initial = decide_paper_smart_limit(plan)
    fill = _fill_result(
        plan,
        initial.proposed_limit_price,
        quantity=100,
    )
    reprice = decide_paper_smart_limit(
        plan,
        previous_decision=initial,
        prior_fill_result=fill,
    )

    assert initial.proposed_limit_price == pytest.approx(99.90)
    assert reprice.proposed_limit_price == pytest.approx(99.85)
    assert reprice.proposed_limit_price < initial.proposed_limit_price
    assert reprice.concession_bps <= reprice.maximum_concession_bps


def test_inconclusive_prior_fill_is_not_repriced() -> None:
    plan = _plan()
    initial = decide_paper_smart_limit(plan)
    fill = _fill_result(
        plan,
        initial.proposed_limit_price,
        quantity=100,
        state="INCONCLUSIVE",
    )

    result = decide_paper_smart_limit(
        plan,
        previous_decision=initial,
        prior_fill_result=fill,
    )

    assert result.state == "INCONCLUSIVE"
    assert result.reason_codes == ["PRIOR_FILL_INCONCLUSIVE"]
    assert result.quantity_to_submit is None
    assert result.proposed_limit_price is None


def test_reprice_rejects_mismatched_fill_lineage() -> None:
    plan = _plan()
    initial = decide_paper_smart_limit(plan)
    fill = _fill_result(
        plan,
        initial.proposed_limit_price,
        quantity=100,
        request_instrument="NSE:WRONG",
    )

    with pytest.raises(ValueError, match="prior fill instrument does not match"):
        decide_paper_smart_limit(
            plan,
            previous_decision=initial,
            prior_fill_result=fill,
        )
