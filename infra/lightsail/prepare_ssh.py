#!/usr/bin/env python3
"""V4 PAPER: validate tagged Lightsail VM and materialize ephemeral SSH certificate.

This tool consumes AWS API JSON only, not untrusted network responses. It does
not print private key material, certs, other instance metadata or host keys.
Its output remains runner-local (0700) and is erased in the workflow trap.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import sys
from pathlib import Path

INSTANCE = "aitradingsystem-paper-dev"
REGION = "us-east-1"
BUNDLE = "medium_3_0"
TAGS = {"Application": "ai-trading-system", "Environment": "paper"}
KEY_ALGORITHMS = {"ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256",
                  "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"}
B64 = re.compile(r"[A-Za-z0-9+/=]{32,8192}\Z")


def check_instance(data: dict) -> str:
    i = data.get("instance", {})
    tags = {t.get("key"): t.get("value") for t in i.get("tags", [])
            if isinstance(t, dict)}
    ip = i.get("publicIpAddress", "")
    if not (isinstance(i, dict)
            and i.get("name") == INSTANCE
            and i.get("bundleId") == BUNDLE
            and i.get("blueprintId") == "ubuntu_24_04"
            and i.get("location", {}).get("regionName") == REGION
            and i.get("hardware", {}).get("ramSizeInGb") == 4
            and i.get("hardware", {}).get("cpuCount") == 2
            and all(tags.get(k) == v for k, v in TAGS.items())
            and i.get("state", {}).get("name") == "running"):
        raise ValueError("unexpected paper instance identity or state")
    parsed = ipaddress.ip_address(ip)
    if parsed.version != 4 or not parsed.is_global:
        raise ValueError("public IPv4 required")
    return ip


def select_ssh(data: dict, expected_ip: str) -> dict[str, str]:
    d = data.get("accessDetails", {})
    private = d.get("privateKey", "")
    certificate = d.get("certKey", "")
    is_key = isinstance(private, str) and 100 <= len(private) <= 16384 and (
        (
            private.startswith("-----BEGIN RSA PRIVATE KEY-----")
            and private.rstrip().endswith("-----END RSA PRIVATE KEY-----")
        )
        or (
            private.startswith("-----BEGIN OPENSSH PRIVATE KEY-----")
            and private.rstrip().endswith("-----END OPENSSH PRIVATE KEY-----")
        )
    )
    if not (d.get("instanceName") == INSTANCE
            and d.get("protocol") == "ssh"
            and d.get("username") == "ubuntu"
            and d.get("ipAddress") == expected_ip
            and is_key):
        raise ValueError("invalid temporary SSH identity")
    if not (isinstance(certificate, str)
            and len(certificate) < 8192
            and "-cert-v01@openssh.com " in certificate):
        raise ValueError("missing temporary SSH user certificate")
    hosts = d.get("hostKeys", [])
    if not isinstance(hosts, list) or not hosts or len(hosts) > 8:
        raise ValueError("AWS SSH host keys unavailable")
    known: list[str] = []
    for host in hosts:
        if not isinstance(host, dict):
            raise ValueError("unexpected SSH host key")
        alg, key = host.get("algorithm"), host.get("publicKey")
        if alg not in KEY_ALGORITHMS or not isinstance(key, str):
            raise ValueError("unknown SSH host key algorithm")
        if key.startswith(alg + " "):
            key = key[len(alg) + 1:].split(" ", 1)[0]
        if not B64.fullmatch(key):
            raise ValueError("invalid SSH host key bytes")
        known.append(f"{expected_ip} {alg} {key}\n")
    return {"private": private.rstrip() + "\n",
            "certificate": certificate.rstrip() + "\n",
            "known_hosts": "".join(known)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument("--access-details", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        instance = json.loads(args.instance.read_text())
        details = json.loads(args.access_details.read_text())
        ip = check_instance(instance)
        selected = select_ssh(details, ip)
        if not args.output_dir.is_dir() or args.output_dir.is_symlink():
            raise ValueError("private directory missing")
        os.chmod(args.output_dir, 0o700)
        for filename, secret in (
            ("id", selected["private"]),
            ("id-cert.pub", selected["certificate"]),
            ("known_hosts", selected["known_hosts"]),
        ):
            destination = args.output_dir / filename
            with destination.open("x", encoding="utf-8") as handle:
                handle.write(secret)
            destination.chmod(0o600)
        print("PAPER Lightsail SSH identity + host keys verified (sanitized)")
        return 0
    except (ValueError, TypeError, KeyError, OSError, AttributeError,
            json.JSONDecodeError):
        print("PAPER Lightsail temporary SSH access BLOCKED (sanitized)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
