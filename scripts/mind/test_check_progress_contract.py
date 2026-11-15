import json
import unittest

import check_progress_contract as c


def body(obj):
    return json.dumps(obj).encode()


class CheckProgressContractTest(unittest.TestCase):
    def test_summary_contract_and_reconciliation(self):
        ok = {"contractVersion": "mind.progress.v2", "overall": {"total": 313, "done": 10, "pending": 303}}
        self.assertIsNone(c.check("/api/customer/mind/v1/progress", body(ok)))

    def test_summary_must_reconcile_and_carry_the_v2_contract(self):
        self.assertIsNotNone(c.check("/api/customer/mind/v1/progress",
            body({"contractVersion": "mind.progress.v1", "overall": {"total": 1, "done": 0, "pending": 1}})))
        self.assertIsNotNone(c.check("/api/customer/mind/v1/progress",
            body({"contractVersion": "mind.progress.v2", "overall": {"total": 5, "done": 1, "pending": 1}})))

    def test_inventory_page_must_be_non_empty_and_under_the_cap(self):
        page = {"contractVersion": "mind.progress-inventory.v1", "items": [{"id": "x"}]}
        self.assertIsNone(c.check("/api/customer/mind/v1/progress/inventory?offset=0&limit=50", body(page)))
        self.assertIsNotNone(c.check("/api/customer/mind/v1/progress/inventory?offset=0&limit=50",
            body({"contractVersion": "mind.progress-inventory.v1", "items": []})))
        big = b"{" + b" " * c.EDGE_CAP + b"}"
        self.assertIsNotNone(c.check("/api/customer/mind/v1/progress/inventory?offset=0&limit=50", big))

    def test_item_detail_and_unknown_paths(self):
        self.assertIsNone(c.check("/api/customer/mind/v1/progress/items/screen.coverage", body({"item": {"id": "x"}})))
        self.assertIsNotNone(c.check("/api/customer/mind/v1/progress/items/screen.coverage", body({})))
        self.assertIsNotNone(c.check("/api/customer/mind/v1/elsewhere", body({})))


if __name__ == "__main__":
    unittest.main()
