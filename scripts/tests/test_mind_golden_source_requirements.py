"""Fail closed when Mintick Mind routes, models or strategy families outgrow the data contract.

No dependencies, AWS accounts, market data, provider credentials or training required.
This is a requirements completeness contract, NOT proof of source availability.
"""
from __future__ import annotations

import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/architecture/mintick-mind-golden-source-requirements.v1.json"
MIND_ROOT = ROOT / "apps/trading_client/lib/src/features/mintick_mind"
ROUTES = MIND_ROOT / "presentation/mind_workspace_page.dart"
FIXTURES = MIND_ROOT / "data/mind_fixtures.dart"


def extract_source_truth():
    routes = re.findall(
        r"MindScreenDescriptor\('([^']+)', '([^']+)', Icons\.[^,]+, '([^']+)'\)",
        ROUTES.read_text(encoding="utf-8"),
    )
    models = re.findall(
        r"MindModel\('([^']+)', '([^']+)', '([^']+)', '([^']+)', '([^']+)', '([^']+)'\)",
        FIXTURES.read_text(encoding="utf-8"),
    )
    family_rows = re.findall(
        r"^\s*\('([^']+)', '([^']+)', '([^']+)'\),?$",
        FIXTURES.read_text(encoding="utf-8"), flags=re.M,
    )
    families = [row for row in family_rows if " " in row[1] or row[1] == "Research"]
    return routes, models, families


def verify_contract(c):
    if c["contract_version"] != "mintick.mind.golden-source-requirements.v1":
        raise ValueError("Unexpected or unreviewed data-requirement contract version")
    expected_routes, expected_models, expected_families = extract_source_truth()
    routes, models, families = c["screens"], c["models"], c["strategy_families"]
    sources = c["data_sources"]
    if not isinstance(sources, dict) or len(sources) < 20:
        raise ValueError("Data source registry is incomplete")
    actual_routes = [(r["id"], r["title"], r["group"]) for r in routes]
    if actual_routes != expected_routes:
        raise ValueError("Mind route registry drift: update every golden-source screen mapping")
    actual_models = [(m["name"], m["family"], m["ui_role"], m["ui_status"],
                      m["fixture_metric"], m.get("input_contract"))
                     for m in models]
    if [(m[0], m[1], m[2], m[3], m[4]) for m in actual_models] != [
        x[:5] for x in expected_models
    ]:
        raise ValueError("Mind fixture model registry drift: review exact model input contract")
    actual_families = [(f["family"], f["horizons"], "|".join(f["templates"]))
                       for f in families]
    if actual_families != expected_families:
        raise ValueError("Strategy family/templates changed without golden-source impact review")
    if len({r["id"] for r in routes}) != len(routes):
        raise ValueError("Duplicate screen routes")
    if len({m["name"] for m in models}) != len(models):
        raise ValueError("Duplicate model entries")
    if len({f["family"] for f in families}) != len(families):
        raise ValueError("Duplicate strategy family entries")

    for source_id, source in sources.items():
        if not re.fullmatch(r"[a-z][a-z_0-9]+", source_id):
            raise ValueError("Invalid source identifier")
        if (not isinstance(source.get("fields"), list)
                or len(source["fields"]) < 3
                or len(set(source["fields"])) != len(source["fields"])
                or not source.get("state")
                or not source.get("history")
                or not source.get("requirements")):
            raise ValueError(f"Missing temporal/field/source evidence for {source_id}")
    for kind, items in (("screen", routes), ("model", models), ("strategy family", families)):
        for entry in items:
            required = entry.get("required_sources")
            optional = entry.get("optional_sources", [])
            if not isinstance(required, list) or not isinstance(optional, list):
                raise ValueError(f"Invalid {kind} source lists")
            if kind != "screen" and not required:
                raise ValueError(f"Empty mandatory source for {kind}")
            if kind == "screen" and not required and entry["id"] not in (
                "design-gallery", "design-preview"
            ):
                raise ValueError(f"Empty mandatory source for live-data screen {entry['id']}")
            if len(required) != len(set(required)) or len(optional) != len(set(optional)):
                raise ValueError(f"Duplicate source IDs in {kind}")
            if set(required) & set(optional):
                raise ValueError(f"Required/optional source overlap in {kind}")
            if not (set(required) | set(optional)) <= set(sources):
                raise ValueError(f"Unregistered source for {kind}: {entry.get('id',entry.get('name'))}")
    for model in models:
        if not model.get("input_contract") or not model.get("verified_state"):
            raise ValueError("Model fields/readiness missing")
        if model["model_authorizes_orders"] is not False:
            raise ValueError("Models cannot be granted broker or capital authority")
        if "label_available_at" not in model.get("training_labels", ""):
            raise ValueError("Training labels must be time-valid and excluded from inference")
    total_templates = sum(len(f["templates"]) for f in families)
    if c["counts"] != {
        "routes": len(routes),
        "models": len(models),
        "strategy_families": len(families),
        "strategy_templates": total_templates,
    }:
        raise ValueError("Stale coverage counters")
    return total_templates


class GoldenSourceRequirementsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_complete_and_current_registered_ui_and_model_source_audit(self):
        self.assertEqual(verify_contract(self.data), 232)
        self.assertEqual(len(self.data["screens"]), 43)
        self.assertEqual(len(self.data["models"]), 18)

    def test_derivatives_are_not_misclassified_as_daily_cash_candles(self):
        by_id = {s["id"]: s for s in self.data["screens"]}
        for route in ("derivatives", "options-chain", "option-builder"):
            self.assertIn("derivatives_chain", by_id[route]["required_sources"])
            self.assertIn("derivatives_reference_margin", by_id[route]["required_sources"])

    def test_long_horizon_research_requires_fundamentals_actions_and_identity(self):
        s = {entry["id"]: entry for entry in self.data["screens"]}
        m = s["multibagger"]["required_sources"]
        self.assertTrue({"fundamental_filings", "daily_bars", "security_identity",
                         "corporate_actions"} <= set(m))
        self.assertIn("ownership_governance", s["thesis-detail"]["required_sources"])

    def test_nlp_and_regime_models_do_not_falsely_claim_ohlcv_is_sufficient(self):
        models = {m["name"]: m for m in self.data["models"]}
        self.assertIn("news_events", models["Financial NLP Challenger"]["required_sources"])
        self.assertIn("news_events", models["Gemini Context"]["required_sources"])
        self.assertIn("index_breadth_vix", models["Regime HMM"]["required_sources"])
        self.assertIn("index_breadth_vix", models["Isolation Forest"]["required_sources"])

    def test_drift_fails_closed(self):
        import copy
        modified = copy.deepcopy(self.data)
        modified["screens"].pop()
        with self.assertRaisesRegex(ValueError, "route registry drift"):
            verify_contract(modified)
        modified = copy.deepcopy(self.data)
        modified["models"][0]["required_sources"].append("nonexistent_vendor")
        with self.assertRaisesRegex(ValueError, "Unregistered source"):
            verify_contract(modified)
        modified = copy.deepcopy(self.data)
        modified["models"][0]["model_authorizes_orders"] = True
        with self.assertRaisesRegex(ValueError, "capital authority"):
            verify_contract(modified)


if __name__ == "__main__":
    unittest.main()
