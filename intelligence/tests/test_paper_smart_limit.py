from datetime import UTC, date, datetime, timedelta
from uuid import UUID

import pytest

from aitrading_intelligence.execution_plan import build_execution_plan
from aitrading_intelligence.execution_quality import evaluate_execution_quality
from aitrading_intelligence.fill_simulator import FillSimulationConfigV1, simulate_fill
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_fee_tax import build_india_fee_tax_schedule
from aitrading_intelligence.market_source import MarketResearchExport, ResearchCandle
from aitrading_intelligence.paper_smart_limit import (
    PaperSmartLimitConfigV1,
    advance_paper_smart_limit_decision,
    build_initial_paper_smart_limit_decision,
)
from aitrading_intelligence.state_dependent_slippage import (
    StateDependentSlippageEstimateV1,
)
from aitrading_intelligence.universe_identity_bridge import (
    MAPPING_RULE_VERSION,
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

AS_OF = datetime(2026, 11, 3, 4, 30, tzinfo=UTC)
CATALOGUE_VERSION = UUID("11111111-1111-1111-1111-111111111111")
CANDIDATE_FINGERPRINT = "f" * 64


def _slippage(*, side: str = "BUY", state: str = "EVALUATED"):
    evaluated = state == "EVALUATED"
    values = {
        "slippage_version": "state-dependent-slippage.v1",
        "state": state,
        "reason_codes": [] if evaluated else ["MISSING_VOLATILITY_RANGE_FEATURE"],
        "side": side,
        "exchange": "NSE",
        "symbol": "RELIANCE",
        "as_of": AS_OF,
        "data_cutoff": AS_OF - timedelta(seconds=5),
        "config_fingerprint": "a" * 64,
        "source_spread_snapshot_fingerprint": "b" * 64,
        "source_feature_row_fingerprint": "c" * 64,
        "source_market_state_id": "d" * 64 if evaluated else None,
        "spread_bps": 4.0 if evaluated else None,
        "adv_notional": 100_000_000.0 if evaluated else None,
        "relative_volume": 1.0 if evaluated else None,
        "volatility_range_pct": 0.01 if evaluated else None,
        "spread_component_bps": 2.0 if evaluated else None,
        "liquidity_pressure": 0.0 if evaluated else None,
        "liquidity_component_bps": 0.0 if evaluated else None,
        "volatility_component_bps": 5.0 if evaluated else None,
        "estimated_slippage_bps": 7.0 if evaluated else None,
        "status": "RESEARCH_SLIPPAGE_ESTIMATE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = StateDependentSlippageEstimateV1.model_construct(
        **values,
        estimate_fingerprint="0" * 64,
    )
    return StateDependentSlippageEstimateV1(
        **values,
        estimate_fingerprint=canonical_fingerprint(
            provisional.model_dump(mode="json", exclude={"estimate_fingerprint"})
        ),
    )


def _bridge() -> Nifty500LiquidityIdentityBridge:
    available_at = AS_OF - timedelta(seconds=10)
    binding_payload = {
        "canonicalInstrumentId": "NSE:INE002A01018",
        "isin": "INE002A01018",
        "symbol": "RELIANCE",
        "series": "EQ",
        "sector": "Energy",
        "expectedTradingSymbol": "RELIANCE-EQ",
        "catalogueInstrumentId": 1,
        "brokerTradingSymbol": "RELIANCE-EQ",
        "brokerSymbolToken": "2885",
        "liquidityEvidenceFingerprint": "1" * 64,
        "sectorMappingEvidenceFingerprint": "2" * 64,
        "membershipEffectiveFrom": (AS_OF - timedelta(days=30)).isoformat(),
        "availableAt": available_at.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
    }
    binding = Nifty500LiquidityIdentityBinding(
        canonical_instrument_id="NSE:INE002A01018",
        isin="INE002A01018",
        symbol="RELIANCE",
        series="EQ",
        sector="Energy",
        expected_trading_symbol="RELIANCE-EQ",
        catalogue_instrument_id=1,
        broker_trading_symbol="RELIANCE-EQ",
        broker_symbol_token="2885",
        liquidity_evidence_fingerprint="1" * 64,
        sector_mapping_evidence_fingerprint="2" * 64,
        membership_effective_from=AS_OF - timedelta(days=30),
        available_at=available_at,
        binding_fingerprint=canonical_fingerprint(binding_payload),
    )
    bridge_payload = {
        "contractVersion": "nifty500-liquidity-identity-bridge.v1",
        "asOf": AS_OF.isoformat(),
        "dataCutoff": available_at.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
        "sourceCatalogueVersion": str(CATALOGUE_VERSION),
        "sourceCatalogueFingerprint": "3" * 64,
        "sourceLiquiditySnapshotFingerprint": "4" * 64,
        "sourceSectorEnrichmentFingerprint": "5" * 64,
        "bindings": [binding.model_dump(mode="json")],
        "bindingCount": 1,
        "status": "RESEARCH_IDENTITY_BRIDGE_ONLY",
        "canAuthorizeCapital": False,
    }
    return Nifty500LiquidityIdentityBridge(
        as_of=AS_OF,
        data_cutoff=available_at,
        source_catalogue_version=str(CATALOGUE_VERSION),
        source_catalogue_fingerprint="3" * 64,
        source_liquidity_snapshot_fingerprint="4" * 64,
        source_sector_enrichment_fingerprint="5" * 64,
        bindings=[binding],
        binding_count=1,
        snapshot_fingerprint=canonical_fingerprint(bridge_payload),
    )


def _schedule():
    return build_india_fee_tax_schedule(
        schedule_id="NSE-INTRADAY-FIXTURE",
        exchange="NSE",
        product="EQUITY_INTRADAY",
        effective_from=date(2026, 1, 1),
        brokerage_rate_bps=10.0,
        brokerage_cap_per_order_inr=20.0,
        brokerage_minimum_per_order_inr=5.0,
        exchange_transaction_charge_bps=0.30,
        stt_buy_bps=0.0,
        stt_sell_bps=2.5,
        sebi_turnover_charge_bps=0.01,
        stamp_duty_buy_bps=0.30,
        gst_rate_percent=18.0,
        source_reference="fixture:fees",
    )


def _artifacts(*, side: str = "BUY", quantity: int = 100, order_type: str = "MARKET"):
    slippage = _slippage(side=side)
    bridge = _bridge()
    schedule = _schedule()
    fill_config = FillSimulationConfigV1(
        latency_milliseconds=250,
        max_fill_fraction_of_bar_volume=0.10,
        max_wait_bars=1,
        max_slippage_age_seconds=60,
    )
    plan = build_execution_plan(
        candidate_fingerprint=CANDIDATE_FINGERPRINT,
        side=side,
        order_type=order_type,
        quantity=quantity,
        reference_price=100.0,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
        limit_price=99.0 if order_type == "LIMIT" else None,
    )
    return plan, slippage, bridge, schedule, fill_config


def _candle(
    minute: int,
    *,
    open_price: float,
    high: float,
    low: float,
    close: float,
    volume: float = 1_000.0,
) -> ResearchCandle:
    candle_time = AS_OF + timedelta(minutes=minute)
    return ResearchCandle(
        candleTime=candle_time,
        availableAt=candle_time + timedelta(minutes=1),
        open=open_price,
        high=high,
        low=low,
        close=close,
        volume=volume,
        source="ANGEL_ONE_SMARTAPI",
        fetchedAt=candle_time + timedelta(minutes=1),
    )


def _market_export(
    *,
    next_open: float = 100.0,
    next_high: float = 101.0,
    next_low: float = 99.0,
    volume: float = 1_000.0,
) -> MarketResearchExport:
    rows = [
        _candle(0, open_price=100.0, high=101.0, low=99.0, close=100.0),
        _candle(
            1,
            open_price=next_open,
            high=next_high,
            low=next_low,
            close=next_open,
            volume=volume,
        ),
        _candle(2, open_price=100.0, high=101.0, low=99.0, close=100.0),
    ]
    return MarketResearchExport(
        contractVersion="market-history.v1",
        generatedAt=AS_OF + timedelta(minutes=4),
        instrumentId=1,
        exchange="NSE",
        tradingSymbol="RELIANCE-EQ",
        symbolToken="2885",
        catalogueVersion=CATALOGUE_VERSION,
        interval="ONE_MINUTE",
        fromInclusive=AS_OF - timedelta(minutes=1),
        toExclusive=AS_OF + timedelta(minutes=10),
        dataCutoff=max(row.available_at for row in rows),
        providerSources=["ANGEL_ONE_SMARTAPI"],
        candles=rows,
    )


def _fill_and_quality(
    *,
    instruction,
    slippage,
    bridge,
    schedule,
    fill_config,
    next_open: float = 100.0,
    next_high: float = 101.0,
    next_low: float = 99.0,
    volume: float = 1_000.0,
    submitted_at: datetime = AS_OF,
):
    plan = instruction.recommended_plan
    fill = simulate_fill(
        order_id=f"paper-limit-{instruction.instruction_index}",
        side=plan.side,
        order_type="LIMIT",
        quantity=plan.quantity,
        submitted_at=submitted_at,
        reference_price=plan.reference_price,
        limit_price=plan.limit_price,
        slippage=slippage,
        identity_bridge=bridge,
        market_export=_market_export(
            next_open=next_open,
            next_high=next_high,
            next_low=next_low,
            volume=volume,
        ),
        config=fill_config,
    )
    quality = evaluate_execution_quality(
        plan=plan,
        fill_result=fill,
        fee_schedule=schedule,
    )
    return fill, quality


def _initial(*, side: str = "BUY", quantity: int = 100, config=None):
    plan, slippage, bridge, schedule, fill_config = _artifacts(
        side=side,
        quantity=quantity,
    )
    decision = build_initial_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
        config=config,
    )
    return decision, plan, slippage, bridge, schedule, fill_config


