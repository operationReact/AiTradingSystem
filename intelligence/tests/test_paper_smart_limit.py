from datetime import UTC, date, datetime, timedelta
from uuid import UUID

import pytest

from aitrading_intelligence.execution_plan import build_execution_plan
from aitrading_intelligence.fill_simulator import (
    FillSimulationConfigV1,
    simulate_fill,
)
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


def _plan(
    *,
    side: str = "BUY",
    quantity: int = 100,
    order_type: str = "MARKET",
):
    slippage = _slippage(side=side)
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
        identity_bridge=_bridge(),
        fee_schedule=_schedule(),
        fill_config=fill_config,
        limit_price=99.0 if order_type == "LIMIT" else None,
    )
    return plan, slippage, fill_config


def _simulate_instruction(
    *,
    instruction,
    slippage,
    fill_config,
    next_open: float = 100.0,
    next_high: float = 101.0,
    next_low: float = 99.0,
    volume: float = 1_000.0,
    submitted_at: datetime = AS_OF,
):
    return simulate_fill(
        order_id=f"paper-limit-{instruction.instruction_index}",
        side=instruction.side,
        order_type="LIMIT",
        quantity=instruction.quantity,
        submitted_at=submitted_at,
        reference_price=instruction.reference_price,
        limit_price=instruction.limit_price,
        slippage=slippage,
        identity_bridge=_bridge(),
        market_export=_market_export(
            next_open=next_open,
            next_high=next_high,
            next_low=next_low,
            volume=volume,
        ),
        config=fill_config,
    )


def test_initial_buy_limit_uses_fraction_of_modeled_slippage() -> None:
    plan, slippage, _ = _plan()

    decision = build_initial_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
    )

    assert decision.state == "INSTRUCTION"
    assert decision.reason_codes == []
    assert decision.instruction is not None
    assert decision.instruction.action == "INITIAL_LIMIT"
    assert decision.instruction.instruction_index == 0
    assert decision.instruction.aggressiveness_fraction == pytest.approx(0.25)
    assert decision.instruction.limit_offset_bps == pytest.approx(1.75)
    assert decision.instruction.limit_price == pytest.approx(100.0175)
    assert decision.instruction.quantity == 100
    assert decision.paper_only is True
    assert decision.executable is False
    assert decision.promotion_allowed is False
    assert decision.can_authorize_capital is False


def test_initial_sell_limit_is_below_reference() -> None:
    plan, slippage, _ = _plan(side="SELL")

    decision = build_initial_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
    )

    assert decision.instruction is not None
    assert decision.instruction.limit_offset_bps == pytest.approx(1.75)
    assert decision.instruction.limit_price == pytest.approx(99.9825)


def test_unfilled_order_reprices_more_aggressively_within_budget() -> None:
    plan, slippage, fill_config = _plan()
    first = build_initial_paper_smart_limit_decision(plan=plan, slippage=slippage)
    assert first.instruction is not None
    fill = _simulate_instruction(
        instruction=first.instruction,
        slippage=slippage,
        fill_config=fill_config,
        next_open=100.04,
        next_high=100.05,
        next_low=100.03,
    )
    assert fill.state == "UNFILLED"
    assert fill.arrival_gap_bps == pytest.approx(4.0)

    second = advance_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
        previous_decision=first,
        fill_result=fill,
    )

    assert second.state == "INSTRUCTION"
    assert second.instruction is not None
    assert second.instruction.action == "REPRICE"
    assert second.instruction.instruction_index == 1
    assert second.instruction.aggressiveness_fraction == pytest.approx(0.50)
    assert second.instruction.limit_price == pytest.approx(100.035)
    assert second.instruction.limit_price > first.instruction.limit_price
    assert second.instruction.quantity == 100
    assert second.instruction.previous_instruction_fingerprint == (
        first.instruction.instruction_fingerprint
    )
    assert second.instruction.source_fill_result_fingerprint == fill.result_fingerprint


