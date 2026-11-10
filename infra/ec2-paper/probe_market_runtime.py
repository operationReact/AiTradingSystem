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



def overview_evidence(postgres):
    """Only read sanitized overview provenance; never print provider prices or raw rows."""
    unavailable = ("UNAVAILABLE", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN")
    if postgres is None:
        return unavailable
    sql = """
        SELECT
          CASE WHEN payload->>'source' = 'ANGEL_ONE_SMARTAPI_FULL_BULK'
               THEN 'ANGEL_ONE_BULK' ELSE 'OTHER' END,
          to_char(generated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
          COALESCE(substring(payload->>'exchangeAsOf'
                             FROM '^[0-9]{4}-[0-9]{2}-[0-9]{2}'), 'UNKNOWN'),
          CASE WHEN jsonb_typeof(payload->'indices') = 'array'
                     AND jsonb_array_length(payload->'indices') > 0
               THEN 'NONEMPTY' ELSE 'EMPTY' END,
          CASE WHEN COALESCE(payload->'universe'->>'returned', '0') ~ '^[0-9]{1,5}$'
                     AND COALESCE((payload->'universe'->>'returned')::int, 0) > 0
               THEN 'NONEMPTY' ELSE 'EMPTY' END
        FROM market_overview_snapshot
        WHERE universe_code = 'NIFTY50'
        LIMIT 1
    """
    p = run(["docker", "exec", postgres, "psql", "-U", "aitrading",
             "-d", "aitrading", "-tA", "-F", "|", "-c", sql], 8)
    if p is None or p.returncode != 0:
        return unavailable
    rows = p.stdout.strip().splitlines()
    if not rows:
        return ("EMPTY", "UNKNOWN", "UNKNOWN", "EMPTY", "EMPTY")
    if len(rows) != 1:
        return unavailable
    fields = rows[0].split("|")
    if len(fields) != 5:
        return unavailable
    source, generated, exchange_day, indices, equities = fields
    if source not in ("ANGEL_ONE_BULK", "OTHER"):
        source = "UNAVAILABLE"
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", generated):
        generated = "UNKNOWN"
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", exchange_day):
        exchange_day = "UNKNOWN"
    if indices not in ("EMPTY", "NONEMPTY"):
        indices = "UNKNOWN"
    if equities not in ("EMPTY", "NONEMPTY"):
        equities = "UNKNOWN"
    return source, generated, exchange_day, indices, equities


def equity_coverage(postgres):
    """Counts only; never prints symbols, prices, identities or session secrets."""
    if postgres is None:
        return "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"
    sql = """
    SELECT
      COUNT(DISTINCT i.instrument_id),
      COUNT(DISTINCT CASE WHEN t.instrument_id IS NOT NULL THEN i.instrument_id END),
      COUNT(DISTINCT CASE WHEN d.instrument_id IS NOT NULL THEN i.instrument_id END)
    FROM instrument_master i
    JOIN instrument_catalogue_state s
      ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
    LEFT JOIN market_last_tick t ON t.instrument_id = i.instrument_id
    LEFT JOIN market_detail_snapshot d ON d.instrument_id = i.instrument_id
    WHERE i.active = TRUE AND i.exchange IN ('NSE','BSE')
      AND i.exchange_segment IN ('NSE_CM','BSE_CM')
      AND i.trading_symbol LIKE '%-EQ'
      AND upper(i.trading_symbol) NOT LIKE '%NSETEST%'
    """
    p = run(["docker", "exec", postgres, "psql", "-U", "aitrading",
             "-d", "aitrading", "-tA", "-F", "|", "-c", sql], 10)
    if p is None or p.returncode:
        return "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"
    fields = p.stdout.strip().split("|")
    if len(fields) != 3 or any(not re.fullmatch(r"[0-9]{1,6}", v) for v in fields):
        return "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"
    if int(fields[1]) > int(fields[0]) or int(fields[2]) > int(fields[0]):
        return "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"
    return tuple(fields)

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
    source, generated, exchange_day, indices, equities = overview_evidence(postgres)
    print("MARKET_OVERVIEW_SOURCE: " + source)
    print("MARKET_OVERVIEW_GENERATED_UTC: " + generated)
    print("MARKET_OVERVIEW_EXCHANGE_DATE: " + exchange_day)
    print("MARKET_OVERVIEW_INDEX_QUOTES: " + indices)
    print("MARKET_OVERVIEW_EQUITY_QUOTES: " + equities)
    catalogue, ticks, details = equity_coverage(postgres)
    print("MARKET_EQUITY_CATALOGUE_COUNT: " + catalogue)
    print("MARKET_EQUITY_SAVED_TICK_COUNT: " + ticks)
    print("MARKET_EQUITY_SAVED_DETAIL_COUNT: " + details)


if __name__ == "__main__":
    main()
