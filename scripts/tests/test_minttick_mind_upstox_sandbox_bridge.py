"""No-network tests: Mind F&O read-only research to non-funded sandbox boundary.

Test fixtures are synthetic and NEVER count as market-data or trading E2E.
"""
from __future__ import annotations

import contextlib
import datetime as dt
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zoneinfo import ZoneInfo

SRC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SRC))
SPEC = importlib.util.spec_from_file_location(
    "minttick_mind_upstox_sandbox_bridge", SRC / "minttick_mind_upstox_sandbox_bridge.py")
bridge = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = bridge
SPEC.loader.exec_module(bridge)
IST = ZoneInfo("Asia/Kolkata")
T = dt.datetime(2026, 11, 23, 10, 30, tzinfo=IST)


def samples(now=T, strategy="strategy.STR-181"):
    expiry = (now.astimezone(IST).date() + dt.timedelta(days=8))
    side = "CE" if strategy == "strategy.STR-181" else "PE"
    key = "NSE_FO|47895" if side == "CE" else "NSE_FO|47896"
    item = {
        "contractVersion": "mind.fno-research.v1",
        "evaluatedAt": now.isoformat(),
        "underlyingKey": "NSE_INDEX|Nifty 50",
        "status": "RESEARCH_CANDIDATE",
        "signal": "BUY_CALL_RESEARCH" if side == "CE" else "BUY_PUT_RESEARCH",
        "strategyId": strategy,
        "candleSource": "UPSTOX_ANALYTICS",
        "completedCandles": 35,
        "latestCompletedCloseAt": (now-dt.timedelta(days=1)).isoformat(),
        "quoteSource": "UPSTOX_V3_EXCHANGE_TIMED",
        "expiry": expiry.isoformat(),
        "strike": 26000,
        "instrumentKey": key,
        "lotSize": 65,
        "bid": 99.95,
        "ask": 100.05,
        "exchangeAt": (now-dt.timedelta(seconds=5)).isoformat(),
        "observedAt": (now-dt.timedelta(seconds=3)).isoformat(),
        "executable": False,
        "orderPlaced": False,
    }
    master = [{
        "instrument_key": key,
        "instrument_type": side,
        "underlying_symbol": "NIFTY",
        "segment": "NSE_FO",
        "lot_size": 65,
        "expiry": int(dt.datetime.combine(expiry, dt.time(), tzinfo=IST).timestamp() * 1000),
        "strike_price": 26000,
        "tick_size": 5,
    }]
    return item, master


class MindToSandboxTests(unittest.TestCase):
    def test_genuine_shape_call_and_put_into_sandbox_only_intents(self):
        for strategy in bridge.STRATEGIES:
            with self.subTest(strategy=strategy):
                data, master = samples(strategy=strategy)
                intent = bridge.intent_from_research(data, master, T)
                self.assertEqual(intent.strategy_id, strategy)
                self.assertEqual(intent.quantity, 65)
                self.assertEqual(str(intent.limit_price), "100.05")
                self.assertEqual(str(intent.tick), "0.05")
                self.assertEqual(intent.instrument_key, data["instrumentKey"])

    def test_blocks_all_other_41_strategies_without_execution(self):
        data, master = samples()
        for i in list(range(170,181)) + list(range(183,213)):
            with self.subTest(strategy=i):
                data["strategyId"] = f"strategy.STR-{i}"
                with self.assertRaisesRegex(bridge.BridgeBlocked, "STRATEGY_NOT_SUPPORTED"):
                    bridge.intent_from_research(data, master, T)

    def test_no_trade_stale_wrong_provenance_and_authority_fail_closed(self):
        for field, value in (
            ("status", "NO_TRADE"),
            ("signal", "BUY_PUT_RESEARCH"),
            ("underlyingKey", "NSE_FO|123"),
            ("quoteSource", "SYNTHETIC"),
            ("candleSource", "UNKNOWN"),
            ("completedCandles", 34),
            ("orderPlaced", True),
            ("executable", True),
            ("instrumentKey", "NSE_EQ|abc"),
            ("bid", 101),
            ("ask", 100.03),
            ("lotSize", 0),
            ("exchangeAt", (T-dt.timedelta(seconds=16)).isoformat()),
            ("evaluatedAt", (T+dt.timedelta(seconds=1)).isoformat()),
        ):
            with self.subTest(field=field, value=value):
                data, master = samples()
                data[field] = value
                with self.assertRaises(bridge.BridgeBlocked):
                    bridge.intent_from_research(data, master, T)

    def test_rejects_bod_wrong_key_duplicate_lot_expiry_side_strike_tick(self):
        data, master = samples()
        for field, value in (
            ("instrument_key", "NSE_FO|NOPE"),
            ("lot_size", 100),
            ("segment", "BSE_FO"),
            ("expiry", 1000000000000),
            ("instrument_type", "PE"),
            ("underlying_symbol", "BANKNIFTY"),
            ("strike_price", 26100),
            ("tick_size", 10),
        ):
            with self.subTest(field=field):
                row = dict(master[0]); row[field] = value
                with self.assertRaisesRegex(bridge.BridgeBlocked, "BOD_CONTRACT_UNVERIFIED"):
                    bridge.intent_from_research(data, [row], T)
        with self.assertRaisesRegex(bridge.BridgeBlocked, "BOD_CONTRACT_UNVERIFIED"):
            bridge.intent_from_research(data, master + master, T)

    def test_dry_run_no_aws_or_broker_calls_and_manual_flag_needed(self):
        now = dt.datetime.now(dt.timezone.utc)
        data, master = samples(now)
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "mind.json"
            p.write_text(json.dumps(data))
            with patch.object(bridge, "current_nse_master", return_value=master), \
                 patch.object(bridge.ssm, "load_sandbox_token") as token, \
                 patch.object(bridge.broker, "run_cycle") as orders, \
                 contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(bridge.main(["--research-json", str(p)]), 0)
                self.assertIn("DRY_RUN:ZERO_BROKER_ORDERS", out.getvalue())
                self.assertEqual(bridge.main(["--research-json", str(p), "--execute-sandbox"]), 2)
                token.assert_not_called()
                orders.assert_not_called()

    def test_untrusted_file_based_execution_is_disabled_even_with_confirmation(self):
        now = dt.datetime.now(dt.timezone.utc)
        data, master = samples(now)
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "mind.json"
            p.write_text(json.dumps(data))
            with patch.object(bridge, "current_nse_master", return_value=master) as bod, \
                 patch.object(bridge.ssm, "load_sandbox_token") as token, \
                 patch.object(bridge.broker, "run_cycle") as orders, \
                 contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(bridge.main(["--research-json", str(p),
                    "--execute-sandbox", "--confirmation", "SANDBOX_ONLY"]), 2)
                self.assertIn("FILE_EXECUTION_DISABLED", out.getvalue())
                bod.assert_not_called()
                token.assert_not_called()
                orders.assert_not_called()


if __name__ == "__main__":
    unittest.main()
