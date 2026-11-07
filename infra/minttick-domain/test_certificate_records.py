"""Offline tests for strict ACM validation record normalization."""
import importlib.util
from pathlib import Path
from unittest import TestCase, mock

script = Path(__file__).with_name("ensure_certificate.py")
spec = importlib.util.spec_from_file_location("minttick_domain_cert", script)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def entry(domain, target=None, name=None, kind="CNAME"):
    owner = name if name is not None else "_a1b2c3d4." + domain
    destination = target if target is not None else "_a1b2c3d4.acm-validations.aws"
    return {"DomainName": domain,
            "ResourceRecord": {"Name": owner, "Type": kind, "Value": destination}}


def result(*records):
    return {"Certificate": {"DomainValidationOptions": list(records),
                            "Status": "PENDING_VALIDATION"}}


class ACMRecordsTest(TestCase):
    def test_normalizes_acm_records_without_terminal_dots(self):
        data = result(entry("minttick.com"), entry("www.minttick.com"),
                      entry("api.minttick.com"))
        with mock.patch.object(module, "aws", return_value=data):
            records, status = module.get_validation_records("test-certificate")
        self.assertEqual(len(records), 3)
        self.assertEqual(status, "PENDING_VALIDATION")
        for row in records:
            self.assertTrue(row["Name"].endswith("."))
            self.assertTrue(row["ResourceRecords"][0]["Value"].endswith("."))

    def test_accepts_dns_safe_dash_underscore_acm_token(self):
        data = result(entry("minttick.com", target="_a-b_c.acm-validations.aws"),
                      entry("www.minttick.com"), entry("api.minttick.com"))
        with mock.patch.object(module, "aws", return_value=data):
            records, _ = module.get_validation_records("test-certificate")
        self.assertEqual(len(records), 3)

    def test_rejects_unrelated_target(self):
        data = result(entry("minttick.com", target="_malicious.example.net"),
                      entry("www.minttick.com"), entry("api.minttick.com"))
        with mock.patch.object(module, "aws", return_value=data):
            with self.assertRaisesRegex(RuntimeError, "target shape"):
                module.get_validation_records("test-certificate")

    def test_rejects_owner_mismatch(self):
        data = result(entry("minttick.com", name="_a1b2c3d4.www.minttick.com"),
                      entry("www.minttick.com"), entry("api.minttick.com"))
        with mock.patch.object(module, "aws", return_value=data):
            with self.assertRaisesRegex(RuntimeError, "record/domain mismatch"):
                module.get_validation_records("test-certificate")

    def test_rejects_non_cname_type(self):
        data = result(entry("minttick.com", kind="A"),
                      entry("www.minttick.com"), entry("api.minttick.com"))
        with mock.patch.object(module, "aws", return_value=data):
            with self.assertRaisesRegex(RuntimeError, "record type"):
                module.get_validation_records("test-certificate")
