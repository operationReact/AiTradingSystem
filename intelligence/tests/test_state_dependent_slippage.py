from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.feature_v2 import FeatureRowV2
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_cross_section import IndiaCrossSectionMemberObservation
from aitrading_intelligence.market_depth_source import (
    MARKET_DEPTH_SPREAD_JOIN_VERSION,
    OBSERVED_SPREAD_FLAG,
    MarketDepthSpreadJoinSnapshot,
)
from aitrading_intelligence.state_dependent_slippage import (
    StateDependentSlippageConfigV1,
    estimate_state_dependent_slippage,
)

AS_OF = datetime(2026, 11, 3, 10, 0, tzinfo=UTC)


def _feature_row(
    *,
    symbol: str = "RELIANCE",
    range_pct: float | None = 0.012,
    age_seconds: int = 10,
) -> FeatureRowV2:
    features = {"market.close": 100.0}
    if range_pct is not None:
        features["market.range_pct"] = range_pct
    features = dict(sorted(features.items()))
    payload = {
        "feature_schema_version": "features.market.v2",
        "instrument_id": "NSE:INE002A01018",
        "exchange": "NSE",
        "symbol": symbol,
        "sector": "Energy",
        "index_memberships": ["NIFTY_50", "NIFTY_500"],
        "as_of": AS_OF.isoformat(),
        "data_cutoff": (AS_OF - timedelta(seconds=age_seconds)).isoformat(),
        "base_feature_row_fingerprint": "1" * 64,
        "universe_snapshot_fingerprint": "2" * 64,
        "market_context_snapshot_fingerprint": "3" * 64,
        "features": features,
        "missing_features": [],
        "quality_flags": [],
        "provenance": [
            "feature-row-v1:" + ("1" * 64),
            "market-context:" + ("3" * 64),
            "universe:" + ("2" * 64),
        ],
        "status": "RESEARCH_FEATURE_ONLY",
        "can_authorize_capital": False,
    }
    return FeatureRowV2(**payload, fingerprint=canonical_fingerprint(payload))


def _spread_snapshot(
    *,
    symbol: str = "RELIANCE",
    spread_bps: float = 4.0,
    adv_notional: float = 50_000_000.0,
    relative_volume: float = 0.80,
    age_seconds: int = 5,
    observed_spread: bool = True,
) -> MarketDepthSpreadJoinSnapshot:
    available_at = AS_OF - timedelta(seconds=age_seconds)
    observation = IndiaCrossSectionMemberObservation(
        exchange="NSE",
        symbol=symbol,
        event_at=available_at,
        available_at=available_at,
        return_1=0.001,
        adv_notional=adv_notional,
        spread_bps=spread_bps,
        relative_volume=relative_volume,
        source=(
            MARKET_DEPTH_SPREAD_JOIN_VERSION
            if observed_spread
            else "fixture-placeholder-spread"
        ),
        source_id="a" * 64,
        quality_flags=[OBSERVED_SPREAD_FLAG] if observed_spread else [],
    )
    payload = {
        "contract_version": MARKET_DEPTH_SPREAD_JOIN_VERSION,
        "as_of": AS_OF.isoformat(),
        "data_cutoff": available_at.isoformat(),
        "identity_bridge_fingerprint": "b" * 64,
        "config_fingerprint": "c" * 64,
        "observations": [observation.model_dump(mode="json")],
        "selected_depth_source_ids": ["d" * 64],
        "status": "RESEARCH_SPREAD_EVIDENCE_ONLY",
        "can_authorize_capital": False,
    }
    return MarketDepthSpreadJoinSnapshot(
        as_of=AS_OF,
        data_cutoff=available_at,
        identity_bridge_fingerprint="b" * 64,
        config_fingerprint="c" * 64,
        observations=[observation],
        selected_depth_source_ids=["d" * 64],
        snapshot_fingerprint=canonical_fingerprint(payload),
    )


def test_slippage_components_are_deterministic_and_reconstructable() -> None:
    estimate = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(),
        feature_row=_feature_row(),
        side="BUY",
    )
    repeated = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(),
        feature_row=_feature_row(),
        side="BUY",
    )

    assert estimate == repeated
    assert estimate.state == "EVALUATED"
    assert estimate.reason_codes == []
    assert estimate.spread_component_bps == pytest.approx(2.0)
    assert estimate.liquidity_pressure == pytest.approx(0.41)
    assert estimate.liquidity_component_bps == pytest.approx(4.1)
    assert estimate.volatility_component_bps == pytest.approx(6.0)
    assert estimate.estimated_slippage_bps == pytest.approx(12.1)
    assert estimate.estimated_slippage_bps == pytest.approx(
        estimate.spread_component_bps
        + estimate.liquidity_component_bps
        + estimate.volatility_component_bps
    )
    assert estimate.status == "RESEARCH_SLIPPAGE_ESTIMATE_ONLY"
    assert estimate.promotion_allowed is False
    assert estimate.can_authorize_capital is False


