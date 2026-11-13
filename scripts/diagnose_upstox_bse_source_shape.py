#!/usr/bin/env python3
"""Public Upstox BSE BOD schema audit. Logs only aggregates, NEVER identifiers."""
import collections
import gzip
import io
import json
import re
import urllib.error
import urllib.request

URL = "https://assets.upstox.com/market-quote/instruments/exchange/BSE.json.gz"
MAX_COMPRESSED = 32 * 1024 * 1024
MAX_JSON = 80 * 1024 * 1024

def main():
    request = urllib.request.Request(URL, headers={
        "Accept": "application/gzip,application/octet-stream",
        "User-Agent": "MintTick-Market-Reference-Contract/1.0",
    })
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            if response.status != 200:
                print("BSE_SOURCE_HTTP:UNAVAILABLE")
                return 2
            blob = response.read(MAX_COMPRESSED+1)
    except urllib.error.HTTPError as error:
        print("BSE_SOURCE_HTTP:FORBIDDEN" if error.code in (401, 403)
              else "BSE_SOURCE_HTTP:UNAVAILABLE")
        return 2
    except (OSError, ValueError):
        print("BSE_SOURCE_HTTP:UNAVAILABLE")
        return 2
    if len(blob)>MAX_COMPRESSED:
        print("BSE_SOURCE:TOO_LARGE")
        return 2
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(blob)) as source:
            body=source.read(MAX_JSON+1)
        if len(body)>MAX_JSON:
            print("BSE_SOURCE:TOO_LARGE")
            return 2
        records=json.loads(body)
    except (OSError, ValueError, UnicodeError):
        print("BSE_SOURCE:INVALID_GZIP_OR_JSON")
        return 2
    if not isinstance(records,list):
        print("BSE_SOURCE:INVALID_ROOT")
        return 2
    counts=collections.Counter()
    tokens=set()
    for row in records:
        if not isinstance(row,dict):continue
        counts["total"]+=1
        if row.get("segment")!="BSE_EQ" or row.get("exchange")!="BSE":
            continue
        counts["bse_eq"]+=1
        typ=row.get("instrument_type")
        security=row.get("security_type")
        if typ=="A":counts["type_A"]+=1
        if typ=="EQ":counts["type_EQ"]+=1
        if security=="NORMAL":counts["normal"]+=1
        if security is None:counts["missing_security"]+=1
        if typ not in ("A","EQ"):continue
        counts["equity_series"]+=1
        if security != "NORMAL":continue
        counts["normal_equity_series"]+=1
        token=row.get("exchange_token")
        symbol=row.get("trading_symbol")
        isin=row.get("isin")
        key=row.get("instrument_key")
        valid=(isinstance(token,str) and re.fullmatch(r"[0-9]{1,15}",token)
          and isinstance(symbol,str) and re.fullmatch(r"[A-Za-z0-9&._/+-]{1,100}",symbol)
          and isinstance(isin,str) and re.fullmatch(r"[A-Z]{2}[A-Z0-9]{9}[0-9]",isin)
          and key=="BSE_EQ|"+isin)
        if not valid:
            counts["invalid_identity"]+=1
            continue
        counts["valid_identity"]+=1
        if token in tokens:counts["duplicate_token"]+=1
        tokens.add(token)
    for key in ("total","bse_eq","type_A","type_EQ","normal",
                "missing_security","equity_series","normal_equity_series",
                "invalid_identity","valid_identity","duplicate_token"):
        print("BSE_REFERENCE_"+key.upper()+":"+str(counts[key]))
    return 0

if __name__=="__main__":
    raise SystemExit(main())
