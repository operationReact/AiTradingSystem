"""Offline tests for restricted GitHub issue receipt; never invoke GitHub or AWS."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
from fno_expired_history_receipt import safe_receipt, safe_issue_message


class SourceReceiptTest(unittest.TestCase):
    def test_only_one_exact_safe_receipt_is_accepted(self):
        line = ("FNO EXPIRED SOURCE: HISTORY_SOURCE_VERIFIED, expiries=6, "
                "futures=1, dailyBars=5; rights=UNKNOWN; "
                "persisted=FALSE; orders=FALSE")
        self.assertEqual(safe_receipt("Private token XYZ\n"+line+"\n"),
                         ("HISTORY_SOURCE_VERIFIED", 6, 1, 5))
        message = safe_issue_message(safe_receipt(line), "44815555555")
        self.assertIn("SOURCE_SAMPLE_VERIFIED", message)
        self.assertIn("/actions/runs/44815555555", message)
        self.assertNotIn("XYZ", message)
        self.assertIn("not evidence of bulk NSE_FO history", message)

    def test_missing_receipt_does_not_fabricate_state(self):
        msg = safe_issue_message(safe_receipt("AWS error or bearer token"), "44815000000")
        self.assertIn("NO_SANITIZED_RECEIPT", msg)
        self.assertNotIn("bearer", msg)

    def test_blocked_source_is_not_pass(self):
        line = ("FNO EXPIRED SOURCE: PLUS_ENTITLEMENT_MISSING, expiries=0, "
                "futures=0, dailyBars=0; rights=UNKNOWN; "
                "persisted=FALSE; orders=FALSE")
        message = safe_issue_message(safe_receipt(line), "44815555555")
        self.assertIn("SOURCE_BLOCKED", message)
        self.assertIn("PLUS_ENTITLEMENT_MISSING", message)
        self.assertNotIn("SOURCE_SAMPLE_VERIFIED", message)

    def test_provider_http_stage_error_is_precise_without_raw_payload(self):
        receipt = ("FNO EXPIRED SOURCE: EXPIRIES_FORBIDDEN, expiries=0, "
                   "futures=0, dailyBars=0; rights=UNKNOWN; "
                   "persisted=FALSE; orders=FALSE")
        parsed = safe_receipt("REDACTED token private\n" + receipt)
        self.assertEqual(parsed, ("EXPIRIES_FORBIDDEN", 0, 0, 0))
        published = safe_issue_message(parsed, "44817825197")
        self.assertIn("HTTP 403 at expiries endpoint", published)
        self.assertNotIn("REDACTED token", published)
        self.assertIn("SOURCE_BLOCKED", published)

    def test_ambiguous_forged_and_out_of_range_receipts_are_rejected(self):
        good = ("FNO EXPIRED SOURCE: HISTORY_SOURCE_VERIFIED, expiries=6, "
                "futures=1, dailyBars=5; rights=UNKNOWN; "
                "persisted=FALSE; orders=FALSE")
        for output in (good+"\n"+good, good.replace("rights=UNKNOWN", "rights=TRUE"),
                       good.replace("dailyBars=5", "dailyBars=1000000"),
                       good.replace("HISTORY_SOURCE_VERIFIED","ALL_DONE"),
                       good.replace("futures=1","futures=0"), good*1000):
            with self.subTest(size=len(output)):
                self.assertIsNone(safe_receipt(output))

    def test_issue_link_is_pinned_to_owned_repo(self):
        for untrusted in ("12<script>", "123/bad", "../42", "", "x"):
            with self.assertRaises(ValueError):
                safe_issue_message(None, untrusted)


if __name__ == "__main__":
    unittest.main()
