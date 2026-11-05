"""Ensure Amazon Linux 2023 default curl-minimal can coexist with Docker setup.

Grounded by read-only running EC2 diagnosis: GitHub Actions run 42122300461.
"""
from __future__ import annotations
from pathlib import Path
import re
import subprocess
import unittest

ROOT=Path(__file__).resolve().parents[2]
BOOTSTRAP=ROOT/"infra/ec2-paper/bootstrap.sh"
REPAIR=ROOT/"infra/ec2-paper/bootstrap_repair_v2.sh"


class Al2023DockerBootstrapContract(unittest.TestCase):
    def test_no_full_curl_dependency_on_al2023_curl_minimal(self):
        for path in (BOOTSTRAP,REPAIR):
            source=path.read_text()
            with self.subTest(path=path):
                self.assertIn("curl-minimal",source)
                self.assertRegex(
                    source,
                    r"dnf install -y --setopt=install_weak_deps=False docker openssl ca-certificates",
                )
                self.assertNotRegex(
                    source,
                    r"dnf install[^\n]*\bdocker\s+curl\b",
                )
                self.assertIn("systemctl enable --now docker",source)
                self.assertIn("docker compose version",source)
                self.assertNotIn("aws ec2 start-instances",source)
                self.assertNotIn("TRADING_MODE=LIVE",source)
                check=subprocess.run(["bash","-n",str(path)],capture_output=True,text=True)
                self.assertEqual(check.returncode,0,check.stderr)

    def test_arm64_compose_official_release_digest_pinned_and_verified(self):
        # Official docker/compose v5.6.0 GitHub release asset metadata digest.
        expected="733ec76717ceb59052a9609b9dadfb523b2df8eab57a54212872d10a58078ea2"
        for path in (BOOTSTRAP, REPAIR):
            source=path.read_text()
            with self.subTest(path=path):
                self.assertIn('compose_sha256="'+expected+'"',source)
                self.assertIn('docker-compose-linux-aarch64',source)
                self.assertIn('sha256sum -c -',source)
                self.assertNotIn('checksums.txt',source.replace(
                    'checksums.txt formatting',''))
                self.assertNotIn('sha256sum -c sha256.txt',source)
                result=subprocess.run(["bash","-n",str(path)],
                                      capture_output=True,text=True)
                self.assertEqual(result.returncode,0,result.stderr)

    def test_host_bootstrap_never_resets_existing_credentials(self):
        for path in (BOOTSTRAP,REPAIR):
            source=path.read_text()
            with self.subTest(path=path):
                self.assertIn("runtime.env",source)
                self.assertIn("openssl rand -hex 32",source)
                self.assertIn("chmod 0600",source)
                self.assertNotIn("set -x",source)
                self.assertNotIn("cat /opt/aitradingsystem-paper/runtime.env",source)


if __name__=="__main__":
    unittest.main()
