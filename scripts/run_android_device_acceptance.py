#!/usr/bin/env python3
"""Run the connected PAPER safety journey on one attached Android device.

This is an evidence harness, not a substitute for physical-device acceptance.
It refuses LIVE-capable backends and never writes the operator key to evidence.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Callable
from urllib.request import Request, urlopen
from urllib.parse import urlparse


class AcceptanceError(RuntimeError):
    pass


@dataclass(frozen=True)
class Device:
    serial: str
    model: str


def parse_adb_devices(output: str) -> list[Device]:
    devices: list[Device] = []
    for line in output.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        model = next((part.removeprefix("model:") for part in parts[2:] if part.startswith("model:")), "unknown")
        devices.append(Device(parts[0], model))
    return devices


def require_single_device(run: Callable[..., subprocess.CompletedProcess[str]], adb: str, requested: str | None) -> Device:
    result = run([adb, "devices", "-l"], check=True, capture_output=True, text=True)
    devices = parse_adb_devices(result.stdout)
    if requested:
        matches = [device for device in devices if device.serial == requested]
        if len(matches) != 1:
            raise AcceptanceError(f"Requested Android device {requested!r} is not connected and authorized.")
        return matches[0]
    if len(devices) != 1:
        raise AcceptanceError(f"Expected exactly one connected, authorized Android device; found {len(devices)}.")
    return devices[0]


def require_loopback_backend(base_url: str) -> int:
    parsed = urlparse(base_url)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}:
        raise AcceptanceError("Device acceptance currently requires a loopback HTTP backend reached through adb reverse.")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment or parsed.username or parsed.password:
        raise AcceptanceError("API_BASE_URL must contain only scheme, loopback host, and optional port.")
    return parsed.port or 80


def read_json(url: str, *, api_key: str | None = None, timeout: int = 10) -> dict:
    headers = {"Accept": "application/json"}
    if api_key:
        headers["X-API-Key"] = api_key
    with urlopen(Request(url, headers=headers), timeout=timeout) as response:
        value = json.load(response)
    if not isinstance(value, dict):
        raise AcceptanceError(f"Expected a JSON object from {url}.")
    return value


def verify_paper_backend(base_url: str, api_key: str) -> dict:
    read_json(f"{base_url.rstrip('/')}/actuator/health")
    health = read_json(
        f"{base_url.rstrip('/')}/api/operations/health",
        api_key=api_key,
    )
    if health.get("executionMode") != "PAPER":
        raise AcceptanceError("Refusing device acceptance because backend executionMode is not PAPER.")
    if health.get("liveAvailable") is not False:
        raise AcceptanceError("Refusing device acceptance because LIVE is reported available.")
    return {
        "executionMode": health.get("executionMode"),
        "liveAvailable": health.get("liveAvailable"),
        "state": health.get("state"),
    }


def command_for(device: Device, base_url: str, api_key: str) -> list[str]:
    return [
        "flutter",
        "test",
        "integration_test/connected_health_app_test.dart",
        "-d",
        device.serial,
        f"--dart-define=API_BASE_URL={base_url}",
        f"--dart-define=CONNECTED_API_KEY={api_key}",
    ]


def redact_command(command: list[str]) -> list[str]:
    return [
        "--dart-define=CONNECTED_API_KEY=<redacted>" if value.startswith("--dart-define=CONNECTED_API_KEY=") else value
        for value in command
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--device")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--evidence-dir", default="build/device-acceptance")
    args = parser.parse_args()

    api_key = os.environ.get("CONNECTED_API_KEY", "").strip()
    if not api_key:
        parser.error("CONNECTED_API_KEY must be provided through the environment; never commit it.")

    root = Path(__file__).resolve().parents[1]
    flutter_dir = root / "apps" / "trading_client"
    evidence_dir = root / args.evidence_dir
    evidence_dir.mkdir(parents=True, exist_ok=True)
    report_path = evidence_dir / "android-paper-device.json"
    log_path = evidence_dir / "flutter-device-test.log"

    report: dict[str, object] = {
        "schemaVersion": 1,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "connected Android PAPER safety prerequisite",
        "paperLifecycleJourney": "PENDING_MANUAL_DEVICE_EVIDENCE",
        "liveBrokerSubmissionEnabled": False,
    }

    try:
        port = require_loopback_backend(args.api_base_url)
        device = require_single_device(subprocess.run, args.adb, args.device)
        backend = verify_paper_backend(args.api_base_url, api_key)
        subprocess.run([args.adb, "-s", device.serial, "reverse", f"tcp:{port}", f"tcp:{port}"], check=True)
        command = command_for(device, args.api_base_url, api_key)
        report.update({
            "device": {"serial": device.serial, "model": device.model},
            "backend": backend,
            "command": redact_command(command),
        })
        with log_path.open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=flutter_dir, stdout=log, stderr=subprocess.STDOUT)
        if completed.returncode != 0:
            raise AcceptanceError(f"Flutter connected device test failed; inspect {log_path}.")
        report["connectedRuntime"] = "VERIFIED"
        report["result"] = "PREREQUISITE_PASSED"
        return_code = 0
    except (AcceptanceError, OSError, subprocess.SubprocessError, ValueError) as exc:
        report["result"] = "FAILED"
        report["error"] = str(exc)
        return_code = 1
    finally:
        report["completedAt"] = datetime.now(timezone.utc).isoformat()
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Evidence: {report_path}")
        print("Operator credential was not written to evidence.")
    return return_code


if __name__ == "__main__":
    sys.exit(main())
