"""Detect incorrect customer-facing brand spelling in tracked application text."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WRONG = ("mint" + "ic", "min" + "tick")


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
                    for word in WRONG:
                        self.assertNotIn(word, content)

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
