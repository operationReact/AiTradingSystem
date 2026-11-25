"""Offline correctness and privacy contract for F&O customer-board audit."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra/ec2-paper"))
from fno_customer_board_audit import (
    FIELDS, classify, expected_request, parse_receipt, safe_receipt
)

class BoardAuditTest(unittest.TestCase):
    def valid(self):
        return {"status":"CONTRACT_MATCHED","boardStatus":"MARKET_CLOSED",
            "validatedStrikes":7, **{k:False for k in FIELDS},
            "safeNoOrders":True, "liveOrderEnabled":False}

    def test_request_fixed_no_orders(self):
        request=expected_request()
        self.assertEqual(request["instrument"],"NSE_FO|47821")
        self.assertEqual(request["expiry"],"2026-12-01")
        self.assertEqual(request["orders"],False)
        self.assertEqual(request["persist"],False)

    def test_real_internal_board_response_presence(self):
        data=self.valid()
        for key in ("displayRightsVerified","discoveryVerified","sourceReady",
                    "listedContractFound","verifiedLastLtpPresent",
                    "chainSnapshotLtpPresent","chainSnapshotOiPresent",
                    "chainSnapshotGreeksComplete","chainSnapshotRecent"):
            data[key]=True
        state,fields=classify(data)
        self.assertEqual(state,"CONTRACT_MATCHED")
        line=safe_receipt(state,fields)
        self.assertIn("listedContractFound=true",line)
        self.assertIn("chainSnapshotGreeksComplete=true",line)
        self.assertEqual(parse_receipt(line),line)
        self.assertNotIn("25650",line)
        self.assertNotIn("NSE_FO",line)

    def test_unverified_data_or_order_authority_cannot_pass(self):
        data=self.valid()
        data["safeNoOrders"]=False
        self.assertEqual(classify(data)[0],"INVALID_RESPONSE")
        data["safeNoOrders"]=True
        data["liveOrderEnabled"]=True
        self.assertEqual(classify(data)[0],"INVALID_RESPONSE")
        data["liveOrderEnabled"]=False
        data["verifiedLastLtpPresent"]="yes"
        self.assertEqual(classify(data)[0],"INVALID_RESPONSE")

    def test_parser_rejects_duplicate_receipts_and_invalid_fields(self):
        line=safe_receipt("HTTP_403")
        self.assertEqual(parse_receipt(line),line)
        self.assertIsNone(parse_receipt(line+"\n"+line))
        self.assertIsNone(parse_receipt(line.replace("safe=true","safe=false")))
        self.assertIsNone(parse_receipt(line+" quote=123"))

if __name__=="__main__":
    unittest.main()
