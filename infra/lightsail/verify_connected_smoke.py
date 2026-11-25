#!/usr/bin/env python3
"""Verify real disposable Java/Postgres/Flutter Compose smoke, fail closed.

Read-only checks from runner-local Docker inspect outputs and curl responses.
No AWS access, external addresses, broker credentials, model promotion or
persistent data authorization.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

EXPECTED = {
    "status": "UP",
    "service": "ai-trading-system",
    "mode": "PAPER",
}


def verify_health(data: object) -> None:
    if not isinstance(data, dict) or any(data.get(k) != v for k, v in EXPECTED.items()):
        raise ValueError("PAPER backend health contract mismatch")
    if not isinstance(data.get("timestamp"), str) or not data["timestamp"]:
        raise ValueError("missing real backend health timestamp")


def verify_network(containers: dict) -> None:
    if set(containers) != {"postgres", "backend", "web"}:
        raise ValueError("unexpected Compose service inventory")
    expected = {
        "postgres": {},
        "backend": {"8080/tcp": ("127.0.0.1", "8080")},
        "web": {"8080/tcp": ("127.0.0.1", "8088")},
    }
    for service, container in containers.items():
        if not isinstance(container, dict) or container.get("State", {}).get("Running") is not True:
            raise ValueError("container not running")
        ports = container.get("NetworkSettings", {}).get("Ports", {})
        if not isinstance(ports, dict):
            raise ValueError("container port inventory missing")
        published = []
        for port, bindings in ports.items():
            if bindings is None:
                continue
            if not isinstance(bindings, list):
                raise ValueError("invalid Docker binding inventory")
            for binding in bindings:
                if not isinstance(binding, dict):
                    raise ValueError("invalid Docker published binding")
                published.append((port, binding))
        allowed = expected[service]
        if len(published) != len(allowed):
            raise ValueError("unreviewed published container port")
        for port, binding in published:
            if port not in allowed or (
                binding.get("HostIp"), binding.get("HostPort")
            ) != allowed[port]:
                raise ValueError("application port not loopback-bound")


def verify_result(direct: dict, proxy: dict, web_health: str, migration_count: str,
                  containers: dict) -> None:
    verify_health(direct)
    verify_health(proxy)
    if web_health.strip() != "ok":
        raise ValueError("Flutter web readiness missing")
    if not migration_count.strip().isdigit() or int(migration_count.strip()) < 1:
        raise ValueError("Flyway migration not verified on Postgres")
    verify_network(containers)


def main(argv: list[str]) -> int:
    if len(argv) != 6:
        print("PAPER connected smoke BLOCKED (sanitized)", file=sys.stderr)
        return 2
    try:
        direct, proxy, web, migrations, services = (Path(x) for x in argv[1:])
        verify_result(
            json.loads(direct.read_text()),
            json.loads(proxy.read_text()),
            web.read_text(), migrations.read_text(),
            json.loads(services.read_text()),
        )
    except (OSError, ValueError, TypeError, KeyError, AttributeError, json.JSONDecodeError):
        print("PAPER connected smoke BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("Disposable Postgres/Flyway/Java/Flutter proxy and loopback ports VERIFIED; AWS not used")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
