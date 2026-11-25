import unittest

import paper_verification_room as rv

REQUEST = {
    "name": "Mind PAPER verification", "focus": "INTRADAY", "plannedBudgetInr": 100000,
    "riskPreference": "CAUTIOUS", "mode": "PAPER", "marketScope": "EQUITY",
}


class PaperVerificationRoomTests(unittest.TestCase):
    def test_fresh_account_with_no_rooms_passes(self):
        rv.assert_fresh_account([])

    def test_account_with_existing_rooms_is_refused(self):
        with self.assertRaisesRegex(rv.RoomError, "already has"):
            rv.assert_fresh_account([{"id": "x", "mode": "PAPER"}])

    def test_missing_room_list_fails_closed(self):
        with self.assertRaises(rv.RoomError):
            rv.assert_fresh_account(None)

    def test_reviewed_request_passes(self):
        rv.assert_room_request(REQUEST)

    def test_live_request_is_refused(self):
        with self.assertRaisesRegex(rv.RoomError, "not PAPER"):
            rv.assert_room_request({**REQUEST, "mode": "LIVE"})

    def test_other_budget_is_refused(self):
        with self.assertRaisesRegex(rv.RoomError, "budget"):
            rv.assert_room_request({**REQUEST, "plannedBudgetInr": 500000})

    def test_fno_scope_is_refused(self):
        with self.assertRaisesRegex(rv.RoomError, "scope"):
            rv.assert_room_request({**REQUEST, "marketScope": "FNO"})

    def test_wallet_must_match_exact_reviewed_cash(self):
        rv.assert_wallet({"initialCashPaise": 10000000, "availableCashPaise": 10000000}, 100000)
        with self.assertRaisesRegex(rv.RoomError, "initial cash"):
            rv.assert_wallet({"initialCashPaise": 20000000, "availableCashPaise": 20000000}, 100000)
        with self.assertRaisesRegex(rv.RoomError, "available cash"):
            rv.assert_wallet({"initialCashPaise": 10000000, "availableCashPaise": 5000000}, 100000)


if __name__ == "__main__":
    unittest.main()
