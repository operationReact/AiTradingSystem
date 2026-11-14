"""Offline, deterministic proof contract for private NIFTY F&O source probe."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
from fno_source_readiness import validate_response, expected_request, sanitized_source_receipt


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
                         "97e6cc183deb6c76e4c29b618a02dc971dd4c40d")
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

    def test_safe_partial_and_disabled_receipts_are_reported_without_secret(self):
        sample=("some secret Bearer abcdef\n"
                "MINTTICK_FNO_SOURCE:status=DISABLED contracts=0 strikes=0 "
                "calls=0 puts=0 greeks=0 expiry=2026-11-17 stored=false rights=false\n"
                "raw provider secret")
        parsed=sanitized_source_receipt(sample)
        self.assertEqual(parsed,
            "status=DISABLED contracts=0 strikes=0 calls=0 puts=0 greeks=0 "
            "expiry=2026-11-17 stored=false rights=false")
        self.assertNotIn("secret",parsed)
        self.assertNotIn("Bearer",parsed)
        sample=sample.replace("status=DISABLED","status=PARTIAL_DATA")
        self.assertIn("status=PARTIAL_DATA",sanitized_source_receipt(sample))

    def test_unsafe_receipts_are_suppressed(self):
        good=("MINTTICK_FNO_SOURCE:status=READS_VERIFIED contracts=130 "
              "strikes=65 calls=65 puts=65 greeks=57 "
              "expiry=2026-11-17 stored=false rights=false")
        self.assertIsNotNone(sanitized_source_receipt(good))
        for invalid in (good+"\n"+good,
                        good.replace("contracts=130","contracts=99999999"),
                        good.replace("status=READS_VERIFIED","status=SUCCESS"),
                        good.replace("rights=false","rights=true"),
                        good.replace("calls=65","calls=70"),
                        good * 2000):
            self.assertIsNone(sanitized_source_receipt(invalid))

    def test_classified_http_failure_is_bounded_and_never_verified(self):
        for status in ("CONTRACTS_UNAUTHORIZED", "CONTRACTS_FORBIDDEN",
                       "CONTRACTS_RATE_LIMITED", "CONTRACTS_NOT_FOUND",
                       "CONTRACTS_UPSTREAM_UNAVAILABLE",
                       "CONTRACTS_UPSTREAM_REJECTED",
                       "CONTRACTS_TRANSPORT_FAILURE",
                       "CONTRACTS_CREDENTIAL_UNAVAILABLE",
                       "CONTRACTS_INVALID_PAYLOAD",
                       "CHAIN_UNAUTHORIZED", "CHAIN_FORBIDDEN",
                       "CHAIN_UPSTREAM_UNAVAILABLE", "CHAIN_INVALID_PAYLOAD",
                       "CHAIN_SCHEMA_INVALID", "CHAIN_ENVELOPE_INVALID",
                       "CONTRACTS_SCHEMA_INVALID", "CONTRACTS_ENVELOPE_INVALID"):
            v=valid()
            v.update(status=status, validatedContracts=0, validatedStrikes=0,
                callsWithContract=0, putsWithContract=0, rowsWithCompleteGreeks=0)
            self.assertEqual(validate_response(v)[0],status)
            line=("Bearer private-secret\n"
                +f"MINTTICK_FNO_SOURCE:status={status} contracts=0 strikes=0 "
                "calls=0 puts=0 greeks=0 expiry=2026-11-17 stored=false rights=false\n"
                "private-secret")
            result=sanitized_source_receipt(line)
            self.assertIn("status="+status,result)
            self.assertNotIn("private-secret",result)
        for invalid in ("CONTRACTS_SECRET_BEARER", "CHAIN_FAILURE_HTTP_BODY",
                        "CONTRACTS_HTTP_200", "CONTRACTS_INTERNAL_ERROR"):
            v=valid()
            v["status"]=invalid
            with self.assertRaisesRegex(ValueError,"FNO_INVALID_PROVIDER_STATUS"):
                validate_response(v)

    def test_unknown_status_never_verified(self):
        v=valid()
        v["status"]="SUCCESS"
        with self.assertRaisesRegex(ValueError,"FNO_INVALID_PROVIDER_STATUS"):
            validate_response(v)


if __name__=="__main__":
    unittest.main()
