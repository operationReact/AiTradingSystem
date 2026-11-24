"""Walk the authenticated Mind progress inventory and verify it end to end.

Prints counts, byte sizes and pass/fail only. It never prints item bodies, evidence,
tokens or user data.

Usage: walk_progress_inventory.py <base_url> <token_file> [expected_total]

Checks:
- summary is mind.progress.v2 and every response is below the 131,072-byte edge cap
- inventory pages are mind.progress-inventory.v1, offsets are contiguous, and nextOffset
  advances correctly until it is null
- every page reports the same manifestVersion and total as the summary
- no duplicate ids and no omissions: walked count == summary overall.total
- done / pending / blocked counts and percentDone are recomputed from the walked items
- a sample of item detail pages resolves (first, last and one per kind)
- optional expected_total (for example 313) must equal the summary total
"""
import json
import sys
import urllib.request

EDGE_CAP = 131_072
PAGE_LIMIT = 50
MAX_PAGES = 400
DETAIL_TIMEOUT = 20


class WalkError(Exception):
    pass


def safe_failure_code(error):
    """Return a bounded cause code without including URLs, bodies, headers or tokens."""
    if isinstance(error, urllib.error.HTTPError):
        return f"HTTP_{error.code}" if 100 <= error.code <= 599 else "HTTP_ERROR"
    if isinstance(error, urllib.error.URLError):
        reason = error.reason
        if isinstance(reason, (TimeoutError, TimeoutError.__mro__[1])):
            return "URL_TIMEOUT"
        if isinstance(reason, OSError) and type(reason.errno) is int:
            return f"URL_OS_ERROR_{reason.errno}"
        return "URL_ERROR"
    if isinstance(error, json.JSONDecodeError):
        return f"JSON_PARSE_ERROR_LINE_{error.lineno}_COLUMN_{error.colno}"
    if isinstance(error, (TimeoutError,)):
        return "TIMEOUT"
    if isinstance(error, OSError) and type(error.errno) is int:
        return f"OS_ERROR_{error.errno}"
    return "UNEXPECTED_" + type(error).__name__


def percent_done(done, total):
    # Mirrors MindCapabilityProgressService: round(done * 1000 / total) / 10.
    return 0.0 if total == 0 else round(done * 1000.0 / total) / 10.0


def verify_summary(summary):
    if summary.get("contractVersion") != "mind.progress.v2":
        raise WalkError("summary contract mismatch")
    overall = summary.get("overall") or {}
    total = overall.get("total")
    if not isinstance(total, int) or total <= 0:
        raise WalkError("summary total missing")
    if overall.get("done", -1) + overall.get("pending", -1) != total:
        raise WalkError("summary done + pending does not equal total")
    return overall


def verify_pages(pages, summary, overall):
    """pages: list of (byte_count, page_dict). Returns the ordered list of items."""
    seen = set()
    items = []
    expected_offset = 0
    manifest = summary.get("manifestVersion")
    total = overall["total"]
    for index, (size, page) in enumerate(pages):
        if size >= EDGE_CAP:
            raise WalkError(f"page {index} reaches the edge cap")
        if page.get("contractVersion") != "mind.progress-inventory.v1":
            raise WalkError(f"page {index} inventory contract mismatch")
        if page.get("manifestVersion") != manifest:
            raise WalkError(f"page {index} manifest changed during walk")
        if page.get("backendRevision") != summary.get("backendRevision"):
            raise WalkError(f"page {index} backend revision changed during walk")
        if page.get("total") != total:
            raise WalkError(f"page {index} total does not match summary")
        if page.get("offset") != expected_offset:
            raise WalkError(f"page {index} offset is not contiguous")
        page_items = page.get("items")
        if not isinstance(page_items, list):
            raise WalkError(f"page {index} items missing")
        for item in page_items:
            item_id = item.get("id")
            if not isinstance(item_id, str) or not item_id:
                raise WalkError("item without id")
            if item_id in seen:
                raise WalkError("duplicate item id in inventory")
            seen.add(item_id)
            items.append(item)
        expected_offset += len(page_items)
        next_offset = page.get("nextOffset")
        if next_offset is None:
            if expected_offset != total:
                raise WalkError("inventory ended before the summary total (omissions)")
        elif next_offset != expected_offset:
            raise WalkError(f"page {index} nextOffset does not advance to walked count")
    if expected_offset != total or len(items) != total:
        raise WalkError("walked count does not equal summary total")
    return items


def verify_percentages(items, overall):
    done = sum(1 for i in items if i.get("done") is True)
    blocked = sum(1 for i in items if not i.get("done") and i.get("blockerCode"))
    total = len(items)
    if done != overall["done"]:
        raise WalkError("summary done count does not match inventory")
    if total - done != overall["pending"]:
        raise WalkError("summary pending count does not match inventory")
    if blocked != overall["blocked"]:
        raise WalkError("summary blocked count does not match inventory")
    if percent_done(done, total) != overall["percentDone"]:
        raise WalkError("summary percentDone does not match inventory")
    return {"done": done, "pending": total - done, "blocked": blocked,
            "percentDone": overall["percentDone"]}


