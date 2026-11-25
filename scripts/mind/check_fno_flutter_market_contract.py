#!/usr/bin/env python3
"""Privacy-safe structural parity audit of the Flutter CustomerFnoMarket decoder.

This checks the real backend JSON against strict Dart model field constraints,
but it does not itself execute Flutter/Dart. Contract-compatibility evidence is
not rendering or installed-APK verification. No raw market/customer data logs.
"""
from __future__ import annotations

import datetime as dt
import json
import math
from pathlib import Path
import re
import sys

MAX_BYTES=2_000_000
KEY=re.compile(r"NSE_FO\|[A-Za-z0-9._-]{1,64}\Z")
STATUS={"MARKET_CLOSED","FRESH_QUOTES","NO_FRESH_QUOTES"}
MARKER="MIND_FNO_FLUTTER_CONTRACT:"
FIELDS_V3=("ltp","previousClose","iv","delta","theta","gamma","vega")
COUNTS_V3=("openInterest","volume","bidQuantity","askQuantity","lastTradedQuantity")
FIELDS_V2=("ltp","bid","ask","iv","delta","theta","gamma","vega")
COUNTS_V2=("openInterest","volume","previousOpenInterest")


class SchemaBlocked(Exception):
    pass


def number(x, *, positive=False, nonnegative=False):
    return type(x) in (int,float) and math.isfinite(x) and (
        (x>0) if positive else (x>=0 if nonnegative else True))


def timestamp(x):
    if x is None:
        return True
    if not isinstance(x,str) or len(x)>64:
        return False
    try:
        t=dt.datetime.fromisoformat(x.replace("Z","+00:00"))
    except ValueError:
        return False
    return t is not None


def blocked(code):
    raise SchemaBlocked(code)


def inspect(raw):
    if not isinstance(raw,bytes) or len(raw)>MAX_BYTES:
        blocked("BOUNDED_BODY_INVALID")
    try:
        d=json.loads(raw)
    except (ValueError,UnicodeDecodeError):
        blocked("JSON_INVALID")
    if not isinstance(d,dict):
        blocked("ROOT_NOT_OBJECT")
    if (not isinstance(d.get("underlyingKey"),str)
        or not timestamp(d.get("expiry"))
        or not isinstance(d.get("expiry"),str)
        or d.get("status") not in STATUS
        or type(d.get("displayRightsVerified")) is not bool
        or d.get("liveOrderEnabled") is True):
        blocked("MARKET_IDENTITY_OR_AUTHORITY_INVALID")
    spot=d.get("underlyingReferenceSpot")
    if spot is not None and not number(spot,positive=True):
        blocked("REFERENCE_SPOT_INVALID")
    if not timestamp(d.get("snapshotAt")):
        blocked("SNAPSHOT_TIME_INVALID")
    strikes=d.get("strikes")
    if not isinstance(strikes,list) or len(strikes)>1024:
        blocked("STRIKES_INVALID")
    keys=set()
    legs=0
    for row in strikes:
        if not isinstance(row,dict) or not number(row.get("strikePrice"),positive=True):
            blocked("STRIKE_INVALID")
        for field in ("call","put"):
            leg=row.get(field)
            if leg is None: continue
            if not isinstance(leg,dict):
                blocked("LEG_NOT_OBJECT")
            key=leg.get("instrumentKey")
            symbol=leg.get("tradingSymbol")
            side=leg.get("side")
            lot=leg.get("lotSize")
            tick=leg.get("tickSize")
            if (not isinstance(key,str) or not KEY.fullmatch(key)
                or not isinstance(symbol,str) or not (1<=len(symbol)<=64)
                or side not in ("CE","PE") or side!=("CE" if field=="call" else "PE")
                or type(lot) is not int or not (1<=lot<=10000)
                or not number(tick,positive=True)
                or type(leg.get("quoteFresh")) is not bool
                or type(leg.get("paperEligible")) is not bool):
                blocked("LEG_IDENTITY_OR_FLAGS_INVALID")
            if key in keys:
                blocked("DUPLICATE_LEG_KEY")
            keys.add(key)
            for name in ("bid","ask"):
                v=leg.get(name)
                if v is not None and not number(v):
                    blocked("LEG_QUOTE_NUMERIC_INVALID")
            for name in FIELDS_V3:
                v=leg.get(name)
                if v is not None and not number(v):
                    blocked("V3_METRIC_INVALID")
                if v is not None and name in ("ltp","previousClose") and not number(v,positive=True):
                    blocked("V3_METRIC_RANGE_INVALID")
                if v is not None and name=="iv" and not number(v,nonnegative=True):
                    blocked("V3_IV_NEGATIVE")
            for name in COUNTS_V3:
                v=leg.get(name)
                if v is not None and (type(v) is not int or v<0):
                    blocked("V3_COUNT_INVALID")
            if not timestamp(leg.get("exchangeAt")) or not timestamp(leg.get("observedAt")):
                blocked("V3_TIMESTAMP_INVALID")
            legs+=1
    indicators=d.get("chainSnapshotIndicators")
    if indicators is None:
        indicators={}
    if not isinstance(indicators,dict) or len(indicators)>512:
        blocked("CHAIN_MAP_SIZE_OR_TYPE_INVALID")
    refs=0
    for key, snap in indicators.items():
        if key not in keys or not isinstance(snap,dict):
            blocked("CHAIN_KEY_OR_TYPE_INVALID")
        for name in FIELDS_V2:
            v=snap.get(name)
            if v is not None and not number(v,
                    positive=name in ("ltp","bid","ask"),
                    nonnegative=name=="iv"):
                blocked("CHAIN_METRIC_INVALID")
        for name in COUNTS_V2:
            v=snap.get(name)
            if v is not None and (type(v) is not int or v<0):
                blocked("CHAIN_COUNT_INVALID")
        if number(snap.get("ltp"),positive=True):
            refs+=1
    return len(strikes),legs,len(indicators),refs


def main(argv=None):
    args=sys.argv[1:] if argv is None else argv
    if len(args)!=1:
        print(MARKER+"status=INVALID_ARGUMENTS")
        return 2
    try:
        path=Path(args[0])
        if not path.is_file() or path.is_symlink() or path.stat().st_size>MAX_BYTES:
            blocked("BOUNDED_FILE_INVALID")
        rows,legs,mapped,refs=inspect(path.read_bytes())
        print(MARKER+"status=FIELD_CONTRACT_COMPATIBLE;strikes="+str(rows)+
              ";legs="+str(legs)+";chainEntries="+str(mapped)+
              ";chainLtpRefs="+str(refs)+";actualDartExecuted=false;orders=false")
        return 0
    except SchemaBlocked as exc:
        print(MARKER+"status=FIELD_CONTRACT_BLOCKED;reason="+str(exc)+";orders=false")
        return 1
    except (OSError,ValueError,TypeError):
        print(MARKER+"status=FIELD_CONTRACT_UNAVAILABLE;orders=false")
        return 1


if __name__=="__main__":
    raise SystemExit(main())
