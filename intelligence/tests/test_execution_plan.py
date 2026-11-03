from datetime import UTC, date, datetime, timedelta
from uuid import UUID

import pytest

from aitrading_intelligence.execution_plan import (
    EXECUTION_COST_MODEL_VERSION,
    EXECUTION_PLAN_VERSION,
    build_execution_plan,
)
from aitrading_intelligence.fill_simulator import FillSimulationConfigV1
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_fee_tax import build_india_fee_tax_schedule
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


def _slippage(*, state: str = "EVALUATED", side: str = "BUY"):
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


def _schedule(*, effective_from: date = date(2026, 1, 1)):
    return build_india_fee_tax_schedule(
        schedule_id="NSE-INTRADAY-FIXTURE",
        exchange="NSE",
        product="EQUITY_INTRADAY",
        effective_from=effective_from,
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


def test_execution_plan_binds_exact_cost_model_and_identity_lineage() -> None:
    fill_config = FillSimulationConfigV1(
        latency_milliseconds=250,
        max_fill_fraction_of_bar_volume=0.05,
    )
    plan = build_execution_plan(
        candidate_fingerprint=CANDIDATE_FINGERPRINT,
        side="BUY",
        order_type="MARKET",
        quantity=1_000,
        reference_price=100.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        fee_schedule=_schedule(),
        fill_config=fill_config,
    )

    assert plan.execution_plan_version == EXECUTION_PLAN_VERSION
    assert plan.state == "PLANNED"
    assert plan.reason_codes == []
    assert plan.canonical_instrument_id == "NSE:INE002A01018"
    assert plan.broker_trading_symbol == "RELIANCE-EQ"
    assert plan.broker_symbol_token == "2885"
    assert plan.candidate_fingerprint == CANDIDATE_FINGERPRINT
    assert plan.expected_cost is not None
    assert plan.expected_cost.cost_model_version == EXECUTION_COST_MODEL_VERSION
    assert plan.expected_cost.fee_schedule_fingerprint == plan.fee_schedule_fingerprint
    assert plan.expected_cost.slippage_estimate_fingerprint == plan.slippage_estimate_fingerprint
    assert plan.expected_cost.fill_config_fingerprint == fill_config.fingerprint
    assert plan.expected_cost.notional_inr == pytest.approx(100_000.0)
    assert plan.expected_cost.brokerage_bps == pytest.approx(2.0)
    assert plan.expected_cost.gst_bps == pytest.approx(0.4158)
    assert plan.expected_cost.total_fee_tax_bps == pytest.approx(3.0258)
    assert plan.expected_cost.expected_slippage_bps == pytest.approx(7.0)
    assert plan.expected_cost.expected_total_cost_bps == pytest.approx(10.0258)
    assert plan.status == "RESEARCH_EXECUTION_PLAN_ONLY"
    assert plan.executable is False
    assert plan.promotion_allowed is False
    assert plan.can_authorize_capital is False


def test_sell_plan_uses_sell_side_stt_and_no_buy_stamp() -> None:
    plan = build_execution_plan(
        candidate_fingerprint=CANDIDATE_FINGERPRINT,
        side="SELL",
        order_type="MARKET",
        quantity=1_000,
        reference_price=100.0,
        slippage=_slippage(side="SELL"),
        identity_bridge=_bridge(),
        fee_schedule=_schedule(),
        fill_config=FillSimulationConfigV1(),
    )

    assert plan.expected_cost is not None
    assert plan.expected_cost.stt_bps == pytest.approx(2.5)
    assert plan.expected_cost.stamp_duty_bps == pytest.approx(0.0)
    assert plan.expected_cost.total_fee_tax_bps == pytest.approx(5.2258)
    assert plan.expected_cost.expected_total_cost_bps == pytest.approx(12.2258)


def test_brokerage_minimum_and_cap_are_converted_to_effective_bps() -> None:
    small = build_execution_plan(
        candidate_fingerprint=CANDIDATE_FINGERPRINT,
        side="BUY",
        order_type="MARKET",
        quantity=10,
        reference_price=100.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        fee_schedule=_schedule(),
        fill_config=FillSimulationConfigV1(),
    )
    large = build_execution_plan(
        candidate_fingerprint=CANDIDATE_FINGERPRINT,
        side="BUY",
        order_type="MARKET",
        quantity=10_000,
        reference_price=100.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        fee_schedule=_schedule(),
        fill_config=FillSimulationConfigV1(),
    )

    assert small.expected_cost is not None
    assert large.expected_cost is not None
    assert small.expected_cost.brokerage_bps == pytest.approx(50.0)
    assert large.expected_cost.brokerage_bps == pytest.approx(0.2)


def test_inconclusive_slippage_yields_non_executable_inconclusive_plan() -> None:
    plan = build_execution_plan(
        candidate_fingerprint=CANDIDATE_FINGERPRINT,
        side="BUY",
        order_type="MARKET",
        quantity=100,
        reference_price=100.0,
        slippage=_slippage(state="INCONCLUSIVE"),
        identity_bridge=_bridge(),
        fee_schedule=_schedule(),
        fill_config=FillSimulationConfigV1(),
    )

    assert plan.state == "INCONCLUSIVE"
    assert plan.reason_codes == ["SLIPPAGE_ESTIMATE_INCONCLUSIVE"]
    assert plan.expected_cost is None
    assert plan.executable is False
    assert plan.can_authorize_capital is False


def test_limit_plan_requires_and_preserves_limit_price() -> None:
    plan = build_execution_plan(
        candidate_fingerprint=CANDIDATE_FINGERPRINT,
        side="BUY",
        order_type="LIMIT",
        quantity=100,
        reference_price=100.0,
        limit_price=99.5,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        fee_schedule=_schedule(),
        fill_config=FillSimulationConfigV1(),
    )

    assert plan.state == "PLANNED"
    assert plan.limit_price == pytest.approx(99.5)

    with pytest.raises(ValueError, match="LIMIT execution plan requires limit_price"):
        build_execution_plan(
            candidate_fingerprint=CANDIDATE_FINGERPRINT,
            side="BUY",
            order_type="LIMIT",
            quantity=100,
            reference_price=100.0,
            slippage=_slippage(),
            identity_bridge=_bridge(),
            fee_schedule=_schedule(),
            fill_config=FillSimulationConfigV1(),
        )


def test_fee_schedule_must_cover_plan_date_and_match_exchange() -> None:
    with pytest.raises(ValueError, match="not effective"):
        build_execution_plan(
            candidate_fingerprint=CANDIDATE_FINGERPRINT,
            side="BUY",
            order_type="MARKET",
            quantity=100,
            reference_price=100.0,
            slippage=_slippage(),
            identity_bridge=_bridge(),
            fee_schedule=_schedule(effective_from=date(2027, 1, 1)),
            fill_config=FillSimulationConfigV1(),
        )

    bse = build_india_fee_tax_schedule(
        schedule_id="BSE-INTRADAY-FIXTURE",
        exchange="BSE",
        product="EQUITY_INTRADAY",
        effective_from=date(2026, 1, 1),
        brokerage_rate_bps=10.0,
        exchange_transaction_charge_bps=0.30,
        stt_buy_bps=0.0,
        stt_sell_bps=2.5,
        sebi_turnover_charge_bps=0.01,
        stamp_duty_buy_bps=0.30,
        gst_rate_percent=18.0,
        source_reference="fixture:bse-fees",
    )
    with pytest.raises(ValueError, match="slippage exchange must match fee schedule"):
        build_execution_plan(
            candidate_fingerprint=CANDIDATE_FINGERPRINT,
            side="BUY",
            order_type="MARKET",
            quantity=100,
            reference_price=100.0,
            slippage=_slippage(),
            identity_bridge=_bridge(),
            fee_schedule=bse,
            fill_config=FillSimulationConfigV1(),
        )


def test_tampered_identity_or_fee_schedule_is_rejected() -> None:
    tampered_bridge = _bridge().model_copy(update={"snapshot_fingerprint": "0" * 64})
    with pytest.raises(ValueError, match="identity bridge fingerprint mismatch"):
        build_execution_plan(
            candidate_fingerprint=CANDIDATE_FINGERPRINT,
            side="BUY",
            order_type="MARKET",
            quantity=100,
            reference_price=100.0,
            slippage=_slippage(),
            identity_bridge=tampered_bridge,
            fee_schedule=_schedule(),
            fill_config=FillSimulationConfigV1(),
        )

    tampered_schedule = _schedule().model_copy(update={"schedule_fingerprint": "0" * 64})
    with pytest.raises(ValueError, match="schedule fingerprint mismatch"):
        build_execution_plan(
            candidate_fingerprint=CANDIDATE_FINGERPRINT,
            side="BUY",
            order_type="MARKET",
            quantity=100,
            reference_price=100.0,
            slippage=_slippage(),
            identity_bridge=_bridge(),
            fee_schedule=tampered_schedule,
            fill_config=FillSimulationConfigV1(),
        )