def test_initial_buy_limit_emits_exact_tca_compatible_execution_plan() -> None:
    decision, source_plan, _, _, _, _ = _initial()

    assert decision.state == "INSTRUCTION"
    assert decision.reason_codes == []
    assert decision.instruction is not None
    instruction = decision.instruction
    assert instruction.action == "INITIAL_LIMIT"
    assert instruction.instruction_index == 0
    assert instruction.aggressiveness_fraction == pytest.approx(0.25)
    assert instruction.limit_offset_bps == pytest.approx(1.75)
    assert instruction.limit_price == pytest.approx(100.0175)
    assert instruction.source_execution_plan_fingerprint == source_plan.plan_fingerprint
    assert instruction.recommended_plan.order_type == "LIMIT"
    assert instruction.recommended_plan.quantity == 100
    assert instruction.recommended_plan.limit_price == pytest.approx(100.0175)
    assert instruction.recommended_plan.plan_fingerprint != source_plan.plan_fingerprint
    assert instruction.recommended_plan.executable is False
    assert decision.paper_only is True
    assert decision.executable is False
    assert decision.promotion_allowed is False
    assert decision.can_authorize_capital is False


def test_initial_sell_limit_moves_below_reference() -> None:
    decision, _, _, _, _, _ = _initial(side="SELL")

    assert decision.instruction is not None
    assert decision.instruction.limit_offset_bps == pytest.approx(1.75)
    assert decision.instruction.limit_price == pytest.approx(99.9825)
    assert decision.instruction.recommended_plan.side == "SELL"


