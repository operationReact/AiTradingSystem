#!/usr/bin/env python3
"""Owner-only personal Angel One ONE_DAY research archive, private EC2 EBS.

Injected only into the existing approved EC2 instance alongside the previously
reviewed SSM/login helper. No customer API, commercial Golden Source state,
broker order endpoint, live-trading permission, external object store, or
GitHub candle output. Never put JWT/keys in a file.
"""
import datetime as dt
import decimal
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

ROOT = Path("/var/lib/minttick/owner-personal-angelone-history")
WINDOW_START = dt.date(2006, 11, 10)
WINDOW_END = dt.date(2026, 11, 10)
WINDOW_DAYS = 2000
MAX_RESPONSE_BYTES = 262144
MAX_ROWS = 2000
MAX_STOCKS_PER_RUN = 5
MIN_REQUEST_SPACING = 10.0
MIN_FREE_BYTES = 256 * 1024 * 1024
PILOT = ("RELIANCE-EQ", "TCS-EQ", "INFY-EQ", "HDFCBANK-EQ", "ICICIBANK-EQ")
URL = API_ROOT + "/rest/secure/angelbroking/historical/v1/getCandleData"
SCHEMA = """CREATE TABLE IF NOT EXISTS page (
    exchange TEXT NOT NULL,
    symbol TEXT NOT NULL,
    token TEXT NOT NULL,
    from_date TEXT NOT NULL,
    through_date TEXT NOT NULL,
    source_sha256 TEXT NOT NULL,
    archive_relative_path TEXT NOT NULL,
    row_count INTEGER NOT NULL,
    earliest TEXT,
    latest TEXT,
    inspected_at TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('NONEMPTY','EMPTY')),
    PRIMARY KEY(exchange,token,from_date,through_date)
)"""


class PersonalHistoryBlocked(Exception):
    pass


def windows(start=WINDOW_START, end=WINDOW_END):
    if end < start:
        raise ValueError("negative date range")
    while start <= end:
        through = min(start + dt.timedelta(days=WINDOW_DAYS - 1), end)
        yield start, through
        start = through + dt.timedelta(days=1)


def ensure_private_store(root=ROOT):
    if os.geteuid() != 0 or not sys.platform.startswith("linux"):
        raise PersonalHistoryBlocked("ROOT_OR_PLATFORM")
    # All subpaths are dedicated to private personal research; reject
    # symlinks/foreign owners to avoid leaking broker data on the host.
    parent = root.parent
    if parent.is_symlink() or root.is_symlink():
        raise PersonalHistoryBlocked("UNTRUSTED_STORE_PATH")
    parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if parent.stat().st_uid != 0:
        raise PersonalHistoryBlocked("UNTRUSTED_STORE_OWNER")
    os.chmod(parent, 0o700)
    root.mkdir(mode=0o700, exist_ok=True)
    if root.stat().st_uid != 0:
        raise PersonalHistoryBlocked("UNTRUSTED_STORE_OWNER")
    os.chmod(root, 0o700)
    pages = root / "pages"
    if pages.is_symlink():
        raise PersonalHistoryBlocked("UNTRUSTED_STORE_PATH")
    pages.mkdir(mode=0o700, exist_ok=True)
    os.chmod(pages, 0o700)
    if shutil.disk_usage(root).free < MIN_FREE_BYTES:
        raise PersonalHistoryBlocked("INSUFFICIENT_FREE_DISK")
    return root


