"""Validate a Mind progress response from the authenticated verification run.

Prints only the path and pass or fail. It never prints response bodies, tokens or user data.
Usage: check_progress_contract.py <path> <body-file>
"""
import json
import sys

EDGE_CAP = 131_072


def check(path, body_bytes):
    if len(body_bytes) >= EDGE_CAP:
        return "response reaches the edge cap"
    body = json.loads(body_bytes)
    if path.endswith("/progress"):
        if body.get("contractVersion") != "mind.progress.v2":
            return "summary contract mismatch"
        overall = body.get("overall") or {}
        if not isinstance(overall.get("total"), int) or overall["total"] <= 0:
            return "summary total missing"
        if overall.get("done", 0) + overall.get("pending", -1) != overall["total"]:
            return "summary does not reconcile"
        return None
    if "/progress/inventory" in path:
        if body.get("contractVersion") != "mind.progress-inventory.v1":
            return "inventory contract mismatch"
        if not isinstance(body.get("items"), list) or not body["items"]:
            return "inventory page is empty"
        return None
    if "/progress/items/" in path:
        if not isinstance(body.get("item"), dict):
            return "item detail missing"
        return None
    return "unknown progress path"


if __name__ == "__main__":
    with open(sys.argv[2], "rb") as fh:
        problem = check(sys.argv[1], fh.read())
    if problem:
        print("CONTRACT_FAILED: " + problem, file=sys.stderr)
        sys.exit(1)