def test_unfilled_limit_reprices_from_exact_execution_quality() -> None:
    first, _, slippage, bridge, schedule, fill_config = _initial()
    assert first.instruction is not None
    fill, quality = _fill_and_quality(
        instruction=first.instruction,
        slippage=slippage,
        bridge=bridge,
        schedule=schedule,
        fill_config=fill_config,
        next_open=100.04,
        next_high=100.05,
        next_low=100.03,
    )
    assert fill.state == "UNFILLED"
    assert quality.state == "UNFILLED"

    second = advance_paper_smart_limit_decision(
        previous_decision=first,
        execution_quality=quality,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )

    assert second.state == "INSTRUCTION"
    assert second.instruction is not None
    assert second.instruction.action == "REPRICE"
    assert second.instruction.instruction_index == 1
    assert second.instruction.aggressiveness_fraction == pytest.approx(0.50)
    assert second.instruction.limit_price == pytest.approx(100.035)
    assert second.instruction.limit_price > first.instruction.limit_price
    assert second.instruction.source_quality_fingerprint == quality.quality_fingerprint
    assert second.instruction.previous_instruction_fingerprint == (
        first.instruction.instruction_fingerprint
    )
    assert second.source_execution_plan_fingerprint == (
        first.instruction.recommended_plan.plan_fingerprint
    )


def test_partial_fill_reprices_remaining_quantity_and_recalculates_cost_model() -> None:
    first, _, slippage, bridge, schedule, fill_config = _initial(quantity=250)
    assert first.instruction is not None
    fill, quality = _fill_and_quality(
        instruction=first.instruction,
        slippage=slippage,
        bridge=bridge,
        schedule=schedule,
        fill_config=fill_config,
        volume=1_000.0,
    )
    assert fill.state == "PARTIALLY_FILLED"
    assert quality.state == "PARTIALLY_FILLED"
    assert quality.filled_quantity == 100

    second = advance_paper_smart_limit_decision(
        previous_decision=first,
        execution_quality=quality,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )

    assert second.instruction is not None
    next_plan = second.instruction.recommended_plan
    assert next_plan.quantity == 150
    assert next_plan.expected_cost is not None
    assert next_plan.expected_cost.notional_inr == pytest.approx(15_000.0)
    assert first.instruction.recommended_plan.expected_cost is not None
    assert next_plan.expected_cost.cost_model_fingerprint != (
        first.instruction.recommended_plan.expected_cost.cost_model_fingerprint
    )


def test_full_fill_completes_without_another_plan() -> None:
    first, _, slippage, bridge, schedule, fill_config = _initial()
    assert first.instruction is not None
    _, quality = _fill_and_quality(
        instruction=first.instruction,
        slippage=slippage,
        bridge=bridge,
        schedule=schedule,
        fill_config=fill_config,
    )
    assert quality.state == "FULLY_FILLED"

    complete = advance_paper_smart_limit_decision(
        previous_decision=first,
        execution_quality=quality,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )

    assert complete.state == "COMPLETE"
    assert complete.reason_codes == []
    assert complete.instruction is None
    assert complete.source_quality_fingerprint == quality.quality_fingerprint