def test_partial_fill_reprices_only_remaining_quantity() -> None:
    plan, slippage, fill_config = _plan(quantity=250)
    first = build_initial_paper_smart_limit_decision(plan=plan, slippage=slippage)
    assert first.instruction is not None
    fill = _simulate_instruction(
        instruction=first.instruction,
        slippage=slippage,
        fill_config=fill_config,
        volume=1_000.0,
    )
    assert fill.state == "PARTIALLY_FILLED"
    assert fill.filled_quantity == 100
    assert fill.remaining_quantity == 150

    second = advance_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
        previous_decision=first,
        fill_result=fill,
    )

    assert second.state == "INSTRUCTION"
    assert second.instruction is not None
    assert second.instruction.quantity == 150
    assert second.instruction.instruction_index == 1


def test_full_fill_completes_without_new_instruction() -> None:
    plan, slippage, fill_config = _plan(quantity=100)
    first = build_initial_paper_smart_limit_decision(plan=plan, slippage=slippage)
    assert first.instruction is not None
    fill = _simulate_instruction(
        instruction=first.instruction,
        slippage=slippage,
        fill_config=fill_config,
    )
    assert fill.state == "FULLY_FILLED"

    complete = advance_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
        previous_decision=first,
        fill_result=fill,
    )

    assert complete.state == "COMPLETE"
    assert complete.reason_codes == []
    assert complete.instruction is None
    assert complete.source_fill_result_fingerprint == fill.result_fingerprint


def test_adverse_gap_beyond_modeled_slippage_stops_chasing() -> None:
    plan, slippage, fill_config = _plan()
    first = build_initial_paper_smart_limit_decision(plan=plan, slippage=slippage)
    assert first.instruction is not None
    fill = _simulate_instruction(
        instruction=first.instruction,
        slippage=slippage,
        fill_config=fill_config,
        next_open=100.10,
        next_high=100.11,
        next_low=100.09,
    )
    assert fill.state == "UNFILLED"
    assert fill.arrival_gap_bps == pytest.approx(10.0)

    stopped = advance_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
        previous_decision=first,
        fill_result=fill,
    )

    assert stopped.state == "STOPPED"
    assert stopped.reason_codes == ["ARRIVAL_GAP_EXCEEDS_SLIPPAGE_BUDGET"]
    assert stopped.instruction is None


def test_max_reprices_is_a_hard_stop() -> None:
    config = PaperSmartLimitConfigV1(max_reprices=0)
    plan, slippage, fill_config = _plan()
    first = build_initial_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
        config=config,
    )
    assert first.instruction is not None
    fill = _simulate_instruction(
        instruction=first.instruction,
        slippage=slippage,
        fill_config=fill_config,
        next_open=100.04,
        next_high=100.05,
        next_low=100.03,
    )

    stopped = advance_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
        previous_decision=first,
        fill_result=fill,
        config=config,
    )

    assert stopped.state == "STOPPED"
    assert stopped.reason_codes == ["MAX_REPRICES_REACHED"]
    assert stopped.instruction is None


def test_existing_limit_plan_is_not_rewritten_by_smart_limit_policy() -> None:
    plan, slippage, _ = _plan(order_type="LIMIT")

    decision = build_initial_paper_smart_limit_decision(
        plan=plan,
        slippage=slippage,
    )

    assert decision.state == "INCONCLUSIVE"
    assert decision.reason_codes == ["MARKET_EXECUTION_PLAN_REQUIRED"]
    assert decision.instruction is None


def test_fill_must_match_instruction_exactly() -> None:
    plan, slippage, fill_config = _plan()
    first = build_initial_paper_smart_limit_decision(plan=plan, slippage=slippage)
    assert first.instruction is not None
    wrong_fill = simulate_fill(
        order_id="wrong-limit",
        side="BUY",
        order_type="LIMIT",
        quantity=first.instruction.quantity,
        submitted_at=AS_OF,
        reference_price=first.instruction.reference_price,
        limit_price=first.instruction.limit_price + 0.01,
        slippage=slippage,
        identity_bridge=_bridge(),
        market_export=_market_export(),
        config=fill_config,
    )

    with pytest.raises(ValueError, match="limit price does not match instruction"):
        advance_paper_smart_limit_decision(
            plan=plan,
            slippage=slippage,
            previous_decision=first,
            fill_result=wrong_fill,
        )
