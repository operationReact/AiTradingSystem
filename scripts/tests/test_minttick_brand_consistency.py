"""Detect incorrect customer-facing brand spelling in tracked application text."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
# The separate in-app engine is explicitly named "Mintick Mind".
# Reject incorrect standalone product brand tokens, not that compound name.
def has_incorrect_brand(value: str) -> bool:
    without_engine = re.sub(r"mintick[\s_-]*mind", "", value.lower())
    return re.search(r"(?<![a-z0-9_])mintic(?:k)?(?![a-z0-9_])", without_engine) is not None


class MintTickBrandConsistencyTest(unittest.TestCase):
    def test_active_product_text_has_no_wrong_spelling(self):
        roots = (ROOT / "apps/trading_client", ROOT / "docs", ROOT / "README.md")
        for root in roots:
            paths = root.rglob("*") if root.is_dir() else (root,)
            for path in paths:
                if not path.is_file() or path.suffix.lower() not in {
                    ".dart", ".md", ".html", ".json", ".yml", ".yaml", ".xml", ".svg", ".kt", ".kts", ".txt"
                }:
                    continue
                with self.subTest(path=str(path.relative_to(ROOT))):
                    content = path.read_text(encoding="utf-8").lower()
                    self.assertFalse(has_incorrect_brand(content))

    def test_mind_name_is_distinct_from_minttick_brand(self):
        self.assertFalse(has_incorrect_brand("Mintick Mind"))
        self.assertFalse(has_incorrect_brand("MintickMindWorkspacePage"))
        self.assertFalse(has_incorrect_brand("features/mintick_mind/widget.dart"))
        self.assertFalse(has_incorrect_brand("MintTick"))
        self.assertTrue(has_incorrect_brand("Mintic"))
        self.assertTrue(has_incorrect_brand("Mintick"))

    def test_mobile_and_web_application_identity(self):
        expected = {
            "apps/trading_client/lib/src/brand/minttick_brand.dart": "static const appName = 'MintTick'",
            "apps/trading_client/web/index.html": "<title>MintTick — AI Trading Platform</title>",
            "apps/trading_client/web/manifest.json": '"short_name": "MintTick"',
            "apps/trading_client/android/app/src/main/AndroidManifest.xml": 'android:label="MintTick"',
            "README.md": "# MintTick — AI Trading Platform",
        }
        for name, token in expected.items():
            with self.subTest(path=name):
                self.assertIn(token, (ROOT / name).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