def test_adverse_gap_beyond_modeled_slippage_stops_chasing() -> None:
    first, _, slippage, bridge, schedule, fill_config = _initial()
    assert first.instruction is not None
    fill, quality = _fill_and_quality(
        instruction=first.instruction,
        slippage=slippage,
        bridge=bridge,
        schedule=schedule,
        fill_config=fill_config,
        next_open=100.10,
        next_high=100.11,
        next_low=100.09,
    )
    assert fill.state == "UNFILLED"
    assert quality.arrival_gap_bps == pytest.approx(10.0)

    stopped = advance_paper_smart_limit_decision(
        previous_decision=first,
        execution_quality=quality,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )

    assert stopped.state == "STOPPED"
    assert stopped.reason_codes == ["ARRIVAL_GAP_EXCEEDS_SLIPPAGE_BUDGET"]
    assert stopped.instruction is None


def test_inconclusive_fill_quality_stops_repricing_fail_closed() -> None:
    first, _, slippage, bridge, schedule, fill_config = _initial()
    assert first.instruction is not None
    _, quality = _fill_and_quality(
        instruction=first.instruction,
        slippage=slippage,
        bridge=bridge,
        schedule=schedule,
        fill_config=fill_config,
        submitted_at=AS_OF + timedelta(seconds=61),
    )
    assert quality.state == "INCONCLUSIVE"

    decision = advance_paper_smart_limit_decision(
        previous_decision=first,
        execution_quality=quality,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )

    assert decision.state == "INCONCLUSIVE"
    assert decision.reason_codes == ["EXECUTION_QUALITY_INCONCLUSIVE"]
    assert decision.instruction is None


def test_max_reprices_is_a_hard_stop() -> None:
    config = PaperSmartLimitConfigV1(max_reprices=0)
    first, _, slippage, bridge, schedule, fill_config = _initial(config=config)
    assert first.instruction is not None
    _, quality = _fill_and_quality(
        instruction=first.instruction,
        slippage=slippage,
        bridge=bridge,
        schedule=schedule,
        fill_config=fill_config,
        next_open=100.04,
        next_high=100.05,
        next_low=100.03,
    )

    stopped = advance_paper_smart_limit_decision(
        previous_decision=first,
        execution_quality=quality,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
        config=config,
    )

    assert stopped.state == "STOPPED"
    assert stopped.reason_codes == ["MAX_REPRICES_REACHED"]


def test_policy_chain_rejects_config_or_quality_lineage_changes() -> None:
    first, _, slippage, bridge, schedule, fill_config = _initial()
    assert first.instruction is not None
    _, quality = _fill_and_quality(
        instruction=first.instruction,
        slippage=slippage,
        bridge=bridge,
        schedule=schedule,
        fill_config=fill_config,
        next_open=100.04,
        next_high=100.05,
        next_low=100.03,
    )

    with pytest.raises(ValueError, match="config changed"):
        advance_paper_smart_limit_decision(
            previous_decision=first,
            execution_quality=quality,
            slippage=slippage,
            identity_bridge=bridge,
            fee_schedule=schedule,
            fill_config=fill_config,
            config=PaperSmartLimitConfigV1(initial_slippage_fraction=0.50),
        )

    other_plan, _, _, _, _ = _artifacts(quantity=101)
    other_first = build_initial_paper_smart_limit_decision(
        plan=other_plan,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )
    assert other_first.instruction is not None
    _, other_quality = _fill_and_quality(
        instruction=other_first.instruction,
        slippage=slippage,
        bridge=bridge,
        schedule=schedule,
        fill_config=fill_config,
        next_open=100.04,
        next_high=100.05,
        next_low=100.03,
    )
    with pytest.raises(ValueError, match="another LIMIT plan"):
        advance_paper_smart_limit_decision(
            previous_decision=first,
            execution_quality=other_quality,
            slippage=slippage,
            identity_bridge=bridge,
            fee_schedule=schedule,
            fill_config=fill_config,
        )


def test_initial_policy_is_deterministic_and_requires_market_plan() -> None:
    first, source_plan, slippage, bridge, schedule, fill_config = _initial()
    second = build_initial_paper_smart_limit_decision(
        plan=source_plan,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )
    assert first == second

    limit_plan, _, _, _, _ = _artifacts(order_type="LIMIT")
    inconclusive = build_initial_paper_smart_limit_decision(
        plan=limit_plan,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )
    assert inconclusive.state == "INCONCLUSIVE"
    assert inconclusive.reason_codes == ["MARKET_EXECUTION_PLAN_REQUIRED"]
    assert inconclusive.instruction is None
