import unittest

import paper_verification_cycle as pv


READY = {
    "mode": "PAPER", "selectedBroker": None, "brokerConnected": False,
    "globalAutomation": {"tradingMode": "PAPER", "orderExecutionMode": "MANUAL", "automatedPaper": False},
}


class PaperVerificationCycleTests(unittest.TestCase):
    def test_exactly_one_paper_room_is_selected(self):
        rooms = [{"id": "a", "mode": "LIVE"}, {"id": "b", "mode": "PAPER"}]
        self.assertEqual(pv.select_paper_room(rooms)["id"], "b")

    def test_zero_or_many_paper_rooms_fail_closed(self):
        with self.assertRaises(pv.VerificationError):
            pv.select_paper_room([{"id": "a", "mode": "LIVE"}])
        with self.assertRaises(pv.VerificationError):
            pv.select_paper_room([{"id": "a", "mode": "PAPER"}, {"id": "b", "mode": "PAPER"}])

    def test_isolation_passes_for_paper_room_with_automation_off(self):
        pv.assert_isolation(READY)

    def test_selected_or_connected_live_broker_blocks(self):
        with self.assertRaises(pv.VerificationError):
            pv.assert_isolation({**READY, "selectedBroker": "ANGEL_ONE"})
        with self.assertRaises(pv.VerificationError):
            pv.assert_isolation({**READY, "brokerConnected": True})

    def test_automated_paper_orders_on_blocks(self):
        bad = {**READY, "globalAutomation": {**READY["globalAutomation"], "automatedPaper": True}}
        with self.assertRaisesRegex(pv.VerificationError, "automated PAPER"):
            pv.assert_isolation(bad)

    def test_missing_automation_state_fails_closed(self):
        with self.assertRaisesRegex(pv.VerificationError, "isolation not demonstrated"):
            pv.assert_isolation({k: v for k, v in READY.items() if k != "globalAutomation"})

    def test_live_trading_mode_blocks(self):
        bad = {**READY, "globalAutomation": {**READY["globalAutomation"], "tradingMode": "LIVE"}}
        with self.assertRaisesRegex(pv.VerificationError, "not PAPER"):
            pv.assert_isolation(bad)

    def test_instrument_must_match_exactly_once(self):
        content = [{"id": 2885, "tradingSymbol": "RELIANCE-EQ", "exchange": "NSE"}]
        self.assertEqual(pv.find_instrument(content, "RELIANCE-EQ", "NSE"), 2885)
        with self.assertRaises(pv.VerificationError):
            pv.find_instrument([], "RELIANCE-EQ", "NSE")

    def test_budget_rejects_more_than_the_allowed_new_orders(self):
        with self.assertRaisesRegex(pv.VerificationError, "exceeds"):
            pv.assert_budget({"outcome": "NO_ENTRY"}, 1_000_000_00, 3, 5, 1)

    def test_no_new_order_is_within_budget(self):
        pv.assert_budget({"outcome": "NO_ENTRY"}, 1_000_000_00, 3, 3, 1)

    def test_fill_respects_twenty_percent_exposure_cap(self):
        # 100 shares at Rs 100 = Rs 10,000 = 1% of Rs 10 lakh: allowed.
        pv.assert_budget({"outcome": "FILLED", "quantity": 100, "limitPrice": 100}, 1_000_000_00, 0, 1, 1)
        # 3,000 shares at Rs 100 = Rs 3,00,000 = 30%: rejected.
        with self.assertRaisesRegex(pv.VerificationError, "exposure cap"):
            pv.assert_budget({"outcome": "FILLED", "quantity": 3000, "limitPrice": 100}, 1_000_000_00, 0, 1, 1)

    def test_replay_must_return_the_same_cycle(self):
        pv.assert_idempotent({"id": "x"}, {"id": "x"})
        with self.assertRaises(pv.VerificationError):
            pv.assert_idempotent({"id": "x"}, {"id": "y"})


if __name__ == "__main__":
    unittest.main()
