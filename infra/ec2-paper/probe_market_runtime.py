#!/usr/bin/env python3
"""Read-only EC2 MintTick Markets diagnostics. Prints ONLY fixed categorical states.

Does not inspect or emit broker credentials, AWS role credentials, exchange
prices, JWTs, raw PostgreSQL rows, provider responses, or customer identities.
"""
import json
import re
import subprocess


def run(args, timeout=12):
    try:
        return subprocess.run(args, check=False, capture_output=True, text=True,
                              timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None


def container(service):
    p = run(["docker", "ps", "-q", "--filter",
             "label=com.docker.compose.project=aitradingsystem-paper",
             "--filter", "label=com.docker.compose.service=" + service], 5)
    if p is None or p.returncode or not re.fullmatch(r"[0-9a-f]{12,64}", p.stdout.strip()):
        return None
    return p.stdout.strip()


def metadata_token(network_pid=None):
    args = ["curl", "--silent", "--show-error", "--fail", "--max-time", "3",
            "--request", "PUT",
            "--header", "X-aws-ec2-metadata-token-ttl-seconds: 30",
            "http://169.254.169.254/latest/api/token"]
    if network_pid is not None:
        args = ["nsenter", "-t", str(network_pid), "-n", "--"] + args
    p = run(args, 6)
    return (p is not None and p.returncode == 0
            and bool(re.fullmatch(r"[A-Za-z0-9+/=_-]{20,4000}", p.stdout.strip())))


def count_category(postgres, name):
    if postgres is None:
        return "UNAVAILABLE"
    if name not in ("market_last_tick", "market_overview_snapshot", "market_detail_snapshot"):
        return "UNAVAILABLE"
    p = run(["docker", "exec", postgres, "psql", "-U", "aitrading",
             "-d", "aitrading", "-tA", "-c", "SELECT count(*) FROM " + name], 8)
    if p is None or p.returncode:
        return "MISSING_OR_UNAVAILABLE"
    value = p.stdout.strip()
    if not value.isdigit():
        return "MISSING_OR_UNAVAILABLE"
    return "NONEMPTY" if int(value) > 0 else "EMPTY"


def main():
    backend = container("backend")
    postgres = container("postgres")
    print("MARKET_BACKEND: " + ("RUNNING" if backend else "UNAVAILABLE"))
    print("MARKET_POSTGRES: " + ("RUNNING" if postgres else "UNAVAILABLE"))
    print("MARKET_HOST_IMDS: " + ("AVAILABLE" if metadata_token() else "UNAVAILABLE"))
    if backend is None:
        print("MARKET_CONTAINER_IMDS: UNAVAILABLE")
        print("MARKET_APP_STREAM: UNKNOWN")
        print("MARKET_OWNER_WARMER: UNKNOWN")
        print("MARKET_NETWORK_HEADERS: UNKNOWN")
        print("MARKET_PROVIDER_MODE: UNKNOWN")
    else:
        p = run(["docker", "inspect", "-f", "{{.State.Pid}}", backend], 5)
        pid = p.stdout.strip() if p is not None and p.returncode == 0 else ""
        if not pid.isdigit() or int(pid) < 1:
            print("MARKET_CONTAINER_IMDS: UNAVAILABLE")
        else:
            print("MARKET_CONTAINER_IMDS: " +
                  ("AVAILABLE" if metadata_token(int(pid)) else "UNAVAILABLE"))
        e = run(["docker", "inspect", "-f", "{{json .Config.Env}}", backend], 5)
        settings = {}
        try:
            values = json.loads(e.stdout) if e is not None and e.returncode == 0 else []
            for item in values:
                if isinstance(item, str) and "=" in item:
                    k, v = item.split("=", 1)
                    if k in ("MINTTICK_ANGEL_ONE_SSM_STREAM_ENABLED",
                             "OWNER_FEED_ENABLED", "ANGELONE_CLIENT_MAC_ADDRESS",
                             "MARKET_DATA_PROVIDER"):
                        settings[k] = v
        except (ValueError, TypeError):
            pass
        print("MARKET_APP_STREAM: " +
              ("ENABLED" if settings.get("MINTTICK_ANGEL_ONE_SSM_STREAM_ENABLED") == "true" else "DISABLED"))
        print("MARKET_OWNER_WARMER: " +
              ("ENABLED" if settings.get("OWNER_FEED_ENABLED") == "true" else "DISABLED"))
        print("MARKET_NETWORK_HEADERS: " +
              ("CONFIGURED" if bool(re.fullmatch(
                  r"(?:[0-9a-f]{2}:){5}[0-9a-f]{2}",
                  settings.get("ANGELONE_CLIENT_MAC_ADDRESS", ""), re.I)) else "MISSING"))
        provider = settings.get("MARKET_DATA_PROVIDER", "")
        print("MARKET_PROVIDER_MODE: " +
              (provider if provider in ("ANGEL_ONE", "ANGEL_ONE_LAMBDA") else "OTHER"))
    print("MARKET_LAST_TICKS: " + count_category(postgres, "market_last_tick"))
    print("MARKET_OVERVIEW_CACHE: " + count_category(postgres, "market_overview_snapshot"))
    print("MARKET_DETAIL_CACHE: " + count_category(postgres, "market_detail_snapshot"))


if __name__ == "__main__":
    main()