def layer_counts(items):
    """Recompute the four evidence layers from walked items, mirroring MindCapabilityProgressService."""
    implemented = ci_tested = deployed = runtime = 0
    for item in items:
        kinds = {e.get("kind") for e in (item.get("evidence") or [])}
        declared = item.get("declaredStatus")
        if "SOURCE" in kinds and declared not in ("NOT_STARTED", "MOCK"):
            implemented += 1
        if "TEST" in kinds and declared in ("TESTED", "DEPLOYED", "RUNTIME_VERIFIED"):
            ci_tested += 1
        runtime_info = item.get("runtime") or {}
        if runtime_info.get("lastVerifiedAt") is not None:
            deployed += 1
        if item.get("done") is True:
            runtime += 1
    return {"implemented": implemented, "ciTested": ci_tested, "deployed": deployed, "runtimeVerified": runtime}


def verify_layers(items, overall):
    layers = overall.get("layers")
    if not isinstance(layers, dict):
        raise WalkError("summary layers missing")
    expected = layer_counts(items)
    total = len(items)
    for name, count in expected.items():
        layer = layers.get(name) or {}
        if layer.get("count") != count:
            raise WalkError(f"layer {name} count does not match walked evidence")
        if layer.get("total") != total:
            raise WalkError(f"layer {name} total does not match inventory")
        if layer.get("percent") != percent_done(count, total):
            raise WalkError(f"layer {name} percent does not match walked evidence")
    if layers["runtimeVerified"]["count"] != overall["done"]:
        raise WalkError("runtimeVerified layer does not equal the strict done count")
    return expected


def detail_sample(items):
    """First, last and one item per kind, so the sample stays bounded."""
    chosen = []
    if items:
        chosen.append(items[0]["id"])
        chosen.append(items[-1]["id"])
    seen_kinds = set()
    for item in items:
        kind = item.get("kind")
        if kind not in seen_kinds:
            seen_kinds.add(kind)
            chosen.append(item["id"])
    return list(dict.fromkeys(chosen))


def _get(base, path, token):
    req = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=DETAIL_TIMEOUT) as resp:
        body = resp.read()
        return resp.status, body


def walk(base, token, expected_total=None):
    status, body = _get(base, "/api/customer/mind/v1/progress", token)
    if status != 200 or len(body) >= EDGE_CAP:
        raise WalkError(f"summary failed or too large (status {status})")
    summary = json.loads(body)
    print(f"summary bytes={len(body)}")
    print(f"backendRevision={summary.get('backendRevision')} revisionKnown={summary.get('revisionKnown')} "
          f"manifestVersion={summary.get('manifestVersion')}")
    overall = verify_summary(summary)
    if expected_total is not None and overall["total"] != expected_total:
        raise WalkError(f"summary total {overall['total']} != expected {expected_total}")

    pages = []
    offset = 0
    for _ in range(MAX_PAGES):
        path = f"/api/customer/mind/v1/progress/inventory?offset={offset}&limit={PAGE_LIMIT}"
        status, body = _get(base, path, token)
        if status != 200:
            raise WalkError(f"inventory page at offset {offset} returned {status}")
        page = json.loads(body)
        pages.append((len(body), page))
        print(f"page offset={offset} items={len(page.get('items', []))} bytes={len(body)}")
        if page.get("nextOffset") is None:
            break
        offset = page["nextOffset"]
    else:
        raise WalkError("inventory did not finish within the page limit")

    items = verify_pages(pages, summary, overall)
    counts = verify_percentages(items, overall)
    layer_result = verify_layers(items, overall)
    print("layers " + " ".join(f"{k}={v}/{len(items)}" for k, v in layer_result.items()))
    print(f"walked items={len(items)} summary_total={overall['total']} pages={len(pages)}")
    print(f"counts done={counts['done']} pending={counts['pending']} blocked={counts['blocked']} "
          f"percentDone={counts['percentDone']}")
    max_page = max(size for size, _ in pages)
    print(f"largest_page_bytes={max_page} cap={EDGE_CAP}")

    for item_id in detail_sample(items):
        path = "/api/customer/mind/v1/progress/items/" + urllib_quote(item_id)
        status, body = _get(base, path, token)
        if status != 200 or len(body) >= EDGE_CAP:
            raise WalkError(f"item detail failed (status {status})")
        detail = json.loads(body)
        if not isinstance(detail.get("item"), dict) or detail["item"].get("id") != item_id:
            raise WalkError("item detail does not match requested id")
        print(f"detail ok bytes={len(body)}")
    return {"total": overall["total"], "pages": len(pages), "items": len(items)}


def urllib_quote(value):
    import urllib.parse
    return urllib.parse.quote(value, safe="")


def main(argv):
    if len(argv) not in (3, 4):
        print("WALK_FAILED: BAD_ARGUMENTS", file=sys.stderr)
        return 2
    base = argv[1].rstrip("/")
    with open(argv[2]) as fh:
        token = fh.read().strip()
    expected = int(argv[3]) if len(argv) == 4 else None
    try:
        result = walk(base, token, expected)
    except WalkError as err:
        print("WALK_FAILED: " + str(err), file=sys.stderr)
        return 1
    except Exception as err:
        print("WALK_FAILED: " + safe_failure_code(err), file=sys.stderr)
        return 1
    print(f"WALK_PASSED: {result['items']} items in {result['pages']} pages")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
