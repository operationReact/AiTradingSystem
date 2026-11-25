import struct
import unittest
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from live_tick_integrity import LiveTickIntegrityGate, RejectedTick

NOW = datetime(2026, 11, 9, 4, 0, 5, tzinfo=timezone.utc)


def frame(*, venue=1, token="2885", seq=7, at=NOW, paise=125455, mode=1):
    return (bytes((mode, venue)) + token.encode("ascii").ljust(25, b"\0") +
            struct.pack("<qqq", seq, int(at.timestamp() * 1000), paise))


def connected():
    gate = LiveTickIntegrityGate({(1, "2885"): 12, (3, "500325"): 15})
    gate.mark_connected(at=NOW - timedelta(seconds=1))
    return gate


class LiveTickIntegrityTests(unittest.TestCase):
    def test_trusted_catalogue_bound_nse_bse_ltp_and_subscription(self):
        gate = connected()
        self.assertEqual(gate.subscription_request("mt123"), {
            "correlationID": "mt123", "action": 1,
            "params": {"mode": 1, "tokenList": [
                {"exchangeType": 1, "tokens": ["2885"]},
                {"exchangeType": 3, "tokens": ["500325"]},
            ]},
        })
        tick = gate.accept_ltp(frame(), received_at=NOW)
        self.assertEqual(tick.instrument_id, 12)
        self.assertEqual(tick.ltp, Decimal("1254.55"))
        self.assertEqual(tick.exchange_at, NOW)
        self.assertEqual(tick.source, "ANGEL_ONE_SMARTAPI_WS_V2")
        self.assertIs(gate.live_quote(12, now=NOW, market_open=True), tick)
        bse = gate.accept_ltp(frame(venue=3, token="500325", paise=900), received_at=NOW)
        self.assertEqual((bse.instrument_id, bse.exchange, bse.ltp), (15, "BSE", Decimal("9")))

    def assertRejected(self, gate, packet, code, *, now=NOW):
        with self.assertRaises(RejectedTick) as caught:
            gate.accept_ltp(packet, received_at=now)
        self.assertEqual(caught.exception.code, code)

    def test_fails_closed_for_unknown_instrument_mode_and_bad_packet(self):
        gate = connected()
        self.assertRejected(gate, frame(token="999"), "UNSUBSCRIBED_INSTRUMENT")
        self.assertRejected(gate, frame(venue=2), "UNSUPPORTED_TICK_MODE_OR_EXCHANGE")
        self.assertRejected(gate, frame(mode=2), "UNSUPPORTED_TICK_MODE_OR_EXCHANGE")
        self.assertRejected(gate, frame()[:-1], "INVALID_TICK_LENGTH")
        self.assertRejected(gate, frame(paise=0), "INVALID_TICK_VALUES")
        self.assertRejected(gate, frame(paise=-2), "INVALID_TICK_VALUES")

    def test_rejects_delayed_future_and_replayed_ticks(self):
        gate = connected()
        self.assertRejected(gate, frame(at=NOW - timedelta(seconds=11)), "STALE_TICK")
        self.assertRejected(gate, frame(at=NOW + timedelta(seconds=3)), "FUTURE_TICK")
        gate.accept_ltp(frame(), received_at=NOW)
        self.assertRejected(gate, frame(), "OUT_OF_ORDER_TICK")
        self.assertRejected(gate, frame(seq=8, at=NOW - timedelta(seconds=1)), "OUT_OF_ORDER_TICK")
        self.assertIsNotNone(gate.live_quote(12, now=NOW + timedelta(seconds=10), market_open=True))
        self.assertIsNone(gate.live_quote(12, now=NOW + timedelta(seconds=11), market_open=True))
        self.assertIsNone(gate.live_quote(12, now=NOW, market_open=False))

    def test_disconnect_and_reconnect_invalidate_old_ticks(self):
        gate = connected()
        gate.accept_ltp(frame(), received_at=NOW)
        gate.mark_disconnected()
        self.assertIsNone(gate.live_quote(12, now=NOW, market_open=True))
        self.assertRejected(gate, frame(seq=9), "FEED_DISCONNECTED")
        gate.mark_connected(at=NOW)
        self.assertIsNone(gate.live_quote(12, now=NOW, market_open=True))
        gate.accept_ltp(frame(seq=1), received_at=NOW)
        self.assertIsNotNone(gate.live_quote(12, now=NOW, market_open=True))

    def test_pong_refreshes_connection_only_not_stale_market_ticks(self):
        gate = connected()
        gate.accept_ltp(frame(), received_at=NOW)
        gate.mark_pong(at=NOW + timedelta(seconds=30))
        self.assertIsNone(gate.live_quote(12, now=NOW + timedelta(seconds=30), market_open=True))
        self.assertRejected(gate, frame(at=NOW), "STALE_TICK", now=NOW + timedelta(seconds=30))
        self.assertIsNone(gate.live_quote(12, now=NOW + timedelta(seconds=76), market_open=True))

    def test_invalid_subscription_config_and_correlation(self):
        for allowed in ({}, {(2, "1"): 1}, {(1, "NIFTY"): 1}, {(1, "1"): 0},
                        {(1, "1"): 3, (3, "2"): 3}, {(1, str(i)): i for i in range(1001)}):
            with self.assertRaises(ValueError):
                LiveTickIntegrityGate(allowed)
        with self.assertRaises(ValueError):
            connected().subscription_request("bad-correlation-code")


if __name__ == "__main__":
    unittest.main()
