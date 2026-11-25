#!/usr/bin/env python3
"""Build and persist one private canonical NIFTY pilot from verified source rows.

Invoked only by a separate exact-request GitHub -> EC2 SSM operation.
No data or key export, no customer API, no PAPER/LIVE orders.
"""
from __future__ import annotations

import json
import os
import sys

import fno_personal_nse_december_compare as comparison
import fno_private_canonical_store as canonical

MARKER = "MINTTICK_FNO_CANONICAL_STORE_V1:"

def main():
    os.umask(0o077)
    if len(sys.argv)!=2 or sys.argv[1]!="persist":
        raise ValueError("CANONICAL_INVALID_OPERATION")
    # compare.run() ensures the existing private owner scope, source receipts,
    # 23 instrument mappings, 643 raw December observations, 14 archives and
    # exactly 3,929 unique validated research rows before calling our writer.
    receipt = comparison.run(on_canonical=canonical.store)
    if (not isinstance(receipt,dict)
            or receipt.get("status")!="PRIVATE_CANONICAL_STORED"
            or receipt.get("total")!=3929
            or receipt.get("upstox")!=3608
            or receipt.get("nse")!=321
            or receipt.get("backtestingApproved") is not False
            or receipt.get("customerApiEnabled") is not False):
        raise ValueError("CANONICAL_SAVE_NOT_VERIFIED")
    print(MARKER+json.dumps(receipt,sort_keys=True,separators=(",",":")),flush=True)

if __name__=="__main__":
    try:
        main()
    except (ValueError,OSError,RuntimeError,TypeError):
        print("FNO_CANONICAL_STORE:BLOCKED_OR_UNAVAILABLE",flush=True)
        raise SystemExit(2)
