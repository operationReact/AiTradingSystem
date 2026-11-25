import unittest

import walk_progress_inventory as w


def item(i, done=True, kind="screen", blocker=None):
    return {"id": f"item.{i}", "kind": kind, "done": done, "blockerCode": blocker}


def make(items_per_page, total, overall_done=None):
    all_items = [item(i, done=(i % 2 == 0), kind="screen" if i % 3 else "api",
                      blocker=None if i % 2 == 0 else "NEEDS_EVIDENCE") for i in range(total)]
    done = sum(1 for i in all_items if i["done"])
    blocked = sum(1 for i in all_items if not i["done"] and i["blockerCode"])
    summary = {
        "contractVersion": "mind.progress.v2",
        "manifestVersion": "m1",
        "backendRevision": "r1",
        "overall": {"total": total, "done": done, "pending": total - done, "blocked": blocked,
                    "percentDone": w.percent_done(done, total)},
    }
    pages = []
    offset = 0
    while offset < total:
        chunk = all_items[offset:offset + items_per_page]
        nxt = offset + len(chunk)
        page = {"contractVersion": "mind.progress-inventory.v1", "manifestVersion": "m1", "backendRevision": "r1",
                "total": total, "offset": offset, "items": chunk,
                "nextOffset": nxt if nxt < total else None}
        pages.append((1000, page))
        offset = nxt
    return summary, pages, all_items


class SafeFailureTests(unittest.TestCase):
    def test_http_failure_reports_status_without_private_body(self):
        from urllib.error import HTTPError
        error = HTTPError("https://private.example/path", 502, "bad gateway", {}, None)
        self.assertEqual("HTTP_502", w.safe_failure_code(error))
        self.assertNotIn("private", w.safe_failure_code(error))

    def test_json_and_timeout_failures_are_classified(self):
        import json
        self.assertEqual("JSON_PARSE_ERROR_LINE_1_COLUMN_1",
                         w.safe_failure_code(json.JSONDecodeError("x", "x", 0)))
        self.assertEqual("TIMEOUT", w.safe_failure_code(TimeoutError()))


class WalkTests(unittest.TestCase):
    def test_full_walk_passes_and_reconciles(self):
        summary, pages, _ = make(50, 313)
        overall = w.verify_summary(summary)
        items = w.verify_pages(pages, summary, overall)
        self.assertEqual(len(items), 313)
        counts = w.verify_percentages(items, overall)
        self.assertEqual(counts["percentDone"], overall["percentDone"])

    def test_pages_beyond_313_growth_still_pass(self):
        summary, pages, _ = make(50, 5000)
        overall = w.verify_summary(summary)
        self.assertEqual(len(w.verify_pages(pages, summary, overall)), 5000)

    def test_duplicate_id_fails(self):
        summary, pages, _ = make(50, 100)
        pages[1][1]["items"][0] = dict(pages[0][1]["items"][0])
        overall = w.verify_summary(summary)
        with self.assertRaisesRegex(w.WalkError, "duplicate"):
            w.verify_pages(pages, summary, overall)

    def test_omitted_page_fails(self):
        summary, pages, _ = make(50, 150)
        del pages[1]
        pages[1] = (pages[1][0], pages[1][1])
        overall = w.verify_summary(summary)
        with self.assertRaises(w.WalkError):
            w.verify_pages(pages, summary, overall)

    def test_next_offset_mismatch_fails(self):
        summary, pages, _ = make(50, 120)
        pages[0][1]["nextOffset"] = 49
        overall = w.verify_summary(summary)
        with self.assertRaisesRegex(w.WalkError, "nextOffset"):
            w.verify_pages(pages, summary, overall)

    def test_page_at_edge_cap_fails(self):
        summary, pages, _ = make(50, 100)
        pages[0] = (w.EDGE_CAP, pages[0][1])
        overall = w.verify_summary(summary)
        with self.assertRaisesRegex(w.WalkError, "edge cap"):
            w.verify_pages(pages, summary, overall)

    def test_manifest_change_mid_walk_fails(self):
        summary, pages, _ = make(50, 120)
        pages[1][1]["manifestVersion"] = "m2"
        overall = w.verify_summary(summary)
        with self.assertRaisesRegex(w.WalkError, "manifest"):
            w.verify_pages(pages, summary, overall)

    def test_revision_change_mid_walk_fails(self):
        summary, pages, _ = make(50, 120)
        pages[2][1]["backendRevision"] = "r2"
        overall = w.verify_summary(summary)
        with self.assertRaisesRegex(w.WalkError, "revision"):
            w.verify_pages(pages, summary, overall)

    def test_percent_mismatch_fails(self):
        summary, pages, items = make(50, 100)
        summary["overall"]["percentDone"] = 99.9
        overall = w.verify_summary(summary)
        with self.assertRaisesRegex(w.WalkError, "percentDone"):
            w.verify_percentages(items, overall)

    def test_done_count_mismatch_fails(self):
        summary, pages, items = make(50, 100)
        summary["overall"]["done"] += 1
        summary["overall"]["pending"] -= 1
        overall = w.verify_summary(summary)
        with self.assertRaisesRegex(w.WalkError, "done"):
            w.verify_percentages(items, overall)

    def test_wrong_summary_contract_fails(self):
        summary, _, _ = make(50, 10)
        summary["contractVersion"] = "mind.progress.v1"
        with self.assertRaisesRegex(w.WalkError, "contract"):
            w.verify_summary(summary)

    def test_summary_not_reconciling_fails(self):
        summary, _, _ = make(50, 10)
        summary["overall"]["pending"] = 0
        with self.assertRaisesRegex(w.WalkError, "reconcile|pending"):
            w.verify_summary(summary)

    def test_percent_rounding_matches_backend(self):
        self.assertEqual(w.percent_done(1, 3), 33.3)
        self.assertEqual(w.percent_done(0, 0), 0.0)

    def test_detail_sample_is_bounded(self):
        items = [item(i, kind=f"k{i % 7}") for i in range(313)]
        sample = w.detail_sample(items)
        self.assertLessEqual(len(sample), 2 + 7)
        self.assertEqual(sample[0], "item.0")
        self.assertEqual(sample[1], "item.312")


