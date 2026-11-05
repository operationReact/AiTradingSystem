"""V98 offline base-image digest guard: no Docker, AWS, registry or broker."""
from __future__ import annotations

import contextlib
import io
import unittest
from unittest.mock import patch

from aitrading_intelligence import research_base_digest_gate as gate

DIGEST = "a" * 64
CANONICAL = f"docker.io/library/python@sha256:{DIGEST}"


class BaseImageDigestGateTest(unittest.TestCase):
    def test_approved_docker_official_python_digest_syntax(self):
        self.assertEqual(gate.verify_reference(CANONICAL), CANONICAL)
        self.assertEqual(
            gate.select_pulled_reference([f"python@sha256:{DIGEST}"]),
            CANONICAL,
        )
        self.assertEqual(
            gate.select_pulled_reference([
                f"python@sha256:{DIGEST}",
                f"docker.io/library/python@sha256:{DIGEST}",
            ]),
            CANONICAL,
        )

    def test_mutable_or_untrusted_references_rejected(self):
        for reference in (
            "", "python:3.11-slim", "docker.io/library/python:3.11-slim",
            f"python@sha256:{DIGEST}",
            f"evil.example/library/python@sha256:{DIGEST}",
            f"docker.io/library/python:3.11-slim@sha256:{DIGEST}",
            f"docker.io/library/python@sha256:{DIGEST}:bad",
            f"docker.io/library/other@sha256:{DIGEST}",
            f"docker.io/library/python@sha256:{'A' * 64}",
            f"docker.io/library/python@sha256:{'a' * 63}",
            "docker.io/library/python@sha512:" + DIGEST,
        ):
            with self.subTest(reference=reference):
                with self.assertRaises(gate.BaseImageBlocked):
                    gate.verify_reference(reference)

    def test_docker_pull_metadata_cannot_pick_ambiguous_or_foreign_digests(self):
        for metadata in (
            [], None, {}, "python:3.11-slim",
            [f"python:3.11-slim"],
            [f"evil.example/python@sha256:{DIGEST}"],
            [f"python@sha256:{DIGEST}", f"python@sha256:{'b' * 64}"],
            [f"python@sha256:{DIGEST}", 42],
        ):
            with self.subTest(metadata=metadata):
                with self.assertRaises(gate.BaseImageBlocked):
                    gate.select_pulled_reference(metadata)

    def test_select_command_writes_only_normalized_digest(self):
        stdout = io.StringIO()
        with (
            patch.object(gate.sys, "stdin",
                         io.StringIO('["python@sha256:' + DIGEST + '"]')),
            contextlib.redirect_stdout(stdout),
        ):
            self.assertEqual(gate.main(["select"]), 0)
        self.assertEqual(stdout.getvalue().strip(), CANONICAL)

    def test_invalid_json_and_reference_fail_sanitized(self):
        for argv, input_text in (
            (["select"], "private-registry-credential"),
            (["select"], "x" * (gate.MAX_DIGEST_JSON + 1)),
            (["verify", "--reference", "example.invalid/secret"], ""),
        ):
            stderr = io.StringIO()
            with (
                patch.object(gate.sys, "stdin", io.StringIO(input_text)),
                contextlib.redirect_stderr(stderr),
            ):
                self.assertEqual(gate.main(argv), 2)
            self.assertIn("BLOCKED (sanitized)", stderr.getvalue())
            self.assertNotIn("private-registry-credential", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
