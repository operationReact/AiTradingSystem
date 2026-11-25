#!/usr/bin/env python3
"""Read-only sampler for GET /api/customer/market-data/overview.

Run during NSE cash hours (09:15-15:30 IST, Mon-Fri) to check that index
freshness and breadth advance. Sends no orders.

Environment:
  CUSTOMER_TOKEN  customer JWT (required, never printed)
  BASE_URL        default http://localhost:8080
  INTERVAL_SEC    seconds between samples, default 30
  SAMPLES         number of samples, default 20
  OUT_FILE        JSON lines output, default overview-samples.jsonl

Each line holds generatedAt, exchangeAsOf, breadth, and per-index ltp,
exchangeAt and freshness. The raw response is not written.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
INTERVAL = float(os.environ.get("INTERVAL_SEC", "30"))
SAMPLES = int(os.environ.get("SAMPLES", "20"))
OUT_FILE = os.environ.get("OUT_FILE", "overview-samples.jsonl")
TOKEN = os.environ.get("CUSTOMER_TOKEN", "")
PATH = "/api/customer/market-data/overview"


def fetch():
    req = urllib.request.Request(
        BASE_URL + PATH,
        headers={"Authorization": "Bearer " + TOKEN, "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def summarise(payload):
    return {
        "generatedAt": payload.get("generatedAt"),
        "exchangeAsOf": payload.get("exchangeAsOf"),
        "breadth": payload.get("breadth"),
        "indices": [
            {
                "indexCode": i.get("indexCode"),
                "ltp": i.get("ltp"),
                "changePct": i.get("changePct"),
                "exchangeAt": i.get("exchangeAt"),
                "freshness": i.get("freshness"),
            }
            for i in payload.get("indices", [])
        ],
    }


def main():
    if not TOKEN:
        print("CUSTOMER_TOKEN is not set.", file=sys.stderr)
        return 2
    with open(OUT_FILE, "a", encoding="utf-8") as out:
        for n in range(SAMPLES):
            try:
                line = summarise(fetch())
            except urllib.error.HTTPError as err:
                line = {"error": "HTTP %d" % err.code}
            except Exception as err:  # network or parse failure
                line = {"error": type(err).__name__}
            line["sampledAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
            out.write(json.dumps(line) + "\n")
            out.flush()
            print(json.dumps(line))
            if n < SAMPLES - 1:
                time.sleep(INTERVAL)
    return 0


if __name__ == "__main__":
    sys.exit(main())
