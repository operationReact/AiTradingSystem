#!/usr/bin/env python3
"""Owner-private, bounded NSE F&O EOD presence/volume triage for three gaps.

Read-only Upstox metadata (4 GET max) + up to 3 official NSE UDiFF archives
in memory. No candle/contract key/strike/trade date/price/token export,
downloads of market data to disk, order actions, or source mutation.
An inaccessible NSE report is BLOCKED, never proof that no trade occurred.
"""
from collections import Counter
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
import csv
import io
import json
import re
import sqlite3
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

MARKER = "MINTTICK_FNO_NSE_GAP_TRIAGE_V1:"
MAX_METADATA_GETS = 4
MAX_ARCHIVES = 3
MAX_ZIP_BYTES = 8 * 1024 * 1024
MAX_CSV_BYTES = 45 * 1024 * 1024
MAX_ROWS = 180000
NSE_HOST = "nsearchives.nseindia.com"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def nse_archive_url(day):
    if day.year != 2026 or day.month not in (5, 10) or day.weekday() >= 5:
        raise ValueError("NSE_ARCHIVE_OUT_OF_SCOPE")
    return ("https://" + NSE_HOST +
            "/content/fo/BhavCopy_NSE_FO_0_0_0_" +
            day.strftime("%Y%m%d") + "_F_0000.csv.zip")

def archive_fetch(day, *, opener=None):
    """No redirects or login bypass, no persistent archive writes."""
    url = nse_archive_url(day)
    opener = opener or urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(url, method="GET", headers={
        "Accept": "application/zip",
        "User-Agent": "MintTick-Owner-ReadOnly-EOD-Diagnostics/1.0",
    })
    try:
        with opener.open(request, timeout=25) as response:
            if response.status != 200 or response.url != url:
                return "SOURCE_REJECTED", None
            length = response.headers.get("Content-Length")
            if length and (not length.isdecimal()
                           or int(length) > MAX_ZIP_BYTES):
                return "INVALID_SOURCE_RESPONSE", None
            blob = response.read(MAX_ZIP_BYTES + 1)
    except urllib.error.HTTPError:
        return "SOURCE_REJECTED", None
    except (urllib.error.URLError, TimeoutError, OSError):
        return "TRANSPORT_UNAVAILABLE", None
    if len(blob) > MAX_ZIP_BYTES or not blob.startswith(b"PK\\x03\\x04"):
        return "INVALID_SOURCE_RESPONSE", None
    return "FETCHED", blob

def positive_number(value):
    try:
        number = Decimal(str(value).strip())
    except (InvalidOperation, ValueError, TypeError):
        raise ValueError("INVALID_EXCHANGE_VOLUME") from None
    if not number.is_finite() or number < 0 or number != number.to_integral_value():
        raise ValueError("INVALID_EXCHANGE_VOLUME")
    return number