def select_catalogue_symbols():
    # The actively listed NSE_CM-EQ catalogue is NOT a historical security
    # master. It supplies only owner account's current broker tokens. Never
    # mark historical identity or survivorship bias as resolved by this list.
    db = [
        "docker", "compose",
        "--env-file", "/opt/aitradingsystem-paper/.env",
        "-f", "/opt/aitradingsystem-paper/paper-compose.yml",
        "exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
        "-U", "aitrading", "-d", "aitrading", "-Atq", "-F", "|", "-c",
        """SELECT i.trading_symbol, i.symbol_token
             FROM instrument_master i
             JOIN instrument_catalogue_state s
               ON s.active_version=i.catalogue_version
            WHERE s.singleton_id=1
              AND i.active=TRUE
              AND i.exchange_segment='NSE_CM'
              AND i.trading_symbol LIKE '%-EQ'
            ORDER BY i.trading_symbol, i.symbol_token
            LIMIT 6000"""
    ]
    import subprocess
    try:
        r = subprocess.run(db, capture_output=True, text=True, timeout=18, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise PersonalHistoryBlocked("PRIVATE_CATALOGUE_UNAVAILABLE") from None
    if r.returncode != 0:
        raise PersonalHistoryBlocked("PRIVATE_CATALOGUE_UNAVAILABLE")
    entries = {}
    by_token = {}
    lines = r.stdout.splitlines()
    if len(lines) > 5999:
        # Refuse silently truncated catalogues. Do not claim all-market scope.
        raise PersonalHistoryBlocked("CATALOGUE_TOO_LARGE")
    for line in lines:
        parts = line.split("|")
        if len(parts) != 2:
            raise PersonalHistoryBlocked("INVALID_CATALOGUE")
        sym, token = parts
        if (not re.fullmatch(r"[A-Z0-9][A-Z0-9_.&-]{0,36}-EQ", sym)
                or not re.fullmatch(r"[0-9]{1,12}", token)):
            raise PersonalHistoryBlocked("INVALID_CATALOGUE")
        if sym in entries and entries[sym] != token:
            raise PersonalHistoryBlocked("AMBIGUOUS_CATALOGUE_TOKEN")
        if token in by_token and by_token[token] != sym:
            raise PersonalHistoryBlocked("AMBIGUOUS_CATALOGUE_TOKEN")
        entries[sym] = token
        by_token[token] = sym
    if not entries:
        raise PersonalHistoryBlocked("NO_ACTIVE_NSE_EQUITIES")
    return [(sym, entries[sym]) for sym in sorted(entries)]


def select_next_batch(conn, catalogue):
    # Use the manifest as a persisted cursor, not an in-memory index that
    # resets after every GitHub run. Prefer the original five if unfinished;
    # afterwards advance deterministically through all active NSE equities.
    completed = {
        (token, first, through)
        for token, first, through in conn.execute(
            "SELECT token, from_date, through_date FROM page WHERE exchange='NSE'")
    }
    mapping = dict(catalogue)
    ordered = [sym for sym in PILOT if sym in mapping]
    ordered += [sym for sym in sorted(mapping) if sym not in PILOT]
    selected = []
    for sym in ordered:
        token = mapping[sym]
        if any((token, a.isoformat(), b.isoformat()) not in completed
               for a, b in windows()):
            selected.append((sym, token))
            if len(selected) == MAX_STOCKS_PER_RUN:
                break
    return selected


def validate_rows(payload, first, through):
    if not isinstance(payload, dict) or payload.get("status") is not True:
        raise PersonalHistoryBlocked("PROVIDER_REJECTED")
    data = payload.get("data")
    if data is None:
        data = []
    if not isinstance(data, list) or len(data) > MAX_ROWS:
        raise PersonalHistoryBlocked("INVALID_PROVIDER_ROWS")
    previous = None
    seen_first = seen_last = None
    for record in data:
        if not isinstance(record, list) or len(record) != 6:
            raise PersonalHistoryBlocked("INVALID_PROVIDER_ROWS")
        try:
            stamp = dt.datetime.fromisoformat(record[0])
            if stamp.tzinfo is None:
                raise ValueError("unaware timestamp")
            date = stamp.astimezone(ZoneInfo("Asia/Kolkata")).date()
            o, h, l, c, volume = (decimal.Decimal(str(v)) for v in record[1:])
            if (not all(v.is_finite() for v in (o, h, l, c, volume))
                    or min(o, h, l, c) <= 0
                    or h < max(o, c) or l > min(o, c) or l > h
                    or volume < 0 or volume != volume.to_integral_value()
                    or date < first or date > through
                    or previous is not None and date <= previous):
                raise ValueError("invalid session or price")
        except (ValueError, TypeError, OverflowError, decimal.InvalidOperation):
            raise PersonalHistoryBlocked("INVALID_PROVIDER_ROWS") from None
        previous = date
        seen_first = seen_first or date
        seen_last = date
    return data, seen_first, seen_last


def fetch(headers, token, first, through):
    body = json.dumps({
        "exchange": "NSE", "symboltoken": token, "interval": "ONE_DAY",
        "fromdate": first.isoformat() + " 00:00",
        "todate": through.isoformat() + " 15:30",
    }, separators=(",", ":")).encode("ascii")
    req = Request(URL, data=body, headers=headers, method="POST")
    try:
        with urlopen(req, timeout=35) as response:
            if response.status != 200:
                raise PersonalHistoryBlocked("PROVIDER_HTTP_ERROR")
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as error:
        if error.code == 429:
            raise PersonalHistoryBlocked("PROVIDER_RATE_LIMITED") from None
        if error.code in (400, 401, 403, 422):
            raise PersonalHistoryBlocked("PROVIDER_REJECTED") from None
        raise PersonalHistoryBlocked("PROVIDER_HTTP_ERROR") from None
    except (OSError, URLError, TimeoutError):
        raise PersonalHistoryBlocked("PROVIDER_UNREACHABLE") from None
    if len(raw) > MAX_RESPONSE_BYTES:
        raise PersonalHistoryBlocked("PROVIDER_RESPONSE_TOO_LARGE")
    try:
        result = json.loads(raw)
    except (ValueError, TypeError, UnicodeError):
        raise PersonalHistoryBlocked("INVALID_PROVIDER_JSON") from None
    return validate_rows(result, first, through)


def authenticated_headers():
    egress()
    network = verified_client_network_headers()
    params = ssm_params()
    if (not re.fullmatch(r"[A-Za-z0-9._-]{4,20}", params["client_code"])
            or not re.fullmatch(r"[0-9]{4,8}", params["pin"])):
        raise PersonalHistoryBlocked("OWNER_CREDENTIALS_INVALID")
    headers = {
        "Content-Type": "application/json", "Accept": "application/json",
        "X-PrivateKey": params["api_key"], "X-UserType": "USER",
        "X-SourceID": "WEB", **network,
    }
    body = json.dumps({
        "clientcode": params["client_code"], "password": params["pin"],
        "totp": totp(params["totp_secret"]),
    }, separators=(",", ":")).encode("utf-8")
    identity = request_json(
        API_ROOT + "/rest/auth/angelbroking/user/v1/loginByPassword",
        "POST", headers, body)
    jwt = identity.get("jwtToken")
    feed = identity.get("feedToken")
    if not isinstance(jwt, str) or not jwt or not isinstance(feed, str) or not feed:
        raise PersonalHistoryBlocked("OWNER_AUTH_UNAVAILABLE")
    profile = request_json(
        API_ROOT + "/rest/secure/angelbroking/user/v1/getProfile",
        "GET", {**headers, "Authorization": "Bearer " + jwt})
    actual = profile.get("clientcode", profile.get("clientCode"))
    if (not isinstance(actual, str) or not hmac.compare_digest(
            actual.strip().upper(), params["client_code"].strip().upper())):
        raise PersonalHistoryBlocked("OWNER_IDENTITY_MISMATCH")
    return {**headers, "Authorization": "Bearer " + jwt}


def init_db(root):
    conn = sqlite3.connect(str(root / "manifest.sqlite3"), timeout=10)
    os.chmod(root / "manifest.sqlite3", 0o600)
    conn.execute("PRAGMA journal_mode=DELETE")
    conn.execute("PRAGMA synchronous=FULL")
    conn.execute(SCHEMA)
    conn.commit()
    return conn


def existing_page(conn, root, token, first, through):
    row = conn.execute("""SELECT source_sha256, archive_relative_path, row_count
                           FROM page WHERE exchange='NSE' AND token=?
                            AND from_date=? AND through_date=?""",
                       (token, first.isoformat(), through.isoformat())).fetchone()
    if row is None:
        return None
    checksum, rel, count = row
    if not re.fullmatch(r"pages/[a-f0-9]{64}[.]json[.]gz", rel):
        raise PersonalHistoryBlocked("INVALID_EXISTING_ARCHIVE")
    p = root / rel
    if p.is_symlink() or not p.is_file() or p.stat().st_size > 1024 * 1024:
        raise PersonalHistoryBlocked("MISSING_EXISTING_ARCHIVE")
    try:
        original = gzip.decompress(p.read_bytes())
    except (OSError, ValueError, EOFError):
        raise PersonalHistoryBlocked("CORRUPT_EXISTING_ARCHIVE") from None
    if hashlib.sha256(original).hexdigest() != checksum:
        raise PersonalHistoryBlocked("ALTERED_EXISTING_ARCHIVE")
    return count


def persist_page(conn, root, symbol, token, first, through, data, earliest, latest):
    # Only provider candle rows and public instrument identity are persisted;
    # there is NO account code, API key, PIN, TOTP, JWT or request headers.
    obj = {
        "format": "minttick.owner.personal.angelone.daily.v1",
        "scope": "PRIVATE_OWNER_PERSONAL_RESEARCH_ONLY",
        "exchange": "NSE", "symbol": symbol, "token": token,
        "interval": "ONE_DAY",
        "window_start": first.isoformat(),
        "window_end": through.isoformat(),
        "historical_listing_identity_verified": False,
        "source_publication_times_verified": False,
        "session_completeness_verified": False,
        "rows": data,
    }
    payload = (json.dumps(obj, separators=(",", ":"), ensure_ascii=True) + "\n").encode()
    digest = hashlib.sha256(payload).hexdigest()
    rel = "pages/" + digest + ".json.gz"
    target = root / rel
    if target.is_symlink():
        raise PersonalHistoryBlocked("UNTRUSTED_ARCHIVE_TARGET")
    if target.exists():
        if gzip.decompress(target.read_bytes()) != payload:
            raise PersonalHistoryBlocked("ARCHIVE_HASH_COLLISION")
    else:
        temp = target.with_name(target.name + ".pending")
        if temp.is_symlink():
            raise PersonalHistoryBlocked("UNTRUSTED_ARCHIVE_TARGET")
        if temp.exists():
            if not temp.is_file() or temp.stat().st_uid != 0:
                raise PersonalHistoryBlocked("UNTRUSTED_ARCHIVE_PENDING")
            # The exclusive collector lock guarantees a .pending file can
            # only be an interrupted previous write; atomic final path stays
            # authoritative and is never overwritten by this recovery.
            temp.unlink()
        fd = os.open(str(temp), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "wb") as fh:
                fh.write(gzip.compress(payload, compresslevel=6, mtime=0))
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(temp, target)
            os.chmod(target, 0o600)
            directory_fd = os.open(str(root / "pages"), os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if temp.exists():
                temp.unlink()
    conn.execute("""INSERT INTO page (
            exchange,symbol,token,from_date,through_date,source_sha256,
            archive_relative_path,row_count,earliest,latest,inspected_at,state)
        VALUES ('NSE',?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(exchange,token,from_date,through_date) DO NOTHING""",
        (symbol, token, first.isoformat(), through.isoformat(), digest, rel,
         len(data), earliest.isoformat() if earliest else None,
         latest.isoformat() if latest else None,
         dt.datetime.now(dt.timezone.utc).isoformat(),
         "NONEMPTY" if data else "EMPTY"))
    conn.commit()
    return digest


def run():
    root = ensure_private_store()
    lockfile = root / ".exclusive.lock"
    fd = os.open(str(lockfile), os.O_RDWR | os.O_CREAT, 0o600)
    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    try:
        conn = init_db(root)
        try:
            catalogue = select_catalogue_symbols()
            entries = select_next_batch(conn, catalogue)
            print("PERSONAL_UNIVERSE_EQUITIES: " + str(len(catalogue)), flush=True)
            if len(entries) > MAX_STOCKS_PER_RUN:
                raise PersonalHistoryBlocked("RUN_STOCK_LIMIT")
            planned = [(sym, token, start, end)
                       for sym, token in entries for start, end in windows()]
            # Validate previous files BEFORE authenticating or making requests.
            remaining = []
            skipped = 0
            for sym, token, start, end in planned:
                if existing_page(conn, root, token, start, end) is None:
                    remaining.append((sym, token, start, end))
                else:
                    skipped += 1
            print("PERSONAL_STOCKS_SELECTED: " + str(len(entries)), flush=True)
            print("PERSONAL_PAGES_ALREADY_VERIFIED: " + str(skipped), flush=True)
            print("PERSONAL_PAGES_REMAINING: " + str(len(remaining)), flush=True)
            if not remaining:
                print("PERSONAL_ARCHIVE_SCOPE: PRIVATE_EC2_OWNER_ONLY", flush=True)
                print("PERSONAL_GOLDEN_SOURCE_PUBLISHED: FALSE", flush=True)
                return 0
            headers = authenticated_headers()
            completed = 0
            new_bars = 0
            previous_call = None
            for sym, token, start, end in remaining:
                if previous_call is not None:
                    time.sleep(max(0, MIN_REQUEST_SPACING
                                   - (time.monotonic() - previous_call)))
                previous_call = time.monotonic()
                rows, first, last = fetch(headers, token, start, end)
                persist_page(conn, root, sym, token, start, end, rows, first, last)
                completed += 1
                new_bars += len(rows)
                # Symbol/candle count only; NEVER show raw OHLCV in public CI logs.
                print("PERSONAL_PAGE_" + str(completed) + ": " + sym
                      + " " + str(len(rows)) + " VALIDATED_ROWS", flush=True)
            summary = conn.execute(
                "SELECT count(*), COALESCE(sum(row_count),0) FROM page").fetchone()
            print("PERSONAL_PAGES_STORED_TOTAL: " + str(summary[0]), flush=True)
            print("PERSONAL_ROWS_STORED_TOTAL: " + str(summary[1]), flush=True)
            print("PERSONAL_PAGES_DOWNLOADED_THIS_RUN: " + str(completed), flush=True)
            print("PERSONAL_ROWS_DOWNLOADED_THIS_RUN: " + str(new_bars), flush=True)
            print("PERSONAL_ARCHIVE_SCOPE: PRIVATE_EC2_OWNER_ONLY", flush=True)
            print("PERSONAL_GOLDEN_SOURCE_PUBLISHED: FALSE", flush=True)
            return 0
        finally:
            conn.close()
    finally:
        os.close(fd)


if __name__ == "__main__":
    try:
        sys.exit(run())
    except (PersonalHistoryBlocked, ProbeFailure) as error:
        safe = str(error)
        if not re.fullmatch(r"[A-Z0-9_: ]{3,80}", safe):
            safe = "INTERNAL_UNAVAILABLE"
        print("PERSONAL_DOWNLOAD_BLOCKED: " + safe, flush=True)
        sys.exit(2)
    except (OSError, ValueError, RuntimeError, sqlite3.Error, BlockingIOError):
        print("PERSONAL_DOWNLOAD_BLOCKED: INTERNAL_OR_STORAGE_UNAVAILABLE", flush=True)
        sys.exit(2)