def test_adverse_slippage_bps_are_symmetric_by_side() -> None:
    snapshot = _spread_snapshot()
    feature = _feature_row()
    buy = estimate_state_dependent_slippage(
        spread_snapshot=snapshot,
        feature_row=feature,
        side="BUY",
    )
    sell = estimate_state_dependent_slippage(
        spread_snapshot=snapshot,
        feature_row=feature,
        side="SELL",
    )

    assert buy.estimated_slippage_bps == sell.estimated_slippage_bps
    assert buy.estimate_fingerprint != sell.estimate_fingerprint


def test_wider_spread_thinner_liquidity_and_higher_volatility_worsen_slippage() -> None:
    healthy = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(
            spread_bps=2.0,
            adv_notional=150_000_000.0,
            relative_volume=1.20,
        ),
        feature_row=_feature_row(range_pct=0.005),
        side="BUY",
    )
    stressed = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(
            spread_bps=8.0,
            adv_notional=20_000_000.0,
            relative_volume=0.40,
        ),
        feature_row=_feature_row(range_pct=0.025),
        side="BUY",
    )

    assert healthy.state == "EVALUATED"
    assert stressed.state == "EVALUATED"
    assert stressed.spread_component_bps > healthy.spread_component_bps
    assert stressed.liquidity_component_bps > healthy.liquidity_component_bps
    assert stressed.volatility_component_bps > healthy.volatility_component_bps
    assert stressed.estimated_slippage_bps > healthy.estimated_slippage_bps


def test_state_beyond_model_support_is_inconclusive_not_capped() -> None:
    estimate = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(
            spread_bps=100.0,
            adv_notional=1_000_000.0,
            relative_volume=0.05,
        ),
        feature_row=_feature_row(range_pct=0.20),
        side="BUY",
        config=StateDependentSlippageConfigV1(
            maximum_supported_slippage_bps=25.0
        ),
    )

    assert estimate.state == "INCONCLUSIVE"
    assert estimate.reason_codes == ["SLIPPAGE_EXCEEDS_MODEL_SUPPORT"]
    assert estimate.estimated_slippage_bps is None
    assert estimate.spread_component_bps is None
    assert estimate.liquidity_component_bps is None
    assert estimate.volatility_component_bps is None


def test_missing_or_stale_state_is_explicitly_inconclusive() -> None:
    missing_volatility = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(),
        feature_row=_feature_row(range_pct=None),
        side="BUY",
    )
    stale_spread = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(age_seconds=301),
        feature_row=_feature_row(),
        side="BUY",
    )
    stale_volatility = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(),
        feature_row=_feature_row(age_seconds=901),
        side="BUY",
    )

    assert missing_volatility.state == "INCONCLUSIVE"
    assert missing_volatility.reason_codes == ["MISSING_VOLATILITY_RANGE_FEATURE"]
    assert missing_volatility.estimated_slippage_bps is None

    assert stale_spread.state == "INCONCLUSIVE"
    assert stale_spread.reason_codes == ["STALE_SPREAD_LIQUIDITY_STATE"]
    assert stale_spread.estimated_slippage_bps is None

    assert stale_volatility.state == "INCONCLUSIVE"
    assert stale_volatility.reason_codes == ["STALE_VOLATILITY_FEATURE"]
    assert stale_volatility.estimated_slippage_bps is None


def test_placeholder_spread_or_missing_symbol_cannot_be_evaluated() -> None:
    placeholder = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(observed_spread=False),
        feature_row=_feature_row(),
        side="BUY",
    )
    missing_symbol = estimate_state_dependent_slippage(
        spread_snapshot=_spread_snapshot(symbol="TCS"),
        feature_row=_feature_row(symbol="RELIANCE"),
        side="BUY",
    )

    assert placeholder.state == "INCONCLUSIVE"
    assert placeholder.reason_codes == ["SPREAD_NOT_OBSERVED_FROM_MARKET_DEPTH"]
    assert missing_symbol.state == "INCONCLUSIVE"
    assert missing_symbol.reason_codes == ["MISSING_SPREAD_LIQUIDITY_STATE"]


def test_tampered_source_fingerprints_are_rejected() -> None:
    snapshot = _spread_snapshot().model_copy(update={"snapshot_fingerprint": "0" * 64})
    with pytest.raises(ValueError, match="spread snapshot fingerprint mismatch"):
        estimate_state_dependent_slippage(
            spread_snapshot=snapshot,
            feature_row=_feature_row(),
            side="BUY",
        )

    feature = _feature_row().model_copy(update={"fingerprint": "0" * 64})
    with pytest.raises(ValueError, match="FeatureRowV2 fingerprint mismatch"):
        estimate_state_dependent_slippage(
            spread_snapshot=_spread_snapshot(),
            feature_row=feature,
            side="BUY",
        )