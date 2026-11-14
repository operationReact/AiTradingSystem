"""Offline, deterministic proof contract for private NIFTY F&O source probe."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
from fno_source_readiness import validate_response, expected_request


def valid():
    return {
        "status": "READS_VERIFIED", "underlyingKey": "NSE_INDEX|Nifty 50",
        "expiry": "2026-11-17", "validatedContracts": 130,
        "validatedStrikes": 65, "callsWithContract": 65,
        "putsWithContract": 65, "rowsWithCompleteGreeks": 57,
        "observedAt": "2026-11-14T10:00:00Z",
        "exchangeQuoteTimeVerified": False, "stored": False,
        "displayRightsVerified": False,
        "modelTrainingRightsVerified": False, "liveTradingEnabled": False,
    }


class FnoSourceReadinessTest(unittest.TestCase):
    def test_only_exact_read_only_owner_request(self):
        d=expected_request()
        self.assertEqual(d["expiry"],"2026-11-17")
        self.assertEqual(d["underlying"],"NSE_INDEX|Nifty 50")
        self.assertEqual(d["environment"],"PAPER")
        self.assertEqual(d["operation"],"probe-upstox-nifty-options-read-only")
        self.assertEqual(d["backend_release"],
                         "641a2bed49ac7ce07aa673e810a03b7a1632dc27")
        self.assertEqual(len(d),10)

    def test_counts_are_accepted_without_any_quote_values(self):
        status, contracts, strikes, calls, puts, greeks=validate_response(valid())
        self.assertEqual(status,"READS_VERIFIED")
        self.assertEqual((contracts,strikes,calls,puts,greeks),(130,65,65,65,57))

    def test_partial_zero_is_observed_not_faked_as_success(self):
        v=valid()
        v.update(status="PARTIAL_DATA",validatedContracts=0,validatedStrikes=0,
                 callsWithContract=0,putsWithContract=0,rowsWithCompleteGreeks=0)
        self.assertEqual(validate_response(v)[0],"PARTIAL_DATA")

    def test_false_success_rejected(self):
        v=valid()
        v["putsWithContract"]=1
        with self.assertRaisesRegex(ValueError,"FNO_FALSE_VERIFIED_STATUS"):
            validate_response(v)

    def test_missing_expiry_and_unexpected_underlying_rejected(self):
        for value in ("2026-11-19",None,"2026-11-16"):
            v=valid()
            v["expiry"]=value
            with self.assertRaisesRegex(ValueError,"FNO_INVALID_PROVIDER_IDENTITY"):
                validate_response(v)
        v=valid()
        v["underlyingKey"]="BSE_INDEX|SENSEX"
        with self.assertRaisesRegex(ValueError,"FNO_INVALID_PROVIDER_IDENTITY"):
            validate_response(v)

    def test_unsupported_rights_and_live_order_assertions_rejected(self):
        for flag in ("exchangeQuoteTimeVerified","stored","displayRightsVerified",
                     "modelTrainingRightsVerified","liveTradingEnabled"):
            v=valid()
            v[flag]=True
            with self.assertRaisesRegex(ValueError,"FNO_UNSUPPORTED_PROVENANCE_OR_RIGHTS"):
                validate_response(v)

    def test_invalid_number_types_and_unbounded_counts_fail_closed(self):
        for value in (True,None,-1,1.5,1025,"1"):
            v=valid()
            v["validatedContracts"]=value
            with self.assertRaisesRegex(ValueError,"FNO_INVALID_COUNTS"):
                validate_response(v)
        v=valid()
        v["callsWithContract"]=66
        with self.assertRaisesRegex(ValueError,"FNO_INVALID_COUNTS"):
            validate_response(v)

    def test_unknown_status_never_verified(self):
        v=valid()
        v["status"]="SUCCESS"
        with self.assertRaisesRegex(ValueError,"FNO_INVALID_PROVIDER_STATUS"):
            validate_response(v)


if __name__=="__main__":
    unittest.main()