def eod_presence(blob, day, identities):
    """Look only for exact known NIFTY contract identity and trade-date rows."""
    if len(identities) > 3 or not blob.startswith(b"PK\\x03\\x04"):
        raise ValueError("BAD_EXCHANGE_INPUT")
    expected_name = ("BhavCopy_NSE_FO_0_0_0_" +
                     day.strftime("%Y%m%d") + "_F_0000.csv")
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        members = archive.infolist()
        if (len(members) != 1 or members[0].filename != expected_name
                or members[0].is_dir()
                or members[0].file_size > MAX_CSV_BYTES):
            raise ValueError("UNEXPECTED_EXCHANGE_ARCHIVE")
        body = archive.read(members[0])
        if len(body) > MAX_CSV_BYTES:
            raise ValueError("EXCHANGE_ARCHIVE_TOO_LARGE")
    reader = csv.DictReader(io.StringIO(body.decode("utf-8-sig"), newline=""))
    required = {"BizDt", "TckrSymb", "FinInstrmTp", "XpryDt",
                "StrkPric", "OptnTp", "TtlTradgVol"}
    if reader.fieldnames is None or not required.issubset(reader.fieldnames):
        raise ValueError("EXCHANGE_SCHEMA_UNEXPECTED")
    observations = {}
    for idx, row in enumerate(reader):
        if idx >= MAX_ROWS or row["BizDt"].strip() != day.isoformat():
            raise ValueError("EXCHANGE_RECORD_BOUND_OR_DATE")
        if row["TckrSymb"].strip() != "NIFTY":
            continue
        kind = row["FinInstrmTp"].strip()
        if kind not in ("IDF", "IDO"):
            continue
        try:
            expiry = date.fromisoformat(row["XpryDt"].strip())
        except (ValueError, AttributeError):
            raise ValueError("EXCHANGE_EXPIRY_INVALID") from None
        if kind == "IDF":
            identity = (expiry, "FUT", Decimal(0))
        else:
            side = row["OptnTp"].strip()
            if side not in ("CE", "PE"):
                continue
            try:
                strike = Decimal(row["StrkPric"].strip())
            except (InvalidOperation, ValueError, AttributeError):
                raise ValueError("EXCHANGE_STRIKE_INVALID") from None
            if not strike.is_finite() or strike <= 0:
                raise ValueError("EXCHANGE_STRIKE_INVALID")
            identity = (expiry, side, strike)
        if identity not in identities:
            continue
        if identity in observations:
            raise ValueError("AMBIGUOUS_EXCHANGE_IDENTITY")
        observations[identity] = positive_number(row["TtlTradgVol"])
    return {identity: (
        "EXCHANGE_TRADED" if observations[identity] > 0
        else "EXCHANGE_ZERO_VOLUME"
    ) if identity in observations else "EXCHANGE_ROW_ABSENT"
            for identity in identities}

def map_gap_identities(gaps, catalogues):
    """Match broker tokens against authenticated contract metadata, never guess."""
    from fno_private_bulk_worker import _key
    result = {}
    for month, key, first, last, day in gaps:
        expiry = datetime.strptime(key.rsplit("|", 1)[-1], "%d-%m-%Y").date()
        if expiry.strftime("%Y-%m") != month or expiry not in catalogues:
            raise ValueError("GAP_EXPIRY_INVALID")
        futures, options = catalogues[expiry]
        matches = []
        if not isinstance(futures, list) or not isinstance(options, list):
            raise ValueError("UNEXPECTED_CONTRACT_METADATA")
        for item in futures:
            if _key(item, expiry, "FUT") == key:
                matches.append((expiry, "FUT", Decimal(0)))
        for item in options:
            for kind in ("CE", "PE"):
                if _key(item, expiry, kind) != key:
                    continue
                try:
                    strike = Decimal(str(item["strike_price"]))
                except (KeyError, InvalidOperation, ValueError, TypeError):
                    raise ValueError("UNEXPECTED_STRIKE") from None
                if not strike.is_finite() or strike <= 0:
                    raise ValueError("UNEXPECTED_STRIKE")
                matches.append((expiry, kind, strike))
        if len(matches) != 1:
            raise ValueError("CONTRACT_CROSSWALK_NOT_UNIQUE")
        result[(month, key, day)] = matches[0]
    if len(result) != 3 or len(set(result.values())) != 3:
        raise ValueError("GAP_CROSSWALK_INCOMPLETE")
    return result

def triage(gaps, crosswalk, fetch):
    days = sorted({gap[4] for gap in gaps})
    if len(gaps) != 3 or len(days) > MAX_ARCHIVES:
        raise ValueError("GAP_SCOPE_CHANGED")
    count = Counter()
    for day in days:
        count["archivesAttempted"] += 1
        status, blob = fetch(day)
        related = [g for g in gaps if g[4] == day]
        if status != "FETCHED":
            if status not in ("SOURCE_REJECTED", "TRANSPORT_UNAVAILABLE",
                              "INVALID_SOURCE_RESPONSE"):
                raise ValueError("ARCHIVE_STATUS_INVALID")
            count["archiveUnavailableGaps"] += len(related)
            continue
        try:
            parsed = eod_presence(blob, day, {crosswalk[(g[0], g[1], g[4])]
                                             for g in related})
        except (ValueError, OSError, zipfile.BadZipFile, UnicodeError,
                TypeError, KeyError):
            count["archiveUnavailableGaps"] += len(related)
            continue
        count["archivesFetched"] += 1
        for month, key, first, last, slot in related:
            state = parsed[crosswalk[(month, key, slot)]]
            count[state] += 1
    if (count["EXCHANGE_TRADED"] + count["EXCHANGE_ZERO_VOLUME"] +
        count["EXCHANGE_ROW_ABSENT"] + count["archiveUnavailableGaps"] != 3):
        raise ValueError("GAP_RESULT_MISMATCH")
    return count

