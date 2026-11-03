from __future__ import annotations

from datetime import UTC, datetime

import pytest

from aitrading_intelligence.execution_plan import (
    ExecutionCostEstimateV1,
    ExecutionPlanV1,
)
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

AS_OF = datetime(2026, 11, 3, 9, 15, tzinfo=UTC)
H = {
    "candidate": "1" * 64,
    "fee": "2" * 64,
    "slippage": "3" * 64,
    "slippage_config": "4" * 64,
    "fill_config": "5" * 64,
    "bridge": "6" * 64,
    "binding": "7" * 64,
    "market": "8" * 64,
}


def _fingerprinted(model_cls, payload: dict, fingerprint_field: str):
    provisional = model_cls.model_construct(**payload, **{fingerprint_field: "0" * 64})
    fingerprint = canonical_fingerprint(
        provisional.model_dump(mode="json", exclude={fingerprint_field})
    )
    return model_cls(**payload, **{fingerprint_field: fingerprint})


def _plan(
    *,
    side: str = "BUY",
    order_type: str = "MARKET",
    limit_price: float | None = None,
    expected_slippage_bps: float = 8.0,
    state: str = "PLANNED",
) -> ExecutionPlanV1:
    expected_cost = None
    if state == "PLANNED":
        fee_bps = 1.8458 if side == "BUY" else 1.5458
        cost_payload = {
            "cost_model_version": "execution-cost-model.v1",
            "fee_tax_config_version": "india-fee-tax-config.v1",
            "slippage_model_version": "state-dependent-slippage.v1",
            "fill_simulator_version": "fill-simulator.v1",
            "fee_schedule_fingerprint": H["fee"],
            "slippage_estimate_fingerprint": H["slippage"],
            "slippage_config_fingerprint": H["slippage_config"],
            "fill_config_fingerprint": H["fill_config"],
            "notional_inr": 10_000.0,
            "brokerage_bps": 1.0,
            "exchange_transaction_charge_bps": 0.3,
            "stt_bps": 0.0,
            "sebi_turnover_charge_bps": 0.01,
            "stamp_duty_bps": 0.3 if side == "BUY" else 0.0,
            "gst_bps": 0.2358,
            "total_fee_tax_bps": fee_bps,
            "expected_slippage_bps": expected_slippage_bps,
            "expected_total_cost_bps": fee_bps + expected_slippage_bps,
        }
        expected_cost = _fingerprinted(
            ExecutionCostEstimateV1,
            cost_payload,
            "cost_model_fingerprint",
        )

    payload = {
        "execution_plan_version": "execution-plan.v1",
        "state": state,
        "reason_codes": [] if state == "PLANNED" else ["SLIPPAGE_ESTIMATE_INCONCLUSIVE"],
        "plan_as_of": AS_OF,
        "candidate_fingerprint": H["candidate"],
        "canonical_instrument_id": "NSE:INE002A01018",
        "canonical_symbol": "RELIANCE",
        "broker_trading_symbol": "RELIANCE-EQ",
        "broker_symbol_token": "2885",
        "exchange": "NSE",
        "product": "EQUITY_INTRADAY",
        "side": side,
        "order_type": order_type,
        "quantity": 100,
        "reference_price": 100.0,
        "limit_price": limit_price,
        "identity_bridge_fingerprint": H["bridge"],
        "identity_binding_fingerprint": H["binding"],
        "fee_schedule_id": "NSE-INTRADAY-FIXTURE",
        "fee_schedule_fingerprint": H["fee"],
        "slippage_estimate_fingerprint": H["slippage"],
        "fill_config_fingerprint": H["fill_config"],
        "expected_cost": expected_cost,
        "status": "RESEARCH_EXECUTION_PLAN_ONLY",
        "executable": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return _fingerprinted(ExecutionPlanV1, payload, "plan_fingerprint")


def _request(*, plan: ExecutionPlanV1, quantity: int, limit_price: float):
    payload = {
        "simulator_version": "fill-simulator.v1",
        "order_id": "paper-order",
        "canonical_symbol": plan.canonical_symbol,
        "canonical_instrument_id": plan.canonical_instrument_id,
        "broker_trading_symbol": plan.broker_trading_symbol,
        "side": plan.side,
        "order_type": "LIMIT",
        "quantity": quantity,
        "submitted_at": AS_OF,
        "reference_price": plan.reference_price,
        "limit_price": limit_price,
        "config_fingerprint": plan.fill_config_fingerprint,
        "slippage_estimate_fingerprint": plan.slippage_estimate_fingerprint,
        "identity_bridge_fingerprint": plan.identity_bridge_fingerprint,
        "identity_binding_fingerprint": plan.identity_binding_fingerprint,
        "market_export_fingerprint": H["market"],
        "status": "RESEARCH_FILL_REQUEST_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return _fingerprinted(FillSimulationRequestV1, payload, "request_fingerprint")


def _simulated_fill(*, quantity: int, price: float):
    payload = {
        "fill_index": 0,
        "candle_time": AS_OF,
        "candle_source_fingerprint": "9" * 64,
        "quantity": quantity,
        "price": price,
        "bar_open": price,
        "bar_high": price + 1.0,
        "bar_low": max(0.01, price - 1.0),
        "bar_volume": 10_000.0,
        "capacity_quantity": 1_000,
        "slippage_bps_applied": 8.0,
        "price_bound_applied": False,
    }
    return _fingerprinted(SimulatedFillV1, payload, "fill_fingerprint")


def _fill_result(
    *,
    plan: ExecutionPlanV1,
    decision,
    state: str,
    filled_quantity: int = 0,
) -> FillSimulationResultV1:
    assert decision.quantity_to_submit is not None
    assert decision.proposed_limit_price is not None
    request = _request(
        plan=plan,
        quantity=decision.quantity_to_submit,
        limit_price=decision.proposed_limit_price,
    )
    remaining = request.quantity - filled_quantity
    fills = []
    average_price = None
    if filled_quantity:
        average_price = decision.proposed_limit_price
        fills = [_simulated_fill(quantity=filled_quantity, price=average_price)]
    if state == "FULLY_FILLED":
        reason_codes = []
    elif state == "PARTIALLY_FILLED":
        reason_codes = ["MAX_WAIT_BARS_REACHED_WITH_REMAINDER"]
    elif state == "UNFILLED":
        reason_codes = ["LIMIT_NOT_REACHED_OR_NO_FILL_CAPACITY"]
    else:
        reason_codes = ["SLIPPAGE_ESTIMATE_INCONCLUSIVE"]
        filled_quantity = 0
        remaining = request.quantity
        fills = []
        average_price = None

    payload = {
        "simulator_version": "fill-simulator.v1",
        "state": state,
        "reason_codes": reason_codes,
        "request": request,
        "eligible_at": AS_OF,
        "latency_skipped_bar_count": 0,
        "considered_bar_count": 1,
        "requested_quantity": request.quantity,
        "filled_quantity": filled_quantity,
        "remaining_quantity": remaining,
        "fill_fraction": filled_quantity / request.quantity,
        "average_fill_price": average_price,
        "arrival_gap_bps": 0.0,
        "fills": fills,
        "status": "RESEARCH_FILL_SIMULATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return _fingerprinted(FillSimulationResultV1, payload, "result_fingerprint")


def test_market_plan_starts_as_tick_normalized_paper_limit_without_market_fallback() -> None:
    plan = _plan(expected_slippage_bps=8.0)
    decision = decide_paper_smart_limit(plan)

    assert decision.state == "PLACE_LIMIT"
    assert decision.reason_codes == ["INITIAL_SMART_LIMIT"]
    assert decision.proposed_limit_price == pytest.approx(100.0)
    assert decision.concession_bps == pytest.approx(0.0)
    assert decision.maximum_concession_bps == pytest.approx(8.0)
    assert decision.quantity_to_submit == 100
    assert decision.market_fallback_allowed is False
    assert decision.broker_submission_allowed is False
    assert decision.executable is False
    assert decision.paper_only is True
    assert decision.can_authorize_capital is False


def test_unfilled_buy_reprices_more_aggressively_but_stays_inside_budget() -> None:
    plan = _plan(expected_slippage_bps=8.0)
    first = decide_paper_smart_limit(plan)
    first_fill = _fill_result(plan=plan, decision=first, state="UNFILLED")
    second = decide_paper_smart_limit(
        plan,
        previous_decision=first,
        prior_fill_result=first_fill,
    )

    assert second.state == "REPRICE_LIMIT"
    assert second.attempt_index == 1
    assert second.previous_limit_price == pytest.approx(100.0)
    assert second.proposed_limit_price == pytest.approx(100.05)
    assert second.concession_bps == pytest.approx(5.0)
    assert second.previous_decision_fingerprint == first.decision_fingerprint
    assert second.prior_fill_result_fingerprint == first_fill.result_fingerprint


def test_sell_repricing_moves_down_not_up() -> None:
    plan = _plan(side="SELL", expected_slippage_bps=8.0)
    first = decide_paper_smart_limit(plan)
    assert first.proposed_limit_price == pytest.approx(100.0)

    fill = _fill_result(plan=plan, decision=first, state="UNFILLED")
    second = decide_paper_smart_limit(
        plan,
        previous_decision=first,
        prior_fill_result=fill,
    )
    assert second.state == "REPRICE_LIMIT"
    assert second.proposed_limit_price == pytest.approx(99.95)


def test_partial_fill_reprices_only_remaining_quantity() -> None:
    plan = _plan()
    first = decide_paper_smart_limit(plan)
    fill = _fill_result(
        plan=plan,
        decision=first,
        state="PARTIALLY_FILLED",
        filled_quantity=40,
    )
    second = decide_paper_smart_limit(
        plan,
        previous_decision=first,
        prior_fill_result=fill,
    )

    assert second.state == "REPRICE_LIMIT"
    assert second.reason_codes == ["PRIOR_PARTIAL_FILL"]
    assert second.quantity_to_submit == 60


def test_full_fill_stops_policy_chain_without_new_instruction() -> None:
    plan = _plan()
    first = decide_paper_smart_limit(plan)
    fill = _fill_result(
        plan=plan,
        decision=first,
        state="FULLY_FILLED",
        filled_quantity=100,
    )
    stopped = decide_paper_smart_limit(
        plan,
        previous_decision=first,
        prior_fill_result=fill,
    )

    assert stopped.state == "STOP"
    assert stopped.reason_codes == ["ORDER_ALREADY_FILLED"]
    assert stopped.quantity_to_submit is None
    assert stopped.proposed_limit_price is None


def test_inconclusive_fill_stops_repricing_fail_closed() -> None:
    plan = _plan()
    first = decide_paper_smart_limit(plan)
    fill = _fill_result(plan=plan, decision=first, state="INCONCLUSIVE")
    decision = decide_paper_smart_limit(
        plan,
        previous_decision=first,
        prior_fill_result=fill,
    )

    assert decision.state == "INCONCLUSIVE"
    assert decision.reason_codes == ["PRIOR_FILL_INCONCLUSIVE"]
    assert decision.proposed_limit_price is None


def test_original_limit_is_a_hard_bound_for_repricing() -> None:
    plan = _plan(order_type="LIMIT", limit_price=100.03)
    first = decide_paper_smart_limit(plan)
    assert first.proposed_limit_price == pytest.approx(100.0)

    fill = _fill_result(plan=plan, decision=first, state="UNFILLED")
    stopped = decide_paper_smart_limit(
        plan,
        previous_decision=first,
        prior_fill_result=fill,
    )
    assert stopped.state == "STOP"
    assert stopped.reason_codes == ["NO_FURTHER_PRICE_CONCESSION"]


def test_max_reprices_stops_chain_without_market_fallback() -> None:
    config = PaperSmartRepriceConfigV1(max_reprices=1)
    plan = _plan(expected_slippage_bps=20.0)
    first = decide_paper_smart_limit(plan, config=config)
    fill1 = _fill_result(plan=plan, decision=first, state="UNFILLED")
    second = decide_paper_smart_limit(
        plan,
        config=config,
        previous_decision=first,
        prior_fill_result=fill1,
    )
    assert second.state == "REPRICE_LIMIT"

    fill2 = _fill_result(plan=plan, decision=second, state="UNFILLED")
    stopped = decide_paper_smart_limit(
        plan,
        config=config,
        previous_decision=second,
        prior_fill_result=fill2,
    )
    assert stopped.state == "STOP"
    assert stopped.reason_codes == ["MAX_REPRICES_REACHED"]
    assert stopped.market_fallback_allowed is False
    assert stopped.broker_submission_allowed is False


def test_inconclusive_execution_plan_never_starts_paper_order() -> None:
    plan = _plan(state="INCONCLUSIVE")
    decision = decide_paper_smart_limit(plan)

    assert decision.state == "INCONCLUSIVE"
    assert decision.reason_codes == ["EXECUTION_PLAN_INCONCLUSIVE"]
    assert decision.proposed_limit_price is None
    assert decision.executable is False


def test_fill_lineage_mismatch_is_rejected() -> None:
    plan = _plan()
    first = decide_paper_smart_limit(plan)
    fill = _fill_result(plan=plan, decision=first, state="UNFILLED")
    bad_request = fill.request.model_copy(update={"config_fingerprint": "a" * 64})
    bad_fill = fill.model_copy(update={"request": bad_request})

    with pytest.raises(ValueError):
        decide_paper_smart_limit(
            plan,
            previous_decision=first,
            prior_fill_result=bad_fill,
        )


def test_same_inputs_produce_identical_policy_evidence() -> None:
    plan = _plan()
    first = decide_paper_smart_limit(plan)
    second = decide_paper_smart_limit(plan)

    assert first == second
    assert first.decision_fingerprint == second.decision_fingerprint
