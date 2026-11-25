import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import gemini_secret_bind as bind


class GeminiSecretBindTests(unittest.TestCase):
    def test_api_key_shape_is_bounded(self):
        bind.validate_key("AIzaSyExampleSyntheticKey0123456789_-")
        bind.validate_key("google-key:v2." + "a" * 300)
        with self.assertRaises(bind.BindError):
            bind.validate_key("invalid key with whitespace")
        self.assertEqual(bind.validate_key("  AIzaSyExampleSyntheticKey0123456789_-\n"), "AIzaSyExampleSyntheticKey0123456789_-")
        with self.assertRaises(bind.BindError):
            bind.validate_key("")
        with self.assertRaises(bind.BindError):
            bind.validate_key("contains newline\ninvalid")

    def test_host_bootstrap_never_contains_api_secret(self):
        command = bind.bootstrap_command("a" * 32)
        self.assertIn("openssl genpkey", command)
        self.assertIn(bind.PUBLIC_MARKER, command)
        self.assertNotIn("AIza", command)

    def test_envelope_encrypts_for_host_public_key(self):
        secret = "AIzaSySyntheticKey0123456789_abcdefghijklmnopqrstuvwxyz"
        with tempfile.TemporaryDirectory() as directory:
            os.chmod(directory, 0o700)
            private = Path(directory) / "private.pem"
            public = Path(directory) / "public.pem"
            ciphertext = Path(directory) / "ciphertext"
            plaintext = Path(directory) / "plaintext"
            recovered = Path(directory) / "recovered"
            subprocess.run([
                "openssl", "genpkey", "-algorithm", "RSA",
                "-pkeyopt", "rsa_keygen_bits:3072", "-out", str(private),
            ], check=True, capture_output=True)
            subprocess.run([
                "openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public),
            ], check=True, capture_output=True)
            public_b64 = base64.b64encode(public.read_bytes()).decode()
            envelope = bind.encrypt_for_host(public_b64, secret)
            self.assertNotIn(secret, envelope)
            ciphertext.write_bytes(base64.b64decode(envelope))
            subprocess.run([
                "openssl", "pkeyutl", "-decrypt", "-inkey", str(private),
                "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256",
                "-in", str(ciphertext), "-out", str(recovered),
            ], check=True, capture_output=True)
            self.assertEqual(recovered.read_text(), secret)

    def test_install_command_contains_only_ciphertext_not_plaintext(self):
        secret = "AIzaSySyntheticKey0123456789_abcdefghijklmnopqrstuvwxyz"
        ciphertext = base64.b64encode(b"x" * 384).decode()
        command = bind.install_command("b" * 32, ciphertext)
        self.assertIn(ciphertext, command)
        self.assertNotIn(secret, command)
        self.assertIn("runtime.env", command)
        self.assertIn("GEMINI_API_KEY", command)
        self.assertIn("NEWS_ANALYSIS_PROVIDER=GEMINI", command)

    def test_invalid_ciphertext_is_rejected(self):
        with self.assertRaises(bind.BindError):
            bind.install_command("c" * 32, "not-an-envelope")


if __name__ == "__main__":
    unittest.main()