def main():
    sys.path.insert(0, "/opt/aitradingsystem-paper/infra/ec2-paper")
    from fno_private_bulk_worker import Discovery, UNDERLYING, grant, ssm_token, today
    from fno_private_partial_gaps import ROOT, SOURCE, read_only, identify_partial_gaps
    approval = grant()
    approval.require("day", today())
    journal = read_only(ROOT / "checkpoints.sqlite")
    cache = read_only(ROOT / "private-research-cache.sqlite")
    try:
        jobs = list(journal.execute(
            "SELECT contract_key,first_day,last_day,state,interval FROM fno_backfill_jobs"))
        candles = list(cache.execute(
            "SELECT contract_key,candle_ts FROM fno_historical_candles "
            "WHERE source=? AND interval='day'", (SOURCE,)))
        if len(jobs) != 276 or len(candles) > 10000:
            raise ValueError("PRIVATE_SOURCE_COHORT_CHANGED")
        gaps = identify_partial_gaps(jobs, candles)
        expiries = sorted({datetime.strptime(g[1].rsplit("|", 1)[-1],
                                            "%d-%m-%Y").date() for g in gaps})
        if len(expiries) != 2 or {x.strftime("%Y-%m") for x in expiries} != {"2026-05","2026-10"}:
            raise ValueError("PRIVATE_EXPIRIES_CHANGED")
        provider = Discovery(ssm_token())
        qs = urllib.parse.quote(UNDERLYING, safe="")
        catalogues = {}
        for expiry in expiries:
            suffix = "?instrument_key=" + qs + "&expiry_date=" + expiry.isoformat()
            catalogues[expiry] = (
                provider.get("/future/contract" + suffix),
                provider.get("/option/contract" + suffix))
        if provider.calls != MAX_METADATA_GETS:
            raise ValueError("SOURCE_METADATA_REQUEST_MISMATCH")
        crosswalk = map_gap_identities(gaps, catalogues)
        counts = triage(gaps, crosswalk, archive_fetch)
        report = {
            "scope": "THREE_PRIVATE_UPSTOX_CANDLE_GAPS",
            "gaps": 3, "may": 1, "october": 2,
            "metadataGets": provider.calls,
            "archivesAttempted": counts["archivesAttempted"],
            "archivesFetched": counts["archivesFetched"],
            "exchangeTraded": counts["EXCHANGE_TRADED"],
            "exchangeZeroVolume": counts["EXCHANGE_ZERO_VOLUME"],
            "exchangeRowAbsent": counts["EXCHANGE_ROW_ABSENT"],
            "archiveUnavailable": counts["archiveUnavailableGaps"],
            "marketPricesExported": False,
            "privateCacheModified": False,
            "backtestingApproved": False,
            "exchangeSessionCalendarVerified": False,
            "retainedDataRightsVerified": False,
        }
        blob = json.dumps(report, sort_keys=True, separators=(",", ":"))
        if len(blob) > 600:
            raise ValueError("TRIAGE_OUTPUT_LIMIT")
        print(MARKER + blob, flush=True)
    finally:
        journal.close()
        cache.close()

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, sqlite3.Error, RuntimeError, TypeError,
            KeyError, urllib.error.URLError, zipfile.BadZipFile):
        print("FNO_NSE_GAP_TRIAGE:BLOCKED_OR_INVALID_SOURCE", flush=True)
        raise SystemExit(2)
