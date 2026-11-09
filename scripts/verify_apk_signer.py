#!/usr/bin/env python3
"""Strictly verify an Android APK's public signing certificate via apksigner.

The permanent signing key is never read here. Only the public, pinned SHA-256
fingerprint is accepted; unknown/multiple signers and malformed output fail closed.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

_LABEL = re.compile(
    # Only APK signers; "Source Stamp Signer" certificates are independent.
    r"^\s*signer\s*#\s*(\d+)\s+certificate\s+sha[\s-]?256\s+"
    r"(?:digest|fingerprint)\s*:\s*",
    re.IGNORECASE,
)
_DIGEST = re.compile(r"((?:[0-9a-f]{2}:){31}[0-9a-f]{2}|[0-9a-f]{64})(?![0-9a-f])", re.IGNORECASE)


def normalize_digest(value: str) -> str:
    normalized = value.strip().replace(":", "").upper()
    if re.fullmatch(r"[0-9A-F]{64}", normalized) is None:
        raise ValueError("Invalid pinned Android signing certificate fingerprint")
    return normalized


def parse_apksigner_certificate(report: str) -> str:
    """Parse only *Signer #1 certificate* SHA-256, not contents/other hashes."""
    found: list[str] = []
    signers: set[int] = set()
    for line in report.splitlines():
        match = _LABEL.search(line)
        if match is None:
            continue
        signer_number = int(match.group(1))
        signers.add(signer_number)
        digest_match = _DIGEST.search(line, match.end())
        if digest_match is None:
            raise ValueError("Signer certificate SHA-256 fingerprint not parseable")
        found.append(normalize_digest(digest_match.group(1)))
    if signers != {1} or not found or len(set(found)) != 1:
        # Only categorical public metadata; never reveal raw verifier output.
        raise ValueError(
            "Missing, conflicting or multiple APK signing certificates"
            f" (parsed_signer_count={len(signers)},"
            f" certificate_lines={len(found)},"
            f" unique_certificates={len(set(found))})"
        )
    return found[0]


def verify_apk(apksigner: Path, apk: Path, expected: str, output: Path) -> str:
    expected = normalize_digest(expected)
    if not apksigner.is_file() or not apk.is_file() or apk.stat().st_size < 1000:
        raise ValueError("APK or Android SDK verifier is missing")
    try:
        result = subprocess.run(
            [str(apksigner), "verify", "--print-certs", str(apk)],
            capture_output=True, text=True, timeout=120, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValueError("APK signature verifier could not complete") from exc
    if result.returncode != 0:
        raise ValueError("APK signature verification failed")
    certificate = parse_apksigner_certificate(result.stdout + "\n" + result.stderr)
    if certificate != expected:
        raise ValueError("APK signing certificate differs from pinned MintTick identity")
    output.write_text(certificate + "\n", encoding="ascii")
    return certificate


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print("usage: verify_apk_signer.py APKSIGNER APK PINNED_PUBLIC_SHA256 OUTPUT", file=sys.stderr)
        return 2
    try:
        certificate = verify_apk(Path(argv[1]), Path(argv[2]), argv[3], Path(argv[4]))
    except ValueError as exc:
        # Never print raw verifier output or certificate private material.
        print(f"::error::{exc}", file=sys.stderr)
        return 2
    print(f"PASS: verified release APK signed with pinned public identity {certificate}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
