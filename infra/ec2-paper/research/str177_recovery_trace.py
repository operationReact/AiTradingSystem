#!/usr/bin/env python3
"""Offline STR-177 ten-session trace + per-session OI reconciliation (research).

Mirrors the Java evaluator math (population std over the first eight closes,
basis-point helpers) to show WHY a given ten-session window produces its
verdict, and reconciles each session's day-over-day open-interest delta with
NSE's independently published ChngInOpnIntrst. Consumes the JSON produced by
nse_udiff_fut_parser.py; does no network I/O.

Usage:
  python3 -I str177_recovery_trace.py rows.json [EXPIRY] [--last 10]
EXPIRY defaults to the most recent expiry present. The window is the last ten
consecutive sessions of that contract in the file.

Verdict reproduction is authoritative only when run through the real compiled
Java evaluator; this script documents the arithmetic behind that verdict.
"""
from __future__ import annotations
import json
import sys
from datetime import date
from decimal import Decimal, getcontext

getcontext().prec = 40
BASELINE = 8
TOTAL = 10


def bps(n, d):
    return n * Decimal(10000) / d


def load(path, expiry):
    rows = json.load(open(path))
    by = {}
    for r in rows:
        if expiry and r["xp"] != expiry:
            continue
        by.setdefault(r["xp"], {})[date.fromisoformat(r["d"])] = r
    if not by:
        raise SystemExit("no rows for expiry")
    xp = expiry or sorted(by)[-1]
    days = sorted(by[xp])
    return xp, [by[xp][d] for d in days]


def main(argv):
    path = argv[0]
    expiry = argv[1] if len(argv) > 1 and not argv[1].startswith("--") else None
    xp, series = load(path, expiry)
    if len(series) < TOTAL:
        raise SystemExit(f"need >= {TOTAL} sessions, have {len(series)}")
    w = series[-TOTAL:]
    closes = [Decimal(r["c"]) for r in w]
    oi = [int(r["oi"]) for r in w]
    chg = [int(r["chg"]) for r in w]
    dates = [r["d"] for r in w]
    mean = sum(closes[:BASELINE]) / BASELINE
    var = sum((c - mean) ** 2 for c in closes[:BASELINE]) / BASELINE
    std = var.sqrt()
    extreme, latest = closes[BASELINE], closes[BASELINE + 1]
    z = (extreme - mean) / std
    print(f"contract expiry: {xp}   window: {dates[0]} .. {dates[-1]}")
    print(f"baseline mean (sessions 1-8) = {mean:.4f}")
    print(f"baseline population std      = {std:.4f}  ({bps(std, mean):.2f} bps, floor 25)")
    print(f"9th session ({dates[8]}) close = {extreme}  z = {z:+.4f}  (|z| floor 2.00)")
    hi, lo = z >= 2, z <= -2
    print(f"extreme confirmed: {'HIGH' if hi else 'LOW' if lo else 'NONE'}")
    if hi or lo:
        if hi:
            rec = latest < extreme and latest > mean
            need = f"latest<extreme({latest}<{extreme}={latest < extreme}) AND latest>mean({latest}>{mean:.4f}={latest > mean})"
        else:
            rec = latest > extreme and latest < mean
            need = f"latest>extreme({latest}>{extreme}={latest > extreme}) AND latest<mean({latest}<{mean:.4f}={latest < mean})"
        print(f"10th session ({dates[9]}) close = {latest}")
        print(f"partial-recovery test: {need}  => {rec}")
    print("\nper-session OI reconciliation (public NSE ChngInOpnIntrst):")
    print(f"{'date':12}{'closeOI':>12}{'prevOI':>12}{'computedDelta':>15}{'NSE_ChngInOI':>14}{'match':>7}")
    ok = True
    for i in range(TOTAL):
        prev = oi[i - 1] if i else None
        delta = (oi[i] - prev) if i else None
        m = "n/a" if i == 0 else (delta == chg[i])
        if i and not m:
            ok = False
        print(f"{dates[i]:12}{oi[i]:>12}{str(prev):>12}{str(delta):>15}{chg[i]:>14}{str(m):>7}")
    print(f"\nall computable deltas reconcile with NSE ChngInOpnIntrst: {ok}")


if __name__ == "__main__":
    main(sys.argv[1:])
