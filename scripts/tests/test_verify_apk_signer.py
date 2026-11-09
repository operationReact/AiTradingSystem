"""Fixtures for real apksigner certificate labels and strict failure states."""
import unittest

from scripts.verify_apk_signer import normalize_digest, parse_apksigner_certificate

SHA = "7C65E750C0D1BEB0E700051CCDEB2796F9EF600CF38B29B90BEB94304883904B"


class VerifyApkSignerTest(unittest.TestCase):
    def test_standard_android_sdk_report(self):
        report = (
            "Verifies\n"
            "Verified using v1 scheme (JAR signing): true\n"
            "Verified using v2 scheme (APK Signature Scheme v2): true\n"
            f"Signer #1 certificate SHA-256 digest: {SHA.lower()}\n"
            "Signer #1 certificate SHA-1 digest: abcdef\n"
        )
        self.assertEqual(parse_apksigner_certificate(report), SHA)

    def test_spacing_colon_bytes_and_stderr_combination(self):
        colon = ":".join(SHA[i:i+2] for i in range(0, 64, 2))
        self.assertEqual(parse_apksigner_certificate(
            f"  Signer #1 certificate SHA256 fingerprint: {colon}  \n"
            "Signer #1 certificate SHA-1 digest: x\n"
        ), SHA)

    def test_reject_no_signer_or_content_checksum(self):
        with self.assertRaises(ValueError):
            parse_apksigner_certificate(f"APK content SHA-256 digest: {SHA}")
        with self.assertRaises(ValueError):
            parse_apksigner_certificate("Verified using v2 scheme: true")

    def test_reject_multiple_or_conflicting_signers(self):
        with self.assertRaises(ValueError):
            parse_apksigner_certificate(
                f"Signer #1 certificate SHA-256 digest: {SHA}\n"
                f"Signer #2 certificate SHA-256 digest: {SHA}\n"
            )
        with self.assertRaises(ValueError):
            parse_apksigner_certificate(
                f"Signer #1 certificate SHA-256 digest: {SHA}\n"
                f"Signer #1 certificate SHA-256 digest: {'A' * 64}\n"
            )

    def test_bad_pinned_certificate_fails(self):
        with self.assertRaises(ValueError):
            normalize_digest("not-a-sha256")


if __name__ == "__main__":
    unittest.main()