class LayerTests(unittest.TestCase):
    def _items(self):
        return [
            {"id": "a", "kind": "SCREEN", "done": True, "declaredStatus": "TESTED",
             "evidence": [{"kind": "SOURCE"}, {"kind": "TEST"}],
             "runtime": {"lastVerifiedAt": "2026-11-16T00:00:00Z"}},
            {"id": "b", "kind": "SCREEN", "done": False, "declaredStatus": "NOT_STARTED",
             "evidence": [], "runtime": {"lastVerifiedAt": None}},
            {"id": "c", "kind": "SCREEN", "done": False, "declaredStatus": "IMPLEMENTING",
             "evidence": [{"kind": "SOURCE"}], "runtime": {"lastVerifiedAt": None}},
            {"id": "d", "kind": "SCREEN", "done": False, "declaredStatus": "MOCK",
             "evidence": [{"kind": "TEST"}], "runtime": {"lastVerifiedAt": None}},
        ]

    def _overall(self, counts, total=4):
        def layer(count):
            return {"count": count, "total": total, "percent": w.percent_done(count, total)}
        return {"done": counts["runtimeVerified"], "layers": {k: layer(v) for k, v in counts.items()}}

    def test_layers_recomputed_from_evidence(self):
        items = self._items()
        expected = w.layer_counts(items)
        self.assertEqual(expected, {"implemented": 2, "ciTested": 1, "deployed": 1, "runtimeVerified": 1})
        self.assertEqual(w.verify_layers(items, self._overall(expected)), expected)

    def test_inflated_layer_fails(self):
        items = self._items()
        counts = w.layer_counts(items)
        counts["implemented"] = 4
        with self.assertRaisesRegex(w.WalkError, "implemented"):
            w.verify_layers(items, self._overall(counts))

    def test_runtime_layer_must_equal_strict_done(self):
        items = self._items()
        counts = w.layer_counts(items)
        overall = self._overall(counts)
        overall["done"] = 2
        with self.assertRaisesRegex(w.WalkError, "strict done"):
            w.verify_layers(items, overall)


if __name__ == "__main__":
    unittest.main()
