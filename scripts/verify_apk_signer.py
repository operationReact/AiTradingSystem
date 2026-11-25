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

# Android Build Tools 37 prints "V2 Signer:" / "V3.0 Signer:", while older
# apksigner builds print "Signer #1".  Never match Source Stamp entries.
_CERT_LABEL = re.compile(
    r"^\s*(?:(?:V\d+(?:\.\d+)?\s+Signer(?P<scheme_number>\s*#\s*\d+)?)"
    r"|(?:Signer\s*#\s*(?P<legacy_number>\d+)))"
    r"\s*:?\s*certificate\s+sha[\s-]?256\s+(?:digest|fingerprint)\s*:\s*",
    re.IGNORECASE,
)
_CERT_SHA_LABEL = re.compile(r"certificate\s+sha[\s-]?256\s+(?:digest|fingerprint)\s*:", re.IGNORECASE)
_NUMBER_OF_SIGNERS = re.compile(r"^\s*Number of signers\s*:\s*(\d+)\s*$", re.IGNORECASE)
_DIGEST = re.compile(r"((?:[0-9a-f]{2}:){31}[0-9a-f]{2}|[0-9a-f]{64})(?![0-9a-f])", re.IGNORECASE)


def normalize_digest(value: str) -> str:
    normalized = value.strip().replace(":", "").upper()
    if re.fullmatch(r"[0-9A-F]{64}", normalized) is None:
        raise ValueError("Invalid pinned Android signing certificate fingerprint")
    return normalized


def parse_apksigner_certificate(report: str) -> str:
    """Allow one verified APK signing identity across V2/V3/V3.1 schemes."""
    fingerprints: list[str] = []
    signer_numbers: set[int] = set()
    declared_counts: list[int] = []
    for line in report.splitlines():
        count = _NUMBER_OF_SIGNERS.fullmatch(line.strip())
        if count is not None:
            declared_counts.append(int(count.group(1)))
            continue
        # The source stamp is a distinct signer and never attests app identity.
        if "source stamp" in line.lower():
            continue
        if not _CERT_SHA_LABEL.search(line):
            continue
        match = _CERT_LABEL.search(line)
        if match is None:
            # An unrecognized APK signer certificate is a hard error, not an
            # invitation to select one of several certificates silently.
            raise ValueError("Unrecognized APK signing certificate label")
        if match.group("legacy_number") is not None:
            signer_numbers.add(int(match.group("legacy_number")))
        elif match.group("scheme_number") is not None:
            signer_numbers.add(int(match.group("scheme_number").replace("#", "").strip()))
        digest_match = _DIGEST.search(line, match.end())
        if digest_match is None:
            raise ValueError("Signer certificate SHA-256 fingerprint not parseable")
        fingerprints.append(normalize_digest(digest_match.group(1)))
    if ((signer_numbers and signer_numbers != {1})
            or (declared_counts and set(declared_counts) != {1})
            or not fingerprints or len(set(fingerprints)) != 1):
        raise ValueError(
            "Missing, conflicting or multiple APK signing certificates"
            f" (parsed_signer_count={len(signer_numbers)},"
            f" certificate_lines={len(fingerprints)},"
            f" unique_certificates={len(set(fingerprints))},"
            f" declared_signer_counts={len(declared_counts)})"
        )
    return fingerprints[0]


def verify_apk(apksigner: Path, apk: Path, expected: str, output: Path) -> str:
    expected = normalize_digest(expected)
    if not apksigner.is_file() or not apk.is_file() or apk.stat().st_size < 1000:
        raise ValueError("APK or Android SDK verifier is missing")
    try:
        result = subprocess.run(
            [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
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
