#!/usr/bin/env python3
"""Offline parser for NSE public UDiFF F&O bhavcopy archives (research-only).

Reads already-downloaded NSE public bhavcopy ZIPs from local paths (never
fetches) and emits the NIFTY index-futures (FinInstrmTp=IDF) rows as JSON.
The public UDiFF archive is downloaded out-of-band with a documented manual
step (see README.md); this module does no network I/O so it is safe to run in
CI and offline. Downloaded archives are untrusted input: pass their paths as
arguments and run this with `python3 -I`.

This is RESEARCH tooling. It is independent of, and must not be confused with,
the owner-gated protected host capture path (str176_forward_nse_capture.py),
which retains original bytes privately and is the only certification-evidence
source. Retrospectively downloaded public data does not satisfy the
first-observed <= decision-time provenance gate required for certification.
"""
from __future__ import annotations
import csv
import hashlib
import io
import json
import sys
import zipfile

MAX_ARCHIVE = 8 * 1024 * 1024
MAX_CSV = 48 * 1024 * 1024
FIELDS = ("BizDt", "TckrSymb", "FinInstrmTp", "XpryDt", "OpnPric", "HghPric",
          "LwPric", "ClsPric", "TtlTradgVol", "OpnIntrst", "ChngInOpnIntrst",
          "NewBrdLotQty", "FinInstrmId")


def parse(path):
    raw = open(path, "rb").read()
    if not raw.startswith(b"PK\x03\x04") or len(raw) > MAX_ARCHIVE:
        raise ValueError("INVALID_NSE_ZIP")
    sha = hashlib.sha256(raw).hexdigest()
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        members = z.infolist()
        if len(members) != 1 or members[0].file_size > MAX_CSV:
            raise ValueError("INVALID_NSE_ZIP_MEMBER")
        data = z.read(members[0])
    reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig"), newline=""))
    if reader.fieldnames is None or not set(FIELDS).issubset(reader.fieldnames):
        raise ValueError("NSE_SCHEMA_MISMATCH")
    rows = []
    for r in reader:
        if r["TckrSymb"].strip() == "NIFTY" and r["FinInstrmTp"] == "IDF":
            rows.append({"d": r["BizDt"], "xp": r["XpryDt"], "o": r["OpnPric"],
                         "h": r["HghPric"], "l": r["LwPric"], "c": r["ClsPric"],
                         "v": r["TtlTradgVol"], "oi": r["OpnIntrst"],
                         "chg": r["ChngInOpnIntrst"], "lot": r["NewBrdLotQty"],
                         "id": r["FinInstrmId"].strip(), "sha256": sha})
    return rows


def main(argv):
    out = []
    for path in argv:
        out.extend(parse(path))
    json.dump(out, sys.stdout, indent=1, sort_keys=True)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv[1:])
